package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * What a builder-visible field holds when no setter was called and the field
 * declares no initializer.
 *
 * <p>Container shapes settle at empty rather than null, so iterating the result
 * of {@code build()} never depends on whether a setter happened to be called.
 * Reference shapes with no meaningful empty value stay null. Pinned across all
 * three emission paths because they carry independent copies of the defaulting
 * logic and an array was previously missed on both.
 */
public class UnsetSlotDefaultsTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("unset-slot-defaults");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, UnsetSlotDefaultsTest.class.getClassLoader());
    }

    /** Builds with no setter calls and reads one getter. */
    private static Object buildAndGet(Compilation c, String fqn, String getter) throws Exception {
        Class<?> target = Class.forName(fqn, true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        Object built = builder.getClass().getMethod("build").invoke(builder);
        return target.getMethod(getter).invoke(built);
    }

    // ------------------------------------------------------------------
    // Arrays - the shape that used to arrive null
    // ------------------------------------------------------------------

    @Test
    public void unsetArray_isEmptyNotNull() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Tagged",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Tagged {",
            "    String[] tags;",
            "    public String[] getTags() { return tags; }",
            "}"));
        assertThat(c).succeeded();

        Object tags = buildAndGet(c, "demo.Tagged", "getTags");
        assertNotNull("an unset array must not be null", tags);
        assertEquals(0, Array.getLength(tags));
        assertEquals(String.class, tags.getClass().getComponentType());
    }

    @Test
    public void unsetPrimitiveArray_isEmptyNotNull() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Nums",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Nums {",
            "    int[] values;",
            "    public int[] getValues() { return values; }",
            "}"));
        assertThat(c).succeeded();

        Object values = buildAndGet(c, "demo.Nums", "getValues");
        assertNotNull("an unset primitive array must not be null", values);
        assertEquals(0, Array.getLength(values));
        assertEquals(int.class, values.getClass().getComponentType());
    }

    /** The zero-length dimension has to lead, so the component's brackets follow it. */
    @Test
    public void unsetMultiDimensionalArray_isEmptyOuterArray() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Grid",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Grid {",
            "    String[][] cells;",
            "    public String[][] getCells() { return cells; }",
            "}"));
        assertThat(c).succeeded();

        Object cells = buildAndGet(c, "demo.Grid", "getCells");
        assertNotNull("an unset 2D array must not be null", cells);
        assertEquals(0, Array.getLength(cells));
        assertEquals(String[].class, cells.getClass().getComponentType());
    }

    /** An explicitly-set array still wins over the empty default. */
    @Test
    public void arraySetterOverridesTheEmptyDefault() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Tagged",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Tagged {",
            "    String[] tags;",
            "    public String[] getTags() { return tags; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Tagged", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        builder.getClass().getMethod("tags", String[].class)
            .invoke(builder, (Object) new String[]{"a", "b"});
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertEquals(2, Array.getLength(target.getMethod("getTags").invoke(built)));
    }

    /** A declared initializer still beats the empty default. */
    @Test
    public void arrayWithDeclaredInitializer_keepsIt() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Seeded",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Seeded {",
            "    String[] tags = {\"seed\"};",
            "    public String[] getTags() { return tags; }",
            "}"));
        assertThat(c).succeeded();

        Object tags = buildAndGet(c, "demo.Seeded", "getTags");
        assertEquals(1, Array.getLength(tags));
        assertEquals("seed", Array.get(tags, 0));
    }

    /** Same on a SuperBuilder chain, where the copy constructor drains the slot. */
    @Test
    public void unsetArrayOnAnInheritedField_isEmptyNotNull() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Root",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Root {",
                "    String[] tags;",
                "    public String[] getTags() { return tags; }",
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
        Object builder = leaf.getMethod("builder").invoke(null);
        Object built = builder.getClass().getMethod("build").invoke(builder);
        Object tags = root.getMethod("getTags").invoke(built);
        assertNotNull("an inherited unset array must not be null", tags);
        assertEquals(0, Array.getLength(tags));
    }

    /** And on the interface path, which emits a sibling builder as text. */
    @Test
    public void unsetArrayOnAnInterfaceTarget_isEmptyNotNull() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Holder",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public interface Holder {",
                "    String[] tags();",
                "    String[][] grid();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseHolder",
                "package demo;",
                "public class UseHolder {",
                "    public static Holder go() { return new HolderBuilder().build(); }",
                "}"));
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> use = Class.forName("demo.UseHolder", true, cl);
        // Through the interface, not the impl: the generated <Name>Impl is
        // package-private, so reflecting on its class is an access error even
        // though the accessors themselves are public.
        Class<?> iface = Class.forName("demo.Holder", true, cl);
        Object holder = use.getMethod("go").invoke(null);
        Object tags = iface.getMethod("tags").invoke(holder);
        Object grid = iface.getMethod("grid").invoke(holder);
        assertNotNull("an unset array on an interface target must not be null", tags);
        assertEquals(0, Array.getLength(tags));
        assertNotNull(grid);
        assertEquals(0, Array.getLength(grid));
    }

    // ------------------------------------------------------------------
    // The shapes that were already safe, pinned so they stay that way
    // ------------------------------------------------------------------

    @Test
    public void unsetBooleanOptionalAndContainers_areAlreadySafe() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Blank",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.*;",
            "@ClassBuilder(validate = false)",
            "public class Blank {",
            "    boolean flag;",
            "    Optional<String> opt;",
            "    List<String> list;",
            "    Set<String> set;",
            "    Map<String,String> map;",
            "    @Collector List<String> collected;",
            "    public boolean isFlag() { return flag; }",
            "    public Optional<String> getOpt() { return opt; }",
            "    public List<String> getList() { return list; }",
            "    public Set<String> getSet() { return set; }",
            "    public Map<String,String> getMap() { return map; }",
            "    public List<String> getCollected() { return collected; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Blank", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        Object built = builder.getClass().getMethod("build").invoke(builder);

        assertEquals(false, target.getMethod("isFlag").invoke(built));
        assertEquals(Optional.empty(), target.getMethod("getOpt").invoke(built));
        assertTrue(target.getMethod("getList").invoke(built));
        assertTrue(target.getMethod("getSet").invoke(built));
        assertTrue(target.getMethod("getCollected").invoke(built));
        assertEquals(0, ((java.util.Map<?, ?>) target.getMethod("getMap").invoke(built)).size());
    }

    private static void assertTrue(Object collection) {
        assertNotNull("container must not be null", collection);
        assertFalse("container must start empty", ((java.util.Collection<?>) collection).iterator().hasNext());
    }

    // ------------------------------------------------------------------
    // A @Collector default is mutated in place, so it has to be mutable
    // ------------------------------------------------------------------

    /**
     * The builder's {@code add} / {@code put} / {@code clear} setters mutate the
     * slot, so a retained default is copied into a fresh mutable container
     * first. Seeding the slot with the initializer's own instance made
     * {@code List.of("a")} - the idiomatic way to write a small default - throw
     * {@link UnsupportedOperationException} on the first {@code addItem}.
     */
    @Test
    public void immutableCollectorDefault_isCopiedSoAddStillWorks() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Bag",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public class Bag {",
            "    @Collector(singular = true) List<String> items = List.of(\"a\");",
            "    public List<String> getItems() { return items; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Bag", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        builder.getClass().getMethod("addItem", String.class).invoke(builder, "b");
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertEquals("the default seeds the collection and the add appends",
            java.util.List.of("a", "b"), target.getMethod("getItems").invoke(built));
    }

    /** An immutable map default is copied too. */
    @Test
    public void immutableCollectorMapDefault_isCopiedSoPutStillWorks() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Reg",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.Map;",
            "@ClassBuilder(validate = false)",
            "public class Reg {",
            "    @Collector(singularMethodName = \"entry\", singular = true) Map<String,String> entries",
            "        = Map.of(\"k\", \"v\");",
            "    public Map<String,String> getEntries() { return entries; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Reg", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        builder.getClass().getMethod("putEntry", String.class, String.class).invoke(builder, "k2", "v2");
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertEquals(2, ((java.util.Map<?, ?>) target.getMethod("getEntries").invoke(built)).size());
    }

    /**
     * The copy is per builder, so mutating one builder's collection cannot
     * reach a default that returns shared state - or another builder.
     */
    @Test
    public void collectorDefault_isNotSharedBetweenBuilders() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Shared",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.*;",
            "@ClassBuilder(validate = false)",
            "public class Shared {",
            "    static final List<String> SEED = new ArrayList<>(List.of(\"a\"));",
            "    @Collector(singular = true) List<String> items = SEED;",
            "    public List<String> getItems() { return items; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Shared", true, loadClasses(c));
        Object first = target.getMethod("builder").invoke(null);
        first.getClass().getMethod("addItem", String.class).invoke(first, "b");
        first.getClass().getMethod("build").invoke(first);

        Object second = target.getMethod("builder").invoke(null);
        Object built = second.getClass().getMethod("build").invoke(second);
        assertEquals("one builder's add must not leak into the shared seed",
            java.util.List.of("a"), target.getMethod("getItems").invoke(built));
    }

    /**
     * A custom container's initializer serves as the field's factory as well as
     * its default - it is the only expression that can produce an instance of
     * the declared type, where a {@code java.util} container has
     * {@code new ArrayList<>()}. The two roles disagree once the initializer
     * carries contents: a replace setter resetting through it kept the
     * default's elements instead of discarding them.
     */
    @Test
    public void seededCustomContainer_replaceDiscardsTheDefault() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Pile",
                "package demo;",
                "import java.util.ArrayList;",
                "import java.util.Collection;",
                "public class Pile extends ArrayList<String> {",
                "    public Pile() { super(); }",
                "    public Pile(Collection<? extends String> c) { super(c); }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Holder",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Holder {",
                "    @Collector(singular = true, clearable = true) Pile items = new Pile(List.of(\"a\"));",
                "    public Pile getItems() { return items; }",
                "}"));
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Holder", true, loadClasses(c));

        Object untouched = target.getMethod("builder").invoke(null);
        assertEquals("the default still seeds the collection",
            java.util.List.of("a"), target.getMethod("getItems")
                .invoke(untouched.getClass().getMethod("build").invoke(untouched)));

        Object added = target.getMethod("builder").invoke(null);
        added.getClass().getMethod("addItem", String.class).invoke(added, "b");
        assertEquals("add still appends onto it",
            java.util.List.of("a", "b"), target.getMethod("getItems")
                .invoke(added.getClass().getMethod("build").invoke(added)));

        Object replaced = target.getMethod("builder").invoke(null);
        replaced.getClass().getMethod("items", Iterable.class)
            .invoke(replaced, java.util.List.of("x"));
        Object result = target.getMethod("getItems")
            .invoke(replaced.getClass().getMethod("build").invoke(replaced));
        assertEquals("a wholesale replace must discard the default, not keep it",
            java.util.List.of("x"), result);
        assertEquals("and the custom type is preserved", "Pile", result.getClass().getSimpleName());
    }

    /** A plain reference has no meaningful empty value and stays null. */
    @Test
    public void unsetReference_staysNull() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Named",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Named {",
            "    String name;",
            "    public String getName() { return name; }",
            "}"));
        assertThat(c).succeeded();

        assertNull("a plain reference must stay null", buildAndGet(c, "demo.Named", "getName"));
    }

}
