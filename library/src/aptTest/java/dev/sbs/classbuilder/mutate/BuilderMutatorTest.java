package dev.sbs.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.sbs.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trip tests for the AST mutation pipeline: compile a fixture, extract
 * the injected nested {@code Builder} class from the compiled output, then
 * drive it reflectively to verify values land on the target instance.
 */
public class BuilderMutatorTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    /**
     * Loads compiled classes from a {@link Compilation} into a fresh classloader
     * so Class objects are usable via reflection. Sister tests in the sibling-
     * path test class string-match on generated source; this test reflects on
     * bytecode, which is the natural API for mutation verification.
     */
    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-mutate-test");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            // URI shape: mem:///CLASS_OUTPUT/demo/Simple.class
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, BuilderMutatorTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested class '" + simpleName + "' on " + outer + "; found "
            + Arrays.toString(outer.getDeclaredClasses()));
        return null;
    }

    // ------------------------------------------------------------------
    // Plain class: nested Builder injected, setters + build round-trip
    // ------------------------------------------------------------------

    @Test
    public void plainClass_builderInjectedAndRoundTrips() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Simple",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Simple {",
            "    String name;",
            "    int count;",
            "    public Simple(String name, int count) { this.name = name; this.count = count; }",
            "    public String getName() { return name; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> simple = Class.forName("demo.Simple", true, cl);
        Class<?> builder = nested(simple, "Builder");

        Object b = builder.getDeclaredConstructor().newInstance();
        Method nameSetter = builder.getMethod("name", String.class);
        Method countSetter = builder.getMethod("count", int.class);
        Method build = builder.getMethod("build");

        assertSame("setter must return builder instance", b, nameSetter.invoke(b, "hello"));
        assertSame("setter must return builder instance", b, countSetter.invoke(b, 42));

        Object result = build.invoke(b);
        assertNotNull(result);
        assertEquals("hello", simple.getMethod("getName").invoke(result));
        assertEquals(42, simple.getMethod("getCount").invoke(result));

        // Sibling file must NOT have been emitted under the mutation path.
        Optional<JavaFileObject> sibling = c.generatedFile(StandardLocation.SOURCE_OUTPUT, "demo", "SimpleBuilder.java");
        assertTrue("sibling builder file should not exist when mutation runs",
            sibling.isEmpty());
    }

    // ------------------------------------------------------------------
    // Boolean field: zero-arg + typed pair
    // ------------------------------------------------------------------

    @Test
    public void booleanField_emitsZeroArgAndTypedPair() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Flag",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Flag {",
            "    boolean enabled;",
            "    public Flag(boolean enabled) { this.enabled = enabled; }",
            "    public boolean isEnabled() { return enabled; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> flag = Class.forName("demo.Flag", true, cl);
        Class<?> builder = nested(flag, "Builder");

        // zero-arg setter flips to true
        Object b1 = builder.getDeclaredConstructor().newInstance();
        builder.getMethod("isEnabled").invoke(b1);
        Object r1 = builder.getMethod("build").invoke(b1);
        assertEquals(Boolean.TRUE, flag.getMethod("isEnabled").invoke(r1));

        // typed setter with false
        Object b2 = builder.getDeclaredConstructor().newInstance();
        builder.getMethod("isEnabled", boolean.class).invoke(b2, false);
        Object r2 = builder.getMethod("build").invoke(b2);
        assertEquals(Boolean.FALSE, flag.getMethod("isEnabled").invoke(r2));
    }

    // ------------------------------------------------------------------
    // Optional<T>: dual nullable-raw + Optional-wrapped
    // ------------------------------------------------------------------

    @Test
    public void optionalField_emitsDualSetter() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Opt",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import java.util.Optional;",
            "@ClassBuilder(validate = false)",
            "public class Opt {",
            "    Optional<String> label;",
            "    public Opt(Optional<String> label) { this.label = label; }",
            "    public Optional<String> getLabel() { return label; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> opt = Class.forName("demo.Opt", true, cl);
        Class<?> builder = nested(opt, "Builder");

        // Raw nullable path
        Object b = builder.getDeclaredConstructor().newInstance();
        builder.getMethod("label", String.class).invoke(b, "seeded");
        Object result = builder.getMethod("build").invoke(b);
        Optional<?> label = (Optional<?>) opt.getMethod("getLabel").invoke(result);
        assertTrue(label.isPresent());
        assertEquals("seeded", label.get());

        // Unset Optional field defaults to Optional.empty()
        Object b2 = builder.getDeclaredConstructor().newInstance();
        Object r2 = builder.getMethod("build").invoke(b2);
        Optional<?> defaulted = (Optional<?>) opt.getMethod("getLabel").invoke(r2);
        assertTrue("unset Optional field must default to Optional.empty()", defaulted.isEmpty());
    }

    // ------------------------------------------------------------------
    // Record target: build() calls the canonical constructor
    // ------------------------------------------------------------------

    @Test
    public void recordTarget_builderInjected() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Point",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public record Point(int x, int y) {}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> point = Class.forName("demo.Point", true, cl);
        Class<?> builder = nested(point, "Builder");

        Object b = builder.getDeclaredConstructor().newInstance();
        builder.getMethod("x", int.class).invoke(b, 3);
        builder.getMethod("y", int.class).invoke(b, 4);
        Object result = builder.getMethod("build").invoke(b);

        assertEquals(3, point.getMethod("x").invoke(result));
        assertEquals(4, point.getMethod("y").invoke(result));
    }

    // ------------------------------------------------------------------
    // Existing nested 'Builder' is respected (skip-on-collision)
    // ------------------------------------------------------------------

    @Test
    public void existingNestedBuilder_skipped() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.HandRolled",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class HandRolled {",
            "    String note;",
            "    public HandRolled(String note) { this.note = note; }",
            "    public String getNote() { return note; }",
            "    public static class Builder {",
            "        private String note;",
            "        public Builder note(String n) { this.note = n; return this; }",
            "        public HandRolled build() { return new HandRolled(note); }",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        // No error, no sibling; processor emitted a NOTE.
    }

    // ------------------------------------------------------------------
    // @ClassBuilder on both an outer and an inner static class: each gets
    // its own independent Builder.
    // ------------------------------------------------------------------

    @Test
    public void outerAndInner_bothAnnotated_bothInjected() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Nest",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Nest {",
            "    String outerField;",
            "    public Nest(String outerField) { this.outerField = outerField; }",
            "    public String getOuterField() { return outerField; }",
            "    @ClassBuilder(validate = false)",
            "    public static class Inner {",
            "        int innerField;",
            "        public Inner(int innerField) { this.innerField = innerField; }",
            "        public int getInnerField() { return innerField; }",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> outer = Class.forName("demo.Nest", true, cl);
        Class<?> inner = Class.forName("demo.Nest$Inner", true, cl);

        // Each has its own nested Builder
        Class<?> outerBuilder = nested(outer, "Builder");
        Class<?> innerBuilder = nested(inner, "Builder");

        Object ob = outerBuilder.getDeclaredConstructor().newInstance();
        outerBuilder.getMethod("outerField", String.class).invoke(ob, "out");
        assertEquals("out", outer.getMethod("getOuterField").invoke(outerBuilder.getMethod("build").invoke(ob)));

        Object ib = innerBuilder.getDeclaredConstructor().newInstance();
        innerBuilder.getMethod("innerField", int.class).invoke(ib, 7);
        assertEquals(7, inner.getMethod("getInnerField").invoke(innerBuilder.getMethod("build").invoke(ib)));
    }

}
