package dev.simplified.silentthrows.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import dev.simplified.silentthrows.apt.SilentThrowsProcessor;
import dev.simplified.utility.apt.UtilityClassProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code @SilentThrows}: the body is wrapped, the original checked exception
 * leaves a declaration that lists no {@code throws}, and the rethrow helper is
 * emitted once per class.
 */
public class SilentThrowsMutatorTest {

    private static final String GENERATED_DESCRIPTOR = "Ldev/simplified/annotations/Generated;";

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new SilentThrowsProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("silent-throws-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, SilentThrowsMutatorTest.class.getClassLoader());
    }

    private static Throwable causeOf(Runnable invocation) {
        try {
            invocation.run();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InvocationTargetException ite) return ite.getCause();
            throw e;
        }
        fail("expected the invocation to throw");
        return null;
    }

    // ------------------------------------------------------------------
    // The shape the feature exists for
    // ------------------------------------------------------------------

    /**
     * An override of a {@code throws}-free interface method whose body raises a
     * checked exception. The compile is only half the claim - the other half is
     * that the caller receives the original instance, with its stack trace still
     * pointing at the line that threw it.
     */
    @Test
    public void checkedExceptionEscapesAThrowsFreeOverride() throws Exception {
        JavaFileObject api = JavaFileObjects.forSourceLines("demo.Runner",
            "package demo;",
            "public interface Runner {",
            "    String run();",
            "}");
        JavaFileObject impl = JavaFileObjects.forSourceLines("demo.Impl",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public class Impl implements Runner {",
            "    @Override",
            "    @SilentThrows",
            "    public String run() {",
            "        throw new IOException(\"boom\");",
            "    }",
            "}");

        Compilation c = compile(api, impl);
        assertThat(c).succeeded();

        ClassLoader loader = loadClasses(c);
        Class<?> type = Class.forName("demo.Impl", true, loader);
        Object instance = type.getDeclaredConstructor().newInstance();
        Method run = type.getMethod("run");

        Throwable cause = causeOf(() -> invoke(run, instance));
        assertTrue("the original checked exception should escape unaltered, got " + cause,
            cause instanceof IOException);
        assertEquals("boom", cause.getMessage());

        StackTraceElement top = cause.getStackTrace()[0];
        assertEquals("demo.Impl", top.getClassName());
        assertEquals("run", top.getMethodName());
        assertEquals("the trace is captured at construction, so the wrap must not move it",
            8, top.getLineNumber());
    }

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    /**
     * An explicit {@code super(...)} has to stay at the top level of the body -
     * nested in a {@code try} it is a hard compile error, and the relaxation
     * Java 25 grants constructor bodies does not extend to it.
     */
    @Test
    public void explicitSuperCallStaysOutsideTheTry() throws Exception {
        JavaFileObject base = JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "public class Base {",
            "    protected final int seed;",
            "    protected Base(int seed) { this.seed = seed; }",
            "    public int seed() { return seed; }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Child",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public class Child extends Base {",
            "    @SilentThrows",
            "    public Child(int seed, boolean fail) {",
            "        super(seed);",
            "        if (fail) throw new IOException(\"boom\");",
            "    }",
            "}");

        Compilation c = compile(base, child);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Child", true, loadClasses(c));
        Constructor<?> ctor = type.getDeclaredConstructor(int.class, boolean.class);
        assertEquals(7, type.getMethod("seed").invoke(ctor.newInstance(7, false)));

        try {
            ctor.newInstance(7, true);
            fail("expected the constructor to throw");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void constructorWithNoExplicitSelfCallIsWrapped() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Plain",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public class Plain {",
            "    @SilentThrows",
            "    public Plain(boolean fail) {",
            "        if (fail) throw new IOException(\"nope\");",
            "    }",
            "}");

        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Plain", true, loadClasses(c));
        Constructor<?> ctor = type.getDeclaredConstructor(boolean.class);
        ctor.newInstance(false);
        try {
            ctor.newInstance(true);
            fail("expected the constructor to throw");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    /**
     * JLS 16 makes a blank final definitely assigned after a {@code try} whose
     * every catch clause completes abruptly, so the wrap does not cost the
     * constructor its assignment. Measured rather than reasoned about, since the
     * failure mode is a compile error on code the author never wrote.
     */
    @Test
    public void blankFinalAssignedInsideTheWrapStillCompiles() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Blank",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "public class Blank {",
            "    private final String value;",
            "    @SilentThrows",
            "    public Blank(String raw) {",
            "        this.value = new String(raw.getBytes(\"UTF-8\"), \"UTF-8\");",
            "    }",
            "    public String value() { return value; }",
            "}");

        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Blank", true, loadClasses(c));
        Object instance = type.getDeclaredConstructor(String.class).newInstance("hello");
        assertEquals("hello", type.getMethod("value").invoke(instance));
    }

    // ------------------------------------------------------------------
    // Narrowing
    // ------------------------------------------------------------------

    @Test
    public void narrowedValueAbsorbsTheNamedType() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Narrow",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public class Narrow {",
            "    @SilentThrows(IOException.class)",
            "    public void run() {",
            "        throw new IOException(\"boom\");",
            "    }",
            "}");
        assertThat(compile(src)).succeeded();
    }

    /**
     * The narrowed clause is emitted verbatim, so a checked exception outside it
     * is still unhandled. That failure is the observable proof the catch was
     * narrowed rather than left at {@code Throwable}.
     */
    @Test
    public void narrowedValueLeavesAnUnrelatedCheckedExceptionUnhandled() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Narrow",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public class Narrow {",
            "    @SilentThrows(IOException.class)",
            "    public void run() {",
            "        throw new Exception(\"boom\");",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("unreported exception");
    }

    // ------------------------------------------------------------------
    // Helper emission
    // ------------------------------------------------------------------

    @Test
    public void twoAnnotatedMembersShareOneHelper() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Pair",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public class Pair {",
            "    @SilentThrows",
            "    public void first() { throw new IOException(\"a\"); }",
            "    @SilentThrows",
            "    public void second() { throw new IOException(\"b\"); }",
            "}");

        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Pair", true, loadClasses(c));
        long helpers = 0;
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals("$silentThrow")) helpers++;
        }
        assertEquals("the helper is emitted once per declaring class", 1, helpers);
    }

    /**
     * A method annotation is never inherited, so the annotation cannot reach an
     * implementation later and start doing something - it is inert forever,
     * which is what makes this an error rather than a warning.
     */
    @Test
    public void abstractMemberIsAnError() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Abstract",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "public abstract class Abstract {",
            "    @SilentThrows",
            "    public abstract void run();",
            "}");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("no body to wrap")
            .inFile(src).onLine(5);
        assertThat(c).hadErrorContaining("does not carry to an implementation");
    }

    @Test
    public void nativeMemberIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Native",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "public class Native {",
            "    @SilentThrows",
            "    public native void run();",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("no body to wrap");
    }

    /**
     * The error is reported against the offending member, so a concrete sibling
     * in the same class is still wrapped rather than the whole type being
     * abandoned.
     */
    @Test
    public void aConcreteSiblingIsStillWrapped() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Mixed",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public abstract class Mixed {",
            "    @SilentThrows",
            "    public abstract void run();",
            "    @SilentThrows",
            "    public void read() { throw new java.io.UncheckedIOException(new IOException(\"x\")); }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("no body to wrap");
        // One error, not two - the concrete member is processed normally.
        assertThat(c).hadErrorCount(1);
    }

    /**
     * The helper is {@code private static} on purpose: {@code @UtilityClass}'s
     * implicit-static pass walks the member list and meets an already-static
     * member, so the two compose with nothing to reconcile.
     */
    @Test
    public void helperIsPrivateStaticAndSurvivesUtilityClass() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Util",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import dev.simplified.annotations.UtilityClass;",
            "import java.io.File;",
            "import java.nio.file.Files;",
            "@UtilityClass",
            "public class Util {",
            "    @SilentThrows",
            "    public static String read(File file) {",
            "        return new String(Files.readAllBytes(file.toPath()));",
            "    }",
            "}");

        Compilation c = Compiler.javac()
            .withProcessors(new SilentThrowsProcessor(), new UtilityClassProcessor())
            .compile(src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Util", true, loadClasses(c));
        assertTrue("@UtilityClass still made the class final", Modifier.isFinal(type.getModifiers()));

        Method helper = type.getDeclaredMethod("$silentThrow", Throwable.class);
        assertTrue("helper should be private", Modifier.isPrivate(helper.getModifiers()));
        assertTrue("helper should be static", Modifier.isStatic(helper.getModifiers()));
    }

    /**
     * The helper is a member no author wrote, so it carries {@code @Generated}
     * and coverage tools skip it. The marker is {@code @Retention(CLASS)} and
     * invisible to reflection by design, so the class file's constant pool is
     * what is read - nothing else in this fixture would intern that descriptor.
     */
    @Test
    public void helperCarriesTheGeneratedMarker() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Marked",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public class Marked {",
            "    @SilentThrows",
            "    public void run() { throw new IOException(\"boom\"); }",
            "}");

        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertTrue("the rethrow helper should carry @Generated",
            classFileText(c, "demo.Marked").contains(GENERATED_DESCRIPTOR));
    }

    /**
     * An explicitly empty {@code value} names no type to catch and falls back to
     * a {@code Throwable} clause, so it behaves exactly like the bare form. The
     * editor answers the same question and has to give the same answer.
     */
    @Test
    public void explicitlyEmptyValueBehavesLikeTheBareForm() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Empty",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "public class Empty {",
            "    @SilentThrows({})",
            "    public void run() { throw new IOException(\"boom\"); }",
            "}");
        assertThat(compile(src)).succeeded();
    }

    // ------------------------------------------------------------------
    // Handover to the builder pipeline
    // ------------------------------------------------------------------

    /**
     * A same-named annotation from another library must not be read as this
     * project's {@code @Lazy}. Standing back from the tree only lands somewhere
     * when {@code ClassBuilderProcessor} runs at all, and it runs only for a
     * round carrying one of the annotations it claims - so a false positive is
     * not a hand-off but a member that is never wrapped, and javac then rejects
     * the file for an unreported exception the author explicitly annotated.
     */
    @Test
    public void aForeignLazyAnnotationDoesNotDefeatTheWrap() throws Exception {
        JavaFileObject foreign = JavaFileObjects.forSourceLines("other.Lazy",
            "package other;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.RetentionPolicy;",
            "import java.lang.annotation.Target;",
            "@Retention(RetentionPolicy.RUNTIME)",
            "@Target(ElementType.FIELD)",
            "public @interface Lazy { }");
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Svc",
            "package demo;",
            "import dev.simplified.annotations.SilentThrows;",
            "import java.io.IOException;",
            "import other.Lazy;",
            "public class Svc {",
            "    @Lazy private String dep;",
            "    @SilentThrows",
            "    public void run() { throw new IOException(\"boom\"); }",
            "}");

        // Both processors, as a consumer's processor path carries them.
        Compilation c = Compiler.javac()
            .withProcessors(new SilentThrowsProcessor(), new ClassBuilderProcessor())
            .compile(foreign, src);
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Svc", true, loadClasses(c));
        Object instance = type.getDeclaredConstructor().newInstance();
        Method run = type.getMethod("run");
        Throwable cause = causeOf(() -> invoke(run, instance));
        assertTrue("the original checked exception should escape, got " + cause,
            cause instanceof IOException);
        assertEquals("boom", cause.getMessage());
    }

    // ------------------------------------------------------------------
    // Reflection plumbing
    // ------------------------------------------------------------------

    /** The compiled bytes of one class, decoded so the constant pool is searchable. */
    private static String classFileText(Compilation compilation, String binaryName) throws Exception {
        String want = binaryName.replace('.', '/') + ".class";
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            if (!f.toUri().toString().endsWith(want)) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                // ISO-8859-1 is byte-preserving, so no pool entry can be mangled
                // into or out of a false match.
                return new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
            }
        }
        fail("no class file for '" + binaryName + "'");
        return null;
    }

    /**
     * Bridges a reflective call into the unchecked world so
     * {@link #causeOf(Runnable)} can unwrap it - the whole point of this suite
     * is what comes out of the invocation, not how it got there.
     */
    private static void invoke(Method method, Object receiver, Object... args) {
        try {
            method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

}
