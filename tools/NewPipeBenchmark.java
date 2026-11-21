import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.*;

class NewPipeBenchmark {
    static HttpClient client() {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1);
        var setting = System.getenv("HTTPS_PROXY");
        if (setting != null && !setting.isBlank()) {
            var proxy = URI.create(setting);
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        }
        return builder.build();
    }
    static boolean youtube(URI uri) {
        var host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null && uri.getUserInfo() == null &&
            (uri.getPort() == -1 || uri.getPort() == 443) &&
            (host.equals("youtube.com") || host.endsWith(".youtube.com") ||
                host.equals("youtubei.googleapis.com") || host.endsWith(".googlevideo.com") ||
                host.endsWith(".ytimg.com"));
    }
    static String group(URI uri) {
        String path = uri.getPath();
        if (path.contains("/player/")) return "player_js";
        if (path.endsWith("/player")) return "player_api";
        if (path.equals("/watch")) return "watch";
        return uri.getHost().endsWith(".googlevideo.com") ? "cdn" : "other";
    }
    static class TimedDownloader extends Downloader {
        final HttpClient http = client();
        @Override public Response execute(Request req) throws IOException {
            URI uri = URI.create(req.url());
            for (int hops = 0; hops < 5; hops++) {
                if (!youtube(uri)) throw new IOException("unexpected origin");
                var method = req.httpMethod();
                var bytes = req.dataToSend();
                var build = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .method(method, bytes == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(bytes));
                req.headers().forEach((k, values) -> {
                    if (!Set.of("host","connection","content-length","upgrade","expect","authorization","proxy-authorization").contains(k.toLowerCase(Locale.ROOT))) {
                        values.forEach(v -> build.header(k,v));
                    }
                });
                long start = System.nanoTime();
                try {
                    var r = http.send(build.build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (var body = r.body()) {
                        int status = r.statusCode();
                        var bytesRead = (status >= 300 && status < 400) ? new byte[0] : body.readNBytes(12_000_001);
                        System.out.printf("  %s %s %d %.3fs%n", group(uri), method, status, (System.nanoTime()-start)/1e9);
                        if (status >= 300 && status < 400 && r.headers().firstValue("location").isPresent()) {
                            uri = uri.resolve(r.headers().firstValue("location").orElseThrow());
                            continue;
                        }
                        if (bytesRead.length > 12_000_000) throw new IOException("oversized response");
                        return new Response(status, "", r.headers().map(), new String(bytesRead, StandardCharsets.UTF_8), uri.toString());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            throw new IOException("redirect limit");
        }
    }
    static void download(TimedDownloader dl, Stream stream, String file) throws Exception {
        var uri = URI.create(stream.getContent());
        if (!youtube(uri)) { System.out.println("unrecognized media origin"); return; }
        var r = dl.http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = r.body()) {
            var buffer = body.readNBytes(25_000_001);
            var type = r.headers().firstValue("Content-Type").orElse("");
            boolean mp4 = buffer.length >= 12 && new String(buffer,4,4, StandardCharsets.US_ASCII).equals("ftyp");
            System.out.printf("transfer %s status=%d bytes=%d mp4=%s html=%s%n", stream.getFormat(),r.statusCode(),buffer.length,mp4,type.toLowerCase(Locale.ROOT).contains("html"));
            if (r.statusCode()==200 && buffer.length <= 25_000_000 && mp4) Files.write(Path.of(file),buffer);
        }
    }
    public static void main(String[] args) {
        var dl = new TimedDownloader();
        NewPipe.init(dl);
        var start = System.nanoTime();
        String phase = "extract";
        try {
            var extractor = ServiceList.YouTube.getStreamExtractor(args[0]);
            extractor.fetchPage();
            var video = extractor.getVideoStreams();
            var adaptive = extractor.getVideoOnlyStreams();
            var audio = extractor.getAudioStreams();
            System.out.printf("metadata ok %.3fs muxed=%d adaptive=%d audio=%d%n", (System.nanoTime()-start)/1e9,video.size(),adaptive.size(),audio.size());
            phase = "verify";
            for (var stream : adaptive) {
                if (stream.getResolution().startsWith("360")) {
                    System.out.printf("adaptive 360 delivery=%s url=%s format=%s%n", stream.getDeliveryMethod(),stream.isUrl(),stream.getFormat());
                }
            }
            var av = adaptive.stream().filter(it -> it.isUrl() && it.getResolution().startsWith("360") &&
                it.getFormat() != null && it.getFormat().getSuffix().equals("mp4")).findFirst();
            var au = audio.stream().filter(it -> it.isUrl() && it.getFormat() != null &&
                it.getFormat().getSuffix().equals("m4a")).findFirst();
            if (av.isEmpty() || au.isEmpty()) { System.out.println("no URL-backed 360p MP4/AAC pair"); return; }
            download(dl, av.orElseThrow(), args[1]+".video.mp4");
            download(dl, au.orElseThrow(), args[1]+".audio.m4a");
        } catch (Exception e) {
            System.out.printf("%s failed %.3fs %s",phase,(System.nanoTime()-start)/1e9,e.getClass().getSimpleName());
            for (Throwable c=e.getCause(); c!=null; c=c.getCause()) System.out.print(" -> "+c.getClass().getSimpleName());
            System.out.println();
        }
    }
}
