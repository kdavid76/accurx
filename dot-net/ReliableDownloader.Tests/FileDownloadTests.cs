using Accurx.ReliableDownloader.Tests.Helpers;
using Microsoft.Extensions.Logging.Abstractions;

namespace Accurx.ReliableDownloader.Tests;

/// <remarks>
/// Feel free to swap out NUnit for any other test framework you may prefer and to use an alternative for mocking the
/// HttpClient/HttpMessageHandler.
///
/// We're looking for unit tests only around your range requests implementation, please test your resilience
/// implementation manually by running the Host project, we're not expecting you to write integration tests.
/// </remarks>>
[FixtureLifeCycle(LifeCycle.InstancePerTestCase)]
public sealed class FileDownloadTests
{
    private readonly FileDownloader _sut;
    private readonly FakeCdn _fakeCdn;

    public FileDownloadTests()
    {
        _fakeCdn = new FakeCdn();
        _sut = new FileDownloader(new NullLogger<FileDownloader>(), new HttpClient(_fakeCdn));
    }

    [Test]
    public async Task It_downloads_content_when_accept_ranges_is_not_supported()
    {
        // Arrange
        var source = _fakeCdn.NoRanges;
        var destination = new MemoryStream();

        // Act
        await _sut.DownloadAsync(source, destination);

        // Assert
        var content = await destination.GetContentAsync();
        Assert.That(content, Is.EqualTo(_fakeCdn.Content));
    }

    [Test]
    public async Task It_returns_the_integrity_hash_when_present()
    {
        // Arrange
        var source = _fakeCdn.NoRanges;
        var destination = new MemoryStream();

        // Act
        var integrityHash = await _sut.DownloadAsync(source, destination);

        // Assert
        Assert.That(integrityHash, Is.EquivalentTo(_fakeCdn.Hash!));
    }

    [Test]
    public async Task It_downloads_content_in_chunks_when_accept_ranges_is_supported()
    {
        // Arrange
        var destination = new MemoryStream();

        // Act
        await _sut.DownloadAsync(_fakeCdn.AcceptsRanges, destination);

        // Assert
        Assert.Inconclusive("TODO");
    }
}
