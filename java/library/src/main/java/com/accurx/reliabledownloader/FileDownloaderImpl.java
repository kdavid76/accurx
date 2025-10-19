package com.accurx.reliabledownloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

public class FileDownloaderImpl implements FileDownloader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FileDownloaderImpl.class);
    private static final String ACCEPT_RANGES_HEADER = "Accept-Ranges";
    private static final String CONTENT_MD5 = "Content-MD5";

    private final HttpClient httpClient;

    public FileDownloaderImpl(HttpClient.Builder httpClientBuilder) {
        this.httpClient = httpClientBuilder.build();
    }

    @Override
    public Optional<String> downloadFile(URI contentFileUrl, OutputStream destination) throws Exception {

        var headResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(contentFileUrl)
                        .HEAD()
                        .build(),
                HttpResponse.BodyHandlers.discarding()
        );

        if (!(headResponse.statusCode() >= 200 && headResponse.statusCode() <= 299))
        {
            throw new Exception("Unexpected status code: " + headResponse.statusCode());
        }

        if (headResponse.headers().allValues(ACCEPT_RANGES_HEADER).contains("bytes"))
        {
            LOGGER.warn("Accept-Ranges: bytes is not yet supported, downloading whole file");
        }

        var getResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(contentFileUrl)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (!(getResponse.statusCode() >= 200 && getResponse.statusCode() <= 299))
        {
            throw new Exception("Unexpected status code: " + getResponse.statusCode());
        }

        try (var body = getResponse.body())
        {
            body.transferTo(destination);
        }

        return getResponse.headers().firstValue(CONTENT_MD5);
    }
}
