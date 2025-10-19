package com.accurx.reliabledownloader;

import com.accurx.reliabledownloader.mocks.FakeCdn;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.util.Optional;

public class FileDownloaderImplTests {

    @RegisterExtension
    private static final FakeCdn fakeCdn = new FakeCdn("example.txt", "Example download content");

    private FileDownloaderImpl fileDownloader;

    @BeforeEach
    public void Setup() {
        fileDownloader = new FileDownloaderImpl(
                HttpClient.newBuilder()
        );
    }

    @Test
    public void It_downloads_content_when_accept_ranges_is_not_supported() throws Exception {
        var destination = new ByteArrayOutputStream();

        fileDownloader.downloadFile(fakeCdn.getNoRangeUri(), destination);

        var content = destination.toString();
        Assertions.assertEquals(fakeCdn.getContent(), content);
    }

    @Test
    public void It_returns_the_integrity_hash_when_present() throws Exception {
        var destination = new ByteArrayOutputStream();

        var hash = fileDownloader.downloadFile(fakeCdn.getNoRangeUri(), destination);

        Assertions.assertEquals(Optional.of(fakeCdn.getContentHash()), hash);
    }

    @Test
    public void It_downloads_content_in_chunks_when_accept_ranges_is_supported() throws Exception {
        var destination = new ByteArrayOutputStream();

        fileDownloader.downloadFile(fakeCdn.getAcceptRangesUri(), destination);

        Assumptions.abort("TODO");
    }

}
