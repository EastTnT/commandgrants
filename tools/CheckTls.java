import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Enumeration;

/** Probes TLS trust for the Maven hosts Loom needs, and lists trust anchors for the failing ones. */
public final class CheckTls {
    public static void main(String[] args) throws Exception {
        String[] hosts = {
            "https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar",
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml",
            "https://repo1.maven.org/maven2/org/ow2/asm/asm/maven-metadata.xml",
            "https://services.gradle.org/distributions/",
            "https://libraries.minecraft.net/",
            "https://resources.download.minecraft.net/",
            "https://meta.fabricmc.net/v2/versions/loader",
            "https://plugins.gradle.org/m2/",
        };
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        for (String url : hosts) {
            String verdict;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "probe/1.0")
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
                verdict = "OK status=" + res.statusCode();
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                verdict = "FAIL " + root.getClass().getSimpleName() + ": " + root.getMessage();
            }
            System.out.println(verdict + "  <-  " + url);
        }

        System.out.println();
        System.out.println("trust anchor count = " + trustAnchorCount());
    }

    private static int trustAnchorCount() throws Exception {
        KeyStore ks = KeyStore.getInstance("Windows-ROOT");
        ks.load(null, null);
        int n = 0;
        for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); e.nextElement()) {
            if (ks.getCertificate(e.nextElement()) instanceof X509Certificate) {
                n++;
            }
        }
        return n;
    }
}
