import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ProbeHosts {
    public static void main(String[] a) {
        HttpClient c = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        for (String u : a) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(u))
                        .timeout(Duration.ofSeconds(30))
                        .method("GET", HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<Void> r = c.send(req, HttpResponse.BodyHandlers.discarding());
                System.out.println("OK status=" + r.statusCode() + "  <- " + u);
            } catch (Exception e) {
                System.out.println("FAIL " + e.getClass().getSimpleName() + ": "
                        + String.valueOf(e.getMessage()).replace('\n', ' ') + "  <- " + u);
            }
        }
    }
}
