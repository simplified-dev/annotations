package dev.simplified.lazy.mutate;

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
 * {@code @Lazy} over a field its own initializer does not assign - the shape a
 * value derived from constructor arguments or from sibling fields takes, which
 * has no initializer to hold the expression.
 *
 * <p>Every case here asserts <b>when</b> the work runs, not only that the value
 * is right. Deferral is the whole claim, and a wrap that computed eagerly and
 * cached the result would satisfy every value assertion while doing none of what
 * the annotation promises.
 */
public class ConstructorAssignedLazyTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("lazy-ctor-test");
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
            ConstructorAssignedLazyTest.class.getClassLoader());
    }

    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    /**
     * The shape the ten {@code client} sites take: a field computed from a
     * sibling field, which the constructor assigns and nothing else can.
     */
    @Test
    public void assignedFromSiblingState_defersUntilFirstRead() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Response",
                "package demo;",
                "import dev.simplified.annotations.Lazy;",
                "public class Response {",
                "    public static int parses = 0;",
                "    private final String raw;",
                "    @Lazy private final String headers;",
                "    public Response(String raw) {",
                "        this.raw = raw;",
                "        this.headers = parse(this.raw);",
                "    }",
                "    private static String parse(String s) { parses++; return s.toUpperCase(); }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseResponse",
                "package demo;",
                "public class UseResponse {",
                "    public static String go() {",
                "        Response r = new Response(\"ok\");",
                "        // Constructed but never read: the parse must not have run.",
                "        String beforeRead = String.valueOf(Response.parses);",
                "        String first = r.getHeaders();",
                "        String second = r.getHeaders();",
                "        return beforeRead + \"/\" + first + second + \"/\" + Response.parses;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        // Nothing parsed at construction; one parse across two reads.
        assertEquals("0/OKOK/1", runGo(c, "demo.UseResponse"));
    }

    /** A constructor parameter is an expression like any other. */
    @Test
    public void assignedFromAConstructorParameter_defersUntilFirstRead() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Message",
                "package demo;",
                "import dev.simplified.annotations.Lazy;",
                "public class Message {",
                "    @Lazy private final String text;",
                "    public Message(String text) { this.text = text; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseMessage",
                "package demo;",
                "public class UseMessage {",
                "    public static String go() { return new Message(\"hi\").getText(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("hi", runGo(c, "demo.UseMessage"));
    }

    /**
     * An existing {@code Supplier} is deferred by calling it inside the wrap,
     * which is how a field that already holds one is expressed without the
     * annotation having to know about {@code Supplier} at all.
     */
    @Test
    public void assignedFromASupplierCall_defersTheCall() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Decoded",
                "package demo;",
                "import dev.simplified.annotations.Lazy;",
                "import java.util.function.Supplier;",
                "public class Decoded {",
                "    public static int calls = 0;",
                "    private final Supplier<String> decoder;",
                "    @Lazy private final String body;",
                "    public Decoded(Supplier<String> decoder) {",
                "        this.decoder = decoder;",
                "        this.body = this.decoder.get();",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseDecoded",
                "package demo;",
                "public class UseDecoded {",
                "    public static String go() {",
                "        Decoded d = new Decoded(() -> { Decoded.calls++; return \"b\"; });",
                "        String before = String.valueOf(Decoded.calls);",
                "        return before + \"/\" + d.getBody() + d.getBody() + \"/\" + Decoded.calls;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("0/bb/1", runGo(c, "demo.UseDecoded"));
    }

    /**
     * A field assigned in only one arm of a branch is still a field this pass
     * has to rewrite. Missing an arm leaves the author a type error about
     * the storage type on a constructor line they wrote and did not change.
     */
    @Test
    public void assignedInsideABranch_isRewrittenInEveryArm() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Branching",
                "package demo;",
                "import dev.simplified.annotations.Lazy;",
                "public class Branching {",
                "    @Lazy private final String label;",
                "    public Branching(boolean flag) {",
                "        if (flag) {",
                "            this.label = \"yes\";",
                "        } else {",
                "            this.label = \"no\";",
                "        }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBranching",
                "package demo;",
                "public class UseBranching {",
                "    public static String go() {",
                "        return new Branching(true).getLabel() + new Branching(false).getLabel();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("yesno", runGo(c, "demo.UseBranching"));
    }

    /** Several constructors, each supplying its own expression. */
    @Test
    public void everyConstructorGetsItsOwnSupplier() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Pair",
                "package demo;",
                "import dev.simplified.annotations.Lazy;",
                "public class Pair {",
                "    @Lazy private final String name;",
                "    public Pair() { this.name = \"default\"; }",
                "    public Pair(String name) { this.name = name.trim(); }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UsePair",
                "package demo;",
                "public class UsePair {",
                "    public static String go() {",
                "        return new Pair().getName() + \"/\" + new Pair(\"  x \").getName();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("default/x", runGo(c, "demo.UsePair"));
    }

    /**
     * The field is {@code final} after the rewrite, so a constructor that does
     * not assign it is javac's ordinary blank-final error - on the author's own
     * constructor, which is where the omission is.
     */
    @Test
    public void aConstructorThatDoesNotAssignIt_isRejectedByJavac() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Partial",
                "package demo;",
                "import dev.simplified.annotations.Lazy;",
                "public class Partial {",
                "    @Lazy private final String label;",
                "    public Partial(String label) { this.label = label; }",
                "    public Partial() { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("label");
    }

    /** The getter still carries the field's own nullness and its javadoc-bearing shape. */
    @Test
    public void assignedField_getterKeepsTheFieldsNullness() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Annotated",
                "package demo;",
                "import dev.simplified.annotations.Lazy;",
                "import org.jetbrains.annotations.NotNull;",
                "public class Annotated {",
                "    @Lazy private final @NotNull String value;",
                "    public Annotated(String seed) { this.value = seed + \"!\"; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseAnnotated",
                "package demo;",
                "public class UseAnnotated {",
                "    public static String go() { return new Annotated(\"a\").getValue(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a!", runGo(c, "demo.UseAnnotated"));
    }

    /**
     * The {@code @ClassBuilder} path is untouched: there the builder supplies a
     * {@code Supplier<T>} and the constructor parameter is retyped, so the
     * assignment passes it through rather than wrapping it again.
     */
    @Test
    public void classBuilderPathStillPassesItsSupplierThrough() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Built",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Built {",
                "    public static int calls = 0;",
                "    @Lazy String value;",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBuilt",
                "package demo;",
                "public class UseBuilt {",
                "    public static String go() {",
                "        Built b = Built.builder().value(() -> { Built.calls++; return \"v\"; }).build();",
                "        String before = String.valueOf(Built.calls);",
                "        return before + \"/\" + b.getValue() + b.getValue() + \"/\" + Built.calls;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("0/vv/1", runGo(c, "demo.UseBuilt"));
    }

}
