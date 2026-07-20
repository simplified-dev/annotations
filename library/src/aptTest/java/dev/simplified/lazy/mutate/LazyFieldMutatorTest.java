package dev.simplified.lazy.mutate;

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
import static org.junit.Assert.assertFalse;
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
    // @Lazy + @BuilderIgnore on a @ClassBuilder target
    // ------------------------------------------------------------------

    /**
     * Control for the case below: on a {@code @ClassBuilder} target, a plain
     * {@code @Lazy} field does get its memoizing getter synthesised.
     */
    @Test
    public void lazyOnBuilderTarget_synthesisesGetter() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Kept",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class Kept {",
            "    @Lazy String value = \"v\";",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Kept", true, loadClasses(c));
        assertTrue("@Lazy must synthesise getValue()", hasMethod(target, "getValue"));
    }

    /**
     * {@code @BuilderIgnore} says the builder should not expose the field; it
     * says nothing about how the field is stored. So it composes with
     * {@code @Lazy}: the field still gets {@code Lazy<T>} storage and a
     * memoizing getter, it simply has no setter and no constructor parameter,
     * and keeps its own initializer as the value source.
     *
     * <p>This pairing was briefly rejected because the lazy pass only saw the
     * builder-visible fields and silently skipped ignored ones. Feeding it the
     * unfiltered list is the actual fix.
     */
    @Test
    public void lazyPlusBuilderIgnore_stillRewritesStorageAndGetter() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Ignored",
            "package demo;",
            "import dev.simplified.annotations.BuilderIgnore;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class Ignored {",
            "    String kept = \"k\";",
            "    @Lazy @BuilderIgnore String hidden = compute();",
            "    int calls = 0;",
            "    String compute() { calls++; return \"h\" + calls; }",
            "    public String getKept() { return kept; }",
            "    public int getCalls() { return calls; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Ignored", true, loadClasses(c));

        Field hidden = target.getDeclaredField("hidden");
        assertEquals("storage must still be rewritten to Lazy<T>",
            "dev.simplified.lazy.Lazy", hidden.getType().getName());
        assertTrue("@Lazy must still synthesise the getter", hasMethod(target, "getHidden"));

        Class<?> builderCls = nested(target, "Builder");
        assertFalse("an ignored field must get no builder setter",
            hasMethod(builderCls, "hidden"));

        Object b = target.getMethod("builder").invoke(null);
        Object built = builderCls.getMethod("build").invoke(b);
        assertEquals("building must not evaluate the ignored lazy field",
            0, target.getMethod("getCalls").invoke(built));
        assertEquals("h1", target.getMethod("getHidden").invoke(built));
        assertEquals("k", target.getMethod("getKept").invoke(built));
    }

    @Test
    public void lazyPlusBuildFlag_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Flagged",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder",
            "public class Flagged {",
            "    @Lazy @BuildFlag(nonNull = true) String value = \"v\";",
            "}");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Lazy cannot be combined with @BuildFlag");
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
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
        assertEquals("dev.simplified.lazy.Lazy",
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
    // What a standalone @Lazy initializer may reference
    // ------------------------------------------------------------------

    /**
     * Standalone {@code @Lazy} wraps the initializer in place, as
     * {@code Lazy.of(() -> <init>)} still sitting in the field initializer -
     * an instance context. So unlike a retained builder default, which is
     * hoisted into a static provider, a lazy initializer may reach the
     * enclosing instance freely.
     */
    @Test
    public void standaloneLazy_initializerMayReferenceInstanceState() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Inst",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class Inst {",
            "    String base = \"b\";",
            "    @Lazy String fromMethod = compute();",
            "    @Lazy String fromField = base + \"x\";",
            "    @Lazy String fromThis = this.base + \"y\";",
            "    String compute() { return \"c\"; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
    }

    /**
     * The wrapping copier must preserve source positions. javac reads them for
     * forward-reference detection - an earlier field looks like a forward
     * reference if the copy claims an invalid position - and for
     * {@code Flow$AssignAnalyzer.trackable}, which allocates a lambda
     * parameter's definite-assignment address only when its position is valid.
     * Failing the latter, javac dies inside {@code Bits.incl} with no
     * diagnostic at all.
     */
    @Test
    public void standaloneLazy_initializerMayContainLambdaWithParameters() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Lam",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import java.util.function.Function;",
            "public class Lam {",
            "    @Lazy Function<String,String> f = s -> s + \"!\";",
            "    @Lazy Function<String,Integer> g = String::length;",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
    }

    /**
     * A {@code @Lazy} field on a {@code @ClassBuilder} target may reference the
     * enclosing instance, matching the standalone case. Its default is computed
     * in the constructor, and both branches stay deferred - the supplied
     * supplier is stored verbatim and the default becomes a lambda over the
     * provider - so laziness survives the trip through the builder.
     */
    @Test
    public void classBuilderLazy_initializerMayReferenceInstanceState() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Mixed",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class Mixed {",
            "    @Lazy String v = compute();",
            "    int calls = 0;",
            "    String compute() { calls++; return \"c\" + calls; }",
            "    public int getCalls() { return calls; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Mixed", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        Object built = builder.getClass().getMethod("build").invoke(builder);

        assertEquals("building must not evaluate the lazy default",
            0, target.getMethod("getCalls").invoke(built));
        assertEquals("c1", target.getMethod("getV").invoke(built));
        assertEquals("evaluated once, on first get()",
            1, target.getMethod("getCalls").invoke(built));
        target.getMethod("getV").invoke(built);
        assertEquals("and memoized thereafter", 1, target.getMethod("getCalls").invoke(built));
    }

    /**
     * {@code @BuilderDefault} is permitted on a {@code @Lazy} field now that the
     * constructor path exists - it is the per-field way to decline retention,
     * where previously only the class-wide {@code retainInit = false} was
     * available.
     */
    @Test
    public void lazyPlusBuilderDefaultFalse_isAccepted() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.LazyOptOut",
            "package demo;",
            "import dev.simplified.annotations.BuilderDefault;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class LazyOptOut {",
            "    @Lazy @BuilderDefault(false) String value = \"declared\";",
            "    @Lazy String kept = \"kept\";",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.LazyOptOut", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        builder.getClass().getMethod("value", String.class).invoke(builder, "set");
        Object built = builder.getClass().getMethod("build").invoke(builder);

        assertEquals("set", target.getMethod("getValue").invoke(built));
        assertEquals("the sibling field still retains its initializer",
            "kept", target.getMethod("getKept").invoke(built));
    }

    // ------------------------------------------------------------------
    // @ClassBuilder + @Lazy + a retained initializer
    // ------------------------------------------------------------------

    /**
     * A {@code @Lazy} field's declared initializer must reach the builder as a
     * default like any other field's. The builder slot is {@code Supplier<T>}
     * while {@code $default$<name>()} returns {@code T}, so the slot default is
     * a lambda wrapping the provider call - which has to preserve both
     * deferral (still lazy) and per-builder freshness (still a new value each
     * build).
     *
     * <p>Before this was wired up the slot had no default at all: building
     * without touching the setter left a null supplier and the NPE surfaced
     * later, at the first getter call rather than at {@code build()}.
     */
    @Test
    public void classBuilderLazy_retainedInitializerBecomesBuilderDefault() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Defaulted",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class Defaulted {",
            "    @Lazy String value = Ticker.next();",
            "}");
        JavaFileObject ticker = JavaFileObjects.forSourceLines("demo.Ticker",
            "package demo;",
            "public class Ticker {",
            "    public static int calls = 0;",
            "    public static String next() { calls++; return \"v\" + calls; }",
            "}");
        Compilation c = compile(src, ticker);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> target = Class.forName("demo.Defaulted", true, cl);
        Class<?> tickerCls = Class.forName("demo.Ticker", true, cl);
        Field calls = tickerCls.getField("calls");

        Object first = build(target);
        assertEquals("building must not evaluate the lazy default", 0, calls.getInt(null));

        assertEquals("v1", target.getMethod("getValue").invoke(first));
        assertEquals("default evaluates once, on first get()", 1, calls.getInt(null));
        target.getMethod("getValue").invoke(first);
        assertEquals("and is memoized thereafter", 1, calls.getInt(null));

        // Fresh per build: the second instance gets its own lambda, so it runs
        // the initializer again rather than sharing the first value.
        Object second = build(target);
        assertEquals("v2", target.getMethod("getValue").invoke(second));
    }

    /** An explicit setter still wins over the retained default. */
    @Test
    public void classBuilderLazy_setterOverridesRetainedDefault() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Overridden",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class Overridden {",
            "    @Lazy String value = \"fromInit\";",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Overridden", true, loadClasses(c));
        Class<?> builderCls = nested(target, "Builder");
        Object b = target.getMethod("builder").invoke(null);
        builderCls.getMethod("value", String.class).invoke(b, "fromSetter");
        Object built = builderCls.getMethod("build").invoke(b);
        assertEquals("fromSetter", target.getMethod("getValue").invoke(built));
    }

    /**
     * A {@code @Lazy} field defers a computation that is expected to exist, so
     * never supplying one is a mistake rather than an empty-but-valid field.
     * The failure must land at {@code build()}, naming the field, instead of
     * surfacing later as a bare NPE inside the getter.
     */
    @Test
    public void missingSupplier_failsAtBuildNamingTheField() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Unset",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class Unset {",
            "    @Lazy String token;",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Unset", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        try {
            builder.getClass().getMethod("build").invoke(builder);
            fail("build() must reject a @Lazy field that was never supplied");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue("expected an NPE from the Lazy null check, got " + cause,
                cause instanceof NullPointerException);
            String message = cause.getMessage();
            assertTrue("message must name the field, was: " + message,
                message != null && message.contains("'token'"));
            assertTrue("message must name the declaring class, was: " + message,
                message.contains("demo.Unset"));
        }
    }

    /** builder().build() with no setter calls, so every value is a default. */
    private static Object build(Class<?> target) throws Exception {
        Object b = target.getMethod("builder").invoke(null);
        return b.getClass().getMethod("build").invoke(b);
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
