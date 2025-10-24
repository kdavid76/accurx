package com.accurx.reliabledownloader;

import com.accurx.reliabledownloader.exceptions.FileDownloadException;
import com.accurx.reliabledownloader.exceptions.FileStreamingException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the {@link FileDownloader} interface for downloading files with support for resumable downloads,
 * integrity checks via MD5, and caching. This class handles HTTP requests to download files, verify their content,
 * and stream the downloaded data to the intended destination.
 * <p>
 * Features include:
 * <ul>
 * <li>Support for resumable downloads via HTTP range requests when advertised by the server.</li>
 * <li>Verification of file integrity using an MD5 hash provided by the server (if available).</li>
 * <li>Local caching of partially downloaded files to resume interrupted downloads.</li>
 * <li>Configurable chunk size and maximum download attempts during resumable downloads.</li>
 * <ul/>
 */
public class FileDownloaderImpl implements FileDownloader {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileDownloaderImpl.class);

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_CHUNK_SIZE = 1024L * 256L;
    private static final String ACCEPT_RANGES_HEADER = "Accept-Ranges";
    private static final String CONTENT_MD5 = "Content-MD5";
    private static final String CONTENT_LENGTH = "Content-Length";

    private final int maxAttempts;
    private final long chunkSize;

    private final HttpClient httpClient;
    private final Path cacheFolder;


    public FileDownloaderImpl(HttpClient.Builder httpClientBuilder) throws IOException {
        this(httpClientBuilder, DEFAULT_CHUNK_SIZE, DEFAULT_MAX_ATTEMPTS);
    }

    public FileDownloaderImpl(HttpClient.Builder httpClientBuilder, long chunkSize, int maxAttempts) throws IOException {
        this.httpClient = httpClientBuilder.build();
        this.cacheFolder = Paths.get(System.getProperty("java.io.tmpdir"), "resumable-downloader-cache");
        this.chunkSize = chunkSize;
        this.maxAttempts = maxAttempts;

        Files.createDirectories(cacheFolder);
    }

    /**
     * ${inheritDoc}
     */
    @Override
    public Optional<String> downloadFile(URI contentFileUrl, OutputStream destination) throws Exception {
        if ( contentFileUrl == null || destination == null ) {
            LOGGER.error("Invalid arguments passed to downloadFile");
            throw new IllegalArgumentException("Invalid arguments passed to downloadFile");
        }

        var key = createUniqueKeyFromUrl(contentFileUrl.toString());
        var tempFile = cacheFolder.resolve(key + ".tmp");
        var completedFile = cacheFolder.resolve(key + ".complete");

        var computedMd5 = checkCompletedDownload(completedFile, destination);
        if ( computedMd5.isPresent() ) {
            cleanUpUnusedFiles(tempFile, completedFile);
            // Integrity has already been checked when a file was marked as completed
            LOGGER.info("MD5 hash is recalculated, skipping download and integrity verification.");
            return computedMd5;
        }

        var headResponse = checkDestination(contentFileUrl);
        var serverMd5 = getServerMd5Hash(headResponse);
        var fileMD5 = downloadFile(contentFileUrl, headResponse, tempFile);

        if ( verifyMd5(serverMd5.orElse(null), fileMD5.orElse(null)) ) {
            // Rename the temp file to .completed for handling failures with streaming to the final destination
            Files.move(tempFile, completedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("MD5 hash is present, download integrity verified. Starting to stream file to destination...");
            streamCompletedDownload(completedFile, destination);
        } else {
            LOGGER.error("Download integrity verification failed. Please try to restart application. Deleting incomplete " +
                "file {}.", tempFile.toAbsolutePath());
        }

        cleanUpUnusedFiles(tempFile, completedFile);
        return serverMd5;
    }

    private String createUniqueKeyFromUrl(String url) throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance("SHA-256");
        var hash = md.digest(url.getBytes(StandardCharsets.UTF_8));
        var sb = new StringBuilder();
        for ( byte b : hash ) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Optional<String> checkCompletedDownload(Path completedFile, OutputStream destination) throws Exception {
        if ( streamCompletedDownload(completedFile, destination) ) {
            // MD5 is not saved along with the completed file. But the file is completed, which means it was checked.
            return calculateMd5(completedFile);
        }
        return Optional.empty();
    }

    private boolean streamCompletedDownload(Path completedFile, OutputStream destination) throws FileStreamingException {

        if ( Files.exists(completedFile) ) {
            try ( InputStream in = Files.newInputStream(completedFile) ) {
                in.transferTo(destination);
            } catch ( IOException e ) {
                throw new FileStreamingException(String.format("Failed to send existing file %s", completedFile.toAbsolutePath()), e);
            }
            LOGGER.info("The completed download file {}, streamed it to destination {}.", completedFile.toAbsolutePath(), destination);
            return true;
        }

        return false;
    }

    private Optional<String> calculateMd5(Path file) throws Exception {
        try ( InputStream in = Files.newInputStream(file) ) {
            var md = MessageDigest.getInstance("MD5");
            var read = 0;
            var buffer = new byte[8192];

            while ( (read = in.read(buffer)) != -1 ) {
                md.update(buffer, 0, read);
            }

            return Optional.ofNullable(Base64.getEncoder().encodeToString(md.digest()));
        } catch ( IOException e ) {
            throw new FileDownloadException("Corrupted download, cannot check integrity.", e);
        } catch ( NoSuchAlgorithmException e ) {
            throw new FileDownloadException("MD5 integrity checking is not supported", e);
        }
    }

    private Optional<String> downloadFile(URI contentFileUrl, HttpResponse<Void> headResponse, Path tempFile) throws Exception {
        Optional<String> fileMD5;

        if ( !isRangesSupported(headResponse) ) {
            LOGGER.warn("Server does not advertise Accept-Ranges; attempting to download the file with a single request.");
            fileMD5 = notResumableDownload(contentFileUrl, tempFile);
        } else {
            fileMD5 = resumableDownload(headResponse, contentFileUrl, tempFile);
        }
        return fileMD5;
    }

    private boolean isRangesSupported(HttpResponse<Void> headResponse) {
        return headResponse.headers().allValues(ACCEPT_RANGES_HEADER).contains("bytes");
    }

    private HttpResponse<Void> checkDestination(URI contentFileUrl) throws Exception {
        try {

            var headResponse = httpClient.send(
                HttpRequest.newBuilder().uri(contentFileUrl).HEAD().build(), HttpResponse.BodyHandlers.discarding());

            if ( headResponse == null || isFailureResponseCode(headResponse.statusCode()) ) {
                throw new FileDownloadException(String.format("Destination file not present for URL %s", contentFileUrl));
            }

            return headResponse;
        } catch ( IOException e ) {
            var message = String.format("Failed to send HEAD request to %s", contentFileUrl.toString());
            throw new FileDownloadException(message, e);
        }
    }

    private boolean isFailureResponseCode(int statusCode) {
        return statusCode < 200 || statusCode > 299;
    }

    public Optional<String> getServerMd5Hash(HttpResponse<Void> headResponse) throws Exception {
        var serverMd5 = headResponse.headers().firstValue(CONTENT_MD5);
        if ( serverMd5.isEmpty() ) {
            throw new FileDownloadException("Server MD5 is not present, cannot check download integrity.");
        }

        return serverMd5;
    }

    private void cleanUpUnusedFiles(Path tempFile, Path completedFile) throws IOException {
        Files.deleteIfExists(completedFile);
        Files.deleteIfExists(tempFile);
    }

    private boolean verifyMd5(String serverMD5, String fileMD5) {
        if ( StringUtils.isBlank(serverMD5) || StringUtils.isBlank(fileMD5) ) {
            LOGGER.warn("MD5 hash is not present, cannot check integrity.");
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(serverMD5);
        byte[] actual = Base64.getDecoder().decode(fileMD5);
        return Arrays.equals(expected, actual);
    }

    public Optional<String> notResumableDownload(URI contentFileUrl, Path tempFile) throws Exception {
        var response = downloadFullFile(contentFileUrl);

        try ( InputStream in = response.body(); OutputStream out = Files.newOutputStream(tempFile,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) ) {
            in.transferTo(out);
        } catch ( IOException e ) {
            throw new FileDownloadException(String.format("Failed to download file from %s", contentFileUrl), e);
        }

        return calculateMd5(tempFile);
    }

    public Optional<String> resumableDownload(HttpResponse<Void> headResponse, URI contentFileUrl, Path tempFile)
        throws Exception {

        var contentLength = getContentLength(headResponse);
        var downloaded = getDownloadedBytes(tempFile);

        try ( BufferedOutputStream out = openOutputStream(tempFile) ) {
            boolean finished = false;

            while ( !finished ) {
                var start = downloaded;
                var end = (contentLength > 0) ? Math.min(contentLength - 1, start + chunkSize - 1) : start + chunkSize - 1;
                var request = createRangeRequest(contentFileUrl, start, end);
                var bytesRead = downloadChunkWithRetries(request, out);

                downloaded += bytesRead;
                finished = shouldFinishDownload(contentLength, downloaded, bytesRead);
                LOGGER.info("Downloaded:{} bytes from:{} to:{}, Should finish process: {}", bytesRead, start, start + bytesRead - 1, finished);
            }
        } catch ( IOException e ) {
            throw new FileDownloadException("Failed to download file from " + contentFileUrl, e);
        }

        return calculateMd5(tempFile);
    }

    private HttpResponse<InputStream> downloadFullFile(URI contentFileUrl) throws Exception {
        var request = HttpRequest.newBuilder().uri(contentFileUrl).GET().build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if ( isFailureResponseCode(response.statusCode()) ) {
                throw new FileDownloadException(String.format("Unexpected response status (%d) after downloading file " +
                    "from URL %s.", response.statusCode(), contentFileUrl));
            }
            return response;
        } catch ( IOException e ) {
            throw new FileDownloadException(String.format("Cannot download file from URL %s.", contentFileUrl));
        }
    }

    private long getContentLength(HttpResponse<Void> headResponse) {
        OptionalLong cl = headResponse.headers().firstValueAsLong(CONTENT_LENGTH);
        long contentLength = cl.orElse(-1L);
        if ( contentLength < 0 ) {
            LOGGER.warn("Content-Length missing, trying to continue with EOF-based download mode.");
        }

        return contentLength;
    }

    private long getDownloadedBytes(Path tempFile) throws IOException {
        return Files.exists(tempFile) ? Files.size(tempFile) : 0L;
    }

    private BufferedOutputStream openOutputStream(Path tempFile) throws IOException {
        return new BufferedOutputStream(Files.newOutputStream(tempFile, StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, StandardOpenOption.APPEND));
    }

    private HttpRequest createRangeRequest(URI contentFileUrl, long start, long end) {
        var rangeHeader = "bytes=" + start + "-" + end;
        return HttpRequest.newBuilder().uri(contentFileUrl).GET().header("Range", rangeHeader).build();
    }

    private long downloadChunkWithRetries(HttpRequest request, BufferedOutputStream out) throws Exception {
        var attempt = 0;
        while ( attempt < maxAttempts ) {
            try {
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if ( response.statusCode() == 206 || response.statusCode() == 200 ) {
                    return writeChunkToFile(response, out);
                } else if ( response.statusCode() == 416 ) {
                    LOGGER.info("Server thinks EOF already reached in previous iteration.");
                    return 0;
                } else if ( response.statusCode() >= 500 ) {
                    attempt++;
                    attemptBasedWait(attempt);
                } else {
                    throw new IOException("Unexpected status code: " + response.statusCode());
                }

            } catch ( IOException e ) {
                attempt++;
                if ( attempt >= maxAttempts ) {
                    throw new FileDownloadException(String.format(
                        "Failed to download file from %s after %d attempts", request.uri(), maxAttempts), e);
                }
                attemptBasedWait(attempt);
            }
        }
        return 0;
    }

    private boolean shouldFinishDownload(long contentLength, long downloaded, long lastChunkBytes) {
        return (contentLength > 0 && downloaded >= contentLength) || (contentLength <= 0 && lastChunkBytes <= 0);
    }

    private long writeChunkToFile(HttpResponse<InputStream> response, BufferedOutputStream out) throws Exception {
        try ( var in = response.body() ) {
            var written = in.transferTo(out);
            out.flush();
            return written;
        } catch ( IOException e ) {
            throw new FileDownloadException("Cannot write chunk to temp file.", e);
        }
    }

    private void attemptBasedWait(int attempt) throws InterruptedException {
        long wait = 1000L * (1L << (attempt - 1));
        TimeUnit.MILLISECONDS.sleep(wait);
    }
}
