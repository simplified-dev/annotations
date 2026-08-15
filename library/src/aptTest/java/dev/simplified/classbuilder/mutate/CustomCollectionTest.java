package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import dev.simplified.classbuilder.apt.FieldSpec;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trip coverage for {@code @Collector} on a project-specific
 * (non-{@code java.util}) collection/map type recognised by the supertype walk
 * in {@link FieldSpec}.
 *
 * <p>The fixtures use an <em>interface</em> container built through a static
 * factory - the exact shape of {@code dev.simplified.collection.ConcurrentList}
 * seeded with {@code Concurrent.newList()}. The builder can't {@code new} such a
 * type, so it must reuse the field's own initializer (auto-captured, surfaced as
 * the {@code $default$} provider) to build fresh instances. These tests prove
 * the whole path: the module compiles, and the generated collector setters
 * produce the custom type with the right contents.
 */
public class CustomCollectionTest {

    // A custom collection/map exposed only through an interface + factory,
    // mirroring dev.simplified.collection.ConcurrentList / Concurrent.newList().
    private static final JavaFileObject BAG = JavaFileObjects.forSourceLines("demo.Bag",
        "package demo;",
        "public interface Bag<E> extends java.util.List<E> {}");
    private static final JavaFileObject BAG_IMPL = JavaFileObjects.forSourceLines("demo.BagImpl",
        "package demo;",
        "public class BagImpl<E> extends java.util.ArrayList<E> implements Bag<E> {}");
    private static final JavaFileObject BAGS = JavaFileObjects.forSourceLines("demo.Bags",
        "package demo;",
        "public final class Bags {",
        "    public static <E> Bag<E> newBag() { return new BagImpl<>(); }",
        "}");
    private static final JavaFileObject LEDGER = JavaFileObjects.forSourceLines("demo.Ledger",
        "package demo;",
        "public interface Ledger<K, V> extends java.util.Map<K, V> {}");
    private static final JavaFileObject LEDGER_IMPL = JavaFileObjects.forSourceLines("demo.LedgerImpl",
        "package demo;",
        "public class LedgerImpl<K, V> extends java.util.LinkedHashMap<K, V> implements Ledger<K, V> {}");
    private static final JavaFileObject LEDGERS = JavaFileObjects.forSourceLines("demo.Ledgers",
        "package demo;",
        "public final class Ledgers {",
        "    public static <K, V> Ledger<K, V> newLedger() { return new LedgerImpl<>(); }",
        "}");

    @Test
    public void customList_collectorRoundTrips() throws Exception {
        JavaFileObject shelf = JavaFileObjects.forSourceLines("demo.Shelf",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "@ClassBuilder(validate = false)",
            "public class Shelf {",
            "    @Collector(singular = true, clearable = true) Bag<String> tags = Bags.newBag();",
            "    public Shelf(Bag<String> tags) { this.tags = tags; }",
            "    public Bag<String> getTags() { return tags; }",
            "}");
        Compilation c = compile(BAG, BAG_IMPL, BAGS, shelf);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> shelfClass = Class.forName("demo.Shelf", true, cl);
        Class<?> builder = nested(shelfClass, "Builder");

        // Varargs replace: builds a fresh custom Bag, not a java.util.ArrayList.
        Object b = builder.getEnclosingClass().getMethod("builder").invoke(null);
        builder.getMethod("tags", String[].class).invoke(b, (Object) new String[]{"a", "b"});
        Object result = builder.getMethod("build").invoke(b);
        List<?> tags = (List<?>) shelfClass.getMethod("getTags").invoke(result);
        assertEquals(List.of("a", "b"), tags);
        assertEquals("fresh container must be the field's own custom type",
            "demo.BagImpl", tags.getClass().getName());

        // Singular add appends to the existing custom Bag.
        Object b2 = builder.getEnclosingClass().getMethod("builder").invoke(null);
        builder.getMethod("addTag", String.class).invoke(b2, "x");
        builder.getMethod("addTag", String.class).invoke(b2, "y");
        List<?> added = (List<?>) shelfClass.getMethod("getTags").invoke(builder.getMethod("build").invoke(b2));
        assertEquals(List.of("x", "y"), added);

        // Clear empties the custom Bag after a bulk set.
        Object b3 = builder.getEnclosingClass().getMethod("builder").invoke(null);
        builder.getMethod("tags", String[].class).invoke(b3, (Object) new String[]{"z"});
        builder.getMethod("clearTags").invoke(b3);
        List<?> cleared = (List<?>) shelfClass.getMethod("getTags").invoke(builder.getMethod("build").invoke(b3));
        assertTrue("clear must empty the custom container", cleared.isEmpty());
    }

