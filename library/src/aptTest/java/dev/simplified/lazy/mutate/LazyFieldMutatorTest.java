package dev.simplified.lazy.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trip tests for the @Lazy AST surgery: standalone usage, @ClassBuilder
 * integration with the dual-setter shape, annotation propagation onto the
 * synthesised getter, and the negative cases the inspection covers
 * compile-time as well.
 */
public class LazyFieldMutatorTest {

    private static final String NOT_NULL = "Lorg/jetbrains/annotations/NotNull;";
    private static final String NULLABLE = "Lorg/jetbrains/annotations/Nullable;";

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

    /**
     * Returns, per method ({@code name+descriptor}), the annotation descriptors
     * attached to it - collected from both the declaration channel
     * ({@code RuntimeInvisibleAnnotations}) and the type-use channel
     * ({@code RuntimeInvisibleTypeAnnotations}) so the assertion holds however
     * javac routes a dual-target annotation.
     */
    private static Map<String, Set<String>> readMethodAnnotations(Compilation c, String className)
        throws IOException {
        byte[] bytes = findClassBytes(c, className);
        assertNotNull("expected class-file output for " + className, bytes);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                Set<String> descriptors = new LinkedHashSet<>();
                out.put(name + descriptor, descriptors);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        descriptors.add(desc);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                 String desc, boolean visible) {
                        descriptors.add(desc);
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return out;
    }

