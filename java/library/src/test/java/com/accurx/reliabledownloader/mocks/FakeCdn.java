package com.accurx.reliabledownloader.mocks;

import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * A fake Cdn implementation for testing purposes that simulates file downloads
 * It provides two URIs, one that accepts range requests and one that does not.
 */
public class FakeCdn implements BeforeAllCallback, AfterAllCallback {

    private static final String ACCEPT_RANGES_HEADER = "Accept-Ranges";
    private static final String CONTENT_DISPOSITION_HEADER = "Content-Disposition";
    private static final String CONTENT_MD5_HEADER = "Content-MD5";
    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String ACCEPTS_RANGES_BYTES = "bytes";

    private final String fileName;
    private final String content;
    private final String contentHash;
    private final String acceptRangesPath;
    private final String noAcceptRangesPath;
    private final MockWebServer server;

    public FakeCdn(String fileName, String content) {
        this.fileName = fileName;
        this.acceptRangesPath = "/accept-ranges/" + fileName;
        this.noAcceptRangesPath = "/no-accept-ranges/" + fileName;
        this.content = content;
        this.contentHash = BaseEncoding.base64().encode(
                Hashing.md5().hashUnencodedChars(content).asBytes()
        );
        this.server = new MockWebServer();
        this.server.setDispatcher(new CndDispatcher());
    }

    public URI getNoRangeUri()
    {
        return server.url(noAcceptRangesPath).uri();
    }

    public URI getAcceptRangesUri()
    {
        return server.url(acceptRangesPath).uri();
    }

    public String getContent() {
        return content;
    }

    public String getContentHash()
    {
        return contentHash;
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        server.shutdown();
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        server.start();
    }

    private class CndDispatcher extends Dispatcher {

        @Override
        public MockResponse dispatch(RecordedRequest request) {

            MockResponse mockResponse = new MockResponse();

            if (noAcceptRangesPath.equals(request.getPath()))
            {
                // Don't add Accept-Ranges header
            }
            else if (acceptRangesPath.equals(request.getPath()))
            {
                mockResponse.addHeader(ACCEPT_RANGES_HEADER, ACCEPTS_RANGES_BYTES);
            }
            else
            {
                throw new UnsupportedOperationException("Unsupported request path " + request.getPath());
            }


            if ("HEAD".equals(request.getMethod()))
            {
                mockResponse.addHeader(CONTENT_LENGTH, content.getBytes(StandardCharsets.UTF_8));
            }
            else if ("GET".equals(request.getMethod()))
            {
                String rangeHeader = request.getHeader("Range");
                if (rangeHeader == null)
                {
                    mockResponse.setBody(content);
                }
                 else
                 {
                    handlePartialDownload(rangeHeader, mockResponse);
                }
            }

            mockResponse.addHeader(CONTENT_DISPOSITION_HEADER,"attachment; filename=\"" +  fileName + "\"");
            mockResponse.addHeader(CONTENT_MD5_HEADER, contentHash);

            return mockResponse;
        }

        private void handlePartialDownload(String rangeHeader, MockResponse mockResponse) {

            Range downloadRange = Range.parseOne(rangeHeader);

            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

            int from = downloadRange.start;
            int to = downloadRange.end + 1;

            if (to > bytes.length)
            {
                mockResponse.setResponseCode(416);
            }
            else
            {
                Buffer buffer = new Buffer().write(Arrays.copyOfRange(bytes, from, to));
                mockResponse.setResponseCode(206);
                mockResponse.setBody(buffer);
                mockResponse.setHeader(CONTENT_LENGTH, to - from);
            }
        }
    }

    private record Range(int start, int end) {

        private static final Pattern rangeHeaderMatcher = Pattern.compile("^bytes=(?<from>\\d+)-(?<to>\\d+)$");

        public static Range parseOne(String rangeHeader)
        {
            Matcher matcher = rangeHeaderMatcher.matcher(rangeHeader);
            if (!matcher.matches())
            {
                throw new UnsupportedOperationException("Unsupported range header " + rangeHeader);
            }
            return new Range(Integer.parseInt(matcher.group("from")), Integer.parseInt(matcher.group("to")));
        }
    }
}

