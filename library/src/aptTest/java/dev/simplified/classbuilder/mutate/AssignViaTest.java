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
 * {@code @AssignVia} - a setter that routes its argument through a static
 * method on the way into the slot.
 *
 * <p>The two shapes are decided by one thing, the transform's parameter type,
 * and both are pinned here: taking the slot's own type shapes the setter the
 * slot already has, and taking any other adds an overload beside it.
 */
public class AssignViaTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-assignvia-test");
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
            AssignViaTest.class.getClassLoader());
    }

    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    // ------------------------------------------------------------------
    // A transform over the slot's own type shapes the setter it already has
    // ------------------------------------------------------------------

    /** The site this exists for: {@code withQuality} clamping into {@code [0, 1]}. */
    @Test
    public void directTransform_clampsTheOrdinarySetter() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.WebPOptions",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class WebPOptions {",
                "    @AssignVia(method = \"clampQuality\") float quality;",
                "    static float clampQuality(float quality) {",
                "        return Math.max(0.0f, Math.min(1.0f, quality));",
                "    }",
                "    public float getQuality() { return quality; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseWebP",
                "package demo;",
                "public class UseWebP {",
                "    public static String go() {",
                "        return WebPOptions.builder().quality(4.0f).build().getQuality()",
                "            + \"/\" + WebPOptions.builder().quality(-1.0f).build().getQuality()",
                "            + \"/\" + WebPOptions.builder().quality(0.5f).build().getQuality();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("1.0/0.0/0.5", runGo(c, "demo.UseWebP"));
    }

    /**
     * A direct transform is written once and covers every route into the slot,
     * the zero-argument {@code boolean} form and the {@code @Negate} inverse
     * included. Those assign a literal the caller never passed, so a transform
     * that skipped them would be a hole nobody can see from the call site.
     */
    @Test
    public void directTransform_reachesTheZeroArgAndNegateForms() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Toggle",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Negate;",
                "@ClassBuilder(validate = false)",
                "public class Toggle {",
                "    @AssignVia(method = \"record\") @Negate(\"disabled\") boolean enabled;",
                "    static java.util.List<Boolean> seen = new java.util.ArrayList<>();",
                "    static boolean record(boolean value) { seen.add(value); return value; }",
                "    public boolean isEnabled() { return enabled; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseToggle",
                "package demo;",
                "public class UseToggle {",
                "    public static String go() {",
                "        Toggle.seen.clear();",
                "        Toggle.builder().isEnabled().build();",
                "        Toggle.builder().isDisabled().build();",
                "        Toggle.builder().enabled(false).build();",
                "        Toggle.builder().disabled(true).build();",
                "        return Toggle.seen.toString();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("[true, false, false, false]", runGo(c, "demo.UseToggle"));
    }

    /** The {@code @Formattable} overload composes a String and assigns it through the transform. */
    @Test
    public void directTransform_reachesTheFormattableOverload() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.DiagramConfig",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Formattable;",
                "@ClassBuilder(validate = false)",
                "public class DiagramConfig {",
                "    @AssignVia(method = \"withExtension\") @Formattable String fileName;",
                "    static String withExtension(String fileName) {",
                "        return fileName.endsWith(\".svg\") ? fileName : fileName + \".svg\";",
                "    }",
                "    public String getFileName() { return fileName; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseDiagram",
                "package demo;",
                "public class UseDiagram {",
                "    public static String go() {",
                "        return DiagramConfig.builder().fileName(\"Context\").build().getFileName()",
                "            + \"/\" + DiagramConfig.builder().fileName(\"Context.svg\").build().getFileName()",
                "            + \"/\" + DiagramConfig.builder().fileName(\"Ctx%d\", 2).build().getFileName();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("Context.svg/Context.svg/Ctx2.svg", runGo(c, "demo.UseDiagram"));
    }

    /**
     * {@code from(T)} seeds a slot by calling its setter, so a direct transform
     * runs on the round trip as well - which is why the javadoc asks for one
     * that gives the same answer applied twice.
     */
    @Test
    public void directTransform_runsAgainOnTheCopyFactory() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Bounded",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Bounded {",
                "    @AssignVia(method = \"cap\") int size;",
                "    static int cap(int size) { return Math.min(size, 10); }",
                "    public int getSize() { return size; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBounded",
                "package demo;",
                "public class UseBounded {",
                "    public static String go() {",
                "        Bounded first = Bounded.builder().size(99).build();",
                "        return first.getSize() + \"/\" + Bounded.from(first).build().getSize();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("10/10", runGo(c, "demo.UseBounded"));
    }

    // ------------------------------------------------------------------
    // A transform over any other type adds an overload beside the setter
    // ------------------------------------------------------------------

    /** {@code sourcePrefix(IPv6Prefix)} keeps its place and {@code sourcePrefix(String)} parses. */
    @Test
    public void coercingTransform_addsAnOverloadBesideThePlainSetter() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Prefix",
                "package demo;",
                "public record Prefix(String cidr) {",
                "    public static Prefix parse(String cidr) { return new Prefix(cidr.trim()); }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Rotation",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Rotation {",
                "    @AssignVia(method = \"parsePrefix\") Prefix sourcePrefix;",
                "    static Prefix parsePrefix(String cidr) { return Prefix.parse(cidr); }",
                "    public Prefix getSourcePrefix() { return sourcePrefix; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRotation",
                "package demo;",
                "public class UseRotation {",
                "    public static String go() {",
                "        Rotation a = Rotation.builder().sourcePrefix(new Prefix(\"a\")).build();",
                "        Rotation b = Rotation.builder().sourcePrefix(\"  b  \").build();",
                "        return a.getSourcePrefix().cidr() + \"/\" + b.getSourcePrefix().cidr();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a/b", runGo(c, "demo.UseRotation"));
    }

    /** The annotation is repeatable, so a ladder of overloads reaches one slot. */
    @Test
    public void repeatable_addsOneOverloadPerTransform() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Reference",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Reference {",
                "    @AssignVia(method = \"fromNumber\")",
                "    @AssignVia(method = \"fromChar\")",
                "    String id;",
                "    static String fromNumber(long id) { return \"n\" + id; }",
                "    static String fromChar(char id) { return \"c\" + id; }",
                "    public String getId() { return id; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseReference",
                "package demo;",
                "public class UseReference {",
                "    public static String go() {",
                "        return Reference.builder().id(\"plain\").build().getId()",
                "            + \"/\" + Reference.builder().id(7L).build().getId()",
                "            + \"/\" + Reference.builder().id('x').build().getId();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("plain/n7/cx", runGo(c, "demo.UseReference"));
    }

    /** A constructor parameter is a slot like any other, so it takes one too. */
    @Test
    public void transform_onAConstructorParameter() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Level",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public class Level {",
                "    private final int depth;",
                "    @ClassBuilder(validate = false)",
                "    Level(@AssignVia(method = \"atLeastOne\") int depth) { this.depth = depth; }",
                "    static int atLeastOne(int depth) { return Math.max(1, depth); }",
                "    public int getDepth() { return depth; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLevel",
                "package demo;",
                "public class UseLevel {",
                "    public static String go() {",
                "        return Level.builder().depth(-4).build().getDepth()",
                "            + \"/\" + Level.builder().depth(6).build().getDepth();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("1/6", runGo(c, "demo.UseLevel"));
    }

    /** An {@code Optional} slot routes through its wrapped setter, which its raw form chains to. */
    @Test
    public void directTransform_onAnOptionalSlot() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Titled",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.Optional;",
                "@ClassBuilder(validate = false)",
                "public class Titled {",
                "    @AssignVia(method = \"trimmed\") Optional<String> title;",
                "    static Optional<String> trimmed(Optional<String> title) {",
                "        return title.map(String::trim);",
                "    }",
                "    public Optional<String> getTitle() { return title; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseTitled",
                "package demo;",
                "import java.util.Optional;",
                "public class UseTitled {",
                "    public static String go() {",
                "        return Titled.builder().title(\"  a  \").build().getTitle().orElse(\"?\")",
                "            + \"/\" + Titled.builder().title(Optional.of(\"  b  \")).build().getTitle().orElse(\"?\")",
                "            + \"/\" + Titled.builder().build().getTitle().orElse(\"?\");",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a/b/?", runGo(c, "demo.UseTitled"));
    }

    // ------------------------------------------------------------------
    // Rejections, all at the annotation
    // ------------------------------------------------------------------

    @Test
    public void missingTransformMethod_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Absent",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Absent {",
                "    @AssignVia(method = \"nope\") int size;",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("names no single-argument method on Absent");
    }

    @Test
    public void ambiguousTransformMethod_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Ambiguous",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Ambiguous {",
                "    @AssignVia(method = \"clean\") String name;",
                "    static String clean(String name) { return name.trim(); }",
                "    static String clean(int name) { return String.valueOf(name); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("names 2 single-argument methods on Ambiguous");
    }

    @Test
    public void instanceTransformMethod_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Instanced",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Instanced {",
                "    @AssignVia(method = \"clean\") String name;",
                "    String clean(String name) { return name.trim(); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("names an instance method");
    }

    @Test
    public void wronglyReturningTransform_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Mistyped",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Mistyped {",
                "    @AssignVia(method = \"clean\") int size;",
                "    static String clean(int size) { return String.valueOf(size); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("does not supply 'size'");
    }

    /**
     * Two transforms taking the same type would be one duplicate method in
     * generated code, which javac reports on a line nobody wrote.
     */
    @Test
    public void twoTransformsOfOneParameterType_areRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Doubled",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Doubled {",
                "    @AssignVia(method = \"first\")",
                "    @AssignVia(method = \"second\")",
                "    String id;",
                "    static String first(int id) { return \"a\" + id; }",
                "    static String second(int id) { return \"b\" + id; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("which is already the argument of a setter 'id' emits");
    }

    /** An {@code Optional<T>} slot already has a {@code T} setter for the raw form. */
    @Test
    public void transformCollidingWithTheOptionalRawSetter_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Collides",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.Optional;",
                "@ClassBuilder(validate = false)",
                "public class Collides {",
                "    @AssignVia(method = \"wrap\") Optional<String> title;",
                "    static Optional<String> wrap(String title) { return Optional.of(title); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("which is already the argument of a setter 'title' emits");
    }

    @Test
    public void transformBesideACollector_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Collected",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Collected {",
                "    @AssignVia(method = \"copy\") @Collector List<String> tags;",
                "    static List<String> copy(List<String> tags) { return List.copyOf(tags); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@AssignVia cannot be combined with @Collector");
    }

    @Test
    public void transformBesideALazyField_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Deferred",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Deferred {",
                "    @AssignVia(method = \"clean\") @Lazy String name = \"  x  \";",
                "    static String clean(String name) { return name.trim(); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@AssignVia cannot be combined with @Lazy");
    }

    @Test
    public void transformBesideASeed_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Seeded",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public class Seeded {",
                "    private final String key;",
                "    @ClassBuilder(validate = false)",
                "    Seeded(@BuilderSeed @AssignVia(method = \"clean\") String key) { this.key = key; }",
                "    static String clean(String key) { return key.trim(); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@BuilderSeed cannot be combined with @AssignVia");
    }

    /**
     * A transform over the slot's own erasure whose declared parameter cannot
     * take the slot's type - the ordinary setter is what hands it its argument,
     * so this fails at the annotation rather than inside the setter.
     */
    @Test
    public void directTransformThatCannotAcceptTheSlot_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Narrowed",
                "package demo;",
                "import dev.simplified.annotations.AssignVia;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Narrowed {",
                "    @AssignVia(method = \"copy\") List<String> tags;",
                "    static List<String> copy(List<Integer> tags) { return List.of(); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot accept 'tags'");
    }

}
