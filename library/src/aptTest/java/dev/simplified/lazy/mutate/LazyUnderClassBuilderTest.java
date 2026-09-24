package dev.simplified.lazy.mutate;

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
import java.util.ArrayList;
import java.util.List;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * A {@code @Lazy} field on a {@code @ClassBuilder} target, where the two passes
 * meet on generated lines: the builder's supplier setters, the constructor
 * {@code build()} calls, and the {@code from(T)} / {@code mutate()} reads.
 *
 * <p>Every case compiles and then runs a consumer's {@code go()}, because each
 * defect pinned here either failed on a line the author never wrote or threw at
 * {@code build()} over source that compiled.
 */
public class LazyUnderClassBuilderTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static JavaFileObject src(String name, String... lines) {
        return JavaFileObjects.forSourceLines(name, lines);
    }

    /** The consumer whose {@code go()} each case runs, in package {@code demo}. */
    private static JavaFileObject use(String... body) {
        String[] lines = new String[body.length + 4];
        lines[0] = "package demo;";
        lines[1] = "public class Use {";
        lines[2] = "    public static Object go() {";
        System.arraycopy(body, 0, lines, 3, body.length);
        lines[lines.length - 1] = "    } }";
        return src("demo.Use", lines);
    }

    private static String diagnostics(Compilation c) {
        StringBuilder out = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : c.diagnostics()) {
            out.append(d.getKind()).append(':').append(d.getLineNumber()).append(": ")
                .append(d.getMessage(null).replace('\n', '|')).append('\n');
        }
        return out.toString();
    }

    private static List<String> errors(Compilation c) {
        List<String> out = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : c.errors()) out.add(d.getMessage(null));
        return out;
    }

    /** Compiles, asserts success with every diagnostic in the message, and runs {@code demo.Use.go()}. */
    private static Object run(JavaFileObject... sources) throws Exception {
        Compilation c = compile(sources);
        assertEquals(diagnostics(c), Compilation.Status.SUCCESS, c.status());
        Path tmp = Files.createTempDirectory("lazy-under-builder");
        for (JavaFileObject f : c.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = f.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            Path out = tmp.resolve(uri.substring(anchor + "CLASS_OUTPUT/".length()));
            Files.createDirectories(out.getParent());
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                Files.write(out, bytes.toByteArray());
            }
        }
        ClassLoader loader = new URLClassLoader(new URL[]{tmp.toUri().toURL()},
            LazyUnderClassBuilderTest.class.getClassLoader());
        return Class.forName("demo.Use", true, loader).getMethod("go").invoke(null);
    }

    // ------------------------------------------------------------------
    // A primitive @Lazy field
    // ------------------------------------------------------------------

    /**
     * The lazy setter and the all-args constructor's retyped parameter were both
     * {@code Supplier<int>}, which javac refused with {@code unexpected type /
     * required: reference / found: int} on the field's line.
     */
    @Test
    public void primitiveLazyField_buildsTheComputedValue() throws Exception {
        Object result = run(
            src("demo.Counted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Counted {",
                "    @Lazy private int count = compute();",
                "    private static int compute() { return 7; }",
                "}"),
            use("return Counted.builder().build().getCount() + \"/\"",
                "    + Counted.builder().count(() -> 9).build().getCount() + \"/\"",
                "    + Counted.builder().count(4).build().getCount();"));
        assertEquals("7/9/4", result);
    }

    /**
     * With a factory method there is no all-args constructor, so the setter
     * alone carried the unboxed supplier type. The author's constructor keeps
     * the field's initializer on it, which the factory's instance reads.
     */
    @Test
    public void primitiveLazyField_withAFactoryMethod_buildsTheComputedValue() throws Exception {
        Object result = run(
            src("demo.Counted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "import java.util.function.Supplier;",
                "@ClassBuilder(validate = false, factoryMethod = \"make\")",
                "public class Counted {",
                "    @Lazy private int count = compute();",
                "    private static int compute() { return 7; }",
                "    Counted() { }",
                "    static Counted make(Supplier<Integer> count) { return new Counted(); }",
                "}"),
            use("return Counted.builder().count(() -> 9).build().getCount();"));
        assertEquals(7, result);
    }

    /**
     * Under a {@code factoryMethod} with no author constructor no generated
     * constructor assigns the lazy holder, so it keeps its initializer and the
     * factory's {@code new Named()} computes the value. The lift took the
     * initializer off, and javac failed with {@code variable label not
     * initialized in the default constructor} on the field's line.
     */
    @Test
    public void lazyFieldWithAnInitializer_underAFactoryMethodWithNoConstructor_buildsTheComputedValue()
        throws Exception {
        Object result = run(
            src("demo.Named",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "import java.util.function.Supplier;",
                "@ClassBuilder(validate = false, factoryMethod = \"make\")",
                "public class Named {",
                "    @Lazy private String label = compute();",
                "    private static String compute() { return \"computed\"; }",
                "    static Named make(Supplier<String> label) { return new Named(); }",
                "}"),
            use("return Named.builder().build().getLabel();"));
        assertEquals("computed", result);
    }

    /**
     * Beside a refused declared builder no setter is appended, and the all-args
     * constructor's retyped parameter alone produced a second error under the
     * refusal.
     */
    @Test
    public void primitiveLazyField_besideARefusedDeclaredBuilder_reportsOnlyTheRefusal() {
        Compilation c = compile(
            src("demo.Refused",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Refused {",
                "    @Lazy private int count = compute();",
                "    private String name;",
                "    private static int compute() { return 7; }",
                "    public class Builder { }",
                "}"));
        assertEquals(diagnostics(c), Compilation.Status.FAILURE, c.status());
        assertEquals(diagnostics(c), 1, errors(c).size());
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder'");
    }

    /** A boolean and a long lazy field box to {@code Boolean} and {@code Long}. */
    @Test
    public void booleanAndLongLazyFields_buildTheComputedValues() throws Exception {
        Object result = run(
            src("demo.Probe",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Probe {",
                "    @Lazy private boolean ready = check();",
                "    @Lazy private long size = measure();",
                "    private static boolean check() { return true; }",
                "    private static long measure() { return 5L; }",
                "}"),
            use("Probe p = Probe.builder().build();",
                "Probe q = Probe.builder().ready(() -> false).size(() -> 8L).build();",
                "return p.isReady() + \"/\" + p.getSize() + \"/\" + q.isReady() + \"/\" + q.getSize();"));
        assertEquals("true/5/false/8", result);
    }

    /**
     * A chain role's builder emits its setters through the self-typed path,
     * which boxed nothing either.
     */
    @Test
    public void primitiveLazyField_onAChainRoot_buildsTheComputedValue() throws Exception {
        Object result = run(
            src("demo.Base",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public abstract class Base {",
                "    @Lazy private int count = compute();",
                "    private static int compute() { return 7; }",
                "}"),
            src("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Base {",
                "    String name;",
                "}"),
            use("return Leaf.builder().name(\"n\").build().getCount() + \"/\"",
                "    + Leaf.builder().count(() -> 9).build().getCount();"));
        assertEquals("7/9", result);
    }

    // ------------------------------------------------------------------
    // An instance default reading a @Lazy field
    // ------------------------------------------------------------------

    /**
     * The default was computed twice before the lazy holder existed - once by
     * its own field initializer, which runs ahead of every constructor body, and
     * once by the generated constructor - so {@code build()} threw
     * {@code NullPointerException: Cannot invoke "AtomicReference.get()" because
     * "this.base" is null}.
     */
    @Test
    public void instanceDefaultReadingALazyFieldThroughAHelper_buildsTheLazyValue() throws Exception {
        Object result = run(
            src("demo.Derived",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Derived {",
                "    @Lazy String base = compute();",
                "    String f = helper() + \"x\";",
                "    private static String compute() { return \"b\"; }",
                "    String helper() { return getBase(); }",
                "}"),
            use("return Derived.builder().build().f + \"/\" + Derived.builder().base(\"s\").build().f;"));
        assertEquals("bx/sx", result);
    }

    /** The same default spelled through the generated getter directly. */
    @Test
    public void instanceDefaultReadingALazyFieldThroughItsGetter_buildsTheLazyValue() throws Exception {
        Object result = run(
            src("demo.Derived",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Derived {",
                "    @Lazy String base = compute();",
                "    String f = getBase() + \"x\";",
                "    private static String compute() { return \"b\"; }",
                "}"),
            use("return Derived.builder().build().f;"));
        assertEquals("bx", result);
    }

    /**
     * A lazy field declared after the default is assigned before it too, where
     * field order alone put the default's computation first.
     */
    @Test
    public void instanceDefaultReadingALazyFieldDeclaredAfterIt_buildsTheLazyValue() throws Exception {
        Object result = run(
            src("demo.Derived",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Derived {",
                "    String f = getBase() + \"x\";",
                "    @Lazy String base = compute();",
                "    private static String compute() { return \"b\"; }",
                "}"),
            use("return Derived.builder().build().f;"));
        assertEquals("bx", result);
    }

    /** The chain's copy constructor assigns its lazy holders first as well. */
    @Test
    public void instanceDefaultReadingALazyFieldOnAChainRoot_buildsTheLazyValue() throws Exception {
        Object result = run(
            src("demo.Base",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public abstract class Base {",
                "    String f = getBase() + \"x\";",
                "    @Lazy String base = compute();",
                "    private static String compute() { return \"b\"; }",
                "}"),
            src("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Base {",
                "    String name;",
                "}"),
            use("return Leaf.builder().name(\"n\").build().f;"));
        assertEquals("bx", result);
    }

    // ------------------------------------------------------------------
    // from(T) and mutate() read a lazy field through its own getter name
    // ------------------------------------------------------------------

    /**
     * {@code from(T)} read every lazy field as {@code getX()}, so a written
     * pattern failed with {@code cannot find symbol method getHeavy()} on a
     * generated line.
     */
    @Test
    public void copyEntryPoints_readALazyFieldThroughItsWrittenPattern() throws Exception {
        Object result = run(
            src("demo.Named",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Named {",
                "    @Lazy(name = \"load{}\") String heavy = compute();",
                "    private static String compute() { return \"h\"; }",
                "}"),
            use("Named built = Named.builder().heavy(\"s\").build();",
                "return Named.from(built).build().loadHeavy() + \"/\" + built.mutate().build().loadHeavy();"));
        assertEquals("s/s", result);
    }

    /** A {@code FLUENT} lazy getter is the bare field name. */
    @Test
    public void copyEntryPoints_readAFluentLazyField() throws Exception {
        Object result = run(
            src("demo.Named",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "import dev.simplified.annotations.NamingStyle;",
                "@ClassBuilder(validate = false)",
                "public class Named {",
                "    @Lazy(style = NamingStyle.FLUENT) String heavy = compute();",
                "    private static String compute() { return \"h\"; }",
                "}"),
            use("Named built = Named.builder().heavy(\"s\").build();",
                "return Named.from(built).build().heavy() + \"/\" + built.mutate().build().heavy();"));
        assertEquals("s/s", result);
    }

    /** A boolean lazy getter is {@code isX()}. */
    @Test
    public void copyEntryPoints_readABooleanLazyField() throws Exception {
        Object result = run(
            src("demo.Flagged",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Flagged {",
                "    @Lazy boolean ready = check();",
                "    private static boolean check() { return false; }",
                "}"),
            use("Flagged built = Flagged.builder().ready(true).build();",
                "return Flagged.from(built).build().isReady() + \"/\" + built.mutate().build().isReady();"));
        assertEquals("true/true", result);
    }

}
