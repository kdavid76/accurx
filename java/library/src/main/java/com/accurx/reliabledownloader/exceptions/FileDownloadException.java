package com.accurx.reliabledownloader.exceptions;

/**
 * A custom exception class that represents errors encountered during file download operations.
 * <p>
 * This exception is used to signify issues that occur while attempting to download or
 * validate the status of a file. It can encapsulate a specific error message,
 * and optionally, an underlying cause for the exception.
 * <p>
 * Typical use cases include scenarios where an HTTP response indicates an
 * unexpected status code or where a network-related issue occurs while sending
 * an HTTP request.
 */
public class FileDownloadException extends Exception {

    public FileDownloadException(String message) {
        super(message);
    }

    public FileDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
