package dev.sbs.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.sbs.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises {@code builder()} / {@code from(T)} / {@code mutate()} injection
 * on classes and records, plus the skip-on-collision policy.
 */
public class BootstrapMethodInjectionTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-bootstrap-test");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = f.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            String rel = anchor >= 0 ? uri.substring(anchor + "CLASS_OUTPUT/".length()) : f.getName();
            Path out = tmp.resolve(rel);
            Files.createDirectories(out.getParent());
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                Files.write(out, baos.toByteArray());
            }
        }
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, BootstrapMethodInjectionTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested '" + simpleName + "' on " + outer);
        return null;
    }

    // ------------------------------------------------------------------
    // builder() + from(T) + mutate() round-trip
    // ------------------------------------------------------------------

    @Test
    public void allThreeBootstrapMethodsCallable() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Widget {",
            "    String label;",
            "    int count;",
            "    public Widget(String label, int count) { this.label = label; this.count = count; }",
            "    public String getLabel() { return label; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> widget = Class.forName("demo.Widget", true, cl);
        Class<?> builder = nested(widget, "Builder");

        // builder() yields a fresh Builder
        Method builderMethod = widget.getMethod("builder");
        Object b = builderMethod.invoke(null);
        assertNotNull(b);
        assertEquals(builder, b.getClass());

        // Build an instance
        builder.getMethod("label", String.class).invoke(b, "first");
        builder.getMethod("count", int.class).invoke(b, 7);
        Object first = builder.getMethod("build").invoke(b);

        // from(T) reads every field
        Object b2 = widget.getMethod("from", widget).invoke(null, first);
        Object second = builder.getMethod("build").invoke(b2);
        assertEquals("first", widget.getMethod("getLabel").invoke(second));
        assertEquals(7, widget.getMethod("getCount").invoke(second));

        // mutate() on the instance produces a builder pre-populated with its state
        Method mutate = widget.getMethod("mutate");
        Object b3 = mutate.invoke(first);
        builder.getMethod("count", int.class).invoke(b3, 99);
        Object third = builder.getMethod("build").invoke(b3);
        assertEquals("first", widget.getMethod("getLabel").invoke(third));
        assertEquals(99, widget.getMethod("getCount").invoke(third));
    }

    // ------------------------------------------------------------------
    // Record target: from() uses component-accessor form
    // ------------------------------------------------------------------

    @Test
    public void recordTarget_fromUsesComponentAccessors() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Coord",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public record Coord(int x, int y) {}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> coord = Class.forName("demo.Coord", true, cl);
        Class<?> builder = nested(coord, "Builder");

        Object b = coord.getMethod("builder").invoke(null);
        builder.getMethod("x", int.class).invoke(b, 10);
        builder.getMethod("y", int.class).invoke(b, 20);
        Object c1 = builder.getMethod("build").invoke(b);

        Object b2 = coord.getMethod("from", coord).invoke(null, c1);
        Object c2 = builder.getMethod("build").invoke(b2);
        assertEquals(10, coord.getMethod("x").invoke(c2));
        assertEquals(20, coord.getMethod("y").invoke(c2));
    }

    // ------------------------------------------------------------------
    // Skip-on-collision with NOTE emitted
    // ------------------------------------------------------------------

    @Test
    public void userWrittenBuilderMethod_skippedWithNote() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Manual",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Manual {",
            "    String x;",
            "    public Manual(String x) { this.x = x; }",
            "    public String getX() { return x; }",
            "    public static Builder builder() {",
            "        return new Builder().x(\"user\");",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        // The generated Builder still exists (injected); only the bootstrap
        // builder() was skipped because the user hand-rolled it.
        List<Diagnostic<? extends JavaFileObject>> notes = c.notes();
        boolean sawSkip = notes.stream().anyMatch(d ->
            d.getMessage(null).contains("skipped bootstrap 'builder'"));
        assertTrue("expected a skip note for builder()", sawSkip);
    }

    // ------------------------------------------------------------------
    // Custom method names honored
    // ------------------------------------------------------------------

    @Test
    public void customBootstrapMethodNames_respected() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Named",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false, builderMethodName = \"make\", fromMethodName = \"of\", toBuilderMethodName = \"edit\")",
            "public class Named {",
            "    String label;",
            "    public Named(String label) { this.label = label; }",
            "    public String getLabel() { return label; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> named = Class.forName("demo.Named", true, cl);

        Object b = named.getMethod("make").invoke(null);
        Class<?> builder = b.getClass();
        builder.getMethod("label", String.class).invoke(b, "hello");
        Object inst = builder.getMethod("build").invoke(b);

        Object b2 = named.getMethod("of", named).invoke(null, inst);
        Object inst2 = builder.getMethod("build").invoke(b2);
        assertEquals("hello", named.getMethod("getLabel").invoke(inst2));

        Object b3 = named.getMethod("edit").invoke(inst);
        assertNotNull(b3);
    }

}
