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
 * {@code @ClassBuilder} on a constructor or static factory, where the builder's
 * slots are that member's parameters rather than the enclosing type's fields.
 *
 * <p>Each passing case compiles a consumer alongside the target and runs it, so
 * the generated entry point is proved by a real call rather than by the target
 * compiling on its own.
 */
public class ExecutableTargetTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-executable-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, ExecutableTargetTest.class.getClassLoader());
    }

    /** Invokes the compiled consumer's {@code go()} so the generated surface is exercised at runtime. */
    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    // ------------------------------------------------------------------
    // The two shapes
    // ------------------------------------------------------------------

    @Test
    public void constructorTarget_buildsThroughTheAnnotatedConstructor() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Range",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Range {",
                "    private final int min;",
                "    private final int max;",
                "    @ClassBuilder",
                "    Range(int min, int max) { this.min = min; this.max = max; }",
                "    public int getMin() { return min; }",
                "    public int getMax() { return max; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRange",
                "package demo;",
                "public class UseRange {",
                "    public static String go() {",
                "        Range r = Range.builder().min(2).max(9).build();",
                "        return r.getMin() + \"-\" + r.getMax();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("2-9", runGo(c, "demo.UseRange"));
    }

    /**
     * A static factory decides both what {@code build()} calls and what it
     * returns - which is why the return type, not the enclosing type, is what
     * {@code build()} declares.
     */
    @Test
    public void staticFactoryTarget_buildsThroughTheAnnotatedFactory() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Span",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Span {",
                "    private final String label;",
                "    private final int width;",
                "    private Span(String label, int width) { this.label = label; this.width = width; }",
                "    @ClassBuilder",
                "    public static Span of(String label, int width) { return new Span(label, width * 2); }",
                "    public String getLabel() { return label; }",
                "    public int getWidth() { return width; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSpan",
                "package demo;",
                "public class UseSpan {",
                "    public static String go() {",
                "        Span s = Span.builder().label(\"a\").width(3).build();",
                "        return s.getLabel() + s.getWidth();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a6", runGo(c, "demo.UseSpan"));
    }

    /**
     * The parameter's own type drives the setter matrix exactly as a field's
     * would - the {@code Optional} pair, the boolean pair, the
     * {@code @Formattable} overload and the {@code @Collector} bulk forms all
     * come off a parameter.
     */
    @Test
    public void parameterSlots_takeTheWholeSetterMatrix() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Wide",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import dev.simplified.annotations.Formattable;",
                "import dev.simplified.annotations.Negate;",
                "import java.util.List;",
                "import java.util.Optional;",
                "public final class Wide {",
                "    private final Optional<String> note;",
                "    private final boolean hidden;",
                "    private final String title;",
                "    private final List<String> tags;",
                "    @ClassBuilder",
                "    Wide(Optional<String> note,",
                "         @Negate(\"visible\") boolean hidden,",
                "         @Formattable String title,",
                "         @Collector(singular = true, clearable = true) List<String> tags) {",
                "        this.note = note; this.hidden = hidden; this.title = title; this.tags = tags;",
                "    }",
                "    public String render() {",
                "        return note.orElse(\"-\") + hidden + title + tags;",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseWide",
                "package demo;",
                "public class UseWide {",
                "    public static String go() {",
                "        return Wide.builder()",
                "            .note(\"n\")",
                "            .isVisible()",
                "            .title(\"%s-%d\", \"t\", 4)",
                "            .tags(\"a\")",
                "            .addTag(\"b\")",
                "            .build().render();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("nfalset-4[a, b]", runGo(c, "demo.UseWide"));
    }

    // ------------------------------------------------------------------
    // Seeded entry points
    // ------------------------------------------------------------------

    @Test
    public void seededParameter_movesOntoBuilderAndDropsItsSetter() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Action",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Action {",
                "    private final String key;",
                "    private final boolean enabled;",
                "    @ClassBuilder",
                "    Action(@BuilderSeed String key, boolean enabled) {",
                "        this.key = key; this.enabled = enabled;",
                "    }",
                "    public String describe() { return key + \":\" + enabled; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseAction",
                "package demo;",
                "public class UseAction {",
                "    public static String go() {",
                "        return Action.builder(\"open\").isEnabled().build().describe();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("open:true", runGo(c, "demo.UseAction"));
    }

    /**
     * The seed's whole point: it is required at the entry point rather than
     * settable afterwards, so neither a bare {@code builder()} nor a setter for
     * it exists to reach.
     */
    @Test
    public void seededParameter_hasNoSetterAndNoBareEntryPoint() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Seeded",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Seeded {",
                "    @ClassBuilder",
                "    Seeded(@BuilderSeed String key, int n) { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSeeded",
                "package demo;",
                "public class UseSeeded {",
                "    public static void go() {",
                "        Seeded.builder(\"k\").key(\"other\").n(1).build();",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot find symbol");
        assertThat(c).hadErrorContaining("method key(java.lang.String)");
    }

    /** The other half of the pair: the seeded entry point is the only one. */
    @Test
    public void seededParameter_leavesNoNullaryEntryPoint() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.OnlySeeded",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class OnlySeeded {",
                "    @ClassBuilder",
                "    OnlySeeded(@BuilderSeed String key, int n) { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseOnlySeeded",
                "package demo;",
                "public class UseOnlySeeded {",
                "    public static void go() {",
                "        OnlySeeded.builder().n(1).build();",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("builder");
    }

    @Test
    public void twoSeeds_appearOnBuilderInDeclarationOrder() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Pairing",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Pairing {",
                "    private final String left;",
                "    private final int right;",
                "    private final boolean flag;",
                "    @ClassBuilder",
                "    Pairing(@BuilderSeed String left, @BuilderSeed int right, boolean flag) {",
                "        this.left = left; this.right = right; this.flag = flag;",
                "    }",
                "    public String render() { return left + right + flag; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UsePairing",
                "package demo;",
                "public class UsePairing {",
                "    public static String go() {",
                "        return Pairing.builder(\"L\", 7).isFlag().build().render();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("L7true", runGo(c, "demo.UsePairing"));
    }

    /** A seeded slot emits no setter, so a companion that only shapes one is a mistake. */
    @Test
    public void seededParameter_rejectsASetterShapingCompanion() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Clashing",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.List;",
                "public final class Clashing {",
                "    @ClassBuilder",
                "    Clashing(@BuilderSeed @Collector List<String> values) { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@BuilderSeed cannot be combined with @Collector");
    }

    // ------------------------------------------------------------------
    // Generics and records
    // ------------------------------------------------------------------

    /** A constructor runs under the enclosing type's parameters. */
    @Test
    public void constructorTarget_onAGenericEnclosingType() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Crate",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Crate<V> {",
                "    private final V item;",
                "    private final int count;",
                "    @ClassBuilder",
                "    Crate(V item, int count) { this.item = item; this.count = count; }",
                "    public V getItem() { return item; }",
                "    public int getCount() { return count; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseCrate",
                "package demo;",
                "public class UseCrate {",
                "    public static String go() {",
                "        // Consumed without an intermediate local, so a raw chain",
                "        // fails rather than warning.",
                "        String s = Crate.<String>builder().item(\"x\").count(2).build().getItem();",
                "        return s + Crate.<String>builder().count(2).build().getCount();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x2", runGo(c, "demo.UseCrate"));
    }

    /**
     * A {@code static} factory cannot see the enclosing type's parameters, so
     * the builder carries the factory's own and {@code build()} returns what the
     * factory declares.
     */
    @Test
    public void staticFactoryTarget_carriesItsOwnTypeParameters() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Box",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Box<V> {",
                "    private final V value;",
                "    private Box(V value) { this.value = value; }",
                "    @ClassBuilder",
                "    public static <T> Box<T> of(T value) { return new Box<>(value); }",
                "    public V getValue() { return value; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBox",
                "package demo;",
                "public class UseBox {",
                "    public static String go() {",
                "        String s = Box.<String>builder().value(\"v\").build().getValue();",
                "        return s;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("v", runGo(c, "demo.UseBox"));
    }

    /** A record's static factory, whose parameters need not be its components. */
    @Test
    public void recordTarget_buildsThroughAStaticFactory() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Point",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public record Point(int x, int y, int sum) {",
                "    @ClassBuilder",
                "    public static Point at(int x, int y) { return new Point(x, y, x + y); }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UsePoint",
                "package demo;",
                "public class UsePoint {",
                "    public static String go() {",
                "        Point p = Point.builder().x(2).y(3).build();",
                "        return p.x() + \"/\" + p.y() + \"/\" + p.sum();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("2/3/5", runGo(c, "demo.UsePoint"));
    }

    /** A record's canonical constructor, whose parameters are its components. */
    @Test
    public void recordTarget_buildsThroughTheCanonicalConstructor() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Cell",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public record Cell(String name, int size) {",
                "    @ClassBuilder",
                "    public Cell(String name, int size) { this.name = name; this.size = size; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseCell",
                "package demo;",
                "public class UseCell {",
                "    public static String go() {",
                "        Cell cell = Cell.builder().name(\"n\").size(4).build();",
                "        return cell.name() + cell.size();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("n4", runGo(c, "demo.UseCell"));
    }

    // ------------------------------------------------------------------
    // What this path deliberately does not emit
    // ------------------------------------------------------------------

    /**
     * {@code from(T)} and {@code mutate()} seed every slot by reading a built
     * instance, and a parameter has no accessor to be read through. Both are
     * suppressed rather than emitted against a guess.
     */
    @Test
    public void executableTarget_emitsNoCopyFactoryAndNoMutate() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Sole",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Sole {",
                "    private final int n;",
                "    @ClassBuilder",
                "    Sole(int n) { this.n = n; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSole",
                "package demo;",
                "public class UseSole {",
                "    public static void go() {",
                "        Sole.from(Sole.builder().n(1).build());",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSoleMutate",
                "package demo;",
                "public class UseSoleMutate {",
                "    public static void go() {",
                "        Sole.builder().n(1).build().mutate();",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("method from(demo.Sole)");
        assertThat(c).hadErrorContaining("method mutate()");
    }

    /**
     * {@code @BuildFlag} stays on the built type's fields, which is where the
     * runtime validator reads it - so a constructor target enforces it without
     * the annotation ever reaching a parameter.
     */
    @Test
    public void constructorTarget_stillEnforcesBuildFlagsOnTheBuiltType() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Named",
                "package demo;",
                "import dev.simplified.annotations.BuildFlag;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Named {",
                "    @BuildFlag(nonNull = true) private final String name;",
                "    @ClassBuilder",
                "    Named(String name) { this.name = name; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseNamed",
                "package demo;",
                "public class UseNamed {",
                "    public static String go() {",
                "        try {",
                "            Named.builder().build();",
                "            return \"accepted\";",
                "        } catch (RuntimeException e) {",
                "            return \"rejected\";",
                "        }",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("rejected", runGo(c, "demo.UseNamed"));
    }

    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Test
    public void instanceMethodTarget_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Inst",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Inst {",
                "    @ClassBuilder",
                "    public Inst make(int n) { return this; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("no receiver");
    }

    @Test
    public void voidMethodTarget_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Voided",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Voided {",
                "    @ClassBuilder",
                "    public static void go(int n) { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("nothing for build() to return");
    }

    @Test
    public void annotationOnBothTheTypeAndAMember_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Both",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public final class Both {",
                "    private final int n;",
                "    @ClassBuilder",
                "    Both(int n) { this.n = n; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("one type carries one builder");
    }

    @Test
    public void twoAnnotatedMembersOnOneType_areRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Twice",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Twice {",
                "    private final int n;",
                "    @ClassBuilder",
                "    Twice(int n) { this.n = n; }",
                "    @ClassBuilder",
                "    public static Twice of(int n) { return new Twice(n); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("already on another member");
    }

    /**
     * {@code @Lazy} retypes the field and every constructor parameter feeding
     * it, so the slots this builder would pass stop matching the member it
     * calls. Loud rather than a compile error inside generated code.
     */
    @Test
    public void lazyFieldOnTheEnclosingType_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Deferred",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "public final class Deferred {",
                "    @Lazy private final String value;",
                "    @ClassBuilder",
                "    Deferred(String value) { this.value = value; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Lazy");
    }

    @Test
    public void excludeOnAnExecutableTarget_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Excluding",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Excluding {",
                "    @ClassBuilder(exclude = \"n\")",
                "    Excluding(int n) { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("exclude");
    }

    @Test
    public void factoryMethodOnAnExecutableTarget_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Redirecting",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Redirecting {",
                "    @ClassBuilder(factoryMethod = \"make\")",
                "    Redirecting(int n) { }",
                "    static Redirecting make(int n) { return new Redirecting(n); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("factoryMethod");
    }

    // ------------------------------------------------------------------
    // Configuration that does carry over
    // ------------------------------------------------------------------

    /**
     * The naming trio reads the same attributes it reads on a type, and the
     * builder-class name expands against the enclosing type's simple name -
     * there being no other subject a member could offer.
     */
    @Test
    public void namingAttributes_applyToAnExecutableTarget() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Config",
                "package demo;",
                "import dev.simplified.annotations.BuilderNames;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.SetterNames;",
                "public final class Config {",
                "    private final int port;",
                "    @ClassBuilder(",
                "        builder = @BuilderNames(type = \"{}Builder\", builder = \"newBuilder\"),",
                "        setters = @SetterNames(set = \"with{}\"))",
                "    Config(int port) { this.port = port; }",
                "    public int getPort() { return port; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseConfig",
                "package demo;",
                "public class UseConfig {",
                "    public static Integer go() {",
                "        Config.ConfigBuilder b = Config.newBuilder();",
                "        return b.withPort(8080).build().getPort();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(8080, runGo(c, "demo.UseConfig"));
    }

    /**
     * The builder's own constructor takes {@code builderConstructorAccess} here
     * as everywhere, so {@code builder(...)} stays the one way in even once it
     * carries a seed.
     */
    @Test
    public void builderConstructorAccess_appliesToASeededBuilder() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Guarded",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Guarded {",
                "    @ClassBuilder",
                "    Guarded(@BuilderSeed String key) { }",
                "}"),
            JavaFileObjects.forSourceLines("other.UseGuarded",
                "package other;",
                "import demo.Guarded;",
                "public class UseGuarded {",
                "    public static void go() {",
                "        new Guarded.Builder(\"k\");",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot be accessed from outside package");
    }

}
