package dev.sbs.classbuilder.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests of the annotation processor: each test compiles a fixture
 * source, runs the processor, then pokes at the generated {@code <Name>Builder.java}
 * for expected shape. The generated source itself is also re-compiled in the
 * same pass so structural errors surface as hard failures.
 */
public class ClassBuilderProcessorTest {

    private static Compilation compile(JavaFileObject... sources) {
        // Force the legacy sibling-emitter path so these tests continue to
        // exercise the <TypeName>Builder.java generation until Phase 6
        // retires it. The AST-mutation path has its own test suite.
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor(false))
            .compile(sources);
    }

    private static String generatedSource(Compilation compilation, String fqn) {
        Optional<JavaFileObject> out = compilation.generatedSourceFile(fqn);
        if (out.isEmpty()) {
            throw new AssertionError("Expected generated source '" + fqn + "' but processor produced none. " +
                "Generated files: " + compilation.generatedFiles());
        }
        try {
            return out.get().getCharContent(false).toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------
    // Plain class: declares fields, setters, build()
    // ------------------------------------------------------------------

    @Test
    public void plainClass_producesBuilder() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Simple",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder",
            "public class Simple {",
            "    String name;",
            "    int count;",
            "    Simple(String name, int count) { this.name = name; this.count = count; }",
            "    public String getName() { return name; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();

        String out = generatedSource(c, "demo.SimpleBuilder");
        assertTrue(out, out.contains("public class SimpleBuilder"));
        assertTrue(out, out.contains("public @NotNull SimpleBuilder withName(String name)"));
        assertTrue(out, out.contains("public @NotNull SimpleBuilder withCount(int count)"));
        assertTrue(out, out.contains("public static @NotNull SimpleBuilder from(@NotNull Simple instance)"));
        assertTrue(out, out.contains("public @NotNull Simple build()"));
        assertTrue(out, out.contains("return new Simple(name, count);"));
        // Validator call is emitted by default
        assertTrue(out, out.contains("BuildFlagValidator.validate(this);"));
        // @XContract emitted by default
        assertTrue(out, out.contains("@XContract"));
    }

    // ------------------------------------------------------------------
    // Boolean setters: zero-arg + typed, plus @Negate
    // ------------------------------------------------------------------

    @Test
    public void booleanField_emitsPairWithIsPrefix() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Flag",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder",
            "public class Flag {",
            "    boolean required;",
            "    Flag(boolean required) { this.required = required; }",
            "    public boolean isRequired() { return required; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.FlagBuilder");
        assertTrue(out, out.contains("public @NotNull FlagBuilder isRequired()"));
        assertTrue(out, out.contains("public @NotNull FlagBuilder isRequired(boolean required)"));
        assertTrue(out, out.contains("this.required = true;"));
    }

    @Test
    public void negateAnnotation_addsInverseSetter() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Toggle",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Negate;",
            "@ClassBuilder",
            "public class Toggle {",
            "    @Negate(\"enabled\") boolean disabled;",
            "    Toggle(boolean disabled) { this.disabled = disabled; }",
            "    public boolean isDisabled() { return disabled; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.ToggleBuilder");
        assertTrue(out, out.contains("public @NotNull ToggleBuilder isDisabled()"));
        assertTrue(out, out.contains("public @NotNull ToggleBuilder isEnabled()"));
        assertTrue(out, out.contains("this.disabled = !enabled;"));
    }

    // ------------------------------------------------------------------
    // Optional setters: dual raw+wrapped
    // ------------------------------------------------------------------

    @Test
    public void optionalField_emitsDualSetter() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Opt",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import java.util.Optional;",
            "@ClassBuilder",
            "public class Opt {",
            "    Optional<String> label;",
            "    Opt(Optional<String> label) { this.label = label; }",
            "    public Optional<String> getLabel() { return label; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.OptBuilder");
        assertTrue(out, out.contains("withLabel(@Nullable String label)"));
        assertTrue(out, out.contains("withLabel(@NotNull Optional<String> label)"));
        assertTrue(out, out.contains("return this.withLabel(Optional.ofNullable(label));"));
        assertTrue(out, out.contains("private Optional<String> label = Optional.empty();"));
    }

    // ------------------------------------------------------------------
    // @Formattable
    // ------------------------------------------------------------------

    @Test
    public void formattableOnPlainString_emitsPrintFormatOverload() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Msg",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Formattable;",
            "@ClassBuilder",
            "public class Msg {",
            "    @Formattable String text;",
            "    Msg(String text) { this.text = text; }",
            "    public String getText() { return text; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.MsgBuilder");
        assertTrue(out, out.contains("withText(@PrintFormat @NotNull String text, @Nullable Object... args)"));
        assertTrue(out, out.contains("this.text = String.format(text, args);"));
    }

    @Test
    public void formattableOnOptionalString_emitsFormatNullable() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.OptMsg",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Formattable;",
            "import java.util.Optional;",
            "@ClassBuilder",
            "public class OptMsg {",
            "    @Formattable Optional<String> description;",
            "    OptMsg(Optional<String> description) { this.description = description; }",
            "    public Optional<String> getDescription() { return description; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.OptMsgBuilder");
        assertTrue(out, out.contains("withDescription(@PrintFormat @Nullable String description, @Nullable Object... args)"));
        assertTrue(out, out.contains("this.description = Strings.formatNullable(description, args);"));
        assertTrue(out, out.contains("import dev.sbs.classbuilder.validate.Strings"));
    }

    // ------------------------------------------------------------------
    // @Singular on List and Map
    // ------------------------------------------------------------------

    @Test
    public void singularList_emitsFamily() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Coll",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Singular;",
            "import java.util.List;",
            "@ClassBuilder",
            "public class Coll {",
            "    @Singular List<String> entries;",
            "    Coll(List<String> entries) { this.entries = entries; }",
            "    public List<String> getEntries() { return entries; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.CollBuilder");
        assertTrue(out, out.contains("withEntries(@NotNull String... entries)"));
        assertTrue(out, out.contains("withEntries(@NotNull Iterable<String> entries)"));
        assertTrue(out, out.contains("withEntry(@NotNull String entry)"));
        assertTrue(out, out.contains("clearEntries()"));
    }

    @Test
    public void singularMap_emitsFamily() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Dict",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Singular;",
            "import java.util.Map;",
            "@ClassBuilder",
            "public class Dict {",
            "    @Singular(\"entry\") Map<String, Integer> entries;",
            "    Dict(Map<String, Integer> entries) { this.entries = entries; }",
            "    public Map<String, Integer> getEntries() { return entries; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.DictBuilder");
        assertTrue(out, out.contains("withEntries(@NotNull Map<String, Integer> entries)"));
        assertTrue(out, out.contains("putEntry(@NotNull String key, Integer value)"));
        assertTrue(out, out.contains("clearEntries()"));
    }

    // ------------------------------------------------------------------
    // @BuilderIgnore and exclude attribute
    // ------------------------------------------------------------------

    @Test
    public void builderIgnoreAndExclude_suppressFields() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Ign",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.BuilderIgnore;",
            "@ClassBuilder(exclude = {\"byName\"})",
            "public class Ign {",
            "    String kept;",
            "    @BuilderIgnore String byAnnotation;",
            "    String byName;",
            "    Ign(String kept) { this.kept = kept; }",
            "    public String getKept() { return kept; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.IgnBuilder");
        assertTrue(out, out.contains("withKept"));
        assertFalse(out, out.contains("withByAnnotation"));
        assertFalse(out, out.contains("withByName"));
    }

    // ------------------------------------------------------------------
    // factoryMethod
    // ------------------------------------------------------------------

    @Test
    public void factoryMethod_overridesConstructorCall() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Factory",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(factoryMethod = \"of\")",
            "public class Factory {",
            "    String x;",
            "    private Factory(String x) { this.x = x; }",
            "    public static Factory of(String x) { return new Factory(x); }",
            "    public String getX() { return x; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.FactoryBuilder");
        assertTrue(out, out.contains("return Factory.of(x);"));
        assertFalse(out, out.contains("return new Factory(x);"));
    }

    // ------------------------------------------------------------------
    // methodPrefix override
    // ------------------------------------------------------------------

    @Test
    public void methodPrefix_override_changesSetterNames() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Set",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(methodPrefix = \"set\")",
            "public class Set {",
            "    String name;",
            "    Set(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.SetBuilder");
        assertTrue(out, out.contains("setName(String name)"));
        assertFalse(out, out.contains("withName"));
    }

    // ------------------------------------------------------------------
    // validate = false suppresses the validator call
    // ------------------------------------------------------------------

    @Test
    public void validateFalse_omitsValidatorCall() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.NoVal",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class NoVal {",
            "    String x;",
            "    NoVal(String x) { this.x = x; }",
            "    public String getX() { return x; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.NoValBuilder");
        assertFalse(out, out.contains("BuildFlagValidator"));
    }

    // ------------------------------------------------------------------
    // emitContracts = false suppresses @XContract
    // ------------------------------------------------------------------

    @Test
    public void emitContractsFalse_omitsXContract() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Plain",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(emitContracts = false)",
            "public class Plain {",
            "    String x;",
            "    Plain(String x) { this.x = x; }",
            "    public String getX() { return x; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.PlainBuilder");
        assertFalse(out, out.contains("@XContract"));
    }

    // ------------------------------------------------------------------
    // Records - record-accessor style in from()
    // ------------------------------------------------------------------

    @Test
    public void record_generatesBuilder() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Point",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder",
            "public record Point(int x, int y) { }");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.PointBuilder");
        assertTrue(out, out.contains("withX(int x)"));
        assertTrue(out, out.contains("withY(int y)"));
        // Record accessor style in from(): instance.x() not instance.getX()
        assertTrue(out, out.contains("b.x = instance.x();"));
        assertTrue(out, out.contains("b.y = instance.y();"));
        assertTrue(out, out.contains("return new Point(x, y);"));
    }

    // ------------------------------------------------------------------
    // Interfaces - generates both Impl and Builder
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // @BuilderDefault - copies source initializer into builder field
    // ------------------------------------------------------------------

    @Test
    public void builderDefault_stringLiteral_copiedVerbatim() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Greeting",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.BuilderDefault;",
            "@ClassBuilder(validate = false)",
            "public class Greeting {",
            "    @BuilderDefault private String salutation = \"Hello\";",
            "    Greeting(String salutation) { this.salutation = salutation; }",
            "    public String getSalutation() { return salutation; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.GreetingBuilder");
        assertTrue(out, out.contains("private String salutation = \"Hello\";"));
    }

    @Test
    public void builderDefault_staticCall_addsImport() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Token",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.BuilderDefault;",
            "import java.util.UUID;",
            "@ClassBuilder(validate = false)",
            "public class Token {",
            "    @BuilderDefault private UUID id = UUID.randomUUID();",
            "    Token(UUID id) { this.id = id; }",
            "    public UUID getId() { return id; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.TokenBuilder");
        assertTrue(out, out.contains("private UUID id = UUID.randomUUID();"));
        assertTrue(out, out.contains("import java.util.UUID;"));
    }

    @Test
    public void builderDefault_overridesCollectionDefault() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Seed",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.BuilderDefault;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public class Seed {",
            "    @BuilderDefault private List<String> tags = new ArrayList<>(List.of(\"seeded\"));",
            "    Seed(List<String> tags) { this.tags = tags; }",
            "    public List<String> getTags() { return tags; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.SeedBuilder");
        // The source initializer wins over the default "new ArrayList<>()".
        assertTrue(out, out.contains("new ArrayList<>(List.of(\"seeded\"))"));
    }

    @Test
    public void builderDefault_withoutInitializer_errors() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Missing",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.BuilderDefault;",
            "@ClassBuilder(validate = false)",
            "public class Missing {",
            "    @BuilderDefault private String label;",
            "    Missing(String label) { this.label = label; }",
            "    public String getLabel() { return label; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).hadErrorContaining("@BuilderDefault requires the field to have an initializer");
    }

    // ------------------------------------------------------------------
    // @ObtainVia - overrides the accessor used in from(T)
    // ------------------------------------------------------------------

    @Test
    public void obtainVia_method_replacesDefaultGetter() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Range",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.ObtainVia;",
            "@ClassBuilder(validate = false)",
            "public class Range {",
            "    @ObtainVia(method = \"resolvedMax\") private int max;",
            "    Range(int max) { this.max = max; }",
            "    public int resolvedMax() { return max; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.RangeBuilder");
        assertTrue(out, out.contains("b.max = instance.resolvedMax();"));
        assertFalse(out, out.contains("instance.getMax()"));
    }

    @Test
    public void obtainVia_static_emitsStaticCall() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Helped",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.ObtainVia;",
            "@ClassBuilder(validate = false)",
            "public class Helped {",
            "    @ObtainVia(method = \"extractName\", isStatic = true) private String name;",
            "    Helped(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "    public static String extractName(Helped h) { return h.name; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.HelpedBuilder");
        assertTrue(out, out.contains("b.name = Helped.extractName(instance);"));
    }

    @Test
    public void obtainVia_field_emitsDirectFieldRead() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Mirror",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.ObtainVia;",
            "@ClassBuilder(validate = false)",
            "public class Mirror {",
            "    @ObtainVia(field = \"backing\") String label;",
            "    String backing;",
            "    Mirror(String label, String backing) { this.label = label; this.backing = backing; }",
            "    public String getLabel() { return label; }",
            "    public String getBacking() { return backing; }",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String out = generatedSource(c, "demo.MirrorBuilder");
        assertTrue(out, out.contains("b.label = instance.backing;"));
    }

    // ------------------------------------------------------------------
    // Interfaces - generates both Impl and Builder
    // ------------------------------------------------------------------

    @Test
    public void interface_generatesImplAndBuilder() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder",
            "public interface Shape {",
            "    int sides();",
            "    String name();",
            "}");
        Compilation c = compile(source);
        assertThat(c).succeeded();
        String impl = generatedSource(c, "demo.ShapeImpl");
        assertTrue(impl, impl.contains("final class ShapeImpl implements Shape"));
        assertTrue(impl, impl.contains("private final int sides;"));
        assertTrue(impl, impl.contains("private final String name;"));
        assertTrue(impl, impl.contains("public int sides()"));
        assertTrue(impl, impl.contains("public String name()"));
        assertTrue(impl, impl.contains("boolean equals"));
        assertTrue(impl, impl.contains("int hashCode"));
        assertTrue(impl, impl.contains("String toString"));

        String builder = generatedSource(c, "demo.ShapeBuilder");
        assertTrue(builder, builder.contains("withSides(int sides)"));
        assertTrue(builder, builder.contains("withName"));
        assertTrue(builder, builder.contains("return new ShapeImpl(sides, name);"));
        // from() uses interface accessor style
        assertTrue(builder, builder.contains("b.sides = instance.sides();"));
    }

}
