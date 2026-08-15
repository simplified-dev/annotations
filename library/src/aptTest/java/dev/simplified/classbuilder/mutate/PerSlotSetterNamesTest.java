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
 * A {@code @SetterNames} written on one slot, and the boolean-prefix rule the
 * builder's setters take from the field's own name.
 *
 * <p>The two travel together because they are the same expression: one decides
 * which pattern a slot expands, the other decides what it expands against.
 */
public class PerSlotSetterNamesTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-perslot-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, PerSlotSetterNamesTest.class.getClassLoader());
    }

    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    // ------------------------------------------------------------------
    // A pattern written on one slot
    // ------------------------------------------------------------------

    /**
     * The shape the feature exists for: one type whose fields do not agree on a
     * spelling. Without a per-slot override the target's single pattern has to
     * fit them all, and the builder stays hand-written.
     */
    @Test
    public void oneSlotOverridesTheTargetsPattern() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.WebPOptions",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.SetterNames;",
                "@ClassBuilder(validate = false, setters = @SetterNames(set = \"with{}\"))",
                "public class WebPOptions {",
                "    float quality;",
                "    @SetterNames(set = \"is{}\") boolean lossless;",
                "    public float getQuality() { return quality; }",
                "    public boolean isLossless() { return lossless; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseWebP",
                "package demo;",
                "public class UseWebP {",
                "    public static String go() {",
                "        WebPOptions o = WebPOptions.builder().withQuality(0.5f).isLossless(true).build();",
                "        return o.getQuality() + \"/\" + o.isLossless();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("0.5/true", runGo(c, "demo.UseWebP"));
    }

    /**
     * An unwritten role on a slot inherits the <em>target's</em> resolved
     * pattern, not the style's. Inheriting from the style would silently undo
     * the target's own override for every role the slot did not name.
     */
    @Test
    public void unwrittenRolesInheritTheTargetsPatternNotTheStyles() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Registry",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import dev.simplified.annotations.SetterNames;",
                "import java.util.List;",
                "@ClassBuilder(validate = false, setters = @SetterNames(set = \"with{}\", add = \"append{}\"))",
                "public class Registry {",
                "    // Overrides `set` only; `add` still comes from the target.",
                "    @Collector(singular = true) @SetterNames(set = \"replace{}\") List<String> tags;",
                "    public List<String> getTags() { return tags; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRegistry",
                "package demo;",
                "public class UseRegistry {",
                "    public static String go() {",
                "        return Registry.builder().replaceTags(\"a\").appendTag(\"b\").build().getTags().toString();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("[a, b]", runGo(c, "demo.UseRegistry"));
    }

    /**
     * {@code from(T)} and {@code mutate()} call the builder's setters by name.
     * Seeding through the target's pattern where the slot renamed it emits a
     * call to a method the builder does not have - which is a compile error in
     * generated code, so this is the assertion that keeps the two in step.
     */
    @Test
    public void copyFactorySeedsThroughTheSlotsOwnSetter() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Seeded",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.SetterNames;",
                "@ClassBuilder(validate = false)",
                "public class Seeded {",
                "    @SetterNames(set = \"withLabel\") String label;",
                "    int size;",
                "    public String getLabel() { return label; }",
                "    public int getSize() { return size; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSeeded",
                "package demo;",
                "public class UseSeeded {",
                "    public static String go() {",
                "        Seeded a = Seeded.builder().withLabel(\"x\").size(1).build();",
                "        Seeded b = Seeded.from(a).size(2).build();",
                "        Seeded d = b.mutate().size(3).build();",
                "        return d.getLabel() + d.getSize();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x3", runGo(c, "demo.UseSeeded"));
    }

    /** The override reaches an inherited slot through a SuperBuilder chain too. */
    @Test
    public void superBuilderChainHonoursAnInheritedSlotsOverride() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Base",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.SetterNames;",
                "@ClassBuilder(validate = false)",
                "public abstract class Base {",
                "    @SetterNames(set = \"withTitle\") String title;",
                "    public String getTitle() { return title; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Base {",
                "    int rank;",
                "    public int getRank() { return rank; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLeaf",
                "package demo;",
                "public class UseLeaf {",
                "    public static String go() {",
                "        Leaf l = Leaf.builder().withTitle(\"t\").rank(1).build();",
                "        return Leaf.from(l).rank(2).build().getTitle() + l.getRank();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("t1", runGo(c, "demo.UseLeaf"));
    }

    /** A slot's pattern is validated where it is written, not at the type. */
    @Test
    public void aSlotsBrokenPatternIsReportedAtTheSlot() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Broken",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.SetterNames;",
            "@ClassBuilder(validate = false)",
            "public class Broken {",
            "    @SetterNames(set = \"with{}Or{}\") String label;",
            "}");
        Compilation c = compile(source);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@SetterNames pattern for 'set'")
            .inFile(source).onLine(6);
    }

    /** A seeded slot emits no setter, so a pattern for one does nothing. */
    @Test
    public void seededParameterRejectsASetterNamesOverride() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Clashing",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.SetterNames;",
                "public final class Clashing {",
                "    @ClassBuilder",
                "    Clashing(@BuilderSeed @SetterNames(set = \"with{}\") String key) { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@BuilderSeed cannot be combined with @SetterNames");
    }

    /** A constructor slot takes an override the same way a field does. */
    @Test
    public void aConstructorParameterTakesAnOverride() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Ranged",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.SetterNames;",
                "public final class Ranged {",
                "    private final int min;",
                "    private final int max;",
                "    @ClassBuilder(setters = @SetterNames(set = \"with{}\"))",
                "    Ranged(int min, @SetterNames(set = \"upTo{}\") int max) {",
                "        this.min = min; this.max = max;",
                "    }",
                "    public String render() { return min + \"-\" + max; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRanged",
                "package demo;",
                "public class UseRanged {",
                "    public static String go() {",
                "        return Ranged.builder().withMin(2).upToMax(9).build().render();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("2-9", runGo(c, "demo.UseRanged"));
    }

    // ------------------------------------------------------------------
    // The boolean prefix the pattern is about to add
    // ------------------------------------------------------------------

    /**
     * A {@code boolean} field already named {@code isX} must not double the
     * prefix. Lombok's rule, expressed on the pattern rather than on the style,
     * and it reaches the builder's setters here as it already reached the
     * accessors.
     */
    @Test
    public void booleanFieldNamedIsX_doesNotDoubleThePrefix() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Forum",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Forum {",
                "    boolean isPermaLink;",
                "    public boolean getRaw() { return isPermaLink; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseForum",
                "package demo;",
                "public class UseForum {",
                "    public static String go() {",
                "        // The zero-arg flag is isPermaLink(), not isIsPermaLink().",
                "        // The value-taking setter is the bare `{}` role, which",
                "        // prepends nothing and so keeps the field's own name.",
                "        return Forum.builder().isPermaLink().build().getRaw()",
                "            + \"/\" + Forum.builder().isPermaLink(false).build().getRaw();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("true/false", runGo(c, "demo.UseForum"));
    }

    /** The same rule on a {@code @Negate} stem, which is a subject like any other. */
    @Test
    public void negateStemNamedIsX_doesNotDoubleThePrefix() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Toggle",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Negate;",
                "@ClassBuilder(validate = false)",
                "public class Toggle {",
                "    @Negate(\"isDisabled\") boolean enabled;",
                "    public boolean getRaw() { return enabled; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseToggle",
                "package demo;",
                "public class UseToggle {",
                "    public static String go() {",
                "        return String.valueOf(Toggle.builder().isDisabled().build().getRaw());",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("false", runGo(c, "demo.UseToggle"));
    }

    /** {@code BEAN} puts the prefix on the value-taking setter, where it doubled too. */
    @Test
    public void beanStyleTypedSetter_doesNotDoubleThePrefix() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Bean",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.NamingStyle;",
                "@ClassBuilder(validate = false, style = NamingStyle.BEAN)",
                "public class Bean {",
                "    boolean isDefault;",
                "    public boolean getRaw() { return isDefault; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBean",
                "package demo;",
                "public class UseBean {",
                "    public static String go() {",
                "        return String.valueOf(Bean.builder().setDefault(true).build().getRaw());",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("true", runGo(c, "demo.UseBean"));
    }

    /**
     * The guards. A field whose name merely begins with the letters keeps them,
     * and a non-{@code boolean} field is not a subject for the rule at all -
     * stripping either would rename an unrelated setter.
     */
    @Test
    public void onlyABooleanNamedIsCapitalIsStripped() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Guards",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Guards {",
                "    boolean island;",
                "    String isPermaLink;",
                "    public String render() { return island + \"/\" + isPermaLink; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseGuards",
                "package demo;",
                "public class UseGuards {",
                "    public static String go() {",
                "        // isIsland() keeps its letters; isPermaLink is a String,",
                "        // so its setter is the bare field name unchanged.",
                "        return Guards.builder().isIsland().isPermaLink(\"p\").build().render();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("true/p", runGo(c, "demo.UseGuards"));
    }

    /** A fluent {@code {}} pattern prepends nothing, so there is no prefix to double. */
    @Test
    public void fluentPatternKeepsTheFieldsOwnName() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Fluent",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.SetterNames;",
                "@ClassBuilder(validate = false, setters = @SetterNames(flag = \"{}\"))",
                "public class Fluent {",
                "    boolean isPermaLink;",
                "    public boolean getRaw() { return isPermaLink; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseFluent",
                "package demo;",
                "public class UseFluent {",
                "    public static String go() {",
                "        return String.valueOf(Fluent.builder().isPermaLink().build().getRaw());",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("true", runGo(c, "demo.UseFluent"));
    }

    /** The SuperBuilder emitter mints the same names, through the same rule. */
    @Test
    public void superBuilderChainAppliesTheSameBooleanRule() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Root",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Root {",
                "    boolean isPinned;",
                "    public boolean getRaw() { return isPinned; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Root {",
                "    int rank;",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLink",
                "package demo;",
                "public class UseLink {",
                "    public static String go() {",
                "        return String.valueOf(Link.builder().isPinned().rank(1).build().getRaw());",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("true", runGo(c, "demo.UseLink"));
    }

}
