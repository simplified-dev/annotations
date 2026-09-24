package dev.simplified.testutil;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.module.Module;
import com.intellij.testFramework.PsiTestUtil;
import dev.simplified.annotations.ClassBuilder;
import org.jetbrains.annotations.NotNull;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * A jar of class files compiled from source inside a test, with or without the
 * library's annotation processor, and attached to a fixture's module as a
 * library - so an editor fixture reads a type the way it reads one from a
 * dependency, through its class file.
 *
 * <p>The compile is javac's own, run in the test JVM against the library the
 * plugin bundles: the processor, when asked for, is the one a consumer's build
 * runs, so the class files carry whatever that build writes into them - the
 * members it generated and the {@code Generated} annotation on each.
 */
public final class CompiledLibrary {

    private CompiledLibrary() {
    }

    /**
     * Compiles sources to a jar.
     *
     * @param processed whether the library's annotation processor runs over them
     * @param sources the sources, keyed by their path under the source root, such as {@code demo/Shape.java}
     * @return the jar holding the class files
     * @throws IOException when a file cannot be written
     * @throws AssertionError when javac reports an error
     */
    public static @NotNull Path compile(boolean processed, @NotNull Map<String, String> sources) throws IOException {
        Path work = Files.createTempDirectory("compiled-library");
        Path sourceRoot = Files.createDirectories(work.resolve("src"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceRoot.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            files.add(file);
        }

        // Found through the boot layer: ToolProvider looks through the system
        // class loader, which the platform's test runner replaces with one that
        // does not reach the jdk.compiler service.
        JavaCompiler javac = ServiceLoader.load(ModuleLayer.boot(), JavaCompiler.class).findFirst()
            .orElseThrow(() -> new AssertionError("the test JVM carries no javac"));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = javac.getStandardFileManager(diagnostics, Locale.ROOT,
            StandardCharsets.UTF_8)) {
            Path library = rootOf(ClassBuilder.class);
            Path jetbrains = rootOf(NotNull.class);
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(library, jetbrains));
            List<String> options = new ArrayList<>(List.of("--release", "17"));
            if (processed) manager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of(library));
            else options.add("-proc:none");
            boolean built = javac.getTask(null, manager, diagnostics, options, null,
                manager.getJavaFileObjectsFromPaths(files)).call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) errors.add(diagnostic.getMessage(Locale.ROOT));
            }
            if (!built || !errors.isEmpty()) throw new AssertionError("javac refused the library: " + errors);
        }
        return jar(classes, work.resolve("compiled.jar"));
    }

    /**
     * Attaches a jar to a module as a library, detached again when the parent
     * is disposed.
     *
     * @param parent the disposable whose end detaches it, the test's root disposable
     * @param module the fixture's module
     * @param jar the jar
     */
    public static void attach(@NotNull Disposable parent, @NotNull Module module, @NotNull Path jar) {
        String directory = jar.getParent().toString().replace('\\', '/');
        PsiTestUtil.addLibrary(parent, module, "compiled-" + jar.getParent().getFileName(), directory + "/",
            jar.getFileName().toString());
    }

    /** Packs every class file under a directory into a jar. */
    private static Path jar(Path classes, Path jar) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream archive = new JarOutputStream(out);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                archive.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                archive.write(Files.readAllBytes(file));
                archive.closeEntry();
            }
        }
        return jar;
    }

    /** The directory or jar a class was loaded from. */
    private static Path rootOf(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        URL url = type.getClassLoader().getResource(resource);
        if (url == null) throw new AssertionError("no class file for " + type.getName());
        try {
            String text = url.toString();
            if (text.startsWith("jar:")) return Paths.get(URI.create(text.substring(4, text.indexOf("!/"))));
            Path file = Paths.get(url.toURI());
            for (int i = 0; i < type.getName().split("\\.").length; i++) file = file.getParent();
            return file;
        } catch (URISyntaxException e) {
            throw new AssertionError("cannot locate " + type.getName(), e);
        }
    }

}
