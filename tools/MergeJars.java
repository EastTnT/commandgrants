import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Merges Minecraft's client and server jars the way Loom intends {@code MinecraftJarMerger} to.
 *
 * <p>Loom 1.18.2 emits a 22-byte (empty) {@code minecraft-merged.jar} for 26.2 without reporting an
 * error, which leaves every {@code net.minecraft.*} import unresolved. This tool builds the archive
 * directly instead: client entries win where both sides have a name, server-only entries are added
 * afterwards. Entries are stored with {@code STORED} so the input bytes are copied verbatim and
 * without needing to trust the size fields of either jar.
 *
 * <p>Usage: {@code java tools/MergeJars.java <client.jar> <server.jar> <out.jar>}
 */
public final class MergeJars {
    private static final String MANIFEST = "META-INF/MANIFEST.MF";

    /** Matches Loom's own handling: jar signatures cannot survive a re-pack. */
    private static boolean isSignature(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        return name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: MergeJars <client.jar> <server.jar> <out.jar>");
            System.exit(2);
        }

        Path clientPath = Path.of(args[0]);
        Path serverPath = Path.of(args[1]);
        Path outPath = Path.of(args[2]);

        if (!Files.isRegularFile(clientPath) || !Files.isRegularFile(serverPath)) {
            System.err.println("missing input jar(s)");
            System.exit(3);
        }

        Files.createDirectories(outPath.toAbsolutePath().getParent());

        int copied = 0;
        int duplicates = 0;
        Set<String> seen = new HashSet<>();

        try (ZipFile client = new ZipFile(clientPath.toFile());
                ZipFile server = new ZipFile(serverPath.toFile());
                ZipOutputStream out = new ZipOutputStream(
                        new FileOutputStream(outPath.toFile()), StandardCharsets.UTF_8)) {
            out.setMethod(ZipOutputStream.STORED);
            out.setLevel(0);

            // A minimal manifest: Loom replaces the original with exactly this, and nothing in the
            // compile classpath reads the rest of it.
            byte[] manifest = "Manifest-Version: 1.0\nMain-Class: net.minecraft.client.Main\n"
                    .getBytes(StandardCharsets.UTF_8);
            putStored(out, MANIFEST, manifest);
            seen.add(MANIFEST);

            for (ZipFile jar : new ZipFile[] { client, server }) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (entry.isDirectory() || isSignature(name)) {
                        continue;
                    }
                    if (!seen.add(name)) {
                        // The client copy wins, matching Loom's own precedence.
                        duplicates++;
                        continue;
                    }

                    putStored(out, name, readAll(jar, entry));
                    copied++;
                }
            }
        }

        System.out.println("merge: client=" + clientPath.getFileName()
                + " server=" + serverPath.getFileName()
                + " -> " + outPath.toAbsolutePath()
                + " entries=" + copied
                + " skippedDuplicates=" + duplicates
                + " bytes=" + Files.size(outPath));
    }

    private static byte[] readAll(ZipFile jar, ZipEntry entry) throws Exception {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /**
     * Writes one entry as {@code STORED}. The whole archive is read into memory one entry at a
     * time, which is required because {@code STORED} entries must carry a correct CRC and size in
     * their local header.
     */
    private static void putStored(ZipOutputStream out, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc32(data));
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static long crc32(byte[] data) {
        var crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
