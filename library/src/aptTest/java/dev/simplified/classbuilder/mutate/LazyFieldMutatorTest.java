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
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trip tests for the @Lazy AST surgery: standalone usage, @ClassBuilder
 * integration with the dual-setter shape, annotation propagation onto the
 * synthesised getter, and the negative cases the inspection covers
 * compile-time as well.
 */
public class LazyFieldMutatorTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("lazy-mutate-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, LazyFieldMutatorTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested class '" + simpleName + "' on " + outer + "; found "
            + Arrays.toString(outer.getDeclaredClasses()));
        return null;
    }

    // ------------------------------------------------------------------
    // Standalone @Lazy: field initializer becomes the supplier
    // ------------------------------------------------------------------

    @Test
    public void standaloneLazy_fieldRewrittenAndGetterMemoizes() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Standalone",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import demo.Counter;",
            "public class Standalone {",
            "    @Lazy",
            "    private String value = Counter.computeValue();",
            "    public Standalone() {}",
            "}");
        // Counter lives in the same package so the cross-fixture initialiser
        // can be observed. Keeping it static-mutable keeps the test single-
        // threaded with no state plumbing.
        JavaFileObject counter = JavaFileObjects.forSourceLines("demo.Counter",
            "package demo;",
            "public class Counter {",
            "    public static int calls = 0;",
            "    public static String computeValue() { calls++; return \"hi\"; }",
            "}");
        Compilation c = compile(src, counter);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> standalone = Class.forName("demo.Standalone", true, cl);
        Class<?> counterCls = Class.forName("demo.Counter", true, cl);

        Field valueField = standalone.getDeclaredField("value");
        assertEquals("dev.simplified.classbuilder.lazy.Lazy",
            valueField.getType().getName());
        assertTrue("@Lazy field must be final after rewrite",
            Modifier.isFinal(valueField.getModifiers()));

        Object instance = standalone.getDeclaredConstructor().newInstance();
        Field calls = counterCls.getField("calls");
        assertEquals("supplier must not run before first get()", 0, calls.getInt(null));

        String first = (String) standalone.getMethod("getValue").invoke(instance);
        assertEquals("hi", first);
        assertEquals("supplier runs exactly once on first get()", 1, calls.getInt(null));

        // Repeated calls hit the memoized cache.
        standalone.getMethod("getValue").invoke(instance);
        standalone.getMethod("getValue").invoke(instance);
        assertEquals("supplier must not re-run for cached reads", 1, calls.getInt(null));
    }

    // ------------------------------------------------------------------
    // @ClassBuilder + @Lazy: dual setter shape, eager + supplier flow
    // ------------------------------------------------------------------

    @Test
    public void classBuilderLazy_dualSetterFlowsValueAndSupplier() throws Exception {
        // The user writes the natural `WithBuilder(String label)` constructor;
        // LazyFieldMutator rewrites the parameter to Supplier<String> and the
        // body assignment to Lazy.of(label). The synthesised getLabel()
        // returns the unwrapped value.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.WithBuilder",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class WithBuilder {",
            "    @Lazy",
            "    private String label;",
            "    public WithBuilder(String label) { this.label = label; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> outer = Class.forName("demo.WithBuilder", true, cl);
        Class<?> builder = nested(outer, "Builder");

        // Value setter form: builder stores () -> "value"
        Object b1 = builder.getDeclaredConstructor().newInstance();
        builder.getMethod("label", String.class).invoke(b1, "eager");
        Object eager = builder.getMethod("build").invoke(b1);
        assertEquals("eager", outer.getMethod("getLabel").invoke(eager));

        // Supplier setter form: builder stores the supplier verbatim, target
        // wraps as Lazy.of(supplier) at construction; the supplier doesn't
        // fire until getLabel() runs.
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> supplier = () -> {
            calls.incrementAndGet();
            return "deferred";
        };
        Object b2 = builder.getDeclaredConstructor().newInstance();
        builder.getMethod("label", Supplier.class).invoke(b2, supplier);
        Object deferred = builder.getMethod("build").invoke(b2);
        assertEquals("supplier must not fire until first get()", 0, calls.get());
        assertEquals("deferred", outer.getMethod("getLabel").invoke(deferred));
        assertEquals(1, calls.get());
        outer.getMethod("getLabel").invoke(deferred);
        assertEquals("memoized after first get()", 1, calls.get());
    }

    // ------------------------------------------------------------------
    // Annotation propagation onto the synthesised getter
    // ------------------------------------------------------------------

    @Test
    public void annotationPropagation_landsOnGetter() throws Exception {
        // Declaration-only annotations (METHOD/FIELD targets, no TYPE_USE)
        // propagate cleanly from the field to the synthesised getter.
        // TYPE_USE-capable annotations like JetBrains @NotNull/@Nullable are
        // covered by the editor-side LazyAugmentProvider, not the AST
        // mutator, because javac would auto-migrate them into the
        // qualified-name type tree and break attribution.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Propagated",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class Propagated {",
            "    @Lazy",
            "    @Deprecated",
            "    private String tag = \"x\";",
            "    public Propagated() {}",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> cls = Class.forName("demo.Propagated", true, cl);
        Method getter = cls.getMethod("getTag");
        assertNotNull("@Deprecated must propagate to the getter",
            getter.getAnnotation(Deprecated.class));
    }

    // ------------------------------------------------------------------
    // Negative cases - errors at compile time
    // ------------------------------------------------------------------

    @Test
    public void negative_staticField_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bad",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class Bad {",
            "    @Lazy private static String foo = \"x\";",
            "}");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("@Lazy is not supported on static fields");
    }

    @Test
    public void negative_primitive_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bad",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class Bad {",
            "    @Lazy private int foo = 42;",
            "}");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("primitive");
    }

    @Test
    public void negative_missingInitializerStandalone_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bad",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class Bad {",
            "    @Lazy private String foo;",
            "    public Bad() {}",
            "}");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("requires an initializer");
    }

    // ------------------------------------------------------------------
    // AccessLevel - Lombok @Getter parity
    // ------------------------------------------------------------------

    @Test
    public void accessLevel_protected_synthesizesProtectedGetter() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Restricted",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import dev.simplified.annotations.AccessLevel;",
            "public class Restricted {",
            "    @Lazy(access = AccessLevel.PROTECTED)",
            "    private String value = \"x\";",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> cls = Class.forName("demo.Restricted", true, cl);
        Method getter = cls.getDeclaredMethod("getValue");
        assertTrue("getValue() should be protected when access=PROTECTED",
            Modifier.isProtected(getter.getModifiers()));
        getter.setAccessible(true);
        assertEquals("x", getter.invoke(cls.getDeclaredConstructor().newInstance()));
    }

    @Test
    public void accessLevel_packagePrivate_emitsNoAccessKeyword() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.PkgPrivate",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import dev.simplified.annotations.AccessLevel;",
            "public class PkgPrivate {",
            "    @Lazy(access = AccessLevel.PACKAGE)",
            "    private String value = \"x\";",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> cls = Class.forName("demo.PkgPrivate", true, cl);
        Method getter = cls.getDeclaredMethod("getValue");
        int mods = getter.getModifiers();
        assertTrue("PACKAGE access has none of public/protected/private",
            !Modifier.isPublic(mods) && !Modifier.isProtected(mods) && !Modifier.isPrivate(mods));
    }

    // Sanity: ClassBuilder+Lazy with no initializer is fine.
    @Test
    public void classBuilderLazy_noInitializer_isAccepted() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Ok",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class Ok {",
            "    @Lazy private String label;",
            "    public Ok(String label) { this.label = label; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
    }

    // Sanity: when the user already wrote getFoo() (and references the
    // rewritten Lazy<T> field via .get()), the mutator skips synthesis and
    // the user's version stays - no duplicate-method compile error.
    @Test
    public void existingGetter_skipsSynthesis() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.WithGetter",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class WithGetter {",
            "    @Lazy private String name = \"hi\";",
            "    public String getName() { return name.get() + \"!\"; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> cls = Class.forName("demo.WithGetter", true, cl);
        Object instance = cls.getDeclaredConstructor().newInstance();
        assertEquals("hi!", cls.getMethod("getName").invoke(instance));
    }

}
