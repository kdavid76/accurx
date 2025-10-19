package com.accurx.reliabledownloader;

import java.io.OutputStream;
import java.net.URI;
import java.util.Optional;

public interface FileDownloader
{
    /**
     * Downloads a file, trying to use reliable downloading if possible
     * @param contentFileUrl The url which the file is hosted at
     * @param destination The stream to write the file contents to
     * @return the MD5 checksum if present (b64 encoded)
     */
    Optional<String> downloadFile(URI contentFileUrl, OutputStream destination) throws Exception;
}
