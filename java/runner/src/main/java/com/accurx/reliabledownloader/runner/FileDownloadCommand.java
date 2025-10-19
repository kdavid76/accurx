package com.accurx.reliabledownloader.runner;

import com.accurx.reliabledownloader.FileDownloadSettings;
import com.accurx.reliabledownloader.FileDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public class FileDownloadCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileDownloadCommand.class);

    private final FileDownloader fileDownloader;
    private final FileDownloadSettings downloadSettings;

    public FileDownloadCommand(FileDownloader fileDownloader, FileDownloadSettings downloadSettings) {
        this.fileDownloader = fileDownloader;
        this.downloadSettings = downloadSettings;
    }

    public void run() throws Exception {

        Path destinationFilePath = downloadSettings.destinationFilePath();

        LOGGER.info(
                "Starting download from {} to {}...",
                downloadSettings.sourceUrl(),
                destinationFilePath.toAbsolutePath()
        );

        Optional<String> contentMd5Opt;

        try (OutputStream outputStream = Files.newOutputStream(destinationFilePath, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE))
        {
            contentMd5Opt = fileDownloader.downloadFile(downloadSettings.sourceUrl(), outputStream);
        }

        if (contentMd5Opt.isPresent()) {
            String contentMd5 = contentMd5Opt.get();
            String computedMd5 = Md5.contentMd5(destinationFilePath.toFile());
            if (contentMd5.equals(computedMd5)) {
                LOGGER.info("MD5 hash is present, download integrity verified.");
            }
            else {
                LOGGER.error("Download failed expectedMd5={}, foundMd5={}!", contentMd5, computedMd5);
            }
        } else {
            LOGGER.warn("MD5 hash is not present, download integrity was not verified.");
        }
    }
}
