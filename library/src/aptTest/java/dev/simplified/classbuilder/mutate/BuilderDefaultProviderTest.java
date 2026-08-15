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
 * {@code @BuilderDefault(provider = "...")} - the way a slot with no initializer
 * to retain states a default, which is every record component.
 *
 * <p>The primitive cases are the ones that earn the feature. An unset
 * {@code boolean} slot and an explicit {@code false} are indistinguishable at
 * every point downstream, so a dropped default there is not recoverable by any
 * coalescing the author could write afterwards - it just silently changes what
 * the built object means.
 */
public class BuilderDefaultProviderTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-provider-test");
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
            BuilderDefaultProviderTest.class.getClassLoader());
    }

    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    // ------------------------------------------------------------------
    // The shape the feature exists for
    // ------------------------------------------------------------------

    /**
     * The unrecoverable case: a {@code boolean} record component defaulting to
     * {@code true}. Without a provider the builder hands the canonical
     * constructor {@code false} and nothing downstream can tell.
     */
    @Test
    public void recordComponent_primitiveBooleanDefaultSurvives() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.TgaOptions",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public record TgaOptions(@BuilderDefault(provider = \"defaultRle\") boolean rle) {",
                "    private static boolean defaultRle() { return true; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseTga",
                "package demo;",
                "public class UseTga {",
                "    public static String go() {",
                "        return TgaOptions.builder().build().rle()",
                "            + \"/\" + TgaOptions.builder().rle(false).build().rle();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("true/false", runGo(c, "demo.UseTga"));
    }

    /** A reference-typed component, and one the builder never touches. */
    @Test
    public void recordComponent_referenceDefaultSurvives() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.PnmOptions",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public record PnmOptions(",
                "    @BuilderDefault(provider = \"defaultVariant\") String variant,",
                "    @BuilderDefault(provider = \"defaultThreshold\") int threshold) {",
                "    private static String defaultVariant() { return \"PPM\"; }",
                "    private static int defaultThreshold() { return 128; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UsePnm",
                "package demo;",
                "public class UsePnm {",
                "    public static String go() {",
                "        PnmOptions a = PnmOptions.builder().build();",
                "        PnmOptions b = PnmOptions.builder().variant(\"PGM\").build();",
                "        return a.variant() + a.threshold() + \"/\" + b.variant() + b.threshold();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("PPM128/PGM128", runGo(c, "demo.UsePnm"));
    }

    /** The default is fetched per builder, exactly as a retained initializer is. */
    @Test
    public void provider_isCalledFreshPerBuilder() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Counted",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public record Counted(@BuilderDefault(provider = \"next\") int n) {",
                "    static int calls = 0;",
                "    private static int next() { return ++calls; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseCounted",
                "package demo;",
                "public class UseCounted {",
                "    public static String go() {",
                "        return Counted.builder().build().n() + \"/\" + Counted.builder().build().n();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("1/2", runGo(c, "demo.UseCounted"));
    }

    /** A provider works on an ordinary field too, where there is no initializer. */
    @Test
    public void plainField_withNoInitializerTakesAProvider() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Widget",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Widget {",
                "    @BuilderDefault(provider = \"defaultName\") String name;",
                "    private static String defaultName() { return \"anonymous\"; }",
                "    public String getName() { return name; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseWidget",
                "package demo;",
                "public class UseWidget {",
                "    public static String go() { return Widget.builder().build().getName(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("anonymous", runGo(c, "demo.UseWidget"));
    }

    /**
     * A provider on a generic target declares its own parameters, since a
     * {@code static} method cannot name the class's - and the generated
     * {@code $default$} that calls it infers one from the other.
     */
    @Test
    public void provider_onAGenericRecord() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Slot",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public record Slot<V>(@BuilderDefault(provider = \"none\") List<V> values) {",
                "    private static <T> List<T> none() { return List.of(); }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSlot",
                "package demo;",
                "public class UseSlot {",
                "    public static Integer go() {",
                "        return Slot.<String>builder().build().values().size();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(0, runGo(c, "demo.UseSlot"));
    }

    /** A written provider is the more specific statement and wins over an initializer beside it. */
    @Test
    public void provider_winsOverADeclaredInitializer() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Both",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Both {",
                "    @BuilderDefault(provider = \"fromProvider\") String label = \"fromInitializer\";",
                "    private static String fromProvider() { return \"fromProvider\"; }",
                "    public String getLabel() { return label; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBoth",
                "package demo;",
                "public class UseBoth {",
                "    public static String go() { return Both.builder().build().getLabel(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("fromProvider", runGo(c, "demo.UseBoth"));
    }

    // ------------------------------------------------------------------
    // Rejections, all at the annotation
    // ------------------------------------------------------------------

    @Test
    public void missingProviderMethod_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Absent",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public record Absent(@BuilderDefault(provider = \"nope\") boolean flag) { }"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("names no no-argument method on Absent");
    }

    @Test
    public void instanceProviderMethod_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Instanced",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Instanced {",
                "    @BuilderDefault(provider = \"defaultFlag\") boolean flag;",
                "    private boolean defaultFlag() { return true; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("names an instance method");
    }

    @Test
    public void wronglyTypedProviderMethod_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Mistyped",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public record Mistyped(@BuilderDefault(provider = \"defaultRle\") boolean rle) {",
                "    private static String defaultRle() { return \"true\"; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("does not supply 'rle'");
    }

    @Test
    public void providerBesideAnOptOut_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Contradictory",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Contradictory {",
                "    @BuilderDefault(value = false, provider = \"defaultName\") String name;",
                "    private static String defaultName() { return \"x\"; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("asks for no default at all");
    }

    /**
     * The warning a bare {@code @BuilderDefault} on an initializer-free field
     * earns has to keep firing - a provider is what silences it, and only
     * because it supplies the default the annotation was asking for.
     */
    @Test
    public void bareBuilderDefaultOnAnInitializerFreeField_stillWarns() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Inert",
                "package demo;",
                "import dev.simplified.annotations.BuilderDefault;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Inert {",
                "    @BuilderDefault String name;",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("@BuilderDefault has no effect on 'name'");
    }

}
