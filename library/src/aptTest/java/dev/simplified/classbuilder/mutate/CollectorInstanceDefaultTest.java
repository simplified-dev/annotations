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
import java.util.List;
import java.util.Map;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * A {@code @Collector} container whose default reads instance state.
 *
 * <p>The rest of the constructor-computed path retypes the builder slot to
 * {@code Supplier<T>}, which a collected container cannot use - its
 * {@code add} / {@code put} / {@code clear} setters need a real container to
 * mutate while the builder runs, and no target exists then to compute the
 * default from. So the slot instead carries <em>only what the caller
 * contributed</em>, alongside a marker recording whether they replaced the
 * collection wholesale, and the constructor folds the two against the
 * instance-computed default.
 *
 * <p>An untouched builder contributes an empty collection, which makes the
 * untouched and appended-to cases the same fold - the observable semantics
 * match a static-safe default exactly, which is what these tests pin.
 */
public class CollectorInstanceDefaultTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("collector-instance-default");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = f.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            String rel = anchor >= 0 ? uri.substring(anchor + "CLASS_OUTPUT/".length()) : f.getName();
            Path out = tmp.resolve(rel);
            Files.createDirectories(out.getParent());
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                in.transferTo(b);
                Files.write(out, b.toByteArray());
            }
        }
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, CollectorInstanceDefaultTest.class.getClassLoader());
    }

    /** A list field defaulted from an instance method. */
    private static Compilation listTarget() {
        return compile(JavaFileObjects.forSourceLines("demo.Bag",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.*;",
            "@ClassBuilder(validate = false)",
            "public class Bag {",
            "    @Collector(singular = true, clearable = true) List<String> items = seed();",
            "    List<String> seed() { return new ArrayList<>(List.of(\"a\")); }",
            "    public List<String> getItems() { return items; }",
            "}"));
    }

    private static Object build(Compilation c, String fqn, Consumer setup, String getter) throws Exception {
        Class<?> target = Class.forName(fqn, true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        setup.accept(builder);
        return target.getMethod(getter).invoke(builder.getClass().getMethod("build").invoke(builder));
    }

    /** Minimal throwing consumer so the setup lambdas stay readable. */
    private interface Consumer {
        void apply(Object builder) throws Exception;

        default void accept(Object builder) {
            try {
                apply(builder);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ------------------------------------------------------------------
    // The semantics a static-safe default already has
    // ------------------------------------------------------------------

    @Test
    public void untouchedBuilder_getsTheInstanceDefault() throws Exception {
        Compilation c = listTarget();
        assertThat(c).succeeded();
        assertEquals(List.of("a"), build(c, "demo.Bag", b -> { }, "getItems"));
    }

    /** The default seeds the collection and the add appends onto it. */
    @Test
    public void addAppendsOntoTheInstanceDefault() throws Exception {
        Compilation c = listTarget();
        assertThat(c).succeeded();
        assertEquals(List.of("a", "b"), build(c, "demo.Bag",
            b -> b.getClass().getMethod("addItem", String.class).invoke(b, "b"), "getItems"));
    }

    /** A wholesale replace discards the default rather than folding onto it. */
    @Test
    public void varargsReplaceDiscardsTheInstanceDefault() throws Exception {
        Compilation c = listTarget();
        assertThat(c).succeeded();
        assertEquals(List.of("x", "y"), build(c, "demo.Bag",
            b -> b.getClass().getMethod("items", String[].class)
                .invoke(b, (Object) new String[]{"x", "y"}), "getItems"));
    }

    @Test
    public void iterableReplaceDiscardsTheInstanceDefault() throws Exception {
        Compilation c = listTarget();
        assertThat(c).succeeded();
        assertEquals(List.of("x"), build(c, "demo.Bag",
            b -> b.getClass().getMethod("items", Iterable.class).invoke(b, List.of("x")), "getItems"));
    }

    /** clear() means empty, not "empty then re-seeded from the default". */
    @Test
    public void clearDiscardsTheInstanceDefault() throws Exception {
        Compilation c = listTarget();
        assertThat(c).succeeded();
        assertEquals(List.of(), build(c, "demo.Bag",
            b -> b.getClass().getMethod("clearItems").invoke(b), "getItems"));
    }

    /** Replace then append: the appends land on the replacement, not the default. */
    @Test
    public void replaceThenAdd_appendsOntoTheReplacement() throws Exception {
        Compilation c = listTarget();
        assertThat(c).succeeded();
        assertEquals(List.of("x", "b"), build(c, "demo.Bag", b -> {
            b.getClass().getMethod("items", String[].class).invoke(b, (Object) new String[]{"x"});
            b.getClass().getMethod("addItem", String.class).invoke(b, "b");
        }, "getItems"));
    }

    /** The default is computed per build, so two builders do not share it. */
    @Test
    public void eachBuilderGetsItsOwnCopyOfTheDefault() throws Exception {
        Compilation c = listTarget();
        assertThat(c).succeeded();
        Class<?> target = Class.forName("demo.Bag", true, loadClasses(c));

        Object first = target.getMethod("builder").invoke(null);
        first.getClass().getMethod("addItem", String.class).invoke(first, "b");
        first.getClass().getMethod("build").invoke(first);

        Object second = target.getMethod("builder").invoke(null);
        assertEquals("one builder's add must not leak into another's default",
            List.of("a"), target.getMethod("getItems")
                .invoke(second.getClass().getMethod("build").invoke(second)));
    }

    // ------------------------------------------------------------------
    // Maps
    // ------------------------------------------------------------------

    private static Compilation mapTarget() {
        return compile(JavaFileObjects.forSourceLines("demo.Reg",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.*;",
            "@ClassBuilder(validate = false)",
            "public class Reg {",
            "    @Collector(singularMethodName = \"entry\", singular = true, clearable = true)",
            "    Map<String,String> entries = seed();",
            "    Map<String,String> seed() { return new LinkedHashMap<>(Map.of(\"k\", \"v\")); }",
            "    public Map<String,String> getEntries() { return entries; }",
            "}"));
    }

    @Test
    public void mapPutFoldsOntoTheInstanceDefault() throws Exception {
        Compilation c = mapTarget();
        assertThat(c).succeeded();
        Object entries = build(c, "demo.Reg",
            b -> b.getClass().getMethod("putEntry", String.class, String.class).invoke(b, "k2", "v2"),
            "getEntries");
        assertEquals(Map.of("k", "v", "k2", "v2"), entries);
    }

    @Test
    public void mapReplaceDiscardsTheInstanceDefault() throws Exception {
        Compilation c = mapTarget();
        assertThat(c).succeeded();
        Object entries = build(c, "demo.Reg",
            b -> b.getClass().getMethod("entries", Map.class).invoke(b, Map.of("only", "1")),
            "getEntries");
        assertEquals(Map.of("only", "1"), entries);
    }

    // ------------------------------------------------------------------
    // The default may read state assigned earlier in the same constructor
    // ------------------------------------------------------------------

    /**
     * Fields are assigned in declaration order, so a collected default declared
     * after a plain one may read it - the fold runs in the constructor, where
     * the earlier assignment has already happened.
     */
    @Test
    public void instanceDefaultMayReadAnEarlierField() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Derived",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.*;",
            "@ClassBuilder(validate = false)",
            "public class Derived {",
            "    String prefix = \"p\";",
            "    @Collector(singular = true) List<String> items = new ArrayList<>(List.of(prefix + \"1\"));",
            "    public String getPrefix() { return prefix; }",
            "    public List<String> getItems() { return items; }",
            "}"));
        assertThat(c).succeeded();
        assertEquals(List.of("p1", "b"), build(c, "demo.Derived",
            b -> b.getClass().getMethod("addItem", String.class).invoke(b, "b"), "getItems"));
    }

    // ------------------------------------------------------------------
    // SuperBuilder chain - the copy constructor takes the same fold
    // ------------------------------------------------------------------

    @Test
    public void collectedInstanceDefaultOnAnAbstractRoot() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Root",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.*;",
                "@ClassBuilder(validate = false)",
                "public abstract class Root {",
                "    @Collector(singular = true, clearable = true) List<String> tags = seed();",
                "    List<String> seed() { return new ArrayList<>(List.of(\"a\")); }",
                "    public List<String> getTags() { return tags; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Root {",
                "    int n;",
                "    public int getN() { return n; }",
                "}"));
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> root = Class.forName("demo.Root", true, cl);
        Class<?> leaf = Class.forName("demo.Leaf", true, cl);

        Object b1 = leaf.getMethod("builder").invoke(null);
        assertEquals("untouched inherits the root's instance default",
            List.of("a"), root.getMethod("getTags")
                .invoke(b1.getClass().getMethod("build").invoke(b1)));

        Object b2 = leaf.getMethod("builder").invoke(null);
        b2.getClass().getMethod("addTag", String.class).invoke(b2, "b");
        assertEquals("the inherited add folds onto the root's default",
            List.of("a", "b"), root.getMethod("getTags")
                .invoke(b2.getClass().getMethod("build").invoke(b2)));

        Object b3 = leaf.getMethod("builder").invoke(null);
        b3.getClass().getMethod("clearTags").invoke(b3);
        assertEquals("clear through the chain discards the default",
            List.of(), root.getMethod("getTags")
                .invoke(b3.getClass().getMethod("build").invoke(b3)));
    }

    // ------------------------------------------------------------------
    // Custom containers, including ones that cannot be constructed at all
    // ------------------------------------------------------------------

    /**
     * The declared type is an <em>interface</em> - it has no constructor, and no
     * amount of reading the initializer would find one. Nothing needs to build
     * it: the builder collects into a plain {@code java.util} scratch and the
     * constructor takes the real container from the field's own initializer, so
     * the built object holds exactly what {@code seed()} returned.
     */
    @Test
    public void customContainerInterface_takesTheMergePath() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.ConcurrentList",
                "package demo;",
                "import java.util.List;",
                "public interface ConcurrentList<E> extends List<E> { }"),
            JavaFileObjects.forSourceLines("demo.ConcurrentLists",
                "package demo;",
                "import java.util.ArrayList;",
                "import java.util.Collection;",
                "public final class ConcurrentLists {",
                "    private ConcurrentLists() { }",
                "    public static <E> ConcurrentList<E> of(Collection<? extends E> c) {",
                "        class Impl extends ArrayList<E> implements ConcurrentList<E> {",
                "            Impl() { super(c); }",
                "        }",
                "        return new Impl();",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Holder",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Holder {",
                "    String prefix = \"p\";",
                "    @Collector(singular = true, clearable = true) ConcurrentList<String> items = seed();",
                "    ConcurrentList<String> seed() { return ConcurrentLists.of(List.of(prefix)); }",
                "    public String getPrefix() { return prefix; }",
                "    public ConcurrentList<String> getItems() { return items; }",
                "}"));
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Holder", true, loadClasses(c));
        Class<?> iface = Class.forName("demo.ConcurrentList", true, loadClasses(c));

        Object untouched = target.getMethod("builder").invoke(null);
        Object plain = target.getMethod("getItems")
            .invoke(untouched.getClass().getMethod("build").invoke(untouched));
        assertEquals("the instance-reading default seeds the collection", List.of("p"), plain);

        Object added = target.getMethod("builder").invoke(null);
        added.getClass().getMethod("addItem", String.class).invoke(added, "b");
        assertEquals("add folds onto the instance default",
            List.of("p", "b"), target.getMethod("getItems")
                .invoke(added.getClass().getMethod("build").invoke(added)));

        Object replaced = target.getMethod("builder").invoke(null);
        replaced.getClass().getMethod("items", Iterable.class).invoke(replaced, List.of("x"));
        Object result = target.getMethod("getItems")
            .invoke(replaced.getClass().getMethod("build").invoke(replaced));
        assertEquals("a wholesale replace discards the default", List.of("x"), result);
        assertEquals("and the container still comes from the initializer, not the declared type",
            "Impl", result.getClass().getSimpleName());
    }

}
