package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Expression shapes a retained field initializer has to survive. The initializer
 * is deep-cloned into a static {@code $default$<field>()} provider, so anything
 * javac attributes differently in a method body than in a field initializer
 * shows up here.
 *
 * <p>Lambdas are the sharp case. Their parameters become {@code VarSymbol}s whose
 * definite-assignment address is only allocated when
 * {@code Flow$AssignAnalyzer.trackable} passes, and that test compares the
 * symbol's source position against the enclosing method's start. A cloned tree
 * carrying an invalid position fails it, gets no address, and javac dies inside
 * {@code Bits.incl} on an assertion with no diagnostic at all.
 */
public class RetainedInitExpressionTest {

    private static Compilation compile(String... body) {
        String[] head = {
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import java.util.ArrayList;",
            "import java.util.Comparator;",
            "import java.util.List;",
            "import java.util.function.BiFunction;",
            "import java.util.function.Function;",
            "import java.util.function.Supplier;",
            "@ClassBuilder(validate = false)",
            "public class Expr {",
        };
        String[] lines = new String[head.length + body.length + 1];
        System.arraycopy(head, 0, lines, 0, head.length);
        System.arraycopy(body, 0, lines, head.length, body.length);
        lines[lines.length - 1] = "}";
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(JavaFileObjects.forSourceLines("demo.Expr", lines));
    }

