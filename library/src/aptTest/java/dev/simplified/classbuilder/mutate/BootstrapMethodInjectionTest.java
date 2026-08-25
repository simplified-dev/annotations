package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
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
import static org.junit.Assert.assertFalse;
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
            "import dev.simplified.annotations.ClassBuilder;",
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
            "import dev.simplified.annotations.ClassBuilder;",
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
            "import dev.simplified.annotations.ClassBuilder;",
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

    @Test
    public void noBuildFlag_buildDoesNotReachForAValidator() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Plain",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Plain {",
            "    String name;",
            "    public Plain(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertFalse("nothing carries a constraint, so build() must not validate",
            classBytes(c, "demo/Plain$Builder.class").contains("$validate$"));
        assertNoRuntimeDependency(c, "demo/Plain$Builder.class");
    }

    @Test
    public void withBuildFlag_buildStillValidates() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Guarded",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Guarded {",
            "    @BuildFlag(nonNull = true) String name;",
            "    public Guarded(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertTrue("a target with a constraint must keep validating",
            classBytes(c, "demo/Guarded$Builder.class").contains("$validate$"));
        assertNoRuntimeDependency(c, "demo/Guarded$Builder.class");
    }

    @Test
    public void inheritedBuildFlag_buildStillValidates() throws Exception {
        // The flag walk climbs to Object, so asking only about declared fields
        // would turn an inherited requirement into an unenforced one.
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "public class Base {",
            "    @BuildFlag(nonNull = true) protected String required;",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Child",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Child extends Base {",
            "    String extra;",
            "    public Child(String extra) { this.extra = extra; }",
            "    public String getExtra() { return extra; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();
        assertTrue("the parent's constraint must keep validating",
            classBytes(c, "demo/Child$Builder.class").contains("$validate$"));
        assertNoRuntimeDependency(c, "demo/Child$Builder.class");
    }

    /**
     * Every feature that generates code, generating at once, with the assertion
     * that binds the whole of it: nothing emitted names a class from this
     * library.
     */
    @Test
    public void generatedCodeNamesNoLibraryClass() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Whole",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Formattable;",
            "import dev.simplified.annotations.Lazy;",
            "import org.jetbrains.annotations.Nullable;",
            "@ClassBuilder",
            "public class Whole {",
            "    @BuildFlag(nonNull = true, limit = 5) String name;",
            "    @Formattable @Nullable String note;",
            "    @Lazy String expensive = compute();",
            "    private static String compute() { return \"v\"; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertNoRuntimeDependency(c, "demo/Whole.class");
        assertNoRuntimeDependency(c, "demo/Whole$Builder.class");
    }

    /**
     * Asserts a generated class names no {@code dev/simplified/} type other
     * than an annotation.
     *
     * <p>The annotations are {@code CLASS}-retention markers, so they are inert
     * at runtime and a consumer never needs them on the classpath. Anything
     * else would be a real reference, and the failure mode it produces is a
     * green {@code compileJava} followed by a {@code NoClassDefFoundError} -
     * which is not a failure a build can catch, and so has to be caught here.
     */
    private static void assertNoRuntimeDependency(Compilation c, String classFile) throws Exception {
        String pool = classBytes(c, classFile);
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("dev/simplified/[A-Za-z0-9/$]+").matcher(pool);
        java.util.List<String> offenders = new java.util.ArrayList<>();
        while (m.find()) {
            String named = m.group();
            if (named.startsWith("dev/simplified/annotations/")) continue;
            offenders.add(named);
        }
        assertTrue(classFile + " must not reference a library class at runtime, found: " + offenders,
            offenders.isEmpty());
    }

    /** The constant pool as text, which is where a referenced class name shows up. */
    private static String classBytes(Compilation c, String classFile) throws Exception {
        for (JavaFileObject f : c.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            if (!f.toUri().toString().endsWith(classFile)) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                return new String(baos.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        }
        fail("no generated class file named " + classFile);
        return "";
    }

    @Test
    public void foreignTypedFrom_doesNotSuppressTheCopyFactory() throws Exception {
        // from(T) is the one bootstrap whose arity is shared with methods that
        // mean something else. Matching on arity alone let a from(String) parser
        // take the copy factory's place, silently - the build stays green and
        // the only trace is a NOTE.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Doc",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Doc {",
            "    String body;",
            "    public Doc(String body) { this.body = body; }",
            "    public String getBody() { return body; }",
            "    public static Doc from(String raw) { return new Doc(raw); }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> doc = Class.forName("demo.Doc", true, cl);
        Class<?> builder = nested(doc, "Builder");

        // The author's own from(String) is untouched and still parses.
        Object parsed = doc.getMethod("from", String.class).invoke(null, "hello");
        assertEquals("hello", doc.getMethod("getBody").invoke(parsed));

        // The copy factory is generated beside it rather than suppressed by it.
        Object seeded = doc.getMethod("from", doc).invoke(null, parsed);
        assertEquals(builder, seeded.getClass());
        Object rebuilt = builder.getMethod("build").invoke(seeded);
        assertEquals("hello", doc.getMethod("getBody").invoke(rebuilt));
    }

    @Test
    public void ownTypedFrom_stillWinsOverTheCopyFactory() {
        // The other side of the same rule: an author's own from(T) is the whole
        // reason the collision check exists, and it still takes precedence.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Owned",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Owned {",
            "    String x;",
            "    Owned(String x) { this.x = x; }",
            "    public String getX() { return x; }",
            "    public static Builder from(Owned other) { return new Builder().x(other.x); }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        boolean sawSkip = c.notes().stream().anyMatch(d ->
            d.getMessage(null).contains("skipped bootstrap 'from'"));
        assertTrue("expected a skip note for from(Owned)", sawSkip);
    }

    // ------------------------------------------------------------------
    // Custom method names honored
    // ------------------------------------------------------------------

    @Test
    public void customBootstrapMethodNames_respected() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Named",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.BuilderNames;",
            "@ClassBuilder(validate = false, builder = @BuilderNames(builder = \"make\", from = \"of\", toBuilder = \"edit\"))",
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
