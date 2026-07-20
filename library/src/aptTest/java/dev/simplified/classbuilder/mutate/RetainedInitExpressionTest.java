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
     * {@code $default$<field>()} is static, so an initializer reading instance
     * state cannot be retained. javac reports it against the generated provider
     * with a clear message rather than failing obscurely - but the restriction
     * is real and {@code @ClassBuilder} documents it.
     */
    @Test
    public void initializerReadingInstanceState_isRejected() {
        Compilation c = compile(
            "  String base = \"b\";",
            "  String f = base + \"x\";",
            "  public String getBase() { return base; }",
            "  public String getF() { return f; }");
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("non-static variable base cannot be referenced from a static context");
    }

    @Test
    public void initializerCallingAnInstanceMethod_isRejected() {
        Compilation c = compile(
            "  String f = compute();",
            "  String compute() { return \"x\"; }",
            "  public String getF() { return f; }");
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("non-static method compute() cannot be referenced from a static context");
    }

}
