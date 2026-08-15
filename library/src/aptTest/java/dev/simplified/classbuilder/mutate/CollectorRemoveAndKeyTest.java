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
 * The two {@code @Collector} roles the six-role scheme lacked - taking one
 * element back out, and letting a map entry's key come off the value.
 *
 * <p>Both are shapes a hand-written builder reaches for and a generated one
 * could not express, which is what kept {@code GsonSettings} and
 * {@code Expression} entirely hand-written.
 */
public class CollectorRemoveAndKeyTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-collector-test");
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
            CollectorRemoveAndKeyTest.class.getClassLoader());
    }

    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    // ------------------------------------------------------------------
    // removable
    // ------------------------------------------------------------------

    /** The site: {@code removeTypeAdapter(Type)} taking one registration back out. */
    @Test
    public void removable_onAMapTakesOneEntryOut() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Settings",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.Map;",
                "@ClassBuilder(validate = false)",
                "public class Settings {",
                "    @Collector(singular = true, removable = true) Map<String, String> adapters;",
                "    public Settings(Map<String, String> adapters) { this.adapters = adapters; }",
                "    public Map<String, String> getAdapters() { return adapters; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSettings",
                "package demo;",
                "public class UseSettings {",
                "    public static String go() {",
                "        return Settings.builder()",
                "            .putAdapter(\"a\", \"1\")",
                "            .putAdapter(\"b\", \"2\")",
                "            .removeAdapter(\"a\")",
                "            .build().getAdapters().toString();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("{b=2}", runGo(c, "demo.UseSettings"));
    }

    /**
     * The collection form removes by value on every element type. A
     * {@code List<Integer>} is the case that decides it: {@code remove(int)}
     * would take out the element at that index instead, which compiles and is
     * simply the wrong element - so removing the value {@code 20} from
     * {@code [10, 20, 30]} would leave {@code [10, 20]}.
     */
    @Test
    public void removable_onAListOfIntegerRemovesByValue() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Sizes",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Sizes {",
                "    @Collector(singular = true, removable = true) List<Integer> sizes;",
                "    public Sizes(List<Integer> sizes) { this.sizes = sizes; }",
                "    public List<Integer> getSizes() { return sizes; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSizes",
                "package demo;",
                "public class UseSizes {",
                "    public static String go() {",
                "        return Sizes.builder()",
                "            .sizes(10, 20, 30)",
                "            .removeSize(20)",
                "            .build().getSizes().toString();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("[10, 30]", runGo(c, "demo.UseSizes"));
    }

    /**
     * A remove takes one thing out of what the builder collected; saying the
     * declared default is gone is what {@code clear} is for.
     */
    @Test
    public void removable_doesNotDiscardTheDeclaredDefault() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Seeded",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Seeded {",
                "    @Collector(singular = true, removable = true) List<String> tags = List.of(\"a\", \"b\");",
                "    public Seeded(List<String> tags) { this.tags = tags; }",
                "    public List<String> getTags() { return tags; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSeeded",
                "package demo;",
                "public class UseSeeded {",
                "    public static String go() {",
                "        return Seeded.builder().removeTag(\"a\").build().getTags().toString();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("[b]", runGo(c, "demo.UseSeeded"));
    }

    /** Lombok has no remove, so its style suppresses the role rather than naming it. */
    @Test
    public void removable_isSuppressedUnderTheLombokStyle() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Lomboked",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import dev.simplified.annotations.NamingStyle;",
                "import java.util.List;",
                "@ClassBuilder(style = NamingStyle.LOMBOK, validate = false)",
                "public class Lomboked {",
                "    @Collector(singular = true, removable = true) List<String> tags;",
                "    public Lomboked(List<String> tags) { this.tags = tags; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLomboked",
                "package demo;",
                "public class UseLomboked {",
                "    public static void go() { Lomboked.builder().removeTag(\"a\"); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("removeTag");
    }

    /** A written pattern names the role like any other. */
    @Test
    public void removable_takesAWrittenPattern() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Named",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import dev.simplified.annotations.SetterNames;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Named {",
                "    @Collector(singular = true, removable = true)",
                "    @SetterNames(remove = \"without{}\")",
                "    List<String> tags;",
                "    public Named(List<String> tags) { this.tags = tags; }",
                "    public List<String> getTags() { return tags; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseNamed",
                "package demo;",
                "public class UseNamed {",
                "    public static String go() {",
                "        return Named.builder().tags(\"a\", \"b\").withoutTag(\"a\").build().getTags().toString();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("[b]", runGo(c, "demo.UseNamed"));
    }

    // ------------------------------------------------------------------
    // key
    // ------------------------------------------------------------------

    /** The site: {@code function(MathFunction)} storing under the element's own name. */
    @Test
    public void key_derivesTheEntryKeyFromTheValue() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Named",
                "package demo;",
                "public record Named(String name, int arity) { }"),
            JavaFileObjects.forSourceLines("demo.Registry",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import dev.simplified.annotations.SetterNames;",
                "import java.util.Map;",
                "@ClassBuilder(validate = false)",
                "public class Registry {",
                "    @Collector(singular = true, key = \"name\")",
                "    @SetterNames(put = \"{}\")",
                "    Map<String, Named> functions;",
                "    public Registry(Map<String, Named> functions) { this.functions = functions; }",
                "    public Map<String, Named> getFunctions() { return functions; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRegistry",
                "package demo;",
                "public class UseRegistry {",
                "    public static String go() {",
                "        return Registry.builder()",
                "            .function(new Named(\"sin\", 1))",
                "            .function(new Named(\"max\", 2))",
                "            .build().getFunctions().keySet().toString();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("[sin, max]", runGo(c, "demo.UseRegistry"));
    }

    // ------------------------------------------------------------------
    // Rejections, all at the annotation
    // ------------------------------------------------------------------

    @Test
    public void key_onANonMapField_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.NotAMap",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class NotAMap {",
                "    @Collector(singular = true, key = \"toString\") List<String> tags;",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("'tags' is not a map");
    }

    @Test
    public void key_withoutSingular_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.NoPut",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.Map;",
                "@ClassBuilder(validate = false)",
                "public class NoPut {",
                "    @Collector(key = \"toString\") Map<String, String> entries;",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("add singular = true");
    }

    @Test
    public void key_besideCompute_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Lazy",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.Map;",
                "@ClassBuilder(validate = false)",
                "public class Lazy {",
                "    @Collector(singular = true, compute = true, key = \"toString\")",
                "    Map<String, String> entries;",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot be combined with compute");
    }

    @Test
    public void key_namingNoSuchMethod_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Absent",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.Map;",
                "@ClassBuilder(validate = false)",
                "public class Absent {",
                "    @Collector(singular = true, key = \"nope\") Map<String, String> entries;",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("names no no-argument method on String");
    }

    @Test
    public void key_returningTheWrongType_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Sized",
                "package demo;",
                "public record Sized(int size) { }"),
            JavaFileObjects.forSourceLines("demo.Mistyped",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.Map;",
                "@ClassBuilder(validate = false)",
                "public class Mistyped {",
                "    @Collector(singular = true, key = \"size\") Map<String, Sized> entries;",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot key 'entries'");
    }

}
