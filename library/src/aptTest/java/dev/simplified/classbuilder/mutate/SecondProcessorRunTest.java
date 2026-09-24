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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * A second {@code ClassBuilderProcessor} over trees a first one already mutated.
 *
 * <p>The marks the passes leave are process-wide, so a second instance handed the
 * same compilation sees every mark the first set - which is how a re-run reaches
 * a tree it has already rewritten. Each case asserts the second run adds nothing:
 * the program compiles and runs as a single run leaves it, and the diagnostics
 * are exactly the ones a single run prints.
 */
public class SecondProcessorRunTest {

    /** What a single run over the sources of the last {@link #compileTwice} prints. */
    private String oneRun;

    /**
     * Two processor instances over one set of trees, so every pass runs a second
     * time, after a single run over the same sources for comparison.
     */
    private Compilation compileTwice(JavaFileObject... sources) {
        oneRun = diagnostics(Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources));
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor(), new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-second-run");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()},
            SecondProcessorRunTest.class.getClassLoader());
    }

    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    /** Every diagnostic, one per line, as kind:file:line: message. */
    private static String diagnostics(Compilation c) {
        StringBuilder out = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : c.diagnostics()) {
            String file = d.getSource() == null ? "-" : d.getSource().getName();
            out.append(d.getKind()).append(':').append(file).append(':').append(d.getLineNumber())
                .append(": ").append(d.getMessage(null).replace('\n', '|')).append('\n');
        }
        return out.toString();
    }

    /** Asserts the second run printed nothing a single run does not. */
    private void assertAddsNothing(Compilation c) {
        assertEquals("a second run adds no diagnostic", oneRun, diagnostics(c));
    }

    private static JavaFileObject src(String name, String... lines) {
        return JavaFileObjects.forSourceLines(name, lines);
    }

    private static JavaFileObject circle() {
        return src("demo.Circle",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Circle extends Shape {",
            "    private int radius;",
            "    public int getRadius() { return radius; }",
            "}");
    }

    // ------------------------------------------------------------------
    // A class target
    // ------------------------------------------------------------------

    /** A second run over a class target stops at the builder the first run generated. */
    @Test
    public void aClassTarget_secondRun_leavesTheGeneratedBuilderAlone() throws Exception {
        Compilation c = compileTwice(
            src("demo.Plain",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Plain {",
                "    private String name;",
                "    public String getName() { return name; }",
                "}"),
            src("demo.UsePlain",
                "package demo;",
                "public class UsePlain {",
                "    public static String go() { return Plain.builder().name(\"x\").build().getName(); }",
                "}"));
        assertEquals(diagnostics(c), Compilation.Status.SUCCESS, c.status());
        assertEquals("x", runGo(c, "demo.UsePlain"));
        assertAddsNothing(c);
    }

    /**
     * A second run over a class target that declares its builder merges nothing
     * twice. It used to merge again, skip every member by name, and append a
     * second {@code from(T)} - the element model it asks for an existing one
     * cannot see a method the first run appended to the tree - failing with
     * {@code method from(demo.Decl) is already defined}.
     */
    @Test
    public void aClassTarget_secondRun_overADeclaredBuilder_mergesOnce() throws Exception {
        Compilation c = compileTwice(
            src("demo.Decl",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Decl {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public static class Builder {",
                "        public Builder shout() { this.name = this.name.toUpperCase(); return this; }",
                "    }",
                "}"),
            src("demo.UseDecl",
                "package demo;",
                "public class UseDecl {",
                "    public static String go() {",
                "        Decl d = Decl.builder().name(\"x\").shout().build();",
                "        return Decl.from(d).build().getName() + d.mutate().build().getName();",
                "    }",
                "}"));
        assertEquals(diagnostics(c), Compilation.Status.SUCCESS, c.status());
        assertEquals("XX", runGo(c, "demo.UseDecl"));
        assertAddsNothing(c);
        assertFalse("nothing is reported as already spelled: " + diagnostics(c),
            diagnostics(c).contains("already spells"));
    }

    /**
     * A second run leaves the all-args constructor the first appended alone. It
     * used to try again, find the constructor it appended, and report it as one
     * a written annotation generates - on a class where no annotation but
     * {@code @ClassBuilder} is written.
     */
    @Test
    public void aClassTarget_secondRun_printsNoConstructorAccessNote() {
        Compilation c = compileTwice(
            src("demo.Plain",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Plain {",
                "    private String name;",
                "    public String getName() { return name; }",
                "}"));
        assertEquals(diagnostics(c), Compilation.Status.SUCCESS, c.status());
        assertFalse("no note about a constructor nobody wrote: " + diagnostics(c),
            diagnostics(c).contains("constructorAccess is not applied"));
    }

    // ------------------------------------------------------------------
    // A constructor target
    // ------------------------------------------------------------------

    /** A second run over a constructor target stops at the builder the first run generated. */
    @Test
    public void aConstructorTarget_secondRun_leavesTheGeneratedBuilderAlone() throws Exception {
        Compilation c = compileTwice(
            src("demo.Action",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Action {",
                "    private final String key;",
                "    @ClassBuilder",
                "    Action(String key) { this.key = key; }",
                "    public String getKey() { return key; }",
                "}"),
            src("demo.UseAction",
                "package demo;",
                "public class UseAction {",
                "    public static String go() { return Action.builder().key(\"k\").build().getKey(); }",
                "}"));
        assertEquals(diagnostics(c), Compilation.Status.SUCCESS, c.status());
        assertEquals("k", runGo(c, "demo.UseAction"));
        assertAddsNothing(c);
    }

    /** A second run over a constructor target's declared builder merges nothing twice. */
    @Test
    public void aConstructorTarget_secondRun_overADeclaredBuilder_mergesOnce() throws Exception {
        Compilation c = compileTwice(
            src("demo.Action",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Action {",
                "    private final String key;",
                "    @ClassBuilder",
                "    Action(String key) { this.key = key; }",
                "    public String getKey() { return key; }",
                "    public static class Builder {",
                "        public Builder shout() { this.key = this.key.toUpperCase(); return this; }",
                "    }",
                "}"),
            src("demo.UseAction",
                "package demo;",
                "public class UseAction {",
                "    public static String go() { return Action.builder().key(\"k\").shout().build().getKey(); }",
                "}"));
        assertEquals(diagnostics(c), Compilation.Status.SUCCESS, c.status());
        assertEquals("K", runGo(c, "demo.UseAction"));
        assertAddsNothing(c);
    }

    // ------------------------------------------------------------------
    // A chain
    // ------------------------------------------------------------------

    /** A second run over a chain stops at the builders the first run generated. */
    @Test
    public void aChain_secondRun_leavesTheGeneratedBuildersAlone() throws Exception {
        Compilation c = compileTwice(
            src("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private String name;",
                "    public String getName() { return name; }",
                "}"),
            circle(),
            src("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static String go() {",
                "        Circle c = Circle.builder().name(\"a\").radius(2).build();",
                "        return c.getName() + \"/\" + c.getRadius();",
                "    }",
                "}"));
        assertEquals(diagnostics(c), Compilation.Status.SUCCESS, c.status());
        assertEquals("a/2", runGo(c, "demo.UseShape"));
        assertAddsNothing(c);
    }

    /** A second run over a chain whose root declares its builder merges nothing twice. */
    @Test
    public void aChain_secondRun_overADeclaredRoot_mergesOnce() throws Exception {
        Compilation c = compileTwice(
            src("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {",
                "        public B named(String first, String last) { return name(first + \" \" + last); }",
                "    }",
                "}"),
            circle(),
            src("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static String go() {",
                "        Circle c = Circle.builder().named(\"a\", \"b\").radius(2).build();",
                "        return c.getName() + \"/\" + c.getRadius();",
                "    }",
                "}"));
        assertEquals(diagnostics(c), Compilation.Status.SUCCESS, c.status());
        assertEquals("a b/2", runGo(c, "demo.UseShape"));
        assertAddsNothing(c);
    }

}
