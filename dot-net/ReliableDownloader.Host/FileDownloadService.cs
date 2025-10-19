using System.Security.Cryptography;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace Accurx.ReliableDownloader.Host;

internal record FileDownloadServiceSettings
{
    public static string SectionName => "FileDownloadService";

    public required Uri SourceUrl { get; init; }
    public required string DestinationFilePath { get; init; }
}

internal sealed class FileDownloadService(
    ILogger<FileDownloadService> logger,
    IOptions<FileDownloadServiceSettings> downloadSettings,
    FileDownloader fileDownloader
) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        var settings = downloadSettings.Value;

        logger.LogInformation(
            "Starting download from {SourceUrl} to {DestinationFilePath}...",
            settings.SourceUrl,
            Path.Join(Directory.GetCurrentDirectory(), settings.DestinationFilePath)
        );

        await using var destination = File.Open(
            settings.DestinationFilePath,
            FileMode.OpenOrCreate,
            FileAccess.ReadWrite
        );

        var contentMd5 = await fileDownloader.DownloadAsync(
            settings.SourceUrl,
            destination,
            cancellationToken
        );

        if (contentMd5 is not null)
        {
            using var md5 = MD5.Create();
            destination.Position = 0;

            var computedMd5 = await md5.ComputeHashAsync(destination, cancellationToken);

            if (computedMd5.SequenceEqual(contentMd5))
            {
                logger.LogInformation("MD5 hash is present, download integrity verified.");
            }
            else
            {
                logger.LogError("Download failed!");
            }
        }
        else
        {
            logger.LogWarning("MD5 hash is not present, download integrity was not verified.");
        }
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        logger.LogInformation("FileDownloadService is stopping...");
        return Task.CompletedTask;
    }
}
