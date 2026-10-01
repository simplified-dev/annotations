package dev.simplified.testutil;

import com.google.testing.compile.Compilation;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The class files a compilation wrote, put on disk where a class loader or a
 * later compilation can read them.
 *
 * <p>Each compilation gets a directory of its own under one root, created the
 * first time this JVM asks for one and deleted with everything under it when the
 * JVM exits - so a run leaves nothing in the temporary directory however many
 * compilations it wrote out.
 */
public final class CompiledClasses {

    /** The directory every compilation's class files are written under, created on first use. */
    private static Path root;

    private CompiledClasses() {
    }

    /**
     * Writes a compilation's class files to a directory of their own.
     *
     * @param compilation the finished compilation
     * @return the directory holding its class files, laid out by package
     * @throws IOException when a file cannot be written
     */
    public static Path classesOf(Compilation compilation) throws IOException {
        Path directory = Files.createTempDirectory(root(), "classes");
        for (JavaFileObject file : compilation.generatedFiles()) {
            if (file.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = file.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            String relative = anchor >= 0 ? uri.substring(anchor + "CLASS_OUTPUT/".length()) : file.getName();
            Path destination = directory.resolve(relative);
            Files.createDirectories(destination.getParent());
            try (InputStream in = file.openInputStream()) {
                Files.write(destination, in.readAllBytes());
            }
        }
        return directory;
    }

    /**
     * Loads a compilation's classes over the test's own class path.
     *
     * @param compilation the finished compilation
     * @return a loader reading the compilation's class files, delegating to the test's loader first
     * @throws IOException when a file cannot be written
     */
    public static ClassLoader loadClasses(Compilation compilation) throws IOException {
        return new URLClassLoader(new URL[]{classesOf(compilation).toUri().toURL()},
            CompiledClasses.class.getClassLoader());
    }

    /** Creates the root on first use and schedules its deletion for when the JVM exits. */
    private static synchronized Path root() throws IOException {
        if (root == null) {
            Path created = Files.createTempDirectory("apt-test-classes");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(created), "delete " + created));
            root = created;
        }
        return root;
    }

    /** Deletes a directory and everything under it, leaving whatever refuses to go. */
    private static void delete(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The JVM is exiting, so there is no one left to report a straggler to.
                }
            }
        } catch (IOException ignored) {
            // As above - the walk failing leaves the root, which is all it can do.
        }
    }

}
