package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
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
 * A builder the generator writes whole - no declared builder to merge into -
 * whose setter meets a {@code java.lang.Object} method it cannot override.
 *
 * <p>Such a builder has {@code Object} as its only supertype on the standalone
 * path, and the chain's own generated builders above it on a link, so the one
 * method a setter can meet and fail to override is one of {@code Object}'s: a
 * {@code final} one, or one whose return type the builder is not. javac refused
 * each on the target's line - a line the author never wrote - and the editor
 * was green. The processor reports it on the slot's field instead, and that
 * report ends the compilation ahead of javac's.
 */
public class ObjectMethodSetterTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-object-method-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, ObjectMethodSetterTest.class.getClassLoader());
    }

    /** Invokes the compiled consumer's {@code go()} so the generated surface is exercised at runtime. */
    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    /**
     * The per-slot naming that spells a boolean's zero-argument flag setter as
     * the slot's own name, where the default {@code is{}} would not meet an
     * {@code Object} method.
     */
    private static final String FLAG_AS_NAME = "@dev.simplified.annotations.SetterNames(flag = \"{}\")";

    /** The sentence for a setter {@code Object} declares {@code final}. */
    private static String declaredFinal(String signature) {
        return "@ClassBuilder generating 'Builder' finds " + signature + " inherited from java.lang.Object "
            + "declared final, so the generated setter of that signature cannot override it";
    }

    /** The sentence for a setter whose {@code Object} method returns a type the builder is not. */
    private static String returning(String signature, String returnType) {
        return "@ClassBuilder generating 'Builder' finds " + signature + " inherited from java.lang.Object "
            + "returning " + returnType + ", which the generated setter returning Builder cannot override";
    }

    /** Asserts the processor's report is the compilation's only error, on the given line. */
    private static void assertOnlyError(Compilation c, JavaFileObject file, int line, String message) {
        assertThat(c).failed();
        assertThat(c).hadErrorContaining(message).inFile(file).onLine(line);
        assertEquals("the report on the slot is the only error: " + c.errors(), 1, c.errors().size());
    }

    // ------------------------------------------------------------------
    // Standalone
    // ------------------------------------------------------------------

    /**
     * The typed setter of a {@code long} slot named {@code wait} would override
     * {@code Object}'s final {@code wait(long)}. javac refused it with
     * {@code wait(long) in demo.Waiter.Builder cannot override wait(long) in
     * java.lang.Object / overridden method is final} on the target's line.
     */
    @Test
    public void standalone_aLongSlotNamedWait_isRejectedOnTheField() {
        JavaFileObject waiter = JavaFileObjects.forSourceLines("demo.Waiter",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Waiter {",
            "    long wait;",
            "}");
        assertOnlyError(compile(waiter), waiter, 5, declaredFinal("wait(long)"));
    }

    /**
     * A boolean's zero-argument flag setter, named by {@code flag = "{}"} after
     * a slot named {@code notify}, would override the final {@code notify()}.
     */
    @Test
    public void standalone_aBooleanSlotNamedNotify_isRejectedForItsFlagSetter() {
        JavaFileObject bell = JavaFileObjects.forSourceLines("demo.Bell",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Bell {",
            "    " + FLAG_AS_NAME + " boolean notify;",
            "}");
        assertOnlyError(compile(bell), bell, 5, declaredFinal("notify()"));
    }

    /**
     * A boolean's flag setter named {@code hashCode} would override
     * {@code Object.hashCode()}, whose {@code int} the builder is not.
     */
    @Test
    public void standalone_aBooleanSlotNamedHashCode_isRejectedForItsReturnType() {
        JavaFileObject hashed = JavaFileObjects.forSourceLines("demo.Hashed",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Hashed {",
            "    " + FLAG_AS_NAME + " boolean hashCode;",
            "}");
        assertOnlyError(compile(hashed), hashed, 5, returning("hashCode()", "int"));
    }

    /**
     * A collector's single-element add, named {@code equals} by its singular
     * name and {@code add = "{}"}, over a list of {@code Object} would override
     * {@code Object.equals(Object)}, whose {@code boolean} the builder is not.
     */
    @Test
    public void standalone_aCollectorAddNamedEquals_isRejectedForItsReturnType() {
        JavaFileObject bag = JavaFileObjects.forSourceLines("demo.Bag",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "@ClassBuilder",
            "public class Bag {",
            "    @Collector(singular = true, singularMethodName = \"equals\")"
                + " @dev.simplified.annotations.SetterNames(add = \"{}\")",
            "    List<Object> items = new ArrayList<>();",
            "}");
        assertOnlyError(compile(bag), bag, 9, returning("equals(Object)", "boolean"));
    }

    /**
     * {@code Object.clone()} is neither final nor typed against the builder,
     * which is an {@code Object}: a boolean's flag setter named {@code clone}
     * overrides it legally, widening its access, and runs.
     */
    @Test
    public void standalone_aBooleanSlotNamedClone_compilesAndRuns() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Cloned",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Cloned {",
                "    " + FLAG_AS_NAME + " boolean clone;",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseCloned",
                "package demo;",
                "public class UseCloned {",
                "    public static boolean go() { return Cloned.builder().clone().build().clone; }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(true, runGo(c, "demo.UseCloned"));
    }

    // ------------------------------------------------------------------
    // Constructor target
    // ------------------------------------------------------------------

    /** On a constructor target the slot is the parameter, and the report sits on it. */
    @Test
    public void constructorTarget_aLongParameterNamedWait_isRejectedOnTheParameter() {
        JavaFileObject waiter = JavaFileObjects.forSourceLines("demo.Waiter",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "public final class Waiter {",
            "    private final long wait;",
            "    @ClassBuilder",
            "    Waiter(long wait) { this.wait = wait; }",
            "}");
        assertOnlyError(compile(waiter), waiter, 6, declaredFinal("wait(long)"));
    }

    // ------------------------------------------------------------------
    // Chain roles
    // ------------------------------------------------------------------

    /** An abstract root annotated with a {@code String name} slot, for a link or chained abstract below it. */
    private static JavaFileObject shape() {
        return JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public abstract class Shape {",
            "    String name;",
            "}");
    }

    /** An abstract root's self-typed setter meets {@code Object}'s final {@code wait(long)} too. */
    @Test
    public void abstractRoot_aLongSlotNamedWait_isRejectedOnTheField() {
        JavaFileObject root = JavaFileObjects.forSourceLines("demo.Timed",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public abstract class Timed {",
            "    long wait;",
            "}");
        assertOnlyError(compile(root), root, 5, declaredFinal("wait(long)"));
    }

    /** A concrete link's flag setter named {@code notify} is reported on the link's field. */
    @Test
    public void concreteLink_aBooleanSlotNamedNotify_isRejectedOnTheField() {
        JavaFileObject link = JavaFileObjects.forSourceLines("demo.Circle",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Circle extends Shape {",
            "    " + FLAG_AS_NAME + " boolean notify;",
            "}");
        assertOnlyError(compile(shape(), link), link, 5, declaredFinal("notify()"));
    }

    /** A chained abstract's self-typed flag setter named {@code notifyAll} is reported on its field. */
    @Test
    public void chainedAbstract_aBooleanSlotNamedNotifyAll_isRejectedOnTheField() {
        JavaFileObject chained = JavaFileObjects.forSourceLines("demo.Round",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public abstract class Round extends Shape {",
            "    " + FLAG_AS_NAME + " boolean notifyAll;",
            "}");
        assertOnlyError(compile(shape(), chained), chained, 5, declaredFinal("notifyAll()"));
    }

}
