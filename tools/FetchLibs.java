import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Prints the {@code libraries[].name} lines of a Minecraft version JSON, optionally filtered.
 * Usage: java FetchLibs.java <versionJsonUrl> [filter]
 */
public final class FetchLibs {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        String filter = args.length > 1 ? args[1].toLowerCase() : "";
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "Mozilla/5.0 (compatible; dsh-probe/1.0)")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String body;
        try (InputStream in = response.body()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.println("status=" + response.statusCode() + " chars=" + body.length());
        int librariesAt = body.indexOf("\"libraries\"");
        String tail = librariesAt < 0 ? body : body.substring(librariesAt);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(tail);
        while (m.find()) {
            String name = m.group(1);
            if (filter.isEmpty() || name.toLowerCase().contains(filter)) {
                System.out.println("  " + name);
            }
        }
    }
}
