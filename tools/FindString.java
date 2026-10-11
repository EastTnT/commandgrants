import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lists the class entries of a jar whose bytecode contains a given literal string.
 * Usage: java FindString.java <needle> <jar>
 */
public final class FindString {
    public static void main(String[] args) throws Exception {
        String needle = args[0];
        String jar = args[1];
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int hits = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = readAll(in);
                    if (contains(bytes, needle)) {
                        System.out.println(entry.getName());
                        hits++;
                    }
                }
            }
            System.out.println("hits=" + hits);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        return in.readAllBytes();
    }

    /** Constant-pool UTF-8 entries are plain modified-UTF-8, so a byte scan is a safe over-approximation. */
    private static boolean contains(byte[] haystack, String needle) {
        byte[] needleBytes = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needleBytes.length <= haystack.length; i++) {
            for (int j = 0; j < needleBytes.length; j++) {
                if (haystack[i + j] != needleBytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
