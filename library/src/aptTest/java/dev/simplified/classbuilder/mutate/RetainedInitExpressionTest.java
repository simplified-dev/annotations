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
     * Shapes whose setters mutate the builder slot in place, or read it as its
     * declared type, cannot carry the {@code Supplier}-typed slot the
     * constructor path needs. Those are reported against the field rather than
     * left to fail inside generated code.
     */
    @Test
    public void instanceDefault_onAnUnsupportedShape_isReportedAgainstTheField() {
        Compilation c = compile(
            "  @dev.simplified.annotations.Collector List<String> f = defaults();",
            "  List<String> defaults() { return new ArrayList<>(); }",
            "  public List<String> getF() { return f; }");
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("reads instance state");
    }

}
