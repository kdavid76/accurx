using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;

namespace Accurx.ReliableDownloader.Tests.Helpers;

/// <summary>
/// A fake implementation of <see cref="HttpMessageHandler"/> for testing purposes that simulates file downloads.
/// It provides two URIs: one that accepts range requests and one that does not.
/// </summary>
internal sealed class FakeCdn : HttpMessageHandler
{
    private const string AcceptsRangesBytes = "bytes";
    private const string DispositionTypeAttachment = "attachment";

    /// <summary>
    /// A fake implementation of <see cref="HttpMessageHandler"/> for testing purposes that simulates file downloads.
    /// It provides two URIs: one that accepts range requests and one that does not.
    /// </summary>
    public FakeCdn(string filename = "example.txt", string content = "Example download content")
    {
        Filename = filename;
        Content = content;
        Hash = MD5.HashData(Encoding.UTF8.GetBytes(content));
        NoRanges = new Uri($"https://example.com/{filename}");
        AcceptsRanges = new Uri($"https://example.com/accept-ranges-{filename}");
    }

    public string Filename { get; }
    public byte[]? Hash { get; }

    public Uri NoRanges { get; }
    public Uri AcceptsRanges { get; }
    public string Content { get; }

    protected override Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken
    )
    {
        cancellationToken.ThrowIfCancellationRequested();

        var response = new HttpResponseMessage();
        response.RequestMessage = request;

        SetAcceptRangesHeader();

        if (request.Method == HttpMethod.Head)
        {
            HandleHeadRequest();
        }
        else if (request.Method == HttpMethod.Get)
        {
            HandleGetRequest();
        }
        else
        {
            throw new NotSupportedException(request.Method.ToString());
        }

        response.Content.Headers.ContentMD5 = Hash;
        response.Content.Headers.ContentDisposition = new ContentDispositionHeaderValue(
            DispositionTypeAttachment
        )
        {
            FileName = Filename,
        };

        return Task.FromResult(response);

        void SetAcceptRangesHeader()
        {
            if (request.RequestUri == AcceptsRanges)
            {
                response.Headers.AcceptRanges.Add(AcceptsRangesBytes);
            }
            else if (request.RequestUri == NoRanges)
            {
                // Don't add Accept-Ranges header
            }
            else
            {
                throw new NotSupportedException(request.RequestUri?.ToString());
            }
        }

        void HandleHeadRequest()
        {
            response.Content.Headers.ContentLength = Encoding.UTF8.GetByteCount(Content);
        }

        void HandleGetRequest()
        {
            switch (request)
            {
                case { Headers.Range: null }:
                    SetWholeContent();
                    break;
                case { Headers.Range: { Unit: AcceptsRangesBytes, Ranges.Count: 1 } }:
                    SetPartialContent();
                    break;
                default:
                    throw new NotSupportedException("Unexpected request configuration");
            }
        }

        void SetWholeContent()
        {
            if (request.RequestUri == AcceptsRanges)
            {
                throw new InvalidOperationException("Null range header used with AcceptsRangesUri");
            }
            response.Content = new StringContent(Content);
            response.Content.Headers.ContentLength = Encoding.UTF8.GetByteCount(Content);
        }

        void SetPartialContent()
        {
            if (request.RequestUri != AcceptsRanges)
            {
                throw new InvalidOperationException(
                    "Range header can only be used with AcceptsRangesUri"
                );
            }

            var bytes = Encoding.UTF8.GetBytes(Content);

            var ranges = request.Headers.Range.Ranges;

            if (ranges.Count > 1)
            {
                throw new NotSupportedException("Multipart ranges are not supported");
            }

            var range = ranges.Single();

            var from = (int)range.From!.Value;
            var to = (int)range.To!.Value + 1;

            if (to > bytes.Length)
            {
                response.StatusCode = HttpStatusCode.RequestedRangeNotSatisfiable;
            }
            else
            {
                response.StatusCode = HttpStatusCode.PartialContent;
                response.Content = new ByteArrayContent(bytes[from..to]);
                response.Content.Headers.ContentLength = to - from;
            }
        }
    }
}
