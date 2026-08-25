package dev.simplified.ordering;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import dev.simplified.cleanup.apt.CleanupProcessor;
import dev.simplified.silentthrows.apt.SilentThrowsProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The pass-ordering contract between the two body rewrites and the {@code @Lazy}
 * field rewrite, exercised with every processor registered at once - which is the
 * only configuration a consumer ever compiles under.
 *
 * <p>Two of these cases are shapes neither feature's own suite can express,
 * because each was written against one annotation in isolation: a member
 * carrying {@code @SilentThrows} whose body also declares a {@code @Cleanup}
 * local, and a {@code @Lazy} field on a class whose constructor declares one.
 * The second is the one that pins an ordering rather than an outcome -
 * {@code LazyFieldMutator} rewrites a constructor's {@code this.foo = foo}
 * assignment by walking the body's statements flat, so a body rewrite that
 * relocates that assignment one level down before it runs leaves the assignment
 * untouched and javac then rejects it against the retyped field.
 */
public class PassOrderingTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(
                new ClassBuilderProcessor(),
                new CleanupProcessor(),
                new SilentThrowsProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("pass-ordering-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, PassOrderingTest.class.getClassLoader());
    }

    // ------------------------------------------------------------------
    // @SilentThrows and @Cleanup on the same member
    // ------------------------------------------------------------------

    /**
     * The shape the two features are co-located in: a codec writer that opens a
     * sink, writes through it and lets a decode failure out of a signature that
     * lists no {@code throws}. The resource must still close, and the checked
     * exception must still arrive at the caller.
     */
    @Test
    public void silentThrowsMemberClosesItsCleanupResourceAndStillPropagates() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Codec",
            "package demo;",
            "import dev.simplified.annotations.Cleanup;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class Codec {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    public static class Sink implements AutoCloseable {",
            "        public Sink() { EVENTS.add(\"open\"); }",
            "        public void write(int b) { EVENTS.add(\"write:\" + b); }",
            "        public void close() { EVENTS.add(\"close\"); }",
            "    }",
            "    @SilentThrows",
            "    public void encode(boolean corrupt) {",
            "        @Cleanup Sink sink = new Sink();",
            "        sink.write(1);",
            "        if (corrupt) throw new IOException(\"bad-header\");",
            "        sink.write(2);",
            "    }",
            "}");

        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Codec", true, loadClasses(c));
        Object events = type.getField("EVENTS").get(null);
        Object instance = type.getDeclaredConstructor().newInstance();
        Method encode = type.getMethod("encode", boolean.class);

        assertEquals("the signature must carry no throws clause",
            0, encode.getExceptionTypes().length);

        encode.invoke(instance, false);
        assertEquals(Arrays.asList("open", "write:1", "write:2", "close"), events);

        ((java.util.List<?>) events).clear();
        Throwable cause = failureOf(encode, instance, true);
        assertTrue("the original checked exception must escape, got " + cause,
            cause instanceof IOException);
        assertEquals("bad-header", cause.getMessage());
        assertEquals("the resource must close on the way out",
            Arrays.asList("open", "write:1", "close"), events);
    }

    /**
     * A close that itself throws a checked exception. Nothing else in the method
     * can handle it, so the file only compiles when the close call lands inside
     * the {@code @SilentThrows} try - and the failure has to arrive at the
     * caller rather than being dropped on the way past.
     */
    @Test
    public void closeFailureIsCaughtBySilentThrowsRatherThanSwallowed() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Failing",
            "package demo;",
            "import dev.simplified.annotations.Cleanup;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class Failing {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    public static class Sink implements AutoCloseable {",
            "        public Sink() { EVENTS.add(\"open\"); }",
            "        public void close() throws IOException {",
            "            EVENTS.add(\"close-attempt\");",
            "            throw new IOException(\"flush-failed\");",
            "        }",
            "    }",
            "    @SilentThrows",
            "    public void encode() {",
            "        @Cleanup Sink sink = new Sink();",
            "        EVENTS.add(\"body\");",
            "    }",
            "}");

        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Failing", true, loadClasses(c));
        Object events = type.getField("EVENTS").get(null);
        Object instance = type.getDeclaredConstructor().newInstance();
        Method encode = type.getMethod("encode");

        Throwable cause = failureOf(encode, instance);
        assertTrue("the close failure must reach the caller, got " + cause,
            cause instanceof IOException);
        assertEquals("flush-failed", cause.getMessage());
        assertEquals(Arrays.asList("open", "body", "close-attempt"), events);
    }

    // ------------------------------------------------------------------
    // @Lazy against a relocated constructor body
    // ------------------------------------------------------------------

    /**
     * The ordering pin. {@code @Lazy} retypes the constructor's parameter to
     * {@code Supplier<T>} and rewrites {@code this.value = value} into
     * a holder over the value, walking the body's statement list flat. The
     * {@code @Cleanup} declaration ahead of that assignment relocates it into
     * the synthesised try, so running the block split first leaves the
     * assignment as written and javac rejects a {@code Supplier<String>} against
     * a {@code Lazy<String>} field. Compiling at all is the assertion; the
     * recorded timeline and the memoized read are what prove neither pass was
     * skipped to get there.
     */
    @Test
    public void lazyAssignmentSurvivesACleanupSplitInTheSameConstructor() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Holder",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Cleanup;",
            "import dev.simplified.annotations.Lazy;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            // A standalone @Lazy field needs an initializer, and an initialized
            // final cannot also be assigned by the constructor - so the shape
            // this case is about only exists on a builder target.
            "@ClassBuilder(validate = false)",
            "public class Holder {",
            "    public static final List<String> EVENTS = new ArrayList<>();",
            "    public static class R implements AutoCloseable {",
            "        public R() { EVENTS.add(\"open\"); }",
            "        public void close() { EVENTS.add(\"close\"); }",
            "    }",
            "    @Lazy String value;",
            "    public Holder(String value) {",
            "        @Cleanup R r = new R();",
            "        EVENTS.add(\"body\");",
            "        this.value = value;",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Holder", true, loadClasses(c));
        assertEquals("the @Lazy field must still be rewritten to deferred holder storage",
            "java.util.concurrent.atomic.AtomicReference", type.getDeclaredField("value").getType().getName());

        Object events = type.getField("EVENTS").get(null);
        Object builder = type.getMethod("builder").invoke(null);
        builder.getClass().getMethod("value", String.class).invoke(builder, "v");
        Object built = builder.getClass().getMethod("build").invoke(builder);

        assertEquals("the @Cleanup resource must close at the end of the constructor",
            Arrays.asList("open", "body", "close"), events);
        assertEquals("v", type.getMethod("getValue").invoke(built));
    }

    /**
     * The same pin for the other body rewrite. {@code @SilentThrows} re-parents
     * the whole constructor body one level down, so every assignment in it is
     * invisible to a flat walk - the failure is the identical type mismatch on
     * generated code.
     */
    @Test
    public void lazyAssignmentSurvivesASilentThrowsWrapInTheSameConstructor() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Wrapped",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "@ClassBuilder(validate = false)",
            "public class Wrapped {",
            "    @Lazy String value;",
            "    @SilentThrows",
            "    public Wrapped(String value) {",
            "        if (value == null) throw new IOException(\"null-value\");",
            "        this.value = value;",
            "    }",
            "}");

        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Wrapped", true, loadClasses(c));
        assertEquals("the @Lazy field must still be rewritten to deferred holder storage",
            "java.util.concurrent.atomic.AtomicReference", type.getDeclaredField("value").getType().getName());

        Object builder = type.getMethod("builder").invoke(null);
        builder.getClass().getMethod("value", String.class).invoke(builder, "v");
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertEquals("v", type.getMethod("getValue").invoke(built));
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** The cause the reflective invocation failed with, failing the test when it did not. */
    private static Throwable failureOf(Method method, Object receiver, Object... args) throws Exception {
        try {
            method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
        fail("expected " + method.getName() + " to throw");
        return null;
    }

}