    @Test
    public void customMap_collectorRoundTrips() throws Exception {
        JavaFileObject book = JavaFileObjects.forSourceLines("demo.Book",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "@ClassBuilder(validate = false)",
            "public class Book {",
            "    @Collector(singular = true) Ledger<String, Integer> entries = Ledgers.newLedger();",
            "    public Book(Ledger<String, Integer> entries) { this.entries = entries; }",
            "    public Ledger<String, Integer> getEntries() { return entries; }",
            "}");
        Compilation c = compile(LEDGER, LEDGER_IMPL, LEDGERS, book);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> bookClass = Class.forName("demo.Book", true, cl);
        Class<?> ledger = Class.forName("demo.Ledger", true, cl);
        Class<?> builder = nested(bookClass, "Builder");

        // Map replace: fresh custom Ledger + putAll of the supplied map.
        Object b = builder.getEnclosingClass().getMethod("builder").invoke(null);
        Map<String, Integer> seed = new LinkedHashMap<>();
        seed.put("one", 1);
        seed.put("two", 2);
        builder.getMethod("entries", Map.class).invoke(b, seed);
        // Singular put onto the existing custom Ledger. Key/value types are
        // read off the java.util.Map supertype, so the params are String/Integer.
        builder.getMethod("putEntry", String.class, Integer.class).invoke(b, "three", 3);
        Object result = builder.getMethod("build").invoke(b);
        Map<?, ?> entries = (Map<?, ?>) bookClass.getMethod("getEntries").invoke(result);
        assertEquals("fresh map must be the field's own custom type",
            "demo.LedgerImpl", entries.getClass().getName());
        assertEquals(Integer.valueOf(1), entries.get("one"));
        assertEquals(Integer.valueOf(3), entries.get("three"));
        assertEquals(3, entries.size());
    }

    /**
     * A custom-container {@code @Collector} field with no initializer can't be
     * built - the processor emits a NOTE and falls back to a plain replace
     * setter, and the module still compiles.
     */
    @Test
    public void customCollectorWithoutInitializer_notesAndFallsBackToPlainSetter() throws Exception {
        JavaFileObject noInit = JavaFileObjects.forSourceLines("demo.NoInit",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "@ClassBuilder(validate = false)",
            "public class NoInit {",
            "    @Collector Bag<String> tags;",
            "    public NoInit(Bag<String> tags) { this.tags = tags; }",
            "    public Bag<String> getTags() { return tags; }",
            "}");
        Compilation c = compile(BAG, BAG_IMPL, BAGS, noInit);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@Collector on 'tags'");

        ClassLoader cl = loadClasses(c);
        Class<?> noInitClass = Class.forName("demo.NoInit", true, cl);
        Class<?> bag = Class.forName("demo.Bag", true, cl);
        Class<?> builder = nested(noInitClass, "Builder");

        // Plain replace setter taking the custom type directly; no bulk API.
        builder.getMethod("tags", bag); // must exist
        assertFalse("no @Collector bulk overloads when the field can't be built",
            hasMethod(builder, "addTag"));

        Object b = builder.getEnclosingClass().getMethod("builder").invoke(null);
        Object bagValue = Class.forName("demo.Bags", true, cl).getMethod("newBag").invoke(null);
        @SuppressWarnings("unchecked")
        List<Object> asList = (List<Object>) bagValue;
        asList.add("solo");
        builder.getMethod("tags", bag).invoke(b, bagValue);
        List<?> tags = (List<?>) noInitClass.getMethod("getTags").invoke(builder.getMethod("build").invoke(b));
        assertEquals(List.of("solo"), tags);
    }

    // ------------------------------------------------------------------
    // Helpers (mirroring BuilderMutatorTest's bytecode-loading approach)
    // ------------------------------------------------------------------

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-customcollection-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, CustomCollectionTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested class '" + simpleName + "' on " + outer + "; found "
            + Arrays.toString(outer.getDeclaredClasses()));
        return null;
    }

}
