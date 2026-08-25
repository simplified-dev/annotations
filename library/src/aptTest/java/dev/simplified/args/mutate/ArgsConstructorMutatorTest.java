package dev.simplified.args.mutate;

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
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the constructors {@code @AllArgsConstructor},
 * {@code @RequiredArgsConstructor} and {@code @NoArgsConstructor} generate:
 * which fields each mode selects, the shapes that are rejected, and the two
 * interactions the field IR cannot express on its own -
 * {@code final}-with-initializer and {@code transient}.
 */
public class ArgsConstructorMutatorTest {

    private static final String MARKER_DESCRIPTOR = "Ldev/simplified/annotations/Generated;";

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("args-ctor-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()},
            ArgsConstructorMutatorTest.class.getClassLoader());
    }

    private static Class<?> compileAndLoad(String fqn, JavaFileObject src) throws Exception {
        Compilation c = compile(src);
        assertThat(c).succeeded();
        return Class.forName(fqn, true, loadClasses(c));
    }

    /** Parameter types of the constructor with the given arity. */
    private static Class<?>[] ctorParams(Class<?> target, int arity) {
        for (Constructor<?> c : target.getDeclaredConstructors()) {
            if (c.getParameterCount() == arity) return c.getParameterTypes();
        }
        fail("expected a " + arity + "-arg constructor on " + target + "; found "
            + Arrays.toString(target.getDeclaredConstructors()));
        return null;
    }

    private static Constructor<?> ctor(Class<?> target, Class<?>... params) throws Exception {
        Constructor<?> c = target.getDeclaredConstructor(params);
        c.setAccessible(true);
        return c;
    }

    private static Object field(Object instance, String name) throws Exception {
        var f = instance.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(instance);
    }

    private static List<String> arities(Class<?> target) {
        List<String> out = new ArrayList<>();
        for (Constructor<?> c : target.getDeclaredConstructors()) {
            out.add(Arrays.toString(c.getParameterTypes()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // @AllArgsConstructor
    // ------------------------------------------------------------------

    @Test
    public void allArgs_takesEveryFieldExceptInitialisedFinal() throws Exception {
        Class<?> target = compileAndLoad("demo.Point", JavaFileObjects.forSourceLines("demo.Point",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "@AllArgsConstructor",
            "public class Point {",
            "    private final int x;",
            "    private int y;",
            "    private String tag = \"default\";",     // non-final with initializer: a parameter
            "    private final String frozen = \"no\";", // final with initializer: not a parameter
            "    private static int shared;",            // static: never a parameter
            "}"));

        assertArrayEquals(new Class<?>[]{int.class, int.class, String.class}, ctorParams(target, 3));
        Object built = ctor(target, int.class, int.class, String.class).newInstance(1, 2, "given");
        assertEquals("the parameter overwrites a non-final initializer", "given", field(built, "tag"));
        assertEquals("an initialised final keeps its declared value", "no", field(built, "frozen"));
    }

    /**
     * The rule the builder's own field collection gets the other way round. A
     * transient field has no place in a builder but every place in a
     * constructor, and dropping it here would shift every later argument.
     */
    @Test
    public void allArgs_keepsTransientFields() throws Exception {
        Class<?> target = compileAndLoad("demo.Model", JavaFileObjects.forSourceLines("demo.Model",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "@AllArgsConstructor",
            "public class Model {",
            "    private String name;",
            "    private transient String cache;",
            "    private int size;",
            "}"));

        assertArrayEquals(new Class<?>[]{String.class, String.class, int.class}, ctorParams(target, 3));
        Object built = ctor(target, String.class, String.class, int.class)
            .newInstance("n", "c", 3);
        assertEquals("c", field(built, "cache"));
        assertEquals(3, field(built, "size"));
    }

    @Test
    public void allArgs_accessIsHonoured() throws Exception {
        Class<?> target = compileAndLoad("demo.Priv", JavaFileObjects.forSourceLines("demo.Priv",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "@AllArgsConstructor(access = AccessLevel.PRIVATE)",
            "public class Priv {",
            "    private int a;",
            "}"));
        assertTrue(Modifier.isPrivate(target.getDeclaredConstructors()[0].getModifiers()));
    }

    @Test
    public void allArgs_defaultsToPublic() throws Exception {
        Class<?> target = compileAndLoad("demo.Pub", JavaFileObjects.forSourceLines("demo.Pub",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "@AllArgsConstructor",
            "public class Pub {",
            "    private int a;",
            "}"));
        assertTrue(Modifier.isPublic(target.getDeclaredConstructors()[0].getModifiers()));
    }

    // ------------------------------------------------------------------
    // @RequiredArgsConstructor
    // ------------------------------------------------------------------

    /**
     * "Required" is final-without-initializer, and the distinction is the whole
     * feature: adding one initializer shortens the constructor.
     */
    @Test
    public void requiredArgs_selectsOnlyUnassignedFinals() throws Exception {
        Class<?> target = compileAndLoad("demo.PackStack",
            JavaFileObjects.forSourceLines("demo.PackStack",
                "package demo;",
                "import dev.simplified.annotations.RequiredArgsConstructor;",
                "import java.util.List;",
                "@RequiredArgsConstructor",
                "public class PackStack {",
                "    private final String id;",                   // required
                "    private final int depth;",                   // required
                "    private final List<String> layers = List.of();", // assigned already
                "    private String mutable;",                    // not final
                "    private transient int cache;",               // not final
                "}"));

        assertArrayEquals(new Class<?>[]{String.class, int.class}, ctorParams(target, 2));
        Object built = ctor(target, String.class, int.class).newInstance("id", 4);
        assertEquals("id", field(built, "id"));
        assertNull("a non-required field keeps its own default", field(built, "mutable"));
    }

    /**
     * A nullness annotation describes what a field may hold, not whether the
     * constructor must be told. Reading it as "required" would lengthen the
     * constructor of every type that annotates its fields.
     */
    @Test
    public void requiredArgs_ignoresNullnessOnMutableFields() throws Exception {
        Class<?> target = compileAndLoad("demo.Nulls", JavaFileObjects.forSourceLines("demo.Nulls",
            "package demo;",
            "import dev.simplified.annotations.RequiredArgsConstructor;",
            "import org.jetbrains.annotations.NotNull;",
            "@RequiredArgsConstructor",
            "public class Nulls {",
            "    private final String required;",
            "    private @NotNull String annotated = \"\";",
            "}"));
        assertArrayEquals(new Class<?>[]{String.class}, ctorParams(target, 1));
    }

    @Test
    public void requiredArgs_selectingNothingYieldsNoArgConstructor() throws Exception {
        Class<?> target = compileAndLoad("demo.Empty", JavaFileObjects.forSourceLines("demo.Empty",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.RequiredArgsConstructor;",
            "@RequiredArgsConstructor(access = AccessLevel.PRIVATE)",
            "public class Empty {",
            "    private int counter;",
            "}"));
        assertEquals(1, target.getDeclaredConstructors().length);
        Constructor<?> only = target.getDeclaredConstructors()[0];
        assertEquals(0, only.getParameterCount());
        assertTrue("the javac default must have been replaced, not joined",
            Modifier.isPrivate(only.getModifiers()));
    }

    // ------------------------------------------------------------------
    // @NoArgsConstructor
    // ------------------------------------------------------------------

    /**
     * The case with no precedent in this pipeline: the annotation has to end up
     * with the private constructor javac had already generated as public. It
     * needs no tree surgery - appending any constructor makes javac drop the
     * default it generated, so the replacement happens by re-entry.
     */
    @Test
    public void noArgs_replacesJavacsDefault() throws Exception {
        Class<?> target = compileAndLoad("demo.Sealed", JavaFileObjects.forSourceLines("demo.Sealed",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.NoArgsConstructor;",
            "@NoArgsConstructor(access = AccessLevel.PRIVATE)",
            "public class Sealed {",
            "    private String name;",
            "    public static Sealed of() { return new Sealed(); }",
            "}"));

        assertEquals("exactly one constructor, not the default plus ours",
            1, target.getDeclaredConstructors().length);
        Constructor<?> only = target.getDeclaredConstructors()[0];
        assertEquals(0, only.getParameterCount());
        assertTrue(Modifier.isPrivate(only.getModifiers()));
    }

    @Test
    public void noArgs_rejectsUnassignedFinalWithoutForce() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Broken",
            "package demo;",
            "import dev.simplified.annotations.NoArgsConstructor;",
            "@NoArgsConstructor",
            "public class Broken {",
            "    private final String key;",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("would leave final field 'key' unassigned");
    }

    /**
     * {@code force} deliberately violates the type's own nullness contract - the
     * JSON layer fills the fields immediately afterwards, and the alternative is
     * giving up {@code final}.
     */
    @Test
    public void noArgs_forceAssignsZeroValuesOverNullness() throws Exception {
        Class<?> target = compileAndLoad("demo.Entry", JavaFileObjects.forSourceLines("demo.Entry",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.NoArgsConstructor;",
            "import dev.simplified.annotations.RequiredArgsConstructor;",
            "import org.jetbrains.annotations.NotNull;",
            "@RequiredArgsConstructor(access = AccessLevel.PRIVATE)",
            "@NoArgsConstructor(access = AccessLevel.PRIVATE, force = true)",
            "public class Entry {",
            "    private final @NotNull String key;",
            "    private final int weight;",
            "    private final boolean enabled;",
            "    private final char marker;",
            "    private final long size;",
            "    private final double ratio;",
            "}"));

        Object forced = ctor(target).newInstance();
        assertNull("force assigns null over @NotNull, deliberately", field(forced, "key"));
        assertEquals(0, field(forced, "weight"));
        assertEquals(false, field(forced, "enabled"));
        assertEquals((char) 0, field(forced, "marker"));
        assertEquals(0L, field(forced, "size"));
        assertEquals(0.0, (Double) field(forced, "ratio"), 0.0);

        // The required form still exists beside it - different erasure.
        assertEquals(2, target.getDeclaredConstructors().length);
    }

    @Test
    public void noArgs_forceWithNothingToAssignWarns() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Pointless",
            "package demo;",
            "import dev.simplified.annotations.NoArgsConstructor;",
            "@NoArgsConstructor(force = true)",
            "public class Pointless {",
            "    private String mutable;",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("has nothing to assign");
    }

    // ------------------------------------------------------------------
    // Adding rather than backing off
    // ------------------------------------------------------------------

    /**
     * The rule that separates these annotations from {@code @ClassBuilder}.
     * Copying the builder's "any constructor present" guard would silently do
     * nothing on every constant-carrying enum, which declares one by necessity.
     */
    @Test
    public void addsBesideAnAuthorDeclaredConstructor() throws Exception {
        Class<?> target = compileAndLoad("demo.Profile",
            JavaFileObjects.forSourceLines("demo.Profile",
                "package demo;",
                "import dev.simplified.annotations.AllArgsConstructor;",
                "import dev.simplified.annotations.NoArgsConstructor;",
                "@NoArgsConstructor @AllArgsConstructor",
                "public class Profile {",
                "    private String id;",
                "    private int rank;",
                "    public Profile(String id) { this.id = id; }",
                "}"));
        assertEquals("author's one-arg, plus a no-args and an all-args",
            3, target.getDeclaredConstructors().length);
        assertEquals("kept", field(ctor(target, String.class).newInstance("kept"), "id"));
    }

    @Test
    public void stackedAnnotationsOfDifferentAritiesBothEmit() throws Exception {
        Class<?> target = compileAndLoad("demo.Stack", JavaFileObjects.forSourceLines("demo.Stack",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "import dev.simplified.annotations.NoArgsConstructor;",
            "@NoArgsConstructor @AllArgsConstructor",
            "public class Stack {",
            "    private String a;",
            "    private int b;",
            "}"));
        assertEquals(2, target.getDeclaredConstructors().length);
        assertArrayEquals(new Class<?>[]{String.class, int.class}, ctorParams(target, 2));
    }

    /**
     * Two annotations that resolve to the same signature are reported here,
     * naming both, rather than reaching javac as a duplicate constructor on a
     * line the author cannot see.
     */
    @Test
    public void collidingErasuresAreReportedNamingBothAnnotations() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Clash",
            "package demo;",
            "import dev.simplified.annotations.NoArgsConstructor;",
            "import dev.simplified.annotations.RequiredArgsConstructor;",
            "@RequiredArgsConstructor @NoArgsConstructor",
            "public class Clash {",
            "    private String mutable;",  // nothing is required, so both give ()
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@RequiredArgsConstructor and @NoArgsConstructor");
    }

    // ------------------------------------------------------------------
    // Enums
    // ------------------------------------------------------------------

    /**
     * Enum constants are themselves field declarations on the enum's tree, so
     * the constant list must not become parameters - and the constructor has to
     * come out {@code private} whatever access is written, since the language
     * permits nothing else.
     */
    @Test
    public void enumTarget_bindsTheConstantListAndForcesPrivate() throws Exception {
        Class<?> target = compileAndLoad("demo.Format", JavaFileObjects.forSourceLines("demo.Format",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.RequiredArgsConstructor;",
            "@RequiredArgsConstructor(access = AccessLevel.PUBLIC)",
            "public enum Format {",
            "    PNG(\"png\", true),",
            "    JPG(\"jpg\", false);",
            "    private final String extension;",
            "    private final boolean lossless;",
            "    public String extension() { return extension; }",
            "    public boolean lossless() { return lossless; }",
            "}"));

        Object png = Enum.valueOf(target.asSubclass(Enum.class), "PNG");
        assertEquals("png", target.getMethod("extension").invoke(png));
        assertEquals(true, target.getMethod("lossless").invoke(png));
        for (Constructor<?> c : target.getDeclaredConstructors()) {
            assertTrue("an enum constructor must be private: " + c,
                Modifier.isPrivate(c.getModifiers()));
        }
    }

    // ------------------------------------------------------------------
    // Rejected shapes
    // ------------------------------------------------------------------

    @Test
    public void recordIsRejected() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Rec",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "@AllArgsConstructor",
            "public record Rec(int x) {}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("canonical constructor is its contract");
    }

    @Test
    public void interfaceIsRejected() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Iface",
            "package demo;",
            "import dev.simplified.annotations.NoArgsConstructor;",
            "@NoArgsConstructor",
            "public interface Iface {}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("which has no constructor");
    }

    @Test
    public void accessNoneIsRejected() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Nope",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "@AllArgsConstructor(access = AccessLevel.NONE)",
            "public class Nope { private int a; }"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("generates nothing");
    }

    @Test
    public void builderArgsWithoutClassBuilderIsRejected() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Orphan",
            "package demo;",
            "import dev.simplified.annotations.BuilderArgsConstructor;",
            "@BuilderArgsConstructor",
            "public class Orphan { private int a; }"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("carries no @ClassBuilder");
    }

    // ------------------------------------------------------------------
    // Interaction with the rest of the pipeline
    // ------------------------------------------------------------------

    /**
     * A {@code @Lazy} field takes no parameter. Its storage is a wrapper the
     * field installs and initialises itself, and the same rewrite makes the
     * field {@code final} - so a parameter would assign it a second time. The
     * omission is reported, because a shortened "all args" signature is exactly
     * the kind of thing that has to be said out loud.
     */
    @Test
    public void lazyFieldTakesNoParameterAndSaysSo() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Deferred",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "import dev.simplified.annotations.Lazy;",
            "@AllArgsConstructor",
            "public class Deferred {",
            "    @Lazy private String name = compute();",
            "    private int count;",
            "    private static String compute() { return \"computed\"; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("'name' is @Lazy, so it takes no constructor parameter");

        Class<?> target = Class.forName("demo.Deferred", true, loadClasses(c));
        assertArrayEquals(new Class<?>[]{int.class}, ctorParams(target, 1));
        assertEquals("computed", target.getMethod("getName")
            .invoke(ctor(target, int.class).newInstance(1)));
    }

    /**
     * A {@code @ClassBuilder} target keeps the constructor {@code build()}
     * calls, and gains the annotated one beside it.
     */
    @Test
    public void classBuilderTargetKeepsItsOwnConstructor() throws Exception {
        Class<?> target = compileAndLoad("demo.Both", JavaFileObjects.forSourceLines("demo.Both",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.NoArgsConstructor;",
            "@ClassBuilder(validate = false)",
            "@NoArgsConstructor(access = AccessLevel.PRIVATE)",
            "public class Both {",
            "    private String name;",
            "    private int count;",
            "}"));

        assertEquals("the builder's constructor and the annotated one: " + arities(target),
            2, target.getDeclaredConstructors().length);
        assertArrayEquals(new Class<?>[]{String.class, int.class}, ctorParams(target, 2));
        assertTrue(Modifier.isPrivate(ctor(target).getModifiers()));
    }

    /**
     * When a written annotation resolves to the builder's own signature, one
     * constructor is emitted rather than two colliding ones, and the written
     * visibility is what survives.
     */
    @Test
    public void writtenAllArgsMatchingTheBuilderIsNotDuplicated() throws Exception {
        Class<?> target = compileAndLoad("demo.Same", JavaFileObjects.forSourceLines("demo.Same",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false, retainInit = false)",
            "@AllArgsConstructor",
            "public class Same {",
            "    private String name;",
            "    private int count;",
            "}"));

        assertEquals("one constructor, not two of the same erasure: " + arities(target),
            1, target.getDeclaredConstructors().length);
        assertTrue("the written annotation's visibility wins",
            Modifier.isPublic(target.getDeclaredConstructors()[0].getModifiers()));
    }

    /** A written {@code @BuilderArgsConstructor} pins the builder ctor's visibility. */
    @Test
    public void builderArgsConstructorOverridesConstructorAccess() throws Exception {
        Class<?> target = compileAndLoad("demo.Pinned", JavaFileObjects.forSourceLines("demo.Pinned",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.BuilderArgsConstructor;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "@BuilderArgsConstructor(access = AccessLevel.PUBLIC)",
            "public class Pinned {",
            "    private String name;",
            "    private int count;",
            "}"));

        assertEquals(1, target.getDeclaredConstructors().length);
        assertTrue(Modifier.isPublic(target.getDeclaredConstructors()[0].getModifiers()));
    }

    /** A bare {@code @BuilderArgsConstructor} states nothing, so constructorAccess still applies. */
    @Test
    public void bareBuilderArgsConstructorDoesNotOverrideConstructorAccess() throws Exception {
        Class<?> target = compileAndLoad("demo.Bare", JavaFileObjects.forSourceLines("demo.Bare",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.BuilderArgsConstructor;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false, constructorAccess = AccessLevel.PRIVATE)",
            "@BuilderArgsConstructor",
            "public class Bare {",
            "    private String name;",
            "}"));
        assertTrue(Modifier.isPrivate(target.getDeclaredConstructors()[0].getModifiers()));
    }

    // ------------------------------------------------------------------
    // Coverage marker
    // ------------------------------------------------------------------

    /**
     * The marker is {@code @Retention(CLASS)}, so it never reaches reflection -
     * the class file is the only place it can be observed, and the only place
     * it matters.
     */
    private static String classFileText(Compilation compilation, String binaryName)
        throws Exception {
        String want = binaryName.replace('.', '/') + ".class";
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            if (!f.toUri().toString().endsWith(want)) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                // ISO-8859-1 is byte-preserving, so no pool entry can be
                // mangled into or out of a false match.
                return new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
            }
        }
        fail("no generated class file for '" + binaryName + "'");
        return null;
    }

    @Test
    public void generatedConstructorCarriesTheCoverageMarker() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Marked",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "@AllArgsConstructor",
            "public class Marked { private int a; }"));
        assertThat(c).succeeded();
        assertTrue("expected @Generated on the synthesised constructor",
            classFileText(c, "demo.Marked").contains(MARKER_DESCRIPTOR));
    }

    @Test
    public void emitGeneratedFalseSuppressesTheMarker() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Unmarked",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "@AllArgsConstructor(emitGenerated = false)",
            "public class Unmarked { private int a; }"));
        assertThat(c).succeeded();
        assertFalse("opted out, so the constructor must not carry the marker",
            classFileText(c, "demo.Unmarked").contains(MARKER_DESCRIPTOR));
    }

    // ------------------------------------------------------------------
    // Nullness on the generated parameters
    // ------------------------------------------------------------------

    /**
     * The parameter's type is the field's own, so the field's nullness
     * describes it verbatim and travels onto it. The plain field beside them is
     * the other half of the claim: the copy restores what a field declared, it
     * does not decorate every parameter.
     *
     * <p>Both JetBrains annotations are {@code @Retention(CLASS)}, so reflection
     * cannot see them and the class-file bytes are the only place to look.
     */
    @Test
    public void generatedParametersCarryTheFieldsNullness() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Hinted",
            "package demo;",
            "import dev.simplified.annotations.AllArgsConstructor;",
            "import org.jetbrains.annotations.NotNull;",
            "import org.jetbrains.annotations.Nullable;",
            "@AllArgsConstructor",
            "public class Hinted {",
            "    private @NotNull String required;",
            "    private @Nullable String optional;",
            "    private String plain;",
            "}"));
        assertThat(c).succeeded();

        List<Set<String>> params = ctorParamAnnotations(c, "demo.Hinted", 3);
        assertTrue("the @NotNull field's parameter must carry it, saw " + params,
            params.get(0).contains(NOT_NULL));
        assertTrue("the @Nullable field's parameter must carry it, saw " + params,
            params.get(1).contains(NULLABLE));
        assertTrue("a field declaring no nullness must not gain one, saw " + params,
            params.get(2).isEmpty());
    }

    /**
     * The same copy on the mode that selects a subset, and on a target that also
     * carries {@code @ClassBuilder} - so the annotated constructor is emitted
     * beside the builder's own rather than instead of it.
     */
    @Test
    public void requiredArgsParametersCarryNullnessBesideABuilder() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.HintedReq",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.RequiredArgsConstructor;",
            "import org.jetbrains.annotations.NotNull;",
            "@ClassBuilder(validate = false, constructorAccess = AccessLevel.PRIVATE)",
            "@RequiredArgsConstructor",
            "public class HintedReq {",
            "    private final @NotNull String id;",
            "    private String mutable;",
            "}"));
        assertThat(c).succeeded();

        List<Set<String>> params = ctorParamAnnotations(c, "demo.HintedReq", 1);
        assertTrue("the required field's parameter must carry @NotNull, saw " + params,
            params.get(0).contains(NOT_NULL));
    }

    // ------------------------------------------------------------------
    // @Lazy beside a generated builder
    // ------------------------------------------------------------------

    /**
     * The field is a blank {@code final} only the builder's constructor can
     * assign, so a second constructor omitting it cannot compile. What javac
     * says on its own is "variable v might not have been initialized" on the
     * class-declaration brace, naming neither annotation and pointing at no
     * remedy - the whole reason this is diagnosed here instead.
     */
    @Test
    public void lazyFieldWithABuilderIsRejectedNamingBothAnnotations() {
        Compilation c = compile(lazyTarget("demo.L1", "@ClassBuilder(validate = false)",
            "@AllArgsConstructor", "private @Lazy String v = c();"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("'v' is @Lazy and the generated builder owns it");
        assertThat(c).hadErrorContaining("@AllArgsConstructor emits a second constructor");
        assertThat(c).hadErrorContaining("Write @BuilderArgsConstructor instead");
    }

    /**
     * Two independent routes reach the same blank {@code final}: an initializer
     * the builder strips so its own constructor can assign the field, and a
     * field that declares none in the first place. Fixing one alone would leave
     * the other reporting nothing.
     */
    @Test
    public void lazyFieldWithNoInitializerIsRejectedTheSameWay() {
        Compilation c = compile(lazyTarget("demo.L2", "@ClassBuilder(validate = false)",
            "@AllArgsConstructor", "private @Lazy String v;"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("'v' is @Lazy and the generated builder owns it");
    }

    /** Retaining or dropping initializers is not the axis: the strip runs either way. */
    @Test
    public void lazyFieldIsRejectedWithRetainInitOff() {
        Compilation c = compile(lazyTarget("demo.L3",
            "@ClassBuilder(validate = false, retainInit = false)",
            "@AllArgsConstructor", "private @Lazy String v = c();"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("'v' is @Lazy and the generated builder owns it");
    }

    @Test
    public void lazyFieldWithABuilderRejectsRequiredArgs() {
        Compilation c = compile(lazyTarget("demo.L4", "@ClassBuilder(validate = false)",
            "@RequiredArgsConstructor", "private @Lazy String v = c();"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@RequiredArgsConstructor emits a second constructor");
    }

    @Test
    public void lazyFieldWithABuilderRejectsNoArgs() {
        Compilation c = compile(lazyTarget("demo.L5", "@ClassBuilder(validate = false)",
            "@NoArgsConstructor", "private @Lazy String v = c();"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@NoArgsConstructor emits a second constructor");
    }

    /**
     * {@code force} fills the {@code final} fields a zero-argument constructor
     * would leave unassigned, and a {@code @Lazy} field is not one of them - its
     * storage holds a deferred supplier, so there is no zero value to write.
     */
    @Test
    public void lazyFieldWithABuilderRejectsNoArgsForce() {
        Compilation c = compile(lazyTarget("demo.L6", "@ClassBuilder(validate = false)",
            "@NoArgsConstructor(force = true)", "private @Lazy String v = c();"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@NoArgsConstructor emits a second constructor");
    }

    /** Every emitting annotation is named, since removing one of them is not the fix. */
    @Test
    public void stackedAnnotationsAreAllNamed() {
        Compilation c = compile(lazyTarget("demo.L7", "@ClassBuilder(validate = false)",
            "@AllArgsConstructor @NoArgsConstructor", "private @Lazy String v = c();"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@AllArgsConstructor and @NoArgsConstructor emits");
    }

    /**
     * The one member of the family that is safe, and the remedy the error
     * names. Its constructor is emitted by the builder pass, which alone knows
     * how to shape the {@code Supplier} parameter a {@code @Lazy} field needs.
     */
    @Test
    public void builderArgsConstructorIsSilentOnALazyField() {
        Compilation c = compile(lazyTarget("demo.L8", "@ClassBuilder(validate = false)",
            "@BuilderArgsConstructor", "private @Lazy String v = c();"));
        assertThat(c).succeeded();
    }

    @Test
    public void builderArgsConstructorIsSilentOnALazyFieldWithNoInitializer() {
        Compilation c = compile(lazyTarget("demo.L9", "@ClassBuilder(validate = false)",
            "@BuilderArgsConstructor", "private @Lazy String v;"));
        assertThat(c).succeeded();
    }

    /**
     * The three escapes, which have to stay compiling. Each takes the field out
     * of the builder's field collection, so its declared initializer is never
     * stripped and no constructor anywhere needs to assign it.
     */
    @Test
    public void builderIgnoreOnTheLazyFieldStillCompiles() {
        Compilation c = compile(lazyTarget("demo.L10", "@ClassBuilder(validate = false)",
            "@AllArgsConstructor", "@BuilderIgnore private @Lazy String v = c();"));
        assertThat(c).succeeded();
    }

    @Test
    public void classBuilderExcludeNamingTheLazyFieldStillCompiles() {
        Compilation c = compile(lazyTarget("demo.L11",
            "@ClassBuilder(validate = false, exclude = \"v\")",
            "@AllArgsConstructor", "private @Lazy String v = c();"));
        assertThat(c).succeeded();
    }

    @Test
    public void transientLazyFieldStillCompiles() {
        Compilation c = compile(lazyTarget("demo.L12", "@ClassBuilder(validate = false)",
            "@AllArgsConstructor", "private transient @Lazy String v = c();"));
        assertThat(c).succeeded();
    }

    /**
     * The other side of the gate, on source that differs only by the builder.
     * Without {@code @ClassBuilder} a {@code @Lazy} field must carry an
     * initializer, so it is a {@code final} field that is already definitely
     * assigned and a constructor skipping it is correct - the omission is worth
     * saying, not worth refusing.
     */
    @Test
    public void withoutAClassBuilderTheSameFieldIsOnlyANote() {
        Compilation c = compile(lazyTarget("demo.L13", "",
            "@AllArgsConstructor", "private @Lazy String v = c();"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("'v' is @Lazy, so it takes no constructor parameter");
    }

    private static JavaFileObject lazyTarget(String fqn, String classBuilder, String argsAnnotation,
                                             String fieldDecl) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        List<String> lines = new ArrayList<>(List.of(
            "package demo;",
            "import dev.simplified.annotations.*;"));
        if (!classBuilder.isEmpty()) lines.add(classBuilder);
        lines.add(argsAnnotation);
        lines.add("public class " + simple + " {");
        lines.add("    " + fieldDecl);
        lines.add("    private int n;");
        lines.add("    private static String c() { return \"x\"; }");
        lines.add("}");
        return JavaFileObjects.forSourceLines(fqn, lines.toArray(new String[0]));
    }

    // ------------------------------------------------------------------
    // Class-file readers
    // ------------------------------------------------------------------

    private static final String NOT_NULL = "Lorg/jetbrains/annotations/NotNull;";
    private static final String NULLABLE = "Lorg/jetbrains/annotations/Nullable;";

    /**
     * Per-parameter annotation descriptors on the constructor of the given
     * arity, collected from both the declaration channel
     * ({@code RuntimeInvisibleParameterAnnotations}) and the type-use channel
     * ({@code RuntimeInvisibleTypeAnnotations}), since the JetBrains pair
     * targets both and javac may route it either way.
     */
    private static List<Set<String>> ctorParamAnnotations(Compilation c, String binaryName,
                                                          int arity) throws Exception {
        byte[] bytes = classFileBytes(c, binaryName);
        List<Set<String>> out = new ArrayList<>();
        for (int i = 0; i < arity; i++) out.add(new LinkedHashSet<>());
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("<init>")) return null;
                if (Type.getArgumentTypes(descriptor).length != arity) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc,
                                                                      boolean visible) {
                        if (parameter < arity) out.get(parameter).add(desc);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                 String desc, boolean visible) {
                        TypeReference ref = new TypeReference(typeRef);
                        if (ref.getSort() != TypeReference.METHOD_FORMAL_PARAMETER) return null;
                        // A nested type argument says something about the element
                        // rather than about the parameter, so only the root path
                        // counts as the parameter's own nullness.
                        if (typePath != null) return null;
                        int index = ref.getFormalParameterIndex();
                        if (index < arity) out.get(index).add(desc);
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return out;
    }

    private static byte[] classFileBytes(Compilation compilation, String binaryName)
        throws Exception {
        String want = binaryName.replace('.', '/') + ".class";
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            if (!f.toUri().toString().endsWith(want)) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                return baos.toByteArray();
            }
        }
        fail("no generated class file for '" + binaryName + "'");
        return null;
    }

    private static void assertArrayEquals(Class<?>[] expected, Class<?>[] actual) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    }

}
