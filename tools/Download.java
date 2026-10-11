import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Minimal downloader used only when the shell's native TLS stack is unavailable.
 * Usage: java Download.java <url> <outFile>
 */
public final class Download {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java Download.java <url> <outFile>");
            System.exit(2);
        }
        URI uri = URI.create(args[0]);
        Path out = Path.of(args[1]);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "Mozilla/5.0 (compatible; dsh-downloader/1.0)")
                .GET()
                .build();
        Exception last = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                System.out.println("status=" + response.statusCode() + " attempt=" + attempt);
                if (response.statusCode() / 100 != 2) {
                    response.body().close();
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                long total;
                try (InputStream in = response.body()) {
                    total = Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                System.out.println("wrote=" + out.toAbsolutePath() + " bytes=" + total);
                return;
            } catch (Exception e) {
                last = e;
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                System.err.println("attempt " + attempt + " failed: " + root.getClass().getSimpleName() + ": " + root.getMessage());
                try {
                    Thread.sleep(1500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw new IllegalStateException("all attempts failed", last);
    }
}
