package dev.simplified.classbuilder.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * Bootstrap methods on a {@code @ClassBuilder} interface. The builder for an
 * interface is a sibling top-level class, but the entry points live on the
 * interface itself so a target is entered the same way whatever its kind -
 * {@code Repo.builder()}, not {@code new RepoBuilder<>()}.
 *
 * <p>Static interface methods have been legal since Java 8 and a {@code default}
 * gives {@code mutate()} a receiver, so this needs no move onto the
 * AST-mutation path.
 */
public class InterfaceBootstrapTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("interface-bootstrap");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = f.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            String rel = anchor >= 0 ? uri.substring(anchor + "CLASS_OUTPUT/".length()) : f.getName();
            Path out = tmp.resolve(rel);
            Files.createDirectories(out.getParent());
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                in.transferTo(b);
                Files.write(out, b.toByteArray());
            }
        }
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, InterfaceBootstrapTest.class.getClassLoader());
    }

    @Test
    public void nonGenericInterface_entryPointsMatchAClass() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public interface Shape {",
                "    String name();",
                "    int sides();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static String go() {",
                "        Shape s = Shape.builder().name(\"tri\").sides(3).build();",
                "        Shape t = Shape.from(s).sides(4).build();",
                "        Shape u = t.mutate().name(\"quad\").build();",
                "        return u.name() + u.sides();",
                "    }",
                "}"));
        assertThat(c).succeeded();

        Class<?> use = Class.forName("demo.UseShape", true, loadClasses(c));
        assertEquals("quad4", use.getMethod("go").invoke(null));
    }

    @Test
    public void genericInterface_entryPointsCarryTheTypeParameter() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Repo",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public interface Repo<T> {",
                "    T head();",
                "    List<T> all();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRepo",
                "package demo;",
                "import java.util.List;",
                "public class UseRepo {",
                "    public static String go() {",
                "        // Consumed without an intermediate local, so a raw or",
                "        // wrongly-bound chain fails rather than warning.",
                "        String head = Repo.<String>builder()",
                "            .head(\"h\").all(List.of(\"a\")).build().head();",
                "        Repo<String> r = Repo.<String>builder().head(\"h\").all(List.of(\"b\")).build();",
                "        String viaFrom = Repo.from(r).build().all().get(0);",
                "        String viaMutate = r.mutate().build().head();",
                "        return head + viaFrom + viaMutate;",
                "    }",
                "}"));
        assertThat(c).succeeded();

        Class<?> use = Class.forName("demo.UseRepo", true, loadClasses(c));
        assertEquals("hbh", use.getMethod("go").invoke(null));
    }

    @Test
    public void boundedGenericInterface_keepsItsBound() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Keyed",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public interface Keyed<K extends Comparable<K>> {",
                "    K key();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseKeyed",
                "package demo;",
                "public class UseKeyed {",
                "    public static String go() {",
                "        return Keyed.<String>builder().key(\"a\").build().key();",
                "    }",
                "}"));
        assertThat(c).succeeded();

        Class<?> use = Class.forName("demo.UseKeyed", true, loadClasses(c));
        assertEquals("a", use.getMethod("go").invoke(null));
    }

    /** A hand-written method of the same name and arity wins, as on a class. */
    @Test
    public void handWrittenBootstrap_wins() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Custom",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public interface Custom {",
                "    String name();",
                "    static CustomBuilder builder() {",
                "        return new CustomBuilder().name(\"preset\");",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseCustom",
                "package demo;",
                "public class UseCustom {",
                "    public static String go() { return Custom.builder().build().name(); }",
                "}"));
        assertThat(c).succeeded();

        Class<?> use = Class.forName("demo.UseCustom", true, loadClasses(c));
        assertEquals("preset", use.getMethod("go").invoke(null));
    }

    /** The opt-out gates apply on an interface exactly as on a class. */
    @Test
    public void generateFlags_suppressTheBootstraps() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Bare",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, generateBuilder = false, generateMutate = false)",
                "public interface Bare {",
                "    String name();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBare",
                "package demo;",
                "public class UseBare {",
                "    public static String go() { return Bare.builder().build().name(); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("builder");
    }

    /** {@code generateImpl = false} routes build() through a factory and still bootstraps. */
    @Test
    public void generateImplFalse_stillGetsEntryPoints() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Made",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, generateImpl = false, factoryMethod = \"create\")",
                "public interface Made {",
                "    String name();",
                "    static Made create(String name) {",
                "        return () -> name;",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseMade",
                "package demo;",
                "public class UseMade {",
                "    public static String go() { return Made.builder().name(\"x\").build().name(); }",
                "}"));
        assertThat(c).succeeded();

        Class<?> use = Class.forName("demo.UseMade", true, loadClasses(c));
        assertEquals("x", use.getMethod("go").invoke(null));
    }

}
