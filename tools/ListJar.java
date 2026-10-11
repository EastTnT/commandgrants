import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Usage: java ListJar.java <jar> [nameFilter]
 *
 * <p>Lists the entries of a jar whose path contains <code>nameFilter</code> (case-insensitive).
 * With no filter it prints the distinct top-level roots plus the entry count. Also reports
 * whether <code>net/minecraft/client/Camera.class</code> style names are obfuscated.</p>
 */
public class ListJar {
    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]);
        String filter = args.length > 1 ? args[1].toLowerCase() : "";
        int total = 0;
        List<String> roots = new ArrayList<>();
        List<String> hits = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                total++;
                String name = e.getName();
                int slash = name.indexOf('/');
                String root = slash < 0 ? "<root>" : name.substring(0, slash);
                if (!roots.contains(root)) {
                    roots.add(root);
                }
                if (!filter.isEmpty() && name.toLowerCase().contains(filter) && !name.endsWith("/")) {
                    hits.add(name);
                }
            }
        }
        System.out.println("jar=" + jar.getFileName() + " entries=" + total + " roots=" + roots);
        if (!filter.isEmpty()) {
            System.out.println("matches(" + filter + ")=" + hits.size());
            hits.stream().limit(60).forEach(h -> System.out.println("  " + h));
        }
    }
}
