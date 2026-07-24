package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * How {@code from(T)} and {@code mutate()} obtain each field's value off the
 * source instance.
 *
 * <p>The headline case is the first test: a target with no accessors at all
 * compiles. Before the read ladder, every seeded field emitted
 * {@code instance.getX()} unconditionally, so a target had to carry a
 * getter-generating annotation whether or not it wanted public accessors, and
 * the failure to do so surfaced as {@code cannot find symbol} inside generated
 * code. The rest pin the ordering: an accessor the author declared is called
 * rather than bypassed, whatever it is named.
 */
public class AccessorSeedingTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-seeding-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, AccessorSeedingTest.class.getClassLoader());
    }

    /** Reads a private field off an instance, bypassing whatever accessor exists. */
    private static Object storedValue(Object instance, String fieldName) throws Exception {
        Field f = instance.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(instance);
    }

    private static Object roundTrip(Class<?> target, Object instance) throws Exception {
        Object builder = target.getMethod("from", target).invoke(null, instance);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    // ------------------------------------------------------------------
    // The F1 closer
    // ------------------------------------------------------------------

    @Test
    public void targetWithNoAccessorsCompilesAndRoundTrips() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Widget {",
            "    private String label;",
            "    private int count;",
            "    private boolean active;",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> widget = Class.forName("demo.Widget", true, cl);

        Object b = widget.getMethod("builder").invoke(null);
        Class<?> builder = b.getClass();
        builder.getMethod("label", String.class).invoke(b, "hello");
        builder.getMethod("count", int.class).invoke(b, 7);
        builder.getMethod("active", boolean.class).invoke(b, true);
        Object w = builder.getMethod("build").invoke(b);

        assertEquals("hello", storedValue(w, "label"));
        assertEquals(7, storedValue(w, "count"));
        assertEquals(true, storedValue(w, "active"));

        // from(T) reads all three straight off the private fields.
        Object copy = roundTrip(widget, w);
        assertEquals("hello", storedValue(copy, "label"));
        assertEquals(7, storedValue(copy, "count"));
        assertEquals(true, storedValue(copy, "active"));

        // mutate() seeds from `this` through the same ladder.
        Object mutated = widget.getMethod("mutate").invoke(w);
        Object rebuilt = mutated.getClass().getMethod("build").invoke(mutated);
        assertEquals("hello", storedValue(rebuilt, "label"));
        assertEquals(7, storedValue(rebuilt, "count"));
    }

    // ------------------------------------------------------------------
    // A declared accessor outranks the field read
    // ------------------------------------------------------------------

    @Test
    public void declaredBeanAccessorIsCalledRatherThanBypassed() throws Exception {
        // getLabel() normalises. Seeding through it stores the normalised form;
        // seeding through the field stores the raw one, so the two are
        // distinguishable by reading the field back directly.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Widget {",
            "    private String label;",
            "    public String getLabel() { return label == null ? null : label.toUpperCase(); }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> widget = Class.forName("demo.Widget", true, cl);

        Object b = widget.getMethod("builder").invoke(null);
        b.getClass().getMethod("label", String.class).invoke(b, "abc");
        Object w = b.getClass().getMethod("build").invoke(b);
        assertEquals("abc", storedValue(w, "label"));

        assertEquals("ABC", storedValue(roundTrip(widget, w), "label"));
    }

    @Test
    public void declaredFluentAccessorIsFound() throws Exception {
        // No getLabel(), only the bare-name form. The probe has to try it, or a
        // fluent-accessor codebase silently bypasses every hand-written body.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Widget {",
            "    private String label;",
            "    public String label() { return label == null ? null : label.toUpperCase(); }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> widget = Class.forName("demo.Widget", true, cl);

        Object b = widget.getMethod("builder").invoke(null);
        b.getClass().getMethod("label", String.class).invoke(b, "abc");
        Object w = b.getClass().getMethod("build").invoke(b);

        assertEquals("ABC", storedValue(roundTrip(widget, w), "label"));
    }

    @Test
    public void declaredBooleanIsAccessorIsPreferred() throws Exception {
        // The pre-ladder behaviour for booleans, pinned: isX() before getX()
        // before the bare name, and above the field read.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Widget {",
            "    private boolean active;",
            "    public boolean isActive() { return !active; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> widget = Class.forName("demo.Widget", true, cl);

        Object b = widget.getMethod("builder").invoke(null);
        b.getClass().getMethod("active", boolean.class).invoke(b, true);
        Object w = b.getClass().getMethod("build").invoke(b);
        assertEquals(true, storedValue(w, "active"));

        // Inverted by isActive(), so seeding through it flips the stored value.
        assertEquals(false, storedValue(roundTrip(widget, w), "active"));
    }

    // ------------------------------------------------------------------
    // Shapes the ladder must leave alone
    // ------------------------------------------------------------------

    @Test
    public void recordStillSeedsThroughComponentAccessors() throws Exception {
        // A record's canonical accessor is the contract, so the ladder returns
        // before it ever probes. A custom component accessor proves which path ran.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Point",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public record Point(int x, int y) {",
            "    public int x() { return x * 2; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> point = Class.forName("demo.Point", true, cl);

        Object b = point.getMethod("builder").invoke(null);
        b.getClass().getMethod("x", int.class).invoke(b, 3);
        b.getClass().getMethod("y", int.class).invoke(b, 5);
        Object p = b.getClass().getMethod("build").invoke(b);

        assertEquals(6, storedValue(roundTrip(point, p), "x"));
        assertEquals(5, storedValue(roundTrip(point, p), "y"));
    }

    @Test
    public void obtainViaStillOutranksEverything() throws Exception {
        // A declared getLabel() exists and would be found by the probe, so this
        // only passes if @ObtainVia is consulted first.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.ObtainVia;",
            "@ClassBuilder(validate = false)",
            "public class Widget {",
            "    @ObtainVia(method = \"redirected\")",
            "    private String label;",
            "    public String getLabel() { return \"from-getter\"; }",
            "    public String redirected() { return \"from-obtain-via\"; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> widget = Class.forName("demo.Widget", true, cl);

        Object b = widget.getMethod("builder").invoke(null);
        b.getClass().getMethod("label", String.class).invoke(b, "seed");
        Object w = b.getClass().getMethod("build").invoke(b);

        assertEquals("from-obtain-via", storedValue(roundTrip(widget, w), "label"));
    }

    @Test
    public void collectionFieldWithNoAccessorStillCopiesDefensively() throws Exception {
        // The defensive copy is applied to whatever the ladder resolved, so a
        // direct field read must still yield a detached container.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bag",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public class Bag {",
            "    private List<String> items;",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> bag = Class.forName("demo.Bag", true, cl);

        Object b = bag.getMethod("builder").invoke(null);
        b.getClass().getMethod("items", java.util.List.class)
            .invoke(b, new java.util.ArrayList<>(java.util.List.of("a", "b")));
        Object original = b.getClass().getMethod("build").invoke(b);

        Object copy = roundTrip(bag, original);
        Object originalItems = storedValue(original, "items");
        Object copiedItems = storedValue(copy, "items");
        assertEquals(originalItems, copiedItems);
        assertTrue("seeded container must not be the source instance",
            originalItems != copiedItems);
    }

    // ------------------------------------------------------------------
    // Inheritance
    // ------------------------------------------------------------------

    @Test
    public void protectedInheritedFieldIsReadDirectly() throws Exception {
        // A SuperBuilder subclass seeds its parent's fields too. protected is
        // reachable through a receiver typed as the subclass (JLS 6.6.2), so
        // this compiles with no accessor anywhere in the chain.
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public abstract class Base {",
            "    protected String owner;",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Leaf",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Leaf extends Base {",
            "    private int size;",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();
    }

}