    private static ClassLoader loadClasses(Compilation compilation, String unusedFqn) throws Exception {
        return loadClasses(compilation);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("retained-init-expr");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = f.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            Path out = tmp.resolve(uri.substring(anchor + "CLASS_OUTPUT/".length()));
            Files.createDirectories(out.getParent());
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                in.transferTo(b);
                Files.write(out, b.toByteArray());
            }
        }
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, RetainedInitExpressionTest.class.getClassLoader());
    }

    /** Builds with no setters called, so the field holds its retained default. */
    private static Object buildAndGet(Compilation c, String getter) throws Exception {
        Class<?> target = Class.forName("demo.Expr", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        Object built = builder.getClass().getMethod("build").invoke(builder);
        Method m = target.getMethod(getter);
        return m.invoke(built);
    }

    // ------------------------------------------------------------------
    // Lambdas - the shape that used to crash javac outright
    // ------------------------------------------------------------------

    @Test
    public void lambdaWithParameter_isRetained() throws Exception {
        Compilation c = compile(
            "  Function<String,String> f = s -> s + \"!\";",
            "  public Function<String,String> getF() { return f; }");
        assertThat(c).succeeded();

        @SuppressWarnings("unchecked")
        Function<String, String> f = (Function<String, String>) buildAndGet(c, "getF");
        assertEquals("hi!", f.apply("hi"));
    }

    @Test
    public void lambdaWithExplicitlyTypedParameters_isRetained() throws Exception {
        Compilation c = compile(
            "  BiFunction<String,String,String> f = (String a, String b) -> a + b;",
            "  public BiFunction<String,String,String> getF() { return f; }");
        assertThat(c).succeeded();

        @SuppressWarnings("unchecked")
        BiFunction<String, String, String> f = (BiFunction<String, String, String>) buildAndGet(c, "getF");
        assertEquals("ab", f.apply("a", "b"));
    }

    @Test
    public void lambdaWithBlockBody_isRetained() throws Exception {
        Compilation c = compile(
            "  Function<String,String> f = s -> { String t = s.trim(); return t + \"!\"; };",
            "  public Function<String,String> getF() { return f; }");
        assertThat(c).succeeded();

        @SuppressWarnings("unchecked")
        Function<String, String> f = (Function<String, String>) buildAndGet(c, "getF");
        assertEquals("hi!", f.apply("  hi  "));
    }

    @Test
    public void zeroParameterLambda_isRetained() throws Exception {
        Compilation c = compile(
            "  Supplier<String> f = () -> \"x\";",
            "  public Supplier<String> getF() { return f; }");
        assertThat(c).succeeded();

        @SuppressWarnings("unchecked")
        Supplier<String> f = (Supplier<String>) buildAndGet(c, "getF");
        assertEquals("x", f.get());
    }

    @Test
    public void lambdaNestedInACallChain_isRetained() throws Exception {
        Compilation c = compile(
            "  List<String> f = new ArrayList<>(List.of(\"a\", \"\")) {{ removeIf(s -> s.isEmpty()); }};",
            "  public List<String> getF() { return f; }");
        assertThat(c).succeeded();

        assertEquals(List.of("a"), buildAndGet(c, "getF"));
    }

    // ------------------------------------------------------------------
    // Method and constructor references
    // ------------------------------------------------------------------

    @Test
    public void methodAndConstructorReferences_areRetained() throws Exception {
        Compilation c = compile(
            "  Function<String,Integer> len = String::length;",
            "  Supplier<List<String>> maker = ArrayList::new;",
            "  Comparator<String> cmp = Comparator.comparing(String::length);",
            "  public Function<String,Integer> getLen() { return len; }",
            "  public Supplier<List<String>> getMaker() { return maker; }",
            "  public Comparator<String> getCmp() { return cmp; }");
        assertThat(c).succeeded();

        @SuppressWarnings("unchecked")
        Function<String, Integer> len = (Function<String, Integer>) buildAndGet(c, "getLen");
        assertEquals(Integer.valueOf(2), len.apply("hi"));
    }

    // ------------------------------------------------------------------
    // Other expression forms
    // ------------------------------------------------------------------

    @Test
    public void anonymousClassAndSwitchExpression_areRetained() throws Exception {
        Compilation c = compile(
            "  static final int K = 2;",
            "  Supplier<String> anon = new Supplier<String>() { public String get() { return \"a\"; } };",
            "  String sw = switch (K) { case 1 -> \"one\"; default -> \"other\"; };",
            "  public Supplier<String> getAnon() { return anon; }",
            "  public String getSw() { return sw; }");
        assertThat(c).succeeded();

        assertEquals("other", buildAndGet(c, "getSw"));
    }

    // ------------------------------------------------------------------
    // The documented limit: the provider is static
    // ------------------------------------------------------------------

    /**
     * An initializer reading instance state cannot be hoisted into the static
     * provider evaluated at {@code builder()}, because no target exists then.
     * Such a field takes the constructor-computed path instead, where
     * {@code this} is available - so it behaves as an ordinary field
     * initializer would.
     */
    @Test
    public void initializerReadingAnEarlierField_isRetained() throws Exception {
        Compilation c = compile(
            "  String base = \"b\";",
            "  String f = base + \"x\";",
            "  public String getBase() { return base; }",
            "  public String getF() { return f; }");
        assertThat(c).succeeded();

        assertEquals("bx", buildAndGet(c, "getF"));
    }

    @Test
    public void initializerCallingAnInstanceMethod_isRetained() throws Exception {
        Compilation c = compile(
            "  String f = compute();",
            "  String compute() { return \"x\"; }",
            "  public String getF() { return f; }");
        assertThat(c).succeeded();

        assertEquals("x", buildAndGet(c, "getF"));
    }

    /** {@code getClass()} is inherited from Object, so detection must see it too. */
    @Test
    public void initializerCallingGetClass_isRetained() throws Exception {
        Compilation c = compile(
            "  String f = getClass().getSimpleName();",
            "  public String getF() { return f; }");
        assertThat(c).succeeded();

        assertEquals("Expr", buildAndGet(c, "getF"));
    }

    /** An explicit setter still beats a constructor-computed default. */
    @Test
    public void instanceDefault_isOverriddenByTheSetter() throws Exception {
        Compilation c = compile(
            "  String f = getClass().getSimpleName();",
            "  public String getF() { return f; }");
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Expr", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        builder.getClass().getMethod("f", String.class).invoke(builder, "set");
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertEquals("set", target.getMethod("getF").invoke(built));
    }

    /**
     * The slot is {@code Supplier<T>} on this path and every setter wraps its
     * argument, so null stays meaningful in both directions: an unset slot is
     * null and takes the default, while an explicitly-set null survives as the
     * caller's chosen value.
     */
    @Test
    public void instanceDefault_explicitNullIsNotTreatedAsUnset() throws Exception {
        Compilation c = compile(
            "  String f = getClass().getSimpleName();",
            "  public String getF() { return f; }");
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Expr", true, loadClasses(c));
        Object builder = target.getMethod("builder").invoke(null);
        builder.getClass().getMethod("f", String.class).invoke(builder, (Object) null);
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertNull("explicit null must win over the default", target.getMethod("getF").invoke(built));
    }

    /**
     * A {@code @Collector} container takes the merge path rather than the
     * {@code Supplier} retype the other shapes use - its setters need a real
     * container to mutate, so the slot carries the caller's contributions and
     * the constructor folds them onto the instance-computed default.
     * {@code CollectorInstanceDefaultTest} covers the semantics in full.
     */
    @Test
    public void instanceDefault_onACollector_foldsOntoTheDefault() throws Exception {
        Compilation c = compile(
            "  @dev.simplified.annotations.Collector(singular = true) List<String> f = defaults();",
            "  List<String> defaults() { return new ArrayList<>(List.of(\"a\")); }",
            "  public List<String> getF() { return f; }");
        assertThat(c).succeeded();
        assertEquals(List.of("a"), buildAndGet(c, "getF"));
    }

    // ------------------------------------------------------------------
    // Shapes whose setters simply assign the slot
    // ------------------------------------------------------------------

    /**
     * A {@code boolean} slot boxes to {@code Supplier<Boolean>} on this path,
     * and both the zero-arg and typed setters assign, so the shape carries an
     * instance-referencing default like any other.
     */
    @Test
    public void instanceDefault_onABoolean() throws Exception {
        Compilation c = compile(
            "  boolean enabled = decide();",
            "  boolean decide() { return true; }",
            "  public boolean isEnabled() { return enabled; }");
        assertThat(c).succeeded();
        assertEquals(true, buildAndGet(c, "isEnabled"));

        // The setters still win over the default, in both forms.
        Class<?> target = Class.forName("demo.Expr", true, loadClasses(c));
        Object b1 = target.getMethod("builder").invoke(null);
        b1.getClass().getMethod("enabled", boolean.class).invoke(b1, false);
        assertEquals(false, target.getMethod("isEnabled")
            .invoke(b1.getClass().getMethod("build").invoke(b1)));

        Object b2 = target.getMethod("builder").invoke(null);
        b2.getClass().getMethod("isEnabled").invoke(b2);
        assertEquals(true, target.getMethod("isEnabled")
            .invoke(b2.getClass().getMethod("build").invoke(b2)));
    }

    /** {@code @Negate}'s inverse pair assigns too, so it composes as well. */
    @Test
    public void instanceDefault_onANegatedBoolean() throws Exception {
        Compilation c = compile(
            "  @dev.simplified.annotations.Negate(\"closed\") boolean open = decide();",
            "  boolean decide() { return true; }",
            "  public boolean isOpen() { return open; }");
        assertThat(c).succeeded();
        assertEquals(true, buildAndGet(c, "isOpen"));

        Class<?> target = Class.forName("demo.Expr", true, loadClasses(c));
        Object b = target.getMethod("builder").invoke(null);
        b.getClass().getMethod("isClosed").invoke(b);
        assertEquals(false, target.getMethod("isOpen")
            .invoke(b.getClass().getMethod("build").invoke(b)));
    }

    @Test
    public void instanceDefault_onAnOptional() throws Exception {
        Compilation c = compile(
            "  java.util.Optional<String> label = java.util.Optional.of(compute());",
            "  String compute() { return \"c\"; }",
            "  public java.util.Optional<String> getLabel() { return label; }");
        assertThat(c).succeeded();
        assertEquals(java.util.Optional.of("c"), buildAndGet(c, "getLabel"));

        // The inner-type overload delegates to the wrapped one, so it wraps too.
        Class<?> target = Class.forName("demo.Expr", true, loadClasses(c));
        Object b = target.getMethod("builder").invoke(null);
        b.getClass().getMethod("label", String.class).invoke(b, "set");
        assertEquals(java.util.Optional.of("set"), target.getMethod("getLabel")
            .invoke(b.getClass().getMethod("build").invoke(b)));
    }

    @Test
    public void instanceDefault_onAnArray() throws Exception {
        Compilation c = compile(
            "  String[] tags = defaults();",
            "  String[] defaults() { return new String[]{\"a\", \"b\"}; }",
            "  public String[] getTags() { return tags; }");
        assertThat(c).succeeded();
        assertEquals(2, java.lang.reflect.Array.getLength(buildAndGet(c, "getTags")));

        Class<?> target = Class.forName("demo.Expr", true, loadClasses(c));
        Object b = target.getMethod("builder").invoke(null);
        b.getClass().getMethod("tags", String[].class).invoke(b, (Object) new String[]{"x"});
        assertEquals(1, java.lang.reflect.Array.getLength(
            target.getMethod("getTags").invoke(b.getClass().getMethod("build").invoke(b))));
    }

    @Test
    public void instanceDefault_onAFormattableString() throws Exception {
        Compilation c = compile(
            "  @dev.simplified.annotations.Formattable String note = compute();",
            "  String compute() { return \"c\"; }",
            "  public String getNote() { return note; }");
        assertThat(c).succeeded();
        assertEquals("c", buildAndGet(c, "getNote"));

        // The @PrintFormat overload assigns String.format(...), and wraps.
        Class<?> target = Class.forName("demo.Expr", true, loadClasses(c));
        Object b = target.getMethod("builder").invoke(null);
        b.getClass().getMethod("note", String.class, Object[].class)
            .invoke(b, "n=%d", new Object[]{7});
        assertEquals("n=7", target.getMethod("getNote")
            .invoke(b.getClass().getMethod("build").invoke(b)));
    }

    /**
     * A custom container that cannot be constructed at all - private
     * constructor, factory-only - still works. Nothing needs to build the
     * declared type: the builder collects into a plain {@code java.util}
     * scratch and the constructor takes the real container from the field's own
     * initializer.
     */
    @Test
    public void instanceDefault_onAnUnconstructableCustomContainer_stillWorks() throws Exception {
        Compilation c = Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(
                JavaFileObjects.forSourceLines("demo.Bag",
                    "package demo;",
                    "import java.util.ArrayList;",
                    "import java.util.Collection;",
                    "public class Bag extends ArrayList<String> {",
                    "    private Bag(Collection<? extends String> c) { super(c); }",
                    "    public static Bag of(Collection<? extends String> c) { return new Bag(c); }",
                    "}"),
                JavaFileObjects.forSourceLines("demo.Holder",
                    "package demo;",
                    "import dev.simplified.annotations.ClassBuilder;",
                    "import dev.simplified.annotations.Collector;",
                    "import java.util.List;",
                    "@ClassBuilder(validate = false)",
                    "public class Holder {",
                    "    String prefix = \"p\";",
                    "    @Collector(singular = true) Bag items = seed();",
                    "    Bag seed() { return Bag.of(List.of(prefix)); }",
                    "    public String getPrefix() { return prefix; }",
                    "    public Bag getItems() { return items; }",
                    "}"));
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Holder", true, loadClasses(c, "demo.Holder"));
        Object builder = target.getMethod("builder").invoke(null);
        builder.getClass().getMethod("addItem", String.class).invoke(builder, "b");
        Object items = target.getMethod("getItems")
            .invoke(builder.getClass().getMethod("build").invoke(builder));
        assertEquals(List.of("p", "b"), items);
        assertEquals("the container comes from the initializer, not the declared type",
            "Bag", items.getClass().getSimpleName());
    }

}
