import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Prints the package + class names under a jar directory, to locate classes by simple name. */
public final class ListClasses {
    public static void main(String[] args) throws Exception {
        String needle = args[0].toLowerCase();
        try (ZipFile zip = new ZipFile(args[1])) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (!n.endsWith(".class")) {
                    continue;
                }
                String simple = n.substring(n.lastIndexOf('/') + 1, n.length() - 6);
                if (simple.toLowerCase().contains(needle)) {
                    System.out.println(n);
                }
            }
        }
    }
}
