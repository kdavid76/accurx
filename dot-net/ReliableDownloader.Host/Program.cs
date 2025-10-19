using Accurx.ReliableDownloader;
using Accurx.ReliableDownloader.Host;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

var builder = Host.CreateApplicationBuilder(args);
builder
    .Services.Configure<FileDownloadServiceSettings>(
        builder.Configuration.GetSection(FileDownloadServiceSettings.SectionName)
    )
    .AddHttpClient<FileDownloader>()
    .Services.AddHostedService<FileDownloadService>();

using var host = builder.Build();
await host.RunAsync();
