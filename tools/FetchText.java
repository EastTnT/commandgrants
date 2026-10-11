import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Prints a URL's body as text (used where the shell's TLS stack is unavailable).
 * Usage: java FetchText.java <url> [maxChars]
 */
public final class FetchText {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 20000;
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "Mozilla/5.0 (compatible; dsh-probe/1.0)")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("status=" + response.statusCode()
                + " contentType=" + response.headers().firstValue("Content-Type").orElse("?")
                + " bytes=" + response.body().length
                + " finalUrl=" + response.uri());
        for (var h : response.headers().map().entrySet()) {
            if (h.getKey().toLowerCase().contains("disposition") || h.getKey().toLowerCase().contains("content-type")) {
                System.out.println("header " + h.getKey() + ": " + h.getValue());
            }
        }
        String body = new String(response.body(), StandardCharsets.UTF_8);
        System.out.println("---- body ----");
        System.out.println(body.substring(0, Math.min(max, body.length())));
    }
}
