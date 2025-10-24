package com.accurx.reliabledownloader;

import com.accurx.reliabledownloader.exceptions.FileStreamingException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class FileDownloaderImplTest {

    private static final Path CACHE_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "resumable-downloader-cache");

    private MockWebServer mockWebServer;
    private FileDownloaderImpl downloader;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
        downloader = new FileDownloaderImpl(httpClientBuilder);

        cleanCacheDir();
        Files.createDirectories(CACHE_DIR);
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
        // clean cache after each test
        if ( Files.exists(CACHE_DIR) ) {
            try ( var s = Files.walk(CACHE_DIR) ) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch ( Exception ignored ) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("Invalid input")
    void testInvalidInput() {
        var destination = Mockito.mock(ByteArrayOutputStream.class);

        assertThatThrownBy(() -> downloader.downloadFile(null, null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> downloader.downloadFile(URI.create("http://some.file/file.msi"), null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> downloader.downloadFile(null, destination))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Single GET download, ranges not supported")
    void testSingleGetDownloadRangesNotSupported() throws Exception {
        byte[] body = "Hello, This is a payload for a single GET request".getBytes();
        String md5 = base64Md5(body);

        var head = new MockResponse().setResponseCode(200).addHeader("Content-MD5", md5)
            .addHeader("Content-Length", body.length);
        mockWebServer.enqueue(head);

        try ( var buff = new Buffer().write(body) ) {
            var get = new MockResponse().setResponseCode(200).setBody(buff);
            mockWebServer.enqueue(get);
        }

        var out = new ByteArrayOutputStream();
        var serverMd5 = downloader.downloadFile(mockUrl("/iNeedThis.txt"), out);

        assertThat(serverMd5).contains(md5);
        assertThat(out.toByteArray()).isEqualTo(body);

        assertCacheDirIsEmpty();
    }

    @Test
    @DisplayName("Success downloading file with multiple ranges used")
    void testResumableDownloadWithContentLength() throws Exception {
        // Download a some data in 3 chunks
        var chunk1 = new byte[300];
        var chunk2 = new byte[300];
        var chunk3 = new byte[300];
        for ( int i = 0; i < 300; i++ ) {
            chunk1[i] = (byte) i;
            chunk2[i] = (byte) (i + 1);
            chunk3[i] = (byte) (i + 2);
        }
        var full = new byte[900];
        System.arraycopy(chunk1, 0, full, 0, 300);
        System.arraycopy(chunk2, 0, full, 300, 300);
        System.arraycopy(chunk3, 0, full, 600, 300);

        var md5 = base64Md5(full);

        var head = new MockResponse().setResponseCode(200)
            .clearHeaders()
            .addHeader("Accept-Ranges", "bytes")
            .addHeader("Content-Length", full.length)
            .addHeader("Content-MD5", md5);
        mockWebServer.enqueue(head);

        // The response for the first request should be the first chunk
        try ( var buff = new Buffer().write(chunk1) ) {
            var firstCallSuccess = new MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 0-299/" + full.length)
                .setBody(buff);
            mockWebServer.enqueue(firstCallSuccess);
        }

        // The getting the second chunk should fail first, and then succeed
        var secondCallFail = new MockResponse().setResponseCode(500).setBody("server error");
        mockWebServer.enqueue(secondCallFail);

        try ( var buff = new Buffer().write(chunk2) ) {
            var secondCallSuccess = new MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 300-599/" + full.length)
                .setBody(buff);
            mockWebServer.enqueue(secondCallSuccess);
        }

        // Third time, let's finish the download with 206 response
        try ( var buff = new Buffer().write(chunk3) ) {
            MockResponse thirdCall = new MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 600-899/" + full.length)
                .setBody(buff);
            mockWebServer.enqueue(thirdCall);
        }

        var out = new ByteArrayOutputStream();
        var serverMd5 = downloader.downloadFile(mockUrl("/file2.bin"), out);

        assertThat(serverMd5).contains(md5);
        assertThat(out.toByteArray()).isEqualTo(full);

        // Check the request what were made to the server
        var headRequest = mockWebServer.takeRequest(); // HEAD
        assertThat(headRequest).isNotNull();
        assertThat(headRequest.getHeader("Range")).isNull();

        var request1 = mockWebServer.takeRequest();   // first GET
        assertThat(request1.getHeader("Range")).isNotNull().startsWith("bytes=0-");

        var requestWithErrorResponse = mockWebServer.takeRequest(); // this the request resulting in 500
        assertThat(requestWithErrorResponse.getHeader("Range")).isNotNull().startsWith("bytes=300-");

        var request2 = mockWebServer.takeRequest();   // 2nd successful GET
        assertThat(request2.getHeader("Range")).isNotNull().startsWith("bytes=300-");

        var request3 = mockWebServer.takeRequest(); // 3rd successful GET
        assertThat(request3.getHeader("Range")).isNotNull().startsWith("bytes=600-");

        assertCacheDirIsEmpty();
    }

    @Test
    @DisplayName("No content length defined, but successful download")
    void testResumableDownloadWithoutContentLength() throws Exception {
        // server does not send Content-Length header, but supports ranges.
        var body = "This is a file representing download without content length".getBytes();
        var md5 = base64Md5(body);

        var head = new MockResponse().setResponseCode(200)
            .addHeader("Accept-Ranges", "bytes")
            .addHeader("Content-MD5", md5);
        mockWebServer.enqueue(head);

        // First request should return the whole file, but cannot decide if it's the end or not
        try ( var buff = new Buffer().write(body) ) {
            MockResponse response1 = new MockResponse()
                .setResponseCode(200).setBody(buff);
            mockWebServer.enqueue(response1);
        }

        // Second request, the server tells us it's the end of the file'
        MockResponse response2 = new MockResponse().setResponseCode(416);
        mockWebServer.enqueue(response2);

        var out = new ByteArrayOutputStream();
        var serverMd5 = downloader.downloadFile(mockUrl("/file3.txt"), out);

        assertThat(serverMd5).contains(md5);
        assertThat(out.toByteArray()).isEqualTo(body);

        // Check the request what were made to the server
        var headRequest = mockWebServer.takeRequest(); // HEAD
        assertThat(headRequest).isNotNull();
        assertThat(headRequest.getHeader("Range")).isNull();

        var request1 = mockWebServer.takeRequest();   // first GET
        assertThat(request1.getHeader("Range")).isNotNull().startsWith("bytes=0-");

        var request2 = mockWebServer.takeRequest();   // first GET
        assertThat(request2.getHeader("Range")).isNotNull().startsWith("bytes=" + body.length + "-");

        assertCacheDirIsEmpty();
    }

    @Test
    @DisplayName("Exception thrown during streaming the file, but finished after restart")
    void testResumableDownloadWithExceptionDuringStreaming() throws Exception {
        byte[] body = "File is downloaded, but streaming will fail. So the next restart will stream the file".getBytes();

        var md5 = base64Md5(body);

        var head = new MockResponse().setResponseCode(200)
            .clearHeaders()
            .addHeader("Content-Length", body.length)
            .addHeader("Accept-Ranges", "bytes")
            .addHeader("Content-MD5", md5);
        mockWebServer.enqueue(head);

        try ( var buff = new Buffer().write(body) ) {
            MockResponse response1 = new MockResponse()
                .setResponseCode(206).setBody(buff);
            mockWebServer.enqueue(response1);
        }

        var outMock = mock(ByteArrayOutputStream.class);
        doThrow(new IOException("broken stream")).when(outMock).write(any(), anyInt(), anyInt());

        assertThatThrownBy(() -> downloader.downloadFile(mockUrl("/someFile.txt"), outMock))
            .isInstanceOf(FileStreamingException.class);

        assertCacheDirIsNotEmpty();

        // Now, restart and save without downloading it again
        var out = new ByteArrayOutputStream();
        var serverMd5 = downloader.downloadFile(mockUrl("/someFile.txt"), out);

        assertThat(serverMd5).contains(md5);
        assertThat(out.toByteArray()).isEqualTo(body);
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);

        var request1 = mockWebServer.takeRequest();
        assertThat(request1.getMethod()).isNotNull().isEqualTo("HEAD");
        var request2 = mockWebServer.takeRequest();
        assertThat(request2.getMethod()).isNotNull().isEqualTo("GET");

        assertCacheDirIsEmpty();
    }

    private void assertCacheDirIsEmpty() throws Exception {
        try ( var s = Files.list(CACHE_DIR) ) {
            assertThat(s.findAny()).isNotPresent();
        }
    }

    private void assertCacheDirIsNotEmpty() throws Exception {
        try ( var s = Files.list(CACHE_DIR) ) {
            assertThat(s.count()).isGreaterThan(0);
        }
    }

    private void cleanCacheDir() throws Exception {
        if ( Files.exists(CACHE_DIR) ) {
            try ( var s = Files.walk(CACHE_DIR) ) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch ( Exception ignored ) {
                    }
                });
            }
        }
    }

    private String base64Md5(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(bytes);
        return Base64.getEncoder().encodeToString(md.digest());
    }

    private URI mockUrl(String path) {
        return mockWebServer.url(path).uri();
    }
}
