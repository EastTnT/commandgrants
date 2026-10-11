import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone re-run of Loom's jar merge, outside Gradle.
 *
 * <p>Loom produces an empty {@code minecraft-merged.jar} for 26.2 and never says why, so this drives
 * {@code MinecraftJarMerger} directly: same jar, same classpath, but every exception printed instead
 * of swallowed by the daemon.
 *
 * <p>Usage: {@code java MergeProbe.java <loomJar> <clientJar> <serverJar> <outJar> <libDir>}
 */
public final class MergeProbe {
    public static void main(String[] args) throws Exception {
        Path loomJar = Path.of(args[0]);
        File client = new File(args[1]);
        File server = new File(args[2]);
        File out = new File(args[3]);
        Path libDir = Path.of(args[4]);

        System.out.println("loom=" + loomJar + " exists=" + loomJar.toFile().exists());
        System.out.println("client=" + client + " size=" + client.length());
        System.out.println("server=" + server + " size=" + server.length());

        try (URLClassLoader loader = new URLClassLoader(new URL[] {loomJar.toUri().toURL()},
                MergeProbe.class.getClassLoader())) {
            Class<?> mergerClass = Class.forName(
                    "net.fabricmc.loom.configuration.providers.minecraft.MinecraftJarMerger", true, loader);
            System.out.println("mergerClass=" + mergerClass);

            // The merger is a Closeable; construct, enable the synthetic-parameters offset, merge.
            Object merger = null;
            for (var ctor : mergerClass.getDeclaredConstructors()) {
                System.out.println("ctor " + ctor);
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length == 3 && types[0] == File.class) {
                    ctor.setAccessible(true);
                    merger = ctor.newInstance(client, server, out);
                } else if (types.length == 4 && types[0] == File.class) {
                    ctor.setAccessible(true);
                    merger = ctor.newInstance(client, server, out, List.of());
                }
            }
            if (merger == null) {
                System.out.println("no usable constructor");
                return;
            }
            System.out.println("merger=" + merger);

            for (Method method : mergerClass.getMethods()) {
                if (method.getName().equals("enableSyntheticParamsOffset") || method.getName().equals("merge")) {
                    System.out.println("invoking " + method);
                    try {
                        method.invoke(merger);
                    } catch (Throwable t) {
                        System.out.println("FAILED " + method.getName() + ": " + t);
                        t.printStackTrace(System.out);
                    }
                }
            }

            for (Field field : mergerClass.getDeclaredFields()) {
                field.setAccessible(true);
                System.out.println("field " + field.getName() + " = " + field.get(merger));
            }
            ((AutoCloseable) merger).close();
        }
        System.out.println("result size=" + out.length());
    }
}
