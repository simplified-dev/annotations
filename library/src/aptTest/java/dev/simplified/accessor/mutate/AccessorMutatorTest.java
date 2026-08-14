package dev.simplified.accessor.mutate;

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
import java.util.stream.Collectors;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code @Getter} and {@code @Setter}: naming, precedence, collision and the
 * shapes each refuses.
 */
public class AccessorMutatorTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("accessor-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, AccessorMutatorTest.class.getClassLoader());
    }

    private static Class<?> compileAndLoad(String fqn, String... lines) throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines(fqn, lines));
        assertThat(c).succeeded();
        return Class.forName(fqn, true, loadClasses(c));
    }

    /**
     * Declared method names, minus everything the compiler adds on its own -
     * synthetic lambda bodies and bridges, and an enum's mandated
     * {@code values} / {@code valueOf}. Without the filter these assertions
     * would pin javac's output rather than this mutator's.
     */
    private static String methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(m -> !m.isSynthetic() && !m.isBridge())
            .map(Method::getName)
            .filter(n -> !type.isEnum() || (!n.equals("values") && !n.equals("valueOf")))
            .sorted()
            .collect(Collectors.joining(","));
    }

    private static Object stored(Object instance, String field) throws Exception {
        Field f = instance.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(instance);
    }

    // ------------------------------------------------------------------
    // Naming
    // ------------------------------------------------------------------

    @Test
    public void bareGetterIsBeanShaped() throws Exception {
        // The single most important default in the feature: a bare @Getter has
        // to keep minting getX/isX, or every existing call site is renamed.
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public class Widget {",
            "    private String label;",
            "    private boolean animated;",
            "}");
        assertEquals("getLabel,isAnimated", methodNames(t));
        assertEquals(String.class, t.getMethod("getLabel").getReturnType());
        assertEquals(boolean.class, t.getMethod("isAnimated").getReturnType());
    }

    @Test
    public void fluentStyleDropsThePrefix() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.NamingStyle;",
            "@Getter(style = NamingStyle.FLUENT)",
            "public class Widget {",
            "    private String label;",
            "    private boolean animated;",
            "}");
        assertEquals("animated,label", methodNames(t));
    }

    @Test
    public void nameOverridesBothReadPatterns() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter(name = \"read{}\")",
            "public class Widget {",
            "    private String label;",
            "    private boolean animated;",
            "}");
        assertEquals("readAnimated,readLabel", methodNames(t));
    }

    @Test
    public void bareSetterIsBeanShaped() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Setter;",
            "@Setter",
            "public class Widget {",
            "    private String label;",
            "}");
        assertEquals("setLabel", methodNames(t));
        Method setter = t.getMethod("setLabel", String.class);
        assertEquals(void.class, setter.getReturnType());

        Object w = t.getDeclaredConstructor().newInstance();
        setter.invoke(w, "hello");
        assertEquals("hello", stored(w, "label"));
    }

    @Test
    public void getterAndSetterCoexistOnOneType() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.Setter;",
            "@Getter @Setter",
            "public class Widget {",
            "    private int count;",
            "}");
        assertEquals("getCount,setCount", methodNames(t));

        Object w = t.getDeclaredConstructor().newInstance();
        t.getMethod("setCount", int.class).invoke(w, 7);
        assertEquals(7, t.getMethod("getCount").invoke(w));
    }

    // ------------------------------------------------------------------
    // Precedence
    // ------------------------------------------------------------------

    @Test
    public void fieldLevelNoneSubtractsFromTypeLevel() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public class Widget {",
            "    private String label;",
            "    @Getter(AccessLevel.NONE) private int cache;",
            "}");
        assertEquals("getLabel", methodNames(t));
    }

    @Test
    public void excludeSkipsNamedFields() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter(exclude = \"cache\")",
            "public class Widget {",
            "    private String label;",
            "    private int cache;",
            "}");
        assertEquals("getLabel", methodNames(t));
    }

    @Test
    public void fieldLevelStyleBeatsTypeLevel() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.NamingStyle;",
            "@Getter",
            "public class Widget {",
            "    private String label;",
            "    @Getter(style = NamingStyle.FLUENT) private int count;",
            "}");
        assertEquals("count,getLabel", methodNames(t));
    }

    @Test
    public void accessLevelIsHonoured() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.Getter;",
            "@Getter(AccessLevel.PROTECTED)",
            "public class Widget {",
            "    private String label;",
            "}");
        assertTrue(Modifier.isProtected(t.getDeclaredMethod("getLabel").getModifiers()));
    }

    @Test
    public void fieldLevelAloneNeedsNoTypeAnnotation() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "public class Widget {",
            "    @Getter private String label;",
            "    private int hidden;",
            "}");
        assertEquals("getLabel", methodNames(t));
    }

    // ------------------------------------------------------------------
    // Collision
    // ------------------------------------------------------------------

    @Test
    public void handWrittenAccessorWins() throws Exception {
        // Adopting @Getter must never break a build, so a declared method with
        // the same name and arity is left in place rather than duplicated.
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public class Widget {",
            "    private String label;",
            "    public String getLabel() { return \"hand-written\"; }",
            "}");
        assertEquals("getLabel", methodNames(t));
        Object w = t.getDeclaredConstructor().newInstance();
        assertEquals("hand-written", t.getMethod("getLabel").invoke(w));
    }

    @Test
    public void collisionIsKeyedOnArityNotParameterType() throws Exception {
        // A hand-written overload taking an argument is a different member, so
        // the zero-arg accessor is still generated beside it.
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public class Widget {",
            "    private String label;",
            "    public String getLabel(String fallback) { return fallback; }",
            "}");
        assertEquals("getLabel,getLabel", methodNames(t));
        assertEquals(0, t.getDeclaredMethod("getLabel").getParameterCount());
    }

    // ------------------------------------------------------------------
    // Shapes each refuses
    // ------------------------------------------------------------------

    @Test
    public void typeLevelSetterSkipsFinalFields() throws Exception {
        // Skip rather than reject, or every mixed final and non-final class is
        // rejected - which is the normal shape.
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Setter;",
            "@Setter",
            "public class Widget {",
            "    private final String id = \"x\";",
            "    private String label;",
            "}");
        assertEquals("setLabel", methodNames(t));
    }

    @Test
    public void fieldLevelSetterOnFinalIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Setter;",
            "public class Widget {",
            "    @Setter private final String id = \"x\";",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("final field 'id'");
    }

    // ------------------------------------------------------------------
    // The name pattern
    // ------------------------------------------------------------------

    @Test
    public void typeLevelNameWithoutThePlaceholderIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter(name = \"value\")",
            "public class Widget {",
            "    private String label;",
            "    private String other;",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Getter naming pattern for 'name'");
        assertThat(c).hadErrorContaining("must contain the '{}' placeholder");
    }

    @Test
    public void fieldLevelNameWithoutThePlaceholderIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Setter;",
            "public class Widget {",
            "    @Setter(name = \"assign\") private String label;",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Setter naming pattern for 'name'");
    }

    /**
     * The error is reported against the field, not the enclosing type, so a
     * class with several offending fields produces several distinguishable
     * errors.
     */
    @Test
    public void nameErrorPointsAtTheAnnotatedElement() {
        JavaFileObject source = JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "public class Widget {",
            "    @Getter(name = \"value\") private String label;",
            "}");
        Compilation c = compile(source);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Getter naming pattern for 'name'")
            .inFile(source).onLine(4);
    }

    /**
     * These annotations suppress through {@code AccessLevel.NONE}, so nothing
     * reads the sentinel as an opt-out and it would otherwise be minted verbatim
     * as the method name.
     */
    @Test
    public void nameCannotBeTheSuppressionSentinel() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "public class Widget {",
            "    @Getter(name = \"-\") private String label;",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot be '-'");
        assertThat(c).hadErrorContaining("write AccessLevel.NONE to generate nothing");
    }

    @Test
    public void aPlaceholderPatternIsAccepted() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter(name = \"read{}\")",
            "public class Widget {",
            "    private String label;",
            "}");
        assertEquals("readLabel", methodNames(t));
    }

    @Test
    public void anEmptyNameStillInheritsTheStyle() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter(name = \"\")",
            "public class Widget {",
            "    private String label;",
            "}");
        assertEquals("getLabel", methodNames(t));
    }

    @Test
    public void recordsAndInterfacesAreRejected() {
        assertThat(compile(JavaFileObjects.forSourceLines("demo.R",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public record R(int x) { }")))
            .hadErrorContaining("only supported on classes and enums");

        assertThat(compile(JavaFileObjects.forSourceLines("demo.I",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public interface I { }")))
            .hadErrorContaining("only supported on classes and enums");
    }

    @Test
    public void setterOnALazyFieldIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import dev.simplified.annotations.Setter;",
            "public class Widget {",
            "    @Lazy @Setter private String heavy = compute();",
            "    private static String compute() { return \"x\"; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot be combined with @Lazy");
    }

    @Test
    public void getterSkipsLazyFieldsRatherThanDuplicating() throws Exception {
        // @Lazy already synthesises getHeavy(); a second one returning Lazy<T>
        // is a duplicate method javac reports with no source line.
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.Lazy;",
            "@Getter",
            "public class Widget {",
            "    @Lazy private String heavy = compute();",
            "    private String label;",
            "    private static String compute() { return \"x\"; }",
            "}");
        assertEquals("compute,getHeavy,getLabel", methodNames(t));
        assertEquals("@Lazy's getter unwraps the storage",
            String.class, t.getDeclaredMethod("getHeavy").getReturnType());
    }

    // ------------------------------------------------------------------
    // Statics, enums, generics
    // ------------------------------------------------------------------

    @Test
    public void staticFieldYieldsAStaticAccessor() throws Exception {
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "public class Widget {",
            "    @Getter private static String shared = \"s\";",
            "}");
        Method m = t.getDeclaredMethod("getShared");
        assertTrue(Modifier.isStatic(m.getModifiers()));
        assertEquals("s", m.invoke(null));
    }

    @Test
    public void typeLevelAccessorsPassOverStaticFields() throws Exception {
        // A static field is the class's own state rather than an instance's, so
        // annotating the class does not reach it. Without this every constant in
        // an annotated class publishes an accessor nobody asked for.
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.Setter;",
            "@Getter",
            "@Setter",
            "public class Widget {",
            "    private static String shared = \"s\";",
            "    private String label;",
            "}");
        assertEquals("getLabel,setLabel", methodNames(t));
    }

    @Test
    public void aStaticFieldNamedDirectlyKeepsItsAccessor() throws Exception {
        // The field-level annotation is the way to ask for a static accessor, so
        // it has to survive the rule that keeps the type-level one away.
        Class<?> t = compileAndLoad("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public class Widget {",
            "    @Getter private static final String CONSTANT = \"c\";",
            "    private String label;",
            "}");
        assertEquals("getCONSTANT,getLabel", methodNames(t));
        assertTrue(Modifier.isStatic(t.getDeclaredMethod("getCONSTANT").getModifiers()));
    }

    @Test
    public void enumTargetsWork() throws Exception {
        // No mutator had ever touched an ElementKind.ENUM before this feature,
        // and enum constants are fields of the enum type - they must not grow
        // accessors of their own.
        Class<?> t = compileAndLoad("demo.Kind",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public enum Kind {",
            "    A(\"a\"), B(\"b\");",
            "    private final String code;",
            "    Kind(String code) { this.code = code; }",
            "}");
        assertEquals("getCode", methodNames(t));
        Object a = t.getEnumConstants()[0];
        assertEquals("a", t.getMethod("getCode").invoke(a));
    }

    @Test
    public void genericFieldKeepsItsTypeArguments() throws Exception {
        // The accessor is an instance member, so it reuses the target's own
        // type parameters rather than re-declaring them the way a static
        // nested Builder has to.
        Class<?> t = compileAndLoad("demo.Box",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import java.util.List;",
            "@Getter",
            "public class Box<T> {",
            "    private List<T> items;",
            "}");
        Method m = t.getDeclaredMethod("getItems");
        assertEquals("java.util.List<T>", m.getGenericReturnType().toString());
    }

    @Test
    public void wildcardFieldKeepsItsWildcard() throws Exception {
        // A field whose type carries a wildcard argument - Class<?>, the array
        // form, and the nested Predicate<Class<?>> - once made the getter's
        // return type parse to a bare identifier named "?", which javac rejected
        // as `cannot find symbol: class ?` at the enclosing declaration.
        Class<?> t = compileAndLoad("demo.Holder",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import java.util.function.Predicate;",
            "@Getter",
            "public class Holder {",
            "    private Class<?> type;",
            "    private Class<?>[] roots;",
            "    private Predicate<Class<?>> filter;",
            "}");
        assertEquals("java.lang.Class<?>",
            t.getDeclaredMethod("getType").getGenericReturnType().toString());
        assertEquals(Class[].class, t.getDeclaredMethod("getRoots").getReturnType());
        assertEquals("java.util.function.Predicate<java.lang.Class<?>>",
            t.getDeclaredMethod("getFilter").getGenericReturnType().toString());
    }

    @Test
    public void extendsBoundedWildcardKeepsItsBound() throws Exception {
        Class<?> t = compileAndLoad("demo.Holder",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import java.util.List;",
            "@Getter",
            "public class Holder {",
            "    private List<? extends Number> nums;",
            "}");
        assertEquals("java.util.List<? extends java.lang.Number>",
            t.getDeclaredMethod("getNums").getGenericReturnType().toString());
    }

    @Test
    public void superBoundedWildcardKeepsItsBound() throws Exception {
        Class<?> t = compileAndLoad("demo.Holder",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import java.util.List;",
            "@Getter",
            "public class Holder {",
            "    private List<? super Integer> sink;",
            "}");
        assertEquals("java.util.List<? super java.lang.Integer>",
            t.getDeclaredMethod("getSink").getGenericReturnType().toString());
    }

    @Test
    public void arrayFieldKeepsItsType() throws Exception {
        Class<?> t = compileAndLoad("demo.Buf",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public class Buf {",
            "    private int[] pixels;",
            "}");
        assertEquals(int[].class, t.getDeclaredMethod("getPixels").getReturnType());
    }

    // ------------------------------------------------------------------
    // Interaction with @ClassBuilder
    // ------------------------------------------------------------------

    @Test
    public void builderTargetCanCarryGetterWithoutCollision() throws Exception {
        // The builder's setters live on the nested Builder; @Getter writes to
        // the target. They cannot collide, and the two naming surfaces are
        // independent - LOMBOK builder naming beside bean accessors.
        Class<?> t = compileAndLoad("demo.Options",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.NamingStyle;",
            "@Getter",
            "@ClassBuilder(style = NamingStyle.LOMBOK, validate = false)",
            "public class Options {",
            "    private String blockId;",
            "    private boolean animated;",
            "}");
        assertTrue(methodNames(t).contains("getBlockId"));
        assertTrue(methodNames(t).contains("isAnimated"));

        Object b = t.getMethod("builder").invoke(null);
        b.getClass().getMethod("blockId", String.class).invoke(b, "stone");
        Object built = b.getClass().getMethod("build").invoke(b);
        assertEquals("stone", t.getMethod("getBlockId").invoke(built));
    }

    @Test
    public void generatedAccessorsCarryTheGeneratedMarker() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "@Getter",
            "public class Widget {",
            "    private String label;",
            "}"));
        assertThat(c).succeeded();

        for (JavaFileObject f : c.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            if (!f.toUri().toString().endsWith("demo/Widget.class")) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                String text = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue("generated accessor should carry @Generated",
                    text.contains("Ldev/simplified/annotations/Generated;"));
                return;
            }
        }
        assertFalse("no class file found", true);
    }

}
