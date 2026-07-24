package dev.simplified.cleanup.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import dev.simplified.cleanup.apt.CleanupProcessor;
import org.junit.Test;

import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code @Cleanup}: the remainder of the enclosing block becomes a
 * try-with-resources over the annotated local.
 *
 * <p>Every assertion here is on the order of effects rather than on the file
 * having compiled. A split at the wrong index still compiles - the only symptom
 * is a resource closing at the wrong moment - so each case records opens, closes
 * and body statements into one list and pins the exact sequence.
 */
public class CleanupBlockMutatorTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new CleanupProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("cleanup-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, CleanupBlockMutatorTest.class.getClassLoader());
    }

    /**
     * A {@code demo.Demo} whose {@code run()} carries the supplied statements.
     * {@code R} records its own construction and close into the static
     * {@code EVENTS} list, and {@code log} records a plain body statement, so
     * one list carries the whole timeline.
     */
    private static JavaFileObject demo(String... body) {
        List<String> lines = new ArrayList<>(Arrays.asList(
            "package demo;",
            "import dev.simplified.annotations.Cleanup;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class Demo {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    public static class R implements AutoCloseable {",
            "        private final String name;",
            "        public R(String name) { this.name = name; EVENTS.add(\"open:\" + name); }",
            "        public void close() { EVENTS.add(\"close:\" + name); }",
            "    }",
            "    public static class Bad implements AutoCloseable {",
            "        private final String name;",
            "        public Bad(String name) { this.name = name; EVENTS.add(\"open:\" + name); }",
            "        public void close() { throw new IllegalStateException(\"close-failed:\" + name); }",
            "    }",
            "    public static void log(String event) { EVENTS.add(event); }",
            "    public static void run() throws Exception {"));
        lines.addAll(Arrays.asList(body));
        lines.add("    }");
        lines.add("}");
        return JavaFileObjects.forSourceLines("demo.Demo", lines.toArray(new String[0]));
    }

    /** Compiles, invokes {@code demo.Demo.run()}, and returns the recorded timeline. */
    private static Object runAndCollect(Compilation c) throws Exception {
        assertThat(c).succeeded();
        Class<?> demo = Class.forName("demo.Demo", true, loadClasses(c));
        demo.getMethod("run").invoke(null);
        return demo.getField("EVENTS").get(null);
    }

    // ------------------------------------------------------------------
    // The empty tail
    // ------------------------------------------------------------------

    /**
     * The declaration as the block's last statement leaves an empty try body,
     * which is exactly the case a {@code tail.isEmpty()} shortcut would skip -
     * and skipping it means the resource is never closed, silently.
     */
    @Test
    public void lastStatementOfTheBlockIsStillClosed() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        @Cleanup R a = new R(\"a\");")));
        assertEquals(Arrays.asList("open:a", "close:a"), events);
    }

    // ------------------------------------------------------------------
    // Ordering
    // ------------------------------------------------------------------

    @Test
    public void adjacentDeclarationsCloseInReverseOrder() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        @Cleanup R a = new R(\"a\");",
            "        @Cleanup R b = new R(\"b\");",
            "        log(\"body\");")));
        assertEquals(Arrays.asList("open:a", "open:b", "body", "close:b", "close:a"), events);
    }

    @Test
    public void separatedDeclarationsKeepTheirBetweenStatementsInPlace() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        @Cleanup R a = new R(\"a\");",
            "        log(\"between\");",
            "        @Cleanup R b = new R(\"b\");",
            "        log(\"tail\");")));
        assertEquals(Arrays.asList("open:a", "between", "open:b", "tail", "close:b", "close:a"), events);
    }

    /**
     * The inner declaration's region is the remainder of the {@code if} block,
     * so it closes before the statement that follows the {@code if} - and well
     * before the declaration the method block owns.
     */
    @Test
    public void declarationInsideAnIfClosesAtTheEndOfThatBlock() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        @Cleanup R a = new R(\"a\");",
            "        if (EVENTS.size() == 1) {",
            "            @Cleanup R b = new R(\"b\");",
            "            log(\"inner\");",
            "        }",
            "        log(\"after-if\");")));
        assertEquals(Arrays.asList("open:a", "open:b", "inner", "close:b", "after-if", "close:a"), events);
    }

    // ------------------------------------------------------------------
    // Every kind of block
    // ------------------------------------------------------------------

    @Test
    public void insideAForBodyClosesOnEachIteration() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        for (int i = 0; i < 2; i++) {",
            "            @Cleanup R r = new R(\"r\" + i);",
            "            log(\"body\" + i);",
            "        }")));
        assertEquals(
            Arrays.asList("open:r0", "body0", "close:r0", "open:r1", "body1", "close:r1"),
            events);
    }

    @Test
    public void insideACatchBlock() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        try {",
            "            throw new java.io.IOException(\"x\");",
            "        } catch (java.io.IOException e) {",
            "            @Cleanup R r = new R(\"c\");",
            "            log(\"caught\");",
            "        }",
            "        log(\"after\");")));
        assertEquals(Arrays.asList("open:c", "caught", "close:c", "after"), events);
    }

    @Test
    public void insideALambdaBlockBody() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        Runnable task = () -> {",
            "            @Cleanup R r = new R(\"l\");",
            "            log(\"in-lambda\");",
            "        };",
            "        task.run();",
            "        log(\"after\");")));
        assertEquals(Arrays.asList("open:l", "in-lambda", "close:l", "after"), events);
    }

    @Test
    public void insideALocalClassMethod() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        class Local {",
            "            void go() {",
            "                @Cleanup R r = new R(\"local\");",
            "                log(\"in-local\");",
            "            }",
            "        }",
            "        new Local().go();",
            "        log(\"after\");")));
        assertEquals(Arrays.asList("open:local", "in-local", "close:local", "after"), events);
    }

    @Test
    public void insideAnAnonymousClassMethod() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        Runnable task = new Runnable() {",
            "            public void run() {",
            "                @Cleanup R r = new R(\"anon\");",
            "                log(\"in-anon\");",
            "            }",
            "        };",
            "        task.run();",
            "        log(\"after\");")));
        assertEquals(Arrays.asList("open:anon", "in-anon", "close:anon", "after"), events);
    }

    @Test
    public void insideAnInstanceInitialiser() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Init",
            "package demo;",
            "import dev.simplified.annotations.Cleanup;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class Init {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    public static class R implements AutoCloseable {",
            "        private final String name;",
            "        public R(String name) { this.name = name; EVENTS.add(\"open:\" + name); }",
            "        public void close() { EVENTS.add(\"close:\" + name); }",
            "    }",
            "    {",
            "        @Cleanup R r = new R(\"init\");",
            "        EVENTS.add(\"in-initialiser\");",
            "    }",
            "    public static void make() { new Init(); }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> init = Class.forName("demo.Init", true, loadClasses(c));
        init.getMethod("make").invoke(null);
        assertEquals(Arrays.asList("open:init", "in-initialiser", "close:init"),
            init.getField("EVENTS").get(null));
    }

    /**
     * A colon-labelled case group's statements hang off the case rather than off
     * any block - a switch's braces are not a block node - so the block walk
     * alone never reaches a declaration written there. Left unhandled, the file
     * compiles and the resource is simply never closed.
     */
    @Test
    public void insideAColonSwitchCaseGroup() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        switch (EVENTS.size()) {",
            "            case 0:",
            "                @Cleanup R r = new R(\"a\");",
            "                log(\"body\");",
            "                break;",
            "            default:",
            "                log(\"other\");",
            "        }",
            "        log(\"after\");")));
        assertEquals(Arrays.asList("open:a", "body", "close:a", "after"), events);
    }

    /**
     * Two declarations in one case group close in reverse order, exactly as they
     * do in a block - the split is the same split.
     */
    @Test
    public void twoDeclarationsInOneCaseGroupCloseInReverseOrder() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        switch (EVENTS.size()) {",
            "            case 0:",
            "                @Cleanup R a = new R(\"a\");",
            "                @Cleanup R b = new R(\"b\");",
            "                log(\"body\");",
            "                break;",
            "        }",
            "        log(\"after\");")));
        assertEquals(Arrays.asList("open:a", "open:b", "body", "close:b", "close:a", "after"),
            events);
    }

    /** The arrow form's braced body is a real block and was always covered. */
    @Test
    public void insideAnArrowSwitchCaseBody() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        switch (EVENTS.size()) {",
            "            case 0 -> {",
            "                @Cleanup R r = new R(\"a\");",
            "                log(\"body\");",
            "            }",
            "            default -> log(\"other\");",
            "        }",
            "        log(\"after\");")));
        assertEquals(Arrays.asList("open:a", "body", "close:a", "after"), events);
    }

    // ------------------------------------------------------------------
    // Tails that leave the block
    // ------------------------------------------------------------------

    @Test
    public void tailEndingInReturn() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        @Cleanup R a = new R(\"a\");",
            "        log(\"body\");",
            "        return;")));
        assertEquals(Arrays.asList("open:a", "body", "close:a"), events);
    }

    @Test
    public void tailEndingInBreak() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        for (int i = 0; i < 3; i++) {",
            "            @Cleanup R r = new R(\"r\" + i);",
            "            if (i == 1) break;",
            "            log(\"body\" + i);",
            "        }",
            "        log(\"after\");")));
        assertEquals(
            Arrays.asList("open:r0", "body0", "close:r0", "open:r1", "close:r1", "after"),
            events);
    }

    @Test
    public void tailEndingInThrow() throws Exception {
        Compilation c = compile(demo(
            "        @Cleanup R a = new R(\"a\");",
            "        log(\"body\");",
            "        throw new IllegalStateException(\"boom\");"));
        assertThat(c).succeeded();

        Class<?> demo = Class.forName("demo.Demo", true, loadClasses(c));
        try {
            demo.getMethod("run").invoke(null);
            fail("expected the body to throw");
        } catch (InvocationTargetException e) {
            assertEquals("boom", e.getCause().getMessage());
        }
        assertEquals(Arrays.asList("open:a", "body", "close:a"), demo.getField("EVENTS").get(null));
    }

    /**
     * Leaving early from between two declarations still closes both, and still
     * in reverse order - the region of each ends at the block boundary, so a
     * {@code return} runs every close on the way out.
     */
    @Test
    public void earlyReturnBetweenTwoDeclarationsClosesBoth() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        @Cleanup R a = new R(\"a\");",
            "        log(\"first\");",
            "        @Cleanup R b = new R(\"b\");",
            "        if (EVENTS.size() > 0) return;",
            "        log(\"unreached\");")));
        assertEquals(Arrays.asList("open:a", "first", "open:b", "close:b", "close:a"), events);
    }

    // ------------------------------------------------------------------
    // Exception semantics
    // ------------------------------------------------------------------

    /**
     * The body's exception stays primary and the close failure arrives through
     * {@link Throwable#getSuppressed()}. This is what separates the
     * try-with-resources desugaring from a {@code finally}-based one, which
     * would discard the body's exception entirely.
     */
    @Test
    public void closeFailureIsSuppressedRatherThanMasking() throws Exception {
        Compilation c = compile(demo(
            "        @Cleanup Bad b = new Bad(\"b\");",
            "        throw new IllegalArgumentException(\"primary\");"));
        assertThat(c).succeeded();

        Class<?> demo = Class.forName("demo.Demo", true, loadClasses(c));
        try {
            demo.getMethod("run").invoke(null);
            fail("expected the body to throw");
        } catch (InvocationTargetException e) {
            Throwable primary = e.getCause();
            assertTrue("the body's exception is the primary one",
                primary instanceof IllegalArgumentException);
            assertEquals("primary", primary.getMessage());
            assertEquals(1, primary.getSuppressed().length);
            assertEquals("close-failed:b", primary.getSuppressed()[0].getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Spelling and legality
    // ------------------------------------------------------------------

    /** The fully-qualified form carries no import, and must still be found. */
    @Test
    public void fullyQualifiedAnnotationWithNoImport() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Q",
            "package demo;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class Q {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    public static class R implements AutoCloseable {",
            "        private final String name;",
            "        public R(String name) { this.name = name; EVENTS.add(\"open:\" + name); }",
            "        public void close() { EVENTS.add(\"close:\" + name); }",
            "    }",
            "    public static void run() {",
            "        @dev.simplified.annotations.Cleanup R a = new R(\"a\");",
            "        EVENTS.add(\"body\");",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> q = Class.forName("demo.Q", true, loadClasses(c));
        q.getMethod("run").invoke(null);
        assertEquals(Arrays.asList("open:a", "body", "close:a"), q.getField("EVENTS").get(null));
    }

    /**
     * A same-named annotation from another library is left alone. The match is
     * on what was written, since a local's annotations are never attributed
     * during processing, so the bare simple name only counts where it resolves
     * to this annotation.
     */
    @Test
    public void aForeignAnnotationOfTheSameSimpleNameIsNotRewritten() throws Exception {
        JavaFileObject foreign = JavaFileObjects.forSourceLines("other.Cleanup",
            "package other;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.RetentionPolicy;",
            "import java.lang.annotation.Target;",
            "@Retention(RetentionPolicy.SOURCE)",
            "@Target(ElementType.LOCAL_VARIABLE)",
            "public @interface Cleanup { }");
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.F",
            "package demo;",
            "import other.Cleanup;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class F {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    public static class R implements AutoCloseable {",
            "        private final String name;",
            "        public R(String name) { this.name = name; EVENTS.add(\"open:\" + name); }",
            "        public void close() { EVENTS.add(\"close:\" + name); }",
            "    }",
            "    public static void run() {",
            "        @Cleanup R a = new R(\"a\");",
            "        EVENTS.add(\"body\");",
            "    }",
            "}");
        Compilation c = compile(foreign, src);
        assertThat(c).succeeded();

        Class<?> f = Class.forName("demo.F", true, loadClasses(c));
        f.getMethod("run").invoke(null);
        assertEquals(Arrays.asList("open:a", "body"), f.getField("EVENTS").get(null));
    }

    /**
     * A single-type import wins over an on-demand one and over the unit's own
     * package, so a unit that star-imports this project's annotations and then
     * names another {@code Cleanup} explicitly is writing that other one. Reading
     * the star import alone would wrap a block the author never asked to be
     * wrapped, closing a resource at a point they did not choose - or failing to
     * compile at a line they did not write.
     */
    @Test
    public void aShadowingSingleTypeImportBeatsAnOnDemandImport() throws Exception {
        JavaFileObject foreign = JavaFileObjects.forSourceLines("other.Cleanup",
            "package other;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.RetentionPolicy;",
            "import java.lang.annotation.Target;",
            "@Retention(RetentionPolicy.SOURCE)",
            "@Target(ElementType.LOCAL_VARIABLE)",
            "public @interface Cleanup { }");
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.S",
            "package demo;",
            "import dev.simplified.annotations.*;",
            "import other.Cleanup;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class S {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    public static class R implements AutoCloseable {",
            "        private final String name;",
            "        public R(String name) { this.name = name; EVENTS.add(\"open:\" + name); }",
            "        public void close() { EVENTS.add(\"close:\" + name); }",
            "    }",
            "    public static void run() {",
            "        @Cleanup R a = new R(\"a\");",
            "        EVENTS.add(\"body\");",
            "    }",
            "}");
        Compilation c = compile(foreign, src);
        assertThat(c).succeeded();

        Class<?> s = Class.forName("demo.S", true, loadClasses(c));
        s.getMethod("run").invoke(null);
        assertEquals(Arrays.asList("open:a", "body"), s.getField("EVENTS").get(null));
    }

    /**
     * A loop variable has no block remainder to be closed at, so the annotation
     * cannot be honoured - reported rather than dropped, which would leave the
     * resource open with nothing said about it.
     */
    @Test
    public void forInitialiserDeclarationIsAnError() {
        Compilation c = compile(demo(
            "        for (@Cleanup R r = new R(\"r\"); false; ) { }"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Cleanup is not supported on a loop variable");
    }

    // ------------------------------------------------------------------
    // Handover to the builder pipeline
    // ------------------------------------------------------------------

    /**
     * A tree that declares a {@code @Lazy} field is rewritten from
     * {@code ClassBuilderProcessor} instead, after the pass that retypes
     * constructor assignments - relocating a constructor's tail before that pass
     * runs would hide {@code this.v = v} from it. Pinned in both processor
     * orders, since order within a round is unspecified.
     */
    @Test
    public void aLazyFieldDefersToTheBuilderPipelineInEitherOrder() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Chained",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Cleanup;",
            "import dev.simplified.annotations.Lazy;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "@ClassBuilder",
            "public class Chained {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    @Lazy private String v;",
            "    public static class R implements AutoCloseable {",
            "        public R() { EVENTS.add(\"open\"); }",
            "        public void close() { EVENTS.add(\"close\"); }",
            "    }",
            "    public Chained(String v) {",
            "        @Cleanup R r = new R();",
            "        this.v = v;",
            "        EVENTS.add(\"assigned\");",
            "    }",
            "    public static String run() { return Chained.builder().v(\"hi\").build().getV(); }",
            "}");

        Processor[][] orders = {
            {new CleanupProcessor(), new ClassBuilderProcessor()},
            {new ClassBuilderProcessor(), new CleanupProcessor()}
        };
        for (Processor[] order : orders) {
            Compilation c = Compiler.javac().withProcessors(order).compile(src);
            assertThat(c).succeeded();

            Class<?> chained = Class.forName("demo.Chained", true, loadClasses(c));
            assertEquals("hi", chained.getMethod("run").invoke(null));
            assertEquals(Arrays.asList("open", "assigned", "close"),
                chained.getField("EVENTS").get(null));
        }
    }

    /**
     * A same-named annotation from another library must not be read as this
     * project's {@code @Lazy}. Standing back only lands somewhere when
     * {@code ClassBuilderProcessor} runs at all, and it runs only for a round
     * carrying one of the annotations it claims - so a false positive here is a
     * resource that is never closed, with no diagnostic at all.
     */
    @Test
    public void aForeignLazyAnnotationDoesNotDefeatTheRewrite() throws Exception {
        JavaFileObject foreign = JavaFileObjects.forSourceLines("other.Lazy",
            "package other;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.RetentionPolicy;",
            "import java.lang.annotation.Target;",
            "@Retention(RetentionPolicy.RUNTIME)",
            "@Target(ElementType.FIELD)",
            "public @interface Lazy { }");
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.L",
            "package demo;",
            "import dev.simplified.annotations.Cleanup;",
            "import other.Lazy;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class L {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    @Lazy private String dep;",
            "    public static class R implements AutoCloseable {",
            "        private final String name;",
            "        public R(String name) { this.name = name; EVENTS.add(\"open:\" + name); }",
            "        public void close() { EVENTS.add(\"close:\" + name); }",
            "    }",
            "    public static void run() {",
            "        @Cleanup R a = new R(\"a\");",
            "        EVENTS.add(\"body\");",
            "    }",
            "}");

        // Both processors, as a consumer's processor path carries them.
        Compilation c = Compiler.javac()
            .withProcessors(new CleanupProcessor(), new ClassBuilderProcessor())
            .compile(foreign, src);
        assertThat(c).succeeded();

        Class<?> l = Class.forName("demo.L", true, loadClasses(c));
        l.getMethod("run").invoke(null);
        assertEquals(Arrays.asList("open:a", "body", "close:a"), l.getField("EVENTS").get(null));
    }

    /**
     * Two dispatches over the same tree wrap it once. The pass mark is what
     * makes the second a no-op, and it is recorded for exactly the blocks that
     * were rewritten - a block visited twice without it splits at the same
     * declaration again and closes the resource twice.
     */
    @Test
    public void aSecondDispatchOverTheSameTreeClosesOnce() throws Exception {
        Compilation c = Compiler.javac()
            .withProcessors(new CleanupProcessor(), new CleanupProcessor())
            .compile(demo(
                "        @Cleanup R a = new R(\"a\");",
                "        log(\"body\");"));
        assertThat(c).succeeded();

        Class<?> demo = Class.forName("demo.Demo", true, loadClasses(c));
        demo.getMethod("run").invoke(null);
        assertEquals(Arrays.asList("open:a", "body", "close:a"), demo.getField("EVENTS").get(null));
    }

    /** An unannotated compilation unit is left byte-for-byte alone. */
    @Test
    public void nothingHappensWithoutTheAnnotation() throws Exception {
        Object events = runAndCollect(compile(demo(
            "        R a = new R(\"a\");",
            "        log(\"body\");")));
        assertEquals(Arrays.asList("open:a", "body"), events);
    }

}