    private static byte[] findClassBytes(Compilation c, String className) throws IOException {
        String expected = "/CLASS_OUTPUT/" + className.replace('.', '/') + ".class";
        for (JavaFileObject f : c.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            if (!f.toUri().toString().contains(expected)) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                return baos.toByteArray();
            }
        }
        return null;
    }

    /** Matches on the method name, so the assertion does not pin a descriptor. */
    private static Set<String> annotationsFor(Map<String, Set<String>> byMethod, String methodName) {
        for (Map.Entry<String, Set<String>> e : byMethod.entrySet()) {
            if (e.getKey().startsWith(methodName + "(")) return e.getValue();
        }
        return Set.of();
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
     * {@code @Lazy}: the field still gets deferred storage and a
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
        assertEquals("storage must still be rewritten to the deferred holder",
            "java.util.concurrent.atomic.AtomicReference", hidden.getType().getName());
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
        assertEquals("java.util.concurrent.atomic.AtomicReference",
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
     * the holder still sitting in the field initializer -
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
        // body assignment into a holder. The synthesised getLabel()
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
        Object b1 = builder.getEnclosingClass().getMethod("builder").invoke(null);
        builder.getMethod("label", String.class).invoke(b1, "eager");
        Object eager = builder.getMethod("build").invoke(b1);
        assertEquals("eager", outer.getMethod("getLabel").invoke(eager));

        // Supplier setter form: builder stores the supplier verbatim, target
        // wraps the supplier in a holder at construction; the supplier doesn't
        // fire until getLabel() runs.
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> supplier = () -> {
            calls.incrementAndGet();
            return "deferred";
        };
        Object b2 = builder.getEnclosingClass().getMethod("builder").invoke(null);
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

    /**
     * Every annotation the field declares that is legal on a method travels to
     * the synthesised getter - nullness included.
     *
     * <p>JetBrains {@code @NotNull}/{@code @Nullable} list {@code METHOD} among
     * their targets and {@code TYPE_USE} beside it, and the dual target alone
     * used to disqualify them. The reason given was that the getter's return
     * type, rebuilt from the field's own, would already carry the annotation
     * and a declaration copy would double it. It cannot:
     * {@code JavacTypeFactory.parseType} strips type-use annotations off the
     * display string on every path, so the rebuilt type carries none and the
     * getter carried no nullness at all - while the IDE-side
     * {@code LazyAugmentProvider} showed one, so the editor claimed what the
     * class file did not have.
     *
     * <p>These annotations are {@code @Retention(CLASS)} and invisible to
     * reflection, so the getter's descriptors are read out of the class file
     * with ASM. Both channels are collected: javac records one written
     * dual-target annotation once as a declaration annotation and once as a
     * {@code METHOD_RETURN} type annotation, which is its ordinary handling of
     * a single annotation rather than a duplicate.
     */
    @Test
    public void annotationPropagation_landsOnGetter() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Propagated",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import org.jetbrains.annotations.NotNull;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Propagated {",
            "    @Lazy",
            "    @Deprecated",
            "    @NotNull",
            "    private String tag = \"x\";",
            "    @Lazy",
            "    @Nullable",
            "    private String note = \"y\";",
            "    @Lazy",
            "    private String plain = \"z\";",
            "    public Propagated() {}",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> cls = Class.forName("demo.Propagated", true, cl);
        Method getter = cls.getMethod("getTag");
        assertNotNull("@Deprecated must propagate to the getter",
            getter.getAnnotation(Deprecated.class));

        Map<String, Set<String>> byMethod = readMethodAnnotations(c, "demo.Propagated");
        Set<String> tag = annotationsFor(byMethod, "getTag");
        Set<String> note = annotationsFor(byMethod, "getNote");
        Set<String> plainGetter = annotationsFor(byMethod, "getPlain");

        assertTrue("getTag() must carry the field's @NotNull, saw " + tag,
            tag.contains(NOT_NULL));
        assertTrue("getNote() must carry the field's @Nullable, saw " + note,
            note.contains(NULLABLE));
        // The copy restores what the field declared, it does not invent a hint.
        assertFalse("a field with no nullness must give a bare getter, saw " + plainGetter,
            plainGetter.contains(NOT_NULL) || plainGetter.contains(NULLABLE));
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

    /**
     * A primitive defers like anything else. The value slot stays primitive and
     * only the supplier's type argument is boxed, so the one boxing happens
     * when the value is computed rather than on every read.
     */
    @Test
    public void primitiveField_defersAndMemoizes() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Counted",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class Counted {",
            "    public static int CALLS = 0;",
            "    @Lazy private int foo = compute();",
            "    private static int compute() { CALLS++; return 42; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> cls = Class.forName("demo.Counted", true, loadClasses(c));
        Object instance = cls.getDeclaredConstructor().newInstance();
        assertEquals("the initializer must not run at construction", 0, cls.getField("CALLS").get(null));
        assertEquals("the getter keeps the primitive return type",
            int.class, cls.getMethod("getFoo").getReturnType());
        assertEquals(42, cls.getMethod("getFoo").invoke(instance));
        assertEquals("evaluated once, on first read", 1, cls.getField("CALLS").get(null));
        cls.getMethod("getFoo").invoke(instance);
        assertEquals("and memoized thereafter", 1, cls.getField("CALLS").get(null));

        assertEquals("the memoized value keeps its primitive slot",
            int.class, cls.getDeclaredField("$value$foo").getType());
    }

    /**
     * Neither an initializer nor a constructor assignment leaves nothing to
     * defer, which is still the one shape this rejects.
     */
    @Test
    public void negative_neitherInitializerNorAssignment_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bad",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class Bad {",
            "    @Lazy private String foo;",
            "    public Bad() {}",
            "}");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("has nothing to defer");
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
    // rewritten field via its resolver), the mutator skips synthesis and
    // the user's version stays - no duplicate-method compile error.
    @Test
    public void existingGetter_skipsSynthesis() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.WithGetter",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "public class WithGetter {",
            "    @Lazy private String name = \"hi\";",
            "    public String getName() { return $resolve$name() + \"!\"; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> cls = Class.forName("demo.WithGetter", true, cl);
        Object instance = cls.getDeclaredConstructor().newInstance();
        assertEquals("hi!", cls.getMethod("getName").invoke(instance));
    }

    /**
     * A field-only annotation beside {@code @Lazy} must not travel onto the
     * synthesised getter.
     *
     * <p>The propagation filter asked whether an annotation had <b>any</b>
     * declaration target rather than whether it targets {@code METHOD}, so
     * {@code @Setter} and {@code @Getter} - both {@code @Target({TYPE, FIELD})} -
     * were copied onto a method and javac rejected the result with "annotation
     * interface not applicable to this kind of declaration", anchored on the
     * author's own field.
     *
     * <p>These two spellings matter more than they look: {@code @Setter} and
     * {@code @Getter} cannot be combined with {@code @Lazy}, and
     * {@code AccessLevel.NONE} is the documented way for one field to opt out of
     * a type-level fan-out - so this was the remedy for that refusal failing
     * with an unrelated error.
     */
    @Test
    public void fieldOnlyAnnotationsDoNotTravelOntoTheGetter() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.OptedOut",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.Lazy;",
            "import dev.simplified.annotations.Setter;",
            "@Getter",
            "@Setter",
            "public class OptedOut {",
            "    private @Lazy @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE) String value = compute();",
            "    private static String compute() { return \"hi\"; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> cls = Class.forName("demo.OptedOut", true, loadClasses(c));
        Object instance = cls.getDeclaredConstructor().newInstance();
        assertEquals("the @Lazy getter is still synthesised and still memoizes",
            "hi", cls.getMethod("getValue").invoke(instance));
    }

    /**
     * The getter states the field's non-nullness as a contract, and states
     * nothing else.
     *
     * <p>The value is the same claim the accessor pass makes for
     * {@code @Getter} - it rests on the author's annotation rather than on
     * proof - so the two getters now agree. {@code pure} is where they
     * deliberately part: a field read has no effect, while the first call here
     * runs the author's supplier, which may do IO or throw, and {@code pure}
     * licenses the IDE to drop or reorder the call that triggers it.
     *
     * <p>A field with no nullness annotation gets no contract at all rather
     * than the bare purity claim the accessor pass falls back to, for the same
     * reason.
     */
    @Test
    public void getterStatesNonNullnessButNeverPurity() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Contracted",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import org.jetbrains.annotations.NotNull;",
            "import org.jetbrains.annotations.Nullable;",
            "public class Contracted {",
            "    private @Lazy @NotNull String req = c();",
            "    private @Lazy @Nullable String opt = c();",
            "    private @Lazy String bare = c();",
            "    private static String c() { return \"x\"; }",
            "}"));
        assertThat(c).succeeded();

        Map<String, Contract> byMethod = readContracts(c, "demo.Contracted");

        Contract req = byMethod.get("getReq");
        assertNotNull("a @NotNull @Lazy field's getter must carry a contract", req);
        assertEquals("-> !null", req.value);
        assertNull("the contract must not claim purity - the first call runs the supplier", req.pure);

        assertNull("a @Nullable field's getter has nothing to state", byMethod.get("getOpt"));
        assertNull("an unannotated field's getter has nothing to state", byMethod.get("getBare"));
    }

    /** The {@code @XContract} attributes per method name, absent when unannotated. */
    private static Map<String, Contract> readContracts(Compilation c, String className) throws IOException {
        byte[] bytes = findClassBytes(c, className);
        assertNotNull("expected class-file output for " + className, bytes);
        Map<String, Contract> out = new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (!"Ldev/simplified/annotations/XContract;".equals(desc)) return null;
                        Contract contract = new Contract();
                        out.put(name, contract);
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String attribute, Object value) {
                                if ("value".equals(attribute)) contract.value = (String) value;
                                if ("pure".equals(attribute)) contract.pure = (Boolean) value;
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return out;
    }

    private static final class Contract {
        String value;
        Boolean pure;
    }

    // ------------------------------------------------------------------
    // Getter naming
    // ------------------------------------------------------------------

    /** Compiles a one-field class and returns the names of its declared methods. */
    private static Set<String> lazyMethodNames(String field, String annotation) throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Named",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import dev.simplified.annotations.NamingStyle;",
            "public class Named {",
            "    " + annotation,
            "    private " + field + ";",
            "    private static String compute() { return \"x\"; }",
            "    private static boolean flag() { return true; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        Class<?> named = Class.forName("demo.Named", true, loadClasses(c));
        Set<String> out = new LinkedHashSet<>();
        for (Method m : named.getDeclaredMethods()) out.add(m.getName());
        return out;
    }

    /**
     * A boolean lazy field reads through {@code is}, the same choice the
     * accessor pair makes. The declared getter used to be {@code getX} for every
     * type, which put a lazy field's accessor in different naming territory from
     * every other generated one on the same class.
     */
    @Test
    public void lazyBooleanField_readsThroughIs() throws Exception {
        Set<String> methods = lazyMethodNames("boolean active = flag()", "@Lazy");
        assertTrue("expected isActive among " + methods, methods.contains("isActive"));
        assertFalse("get must not double up with is", methods.contains("getActive"));
    }

    @Test
    public void lazyStyleDropsThePrefix() throws Exception {
        Set<String> methods = lazyMethodNames("String value = compute()", "@Lazy(style = NamingStyle.FLUENT)");
        assertTrue("expected value among " + methods, methods.contains("value"));
        assertFalse(methods.contains("getValue"));
    }

    @Test
    public void lazyNamePatternOverridesTheStyle() throws Exception {
        Set<String> methods = lazyMethodNames("String value = compute()", "@Lazy(name = \"fetch{}\")");
        assertTrue("expected fetchValue among " + methods, methods.contains("fetchValue"));
        assertFalse(methods.contains("getValue"));
    }

    /** A boolean field already named {@code isX} must not double the prefix. */
    @Test
    public void lazyBooleanFieldAlreadyNamedIs_keepsTheOnePrefix() throws Exception {
        Set<String> methods = lazyMethodNames("boolean isReady = flag()", "@Lazy");
        assertTrue("expected isReady among " + methods, methods.contains("isReady"));
        assertFalse(methods.contains("isIsReady"));
    }

    /** A hand-written getter still wins over the synthesised one, under any style. */
    @Test
    public void handWrittenFluentGetter_suppressesTheSynthesisedOne() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Declared",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import dev.simplified.annotations.NamingStyle;",
            "public class Declared {",
            "    @Lazy(style = NamingStyle.FLUENT)",
            "    private String value = compute();",
            "    public String value() { return \"hand-written\"; }",
            "    private static String compute() { return \"x\"; }",
            "}");
        assertThat(compile(src)).succeeded();
    }

}
