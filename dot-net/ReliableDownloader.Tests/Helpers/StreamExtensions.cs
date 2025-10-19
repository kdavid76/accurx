namespace Accurx.ReliableDownloader.Tests.Helpers;

internal static class StreamExtensions
{
    public static async Task<string> GetContentAsync(this Stream destination)
    {
        destination.Position = 0;
        using var reader = new StreamReader(destination);
        return await reader.ReadToEndAsync();
    }
}
