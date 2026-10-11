import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts a chosen subset of classes from the Minecraft jar into a directory tree,
 * so a decompiler can be pointed at individual classes. Keeps output small and reviewable.
 * Usage: java ExtractClasses.java <jar> <outDir> <prefix>...
 */
public final class ExtractClasses {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        Path out = Path.of(args[1]);
        List<String> prefixes = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            prefixes.add(args[i]);
        }
        int written = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || !matches(name, prefixes)) {
                    continue;
                }
                Path target = out.resolve(name);
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                written++;
            }
        }
        System.out.println("extracted " + written + " classes to " + out.toAbsolutePath());
    }

    private static boolean matches(String name, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return true;
        }
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
