package dev.simplified.equality.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code @EqualsAndHashCode}: the injected pair, every emission row it can
 * dispatch a member down, and the refusals that stop a broken relation reaching
 * a hash table.
 *
 * <p>Almost every assertion here is on <b>runtime behaviour</b> rather than on
 * the compilation succeeding. A member silently dropped, an array compared by
 * reference or a float row emitting {@code ==} all compile perfectly, and the
 * first symptom of any of them is a map losing entries a long way from here.
 */
public class EqualsAndHashCodeTest {

    private static final String XCONTRACT = "Ldev/simplified/annotations/XContract;";
    private static final String GENERATED = "Ldev/simplified/annotations/Generated;";

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("equality-mutate-test");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            // URI shape: mem:///CLASS_OUTPUT/demo/Point.class
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
            EqualsAndHashCodeTest.class.getClassLoader());
    }

    /** The single public constructor of a fixture, which every fixture here has exactly one of. */
    private static Constructor<?> soleConstructor(Class<?> type) {
        Constructor<?>[] ctors = type.getConstructors();
        assertEquals("fixture should declare exactly one public constructor", 1, ctors.length);
        return ctors[0];
    }

    /** {@code from(instance)} then {@code build()} - the round trip a builder promises. */
    private static Object roundTrip(Class<?> target, Object instance) throws Exception {
        Object builder = target.getMethod("from", target).invoke(null, instance);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    /**
     * Fails when any diagnostic carries the needle.
     *
     * <p>Not {@code hadWarningCount(0)}: javac always emits its own "source
     * version RELEASE_17" warning for this processor, so an absence assertion
     * has to be scoped to the diagnostic under test.
     */
    private static void assertNoneContaining(
        Iterable<? extends Diagnostic<? extends JavaFileObject>> diagnostics, String needle) {
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            String message = d.getMessage(null);
            if (message != null && message.contains(needle)) {
                fail("expected no diagnostic containing '" + needle + "', but got: " + message);
            }
        }
    }

    // ------------------------------------------------------------------
    // The relation itself
    // ------------------------------------------------------------------

    private static JavaFileObject point() {
        return JavaFileObjects.forSourceLines("demo.Point",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Point {",
            "    private final int x;",
            "    private final double y;",
            "    private final String label;",
            "    private final byte[] blob;",
            "    public Point(int x, double y, String label, byte[] blob) {",
            "        this.x = x; this.y = y; this.label = label; this.blob = blob;",
            "    }",
            "}");
    }

    @Test
    public void plainClass_isReflexiveSymmetricAndHashConsistent() throws Exception {
        Compilation c = compile(point());
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Point", true, loadClasses(c)));
        Object a = ctor.newInstance(1, 2.0, "hi", new byte[]{1, 2});
        Object b = ctor.newInstance(1, 2.0, "hi", new byte[]{1, 2});

        assertEquals("equals must be reflexive", a, a);
        assertEquals("distinct arrays with equal contents must compare equal", a, b);
        assertTrue("equals must be symmetric", b.equals(a));
        assertEquals("equal objects must hash equally", a.hashCode(), b.hashCode());
        assertEquals("hashCode must be stable across calls", a.hashCode(), a.hashCode());
    }

    @Test
    public void plainClass_rejectsNullAndAForeignType() throws Exception {
        Compilation c = compile(point());
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Point", true, loadClasses(c)));
        Object a = ctor.newInstance(1, 2.0, "hi", new byte[]{1, 2});

        assertFalse("null is never equal", a.equals(null));
        assertFalse("a foreign type is never equal", a.equals("Point"));
    }

    @Test
    public void plainClass_differsOnEachMemberInTurn() throws Exception {
        Compilation c = compile(point());
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Point", true, loadClasses(c)));
        Object a = ctor.newInstance(1, 2.0, "hi", new byte[]{1, 2});

        assertNotEquals(a, ctor.newInstance(9, 2.0, "hi", new byte[]{1, 2}));
        assertNotEquals(a, ctor.newInstance(1, 9.0, "hi", new byte[]{1, 2}));
        assertNotEquals(a, ctor.newInstance(1, 2.0, "no", new byte[]{1, 2}));
        assertNotEquals(a, ctor.newInstance(1, 2.0, "hi", new byte[]{9}));
    }

    // ------------------------------------------------------------------
    // Every emission row, each distinguished from its neighbours
    // ------------------------------------------------------------------

    private static JavaFileObject rows() {
        return JavaFileObjects.forSourceLines("demo.Rows",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import java.util.UUID;",
            "@EqualsAndHashCode",
            "public final class Rows {",
            "    private final boolean flag;",
            "    private final byte b;",
            "    private final short s;",
            "    private final char c;",
            "    private final int i;",
            "    private final long l;",
            "    private final float f;",
            "    private final double d;",
            "    private final String text;",
            "    private final UUID id;",
            "    private final byte[] bytes;",
            "    private final int[][] grid;",
            "    private final String[] words;",
            "    public Rows(boolean flag, byte b, short s, char c, int i, long l, float f,",
            "                double d, String text, UUID id, byte[] bytes, int[][] grid,",
            "                String[] words) {",
            "        this.flag = flag; this.b = b; this.s = s; this.c = c; this.i = i;",
            "        this.l = l; this.f = f; this.d = d; this.text = text; this.id = id;",
            "        this.bytes = bytes; this.grid = grid; this.words = words;",
            "    }",
            "}");
    }

    private static final UUID ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /**
     * Arguments for {@code Rows}, freshly minted every call - so two calls
     * produce equal contents in <b>distinct</b> arrays, which is the whole
     * question an array row answers.
     */
    private static Object[] base() {
        return new Object[]{
            true, (byte) 7, (short) 9, 'k', 11, 13L, 1.5f, 2.5d, "text", ONE,
            new byte[]{1, 2}, new int[][]{{1, 2}, {3}}, new String[]{"a", "b"}
        };
    }

    private static Object[] with(int index, Object value) {
        Object[] args = base();
        args[index] = value;
        return args;
    }

    @Test
    public void everyEmissionRow_distinguishesItsOwnMember() throws Exception {
        Compilation c = compile(rows());
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Rows", true, loadClasses(c)));
        Object a = ctor.newInstance(base());
        assertEquals("the same values must compare equal whatever row they take",
            a, ctor.newInstance(base()));
        assertEquals(a.hashCode(), ctor.newInstance(base()).hashCode());

        Object[][] differing = {
            with(0, false),                             // boolean
            with(1, (byte) 8),                          // byte
            with(2, (short) 10),                        // short
            with(3, 'z'),                               // char
            with(4, 12),                                // int
            with(5, 14L),                               // long
            with(6, 2.5f),                              // float
            with(7, 3.5d),                              // double
            with(8, "other"),                           // String
            with(9, TWO),                               // reference
            with(10, new byte[]{1, 3}),                 // primitive array
            with(11, new int[][]{{1, 2}, {4}}),         // array of arrays
            with(12, new String[]{"a", "c"})            // array of references
        };
        for (int index = 0; index < differing.length; index++) {
            assertNotEquals("member " + index + " must be part of the relation",
                a, ctor.newInstance(differing[index]));
        }
    }

    /**
     * The deep form is not a nicety. {@code Arrays.equals} on an
     * {@code int[][]} compares its rows by reference, so the two fixtures here
     * differ under the flat spelling and agree under the deep one.
     */
    @Test
    public void arrayRows_compareByContentAndUseTheDeepFormWhereTheyMust() throws Exception {
        Compilation c = compile(rows());
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Rows", true, loadClasses(c)));
        Object a = ctor.newInstance(base());
        Object b = ctor.newInstance(base());

        assertEquals("distinct byte[], int[][] and String[] holding equal contents must agree",
            a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals("a nested row is part of the comparison",
            a, ctor.newInstance(with(11, new int[][]{{1, 2}, {3, 4}})));
    }

    /**
     * {@code ==} is not an equivalence relation on a float: it is non-reflexive
     * for {@code NaN} and conflates the two signed zeroes. {@code Float.compare}
     * and {@code floatToIntBits} induce the same partition as each other, which
     * is what keeps the emitted pair consistent.
     */
    @Test
    public void floatRow_holdsForNaNAndSeparatesTheSignedZeroes() throws Exception {
        Compilation c = compile(rows());
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Rows", true, loadClasses(c)));
        Object nan = ctor.newInstance(with(6, Float.NaN));

        assertEquals("NaN must equal itself", nan, ctor.newInstance(with(6, Float.NaN)));
        assertEquals(nan.hashCode(), ctor.newInstance(with(6, Float.NaN)).hashCode());
        assertNotEquals("-0.0f and 0.0f are different values",
            ctor.newInstance(with(6, 0.0f)), ctor.newInstance(with(6, -0.0f)));
    }

    @Test
    public void doubleRow_holdsForNaNAndSeparatesTheSignedZeroes() throws Exception {
        Compilation c = compile(rows());
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Rows", true, loadClasses(c)));
        Object nan = ctor.newInstance(with(7, Double.NaN));

        assertEquals("NaN must equal itself", nan, ctor.newInstance(with(7, Double.NaN)));
        assertEquals(nan.hashCode(), ctor.newInstance(with(7, Double.NaN)).hashCode());
        assertNotEquals("-0.0 and 0.0 are different values",
            ctor.newInstance(with(7, 0.0d)), ctor.newInstance(with(7, -0.0d)));
    }

    // ------------------------------------------------------------------
    // Records - the shape this exists for
    // ------------------------------------------------------------------

    /**
     * The implicit pair compares an array component by reference and looks
     * correct doing it, which is the defect being closed. The injected pair is
     * the one that survives, so two records holding equal contents in distinct
     * arrays agree - and agree on their hash, which the implicit one would not.
     */
    @Test
    public void record_suppressesTheImplicitPairAndComparesAnArrayByContent() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Pixels",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public record Pixels(float[] values, int width) {}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Pixels", true, loadClasses(c)));
        Object a = ctor.newInstance(new float[]{1f, 2f}, 3);
        Object b = ctor.newInstance(new float[]{1f, 2f}, 3);

        assertEquals("the injected pair must be the one that survives", a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, ctor.newInstance(new float[]{1f, 9f}, 3));
        assertNotEquals(a, ctor.newInstance(new float[]{1f, 2f}, 4));
    }

    /**
     * The include marker is applicable to a field and to a method, and the
     * language copies a component's annotations onto both the backing field and
     * the canonical accessor - so the component arrives twice unless the
     * accessor is skipped. A {@code double} is where that stops being a quiet
     * duplication: both copies mint the same prelude local and the build fails
     * on the record's own declaration line, naming a variable nobody wrote.
     */
    @Test
    public void record_componentCarryingTheIncludeMarkerIsSelectedOnce() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Reading",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsInclude;",
            "@EqualsAndHashCode",
            "public record Reading(@EqualsInclude double value, int scale) {}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Reading", true, loadClasses(c)));
        Object a = ctor.newInstance(1.5d, 3);
        assertEquals(a, ctor.newInstance(1.5d, 3));
        assertEquals(a.hashCode(), ctor.newInstance(1.5d, 3).hashCode());
        assertNotEquals("the marked component is compared", a, ctor.newInstance(2.5d, 3));
        assertNotEquals("and the unmarked one still is", a, ctor.newInstance(1.5d, 4));
    }

    @Test
    public void record_declaringItsOwnPairIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Own",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public record Own(int x) {",
            "    @Override public boolean equals(Object o) { return false; }",
            "    @Override public int hashCode() { return 0; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("already declares equals and hashCode");
    }

    @Test
    public void classDeclaringOneHalfOfThePairIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Half",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Half {",
            "    private final int x;",
            "    public Half(int x) { this.x = x; }",
            "    @Override public int hashCode() { return x; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("already declares hashCode");
    }

    /**
     * A one-argument {@code equals} whose parameter is the target's own type
     * overrides nothing and supplies no relation - it is an overload that wants
     * to sit beside the generated pair, and is the ordinary shape of a value
     * type written by hand. Counting it as a written half refuses the annotation
     * outright, over a member the author has no way to connect the message to.
     */
    @Test
    public void typedEqualsOverloadOnTheTargetIsNotTheWrittenPair() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Vec3",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Vec3 {",
            "    private final float x;",
            "    public Vec3(float x) { this.x = x; }",
            "    public boolean equals(Vec3 other) { return other != null && other.x == this.x; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Vec3", true, loadClasses(c));
        assertNotNull("the override is generated beside the overload",
            type.getDeclaredMethod("equals", Object.class));
        assertNotNull("and the author's overload survives",
            type.getDeclaredMethod("equals", type));

        Object a = soleConstructor(type).newInstance(1f);
        assertEquals(a, soleConstructor(type).newInstance(1f));
        assertNotEquals(a, soleConstructor(type).newInstance(2f));
    }

    @Test
    public void supertypeDeclaringTheMemberFinalIsAnError() {
        JavaFileObject sealedSuper = JavaFileObjects.forSourceLines("demo.Locked",
            "package demo;",
            "public class Locked {",
            "    @Override public final boolean equals(Object o) { return this == o; }",
            "}");
        JavaFileObject target = JavaFileObjects.forSourceLines("demo.Below",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Below extends Locked {",
            "    private final int x;",
            "    public Below(int x) { this.x = x; }",
            "}");
        Compilation c = compile(sealedSuper, target);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("demo.Locked declares it final");
    }

    /**
     * The same overload read from the other side. A supertype's
     * {@code equals(Sup)} may be {@code final} without saying anything about the
     * {@code equals(Object)} beside it, so refusing there refuses over a member
     * that was never in the way.
     */
    @Test
    public void supertypeDeclaringATypedOverloadFinalIsNotTheOverride() throws Exception {
        JavaFileObject anchor = JavaFileObjects.forSourceLines("demo.Anchor",
            "package demo;",
            "public class Anchor {",
            "    public final int a;",
            "    public Anchor(int a) { this.a = a; }",
            "    public final boolean equals(Anchor other) {",
            "        return other != null && other.a == this.a;",
            "    }",
            "    @Override public boolean equals(Object other) {",
            "        return other instanceof Anchor && ((Anchor) other).a == this.a;",
            "    }",
            "    @Override public int hashCode() { return a; }",
            "}");
        JavaFileObject target = JavaFileObjects.forSourceLines("demo.Moored",
            "package demo;",
            "import dev.simplified.annotations.CallSuper;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(callSuper = CallSuper.NO)",
            "public class Moored extends Anchor {",
            "    private final int q;",
            "    public Moored(int a, int q) { super(a); this.q = q; }",
            "}");
        Compilation c = compile(anchor, target);
        assertThat(c).succeeded();

        Constructor<?> ctor = Class.forName("demo.Moored", true, loadClasses(c))
            .getConstructor(int.class, int.class);
        assertEquals("the non-final override is what the pair replaces",
            ctor.newInstance(1, 5), ctor.newInstance(1, 5));
        assertNotEquals(ctor.newInstance(1, 5), ctor.newInstance(1, 6));
    }

    // ------------------------------------------------------------------
    // identity - which instances are even candidates
    // ------------------------------------------------------------------

    private static JavaFileObject shape(String attributes) {
        return JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode" + attributes,
            "public class Shape {",
            "    private final int side;",
            "    public Shape(int side) { this.side = side; }",
            "}");
    }

    private static JavaFileObject square() {
        return JavaFileObjects.forSourceLines("demo.Square",
            "package demo;",
            "public class Square extends Shape {",
            "    public Square(int side) { super(side); }",
            "}");
    }

    @Test
    public void exactClass_rejectsASubclassInstance() throws Exception {
        Compilation c = compile(shape(""), square());
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Object shape = soleConstructor(Class.forName("demo.Shape", true, cl)).newInstance(2);
        Object square = soleConstructor(Class.forName("demo.Square", true, cl)).newInstance(2);

        assertNotEquals("getClass() comparison keeps a subclass out", shape, square);
        assertNotEquals("and keeps it out from the other side too", square, shape);
    }

    @Test
    public void instanceOf_acceptsASubclassInstance() throws Exception {
        Compilation c = compile(
            shape("(identity = EqualsAndHashCode.Identity.INSTANCE_OF)"), square());
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Object shape = soleConstructor(Class.forName("demo.Shape", true, cl)).newInstance(2);
        Object square = soleConstructor(Class.forName("demo.Square", true, cl)).newInstance(2);

        assertEquals("the bare instanceof relation admits a subclass", shape, square);
        assertNotEquals(shape, soleConstructor(Class.forName("demo.Square", true, cl)).newInstance(3));
    }

    /**
     * The hook is a cooperation protocol rather than a defence: symmetry comes
     * back only because the subclass mints one of its own, and each side then
     * asks the other whether it is willing to be compared.
     */
    @Test
    public void instanceOfCanEqual_emitsTheHookOnANonFinalTarget() throws Exception {
        JavaFileObject sub = JavaFileObjects.forSourceLines("demo.Square",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)",
            "public class Square extends Shape {",
            "    public Square(int side) { super(side); }",
            "}");
        Compilation c = compile(
            shape("(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)"), sub);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> shapeType = Class.forName("demo.Shape", true, cl);
        Class<?> squareType = Class.forName("demo.Square", true, cl);

        Method hook = shapeType.getDeclaredMethod("canEqual", Object.class);
        assertTrue("the hook has to be overridable, so it is protected",
            Modifier.isProtected(hook.getModifiers()));
        assertNotNull("the subclass mints its own hook",
            squareType.getDeclaredMethod("canEqual", Object.class));

        Object shape = soleConstructor(shapeType).newInstance(2);
        Object square = soleConstructor(squareType).newInstance(2);
        assertFalse("the hook refuses the comparison", shape.equals(square));
        assertFalse("and refuses it from the other side, which is the point",
            square.equals(shape));
    }

    /**
     * On a type that is both {@code final} and a direct subclass of
     * {@code Object} the hook can never be overridden, so emitting it would put
     * a {@code protected} member on the public surface doing nothing.
     */
    @Test
    public void instanceOfCanEqual_onAFinalRootTypeDegradesWithANote() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Leaf",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)",
            "public final class Leaf {",
            "    private final int x;",
            "    public Leaf(int x) { this.x = x; }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("no subclass can override the hook");

        Class<?> type = Class.forName("demo.Leaf", true, loadClasses(c));
        for (Method method : type.getDeclaredMethods()) {
            assertNotEquals("no hook should be emitted on a final root type",
                "canEqual", method.getName());
        }
        assertEquals(soleConstructor(type).newInstance(1), soleConstructor(type).newInstance(1));
    }

    /**
     * Writing the hook by hand is the one act that says "I want this protocol",
     * so it has to reuse the declaration rather than stand in for the whole
     * decision. Skipping the call along with the emission collapses the relation
     * to bare {@code instanceof} - and the symptom is not a compile error but a
     * base that admits a subclass the subclass refuses back, holding a different
     * hash, which is a map quietly losing entries.
     */
    @Test
    public void instanceOfCanEqual_reusesAnAuthorDeclaredHook() throws Exception {
        JavaFileObject base = JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)",
            "public class Base {",
            "    private final int x;",
            "    public Base(int x) { this.x = x; }",
            "    protected boolean canEqual(Object other) { return other instanceof Base; }",
            "}");
        JavaFileObject sub = JavaFileObjects.forSourceLines("demo.Sub",
            "package demo;",
            "public class Sub extends Base {",
            "    private final int y;",
            "    public Sub(int x, int y) { super(x); this.y = y; }",
            "    @Override protected boolean canEqual(Object other) {",
            "        return other instanceof Sub;",
            "    }",
            "    @Override public boolean equals(Object other) {",
            "        if (this == other) return true;",
            "        if (!(other instanceof Sub)) return false;",
            "        Sub that = (Sub) other;",
            "        return that.canEqual(this) && super.equals(other) && that.y == this.y;",
            "    }",
            "    @Override public int hashCode() { return super.hashCode() * 59 + y; }",
            "}");
        Compilation c = compile(base, sub);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> baseType = Class.forName("demo.Base", true, cl);
        Class<?> subType = Class.forName("demo.Sub", true, cl);

        int hooks = 0;
        for (Method method : baseType.getDeclaredMethods()) {
            if (method.getName().equals("canEqual")) hooks++;
        }
        assertEquals("the written hook is reused, so no second one is emitted", 1, hooks);

        Object plain = soleConstructor(baseType).newInstance(1);
        Object extended = subType.getConstructor(int.class, int.class).newInstance(1, 9);

        assertEquals("the two sides must answer alike",
            plain.equals(extended), extended.equals(plain));
        assertFalse("and the hook the author wrote is what refuses the subclass",
            plain.equals(extended));
        assertNotEquals("their hashes disagree, which is what admitting the comparison "
            + "would have made inconsistent", plain.hashCode(), extended.hashCode());
        assertEquals("the relation still holds inside the base type",
            plain, soleConstructor(baseType).newInstance(1));
    }

    @Test
    public void identityDisagreeingWithAnAnnotatedSuperIsAnError() {
        JavaFileObject sub = JavaFileObjects.forSourceLines("demo.Square",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF)",
            "public class Square extends Shape {",
            "    public Square(int side) { super(side); }",
            "}");
        Compilation c = compile(shape(""), sub);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("disagrees with Shape, which asks for EXACT_CLASS");
    }

    // ------------------------------------------------------------------
    // callSuper - inherited state is folded in or it is dropped
    // ------------------------------------------------------------------

    private static JavaFileObject parent() {
        return JavaFileObjects.forSourceLines("demo.Parent",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public class Parent {",
            "    private final int a;",
            "    public Parent(int a) { this.a = a; }",
            "}");
    }

    private static JavaFileObject child(String attributes) {
        return JavaFileObjects.forSourceLines("demo.Child",
            "package demo;",
            "import dev.simplified.annotations.CallSuper;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode" + attributes,
            "public class Child extends Parent {",
            "    private final int b;",
            "    public Child(int a, int b) { super(a); this.b = b; }",
            "}");
    }

    @Test
    public void callSuperAuto_resolvesNoAndSaysNothingOnAnObjectSubclass() throws Exception {
        Compilation c = compile(point());
        assertThat(c).succeeded();
        // Deliberately silent. The note exists because AUTO can flip from NO to
        // YES when a superclass in another artifact gains the annotation, and a
        // type with no superclass to gain it can never flip - so reporting here
        // would put a message on nearly every annotated type in a codebase.
        assertNoneContaining(c.notes(), "resolved callSuper");

        // It did resolve NO, which is observable: a super call would reach
        // Object's identity equals, and two distinct instances never satisfy it.
        Class<?> type = Class.forName("demo.Point", true, loadClasses(c));
        Object a = soleConstructor(type).newInstance(1, 2.0, "hi", new byte[]{1, 2});
        Object b = soleConstructor(type).newInstance(1, 2.0, "hi", new byte[]{1, 2});
        assertEquals("callSuper must have resolved NO - a super.equals call would compare "
            + "identity and reject two distinct instances", a, b);
    }

    @Test
    public void callSuperAuto_resolvesYesWhenTheSuperclassCarriesTheAnnotation() {
        Compilation c = compile(parent(), child(""));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("resolved callSuper = YES - Parent carries");
    }

    @Test
    public void callSuperAuto_resolvesYesWhenTheSuperclassDeclaresThePairItself() {
        JavaFileObject hand = JavaFileObjects.forSourceLines("demo.Handwritten",
            "package demo;",
            "public class Handwritten {",
            "    protected final int a;",
            "    public Handwritten(int a) { this.a = a; }",
            "    @Override public boolean equals(Object o) {",
            "        return o instanceof Handwritten && ((Handwritten) o).a == this.a;",
            "    }",
            "    @Override public int hashCode() { return a; }",
            "}");
        JavaFileObject target = JavaFileObjects.forSourceLines("demo.Extended",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public class Extended extends Handwritten {",
            "    private final int b;",
            "    public Extended(int a, int b) { super(a); this.b = b; }",
            "}");
        Compilation c = compile(hand, target);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("resolved callSuper = YES - Handwritten declares its own");
    }

    /**
     * A superclass overriding {@code equals} without {@code hashCode} is value
     * equality over an identity hash, which is already inconsistent - so the
     * resolution refuses to guess in either direction.
     */
    @Test
    public void callSuperAuto_refusesASuperclassDeclaringHalfThePair() {
        JavaFileObject hand = JavaFileObjects.forSourceLines("demo.Lopsided",
            "package demo;",
            "public class Lopsided {",
            "    @Override public boolean equals(Object o) { return this == o; }",
            "}");
        JavaFileObject target = JavaFileObjects.forSourceLines("demo.Beneath",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public class Beneath extends Lopsided {",
            "    private final int b;",
            "    public Beneath(int b) { this.b = b; }",
            "}");
        Compilation c = compile(hand, target);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("declares some of the pair and not the rest");
    }

    /**
     * A superclass carrying only {@code equals(Sup)} declares neither half of the
     * pair, so the resolution is NO. Reading the overload as the override instead
     * makes the superclass look half written, and the build fails over an
     * inconsistency that is not there.
     */
    @Test
    public void callSuperAuto_ignoresATypedEqualsOverloadInTheSuperclass() throws Exception {
        JavaFileObject vec = JavaFileObjects.forSourceLines("demo.Vec",
            "package demo;",
            "public class Vec {",
            "    public final float x;",
            "    public Vec(float x) { this.x = x; }",
            "    public boolean equals(Vec other) { return other != null && other.x == this.x; }",
            "}");
        JavaFileObject target = JavaFileObjects.forSourceLines("demo.Tagged",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public class Tagged extends Vec {",
            "    private final int z;",
            "    public Tagged(float x, int z) { super(x); this.z = z; }",
            "}");
        Compilation c = compile(vec, target);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining(
            "resolved callSuper = NO - Vec declares no implementation of its own");

        Constructor<?> ctor = Class.forName("demo.Tagged", true, loadClasses(c))
            .getConstructor(float.class, int.class);
        assertEquals("with no super call the inherited member stays out of the relation",
            ctor.newInstance(1f, 5), ctor.newInstance(2f, 5));
        assertNotEquals(ctor.newInstance(1f, 5), ctor.newInstance(1f, 6));
    }

    @Test
    public void callSuperYes_onAnObjectSubclassIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Alone",
            "package demo;",
            "import dev.simplified.annotations.CallSuper;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(callSuper = CallSuper.YES)",
            "public final class Alone {",
            "    private final int x;",
            "    public Alone(int x) { this.x = x; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("whose superclass supplies no implementation to call");
    }

    @Test
    public void callSuper_foldsInTheSuperclassState() throws Exception {
        Compilation c = compile(parent(), child(""));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Child", true, loadClasses(c)));
        assertEquals(ctor.newInstance(1, 5), ctor.newInstance(1, 5));
        assertNotEquals("the inherited member is part of the relation",
            ctor.newInstance(1, 5), ctor.newInstance(2, 5));
        assertNotEquals(ctor.newInstance(1, 5).hashCode(), ctor.newInstance(2, 5).hashCode());
    }

    @Test
    public void callSuperNo_leavesTheSuperclassStateOut() throws Exception {
        Compilation c = compile(parent(), child("(callSuper = CallSuper.NO)"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Child", true, loadClasses(c)));
        assertEquals("without the super call the inherited member is invisible",
            ctor.newInstance(1, 5), ctor.newInstance(2, 5));
    }

    // ------------------------------------------------------------------
    // Member selection
    // ------------------------------------------------------------------

    @Test
    public void of_narrowsToTheNamedMembers() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Narrowed",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(of = \"a\")",
            "public final class Narrowed {",
            "    private final int a;",
            "    private final int b;",
            "    public Narrowed(int a, int b) { this.a = a; this.b = b; }",
            "}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Narrowed", true, loadClasses(c)));
        assertEquals("the unnamed member is outside the relation",
            ctor.newInstance(1, 2), ctor.newInstance(1, 3));
        assertEquals(ctor.newInstance(1, 2).hashCode(), ctor.newInstance(1, 3).hashCode());
        assertNotEquals(ctor.newInstance(1, 2), ctor.newInstance(9, 2));
    }

    @Test
    public void exclude_removesTheNamedMember() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Trimmed",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(exclude = \"b\")",
            "public final class Trimmed {",
            "    private final int a;",
            "    private final int b;",
            "    public Trimmed(int a, int b) { this.a = a; this.b = b; }",
            "}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Trimmed", true, loadClasses(c)));
        assertEquals(ctor.newInstance(1, 2), ctor.newInstance(1, 3));
        assertNotEquals(ctor.newInstance(1, 2), ctor.newInstance(9, 2));
    }

    @Test
    public void excludeMarker_removesTheField() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Skipped",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsExclude;",
            "@EqualsAndHashCode",
            "public final class Skipped {",
            "    private final int a;",
            "    @EqualsExclude private final int b;",
            "    public Skipped(int a, int b) { this.a = a; this.b = b; }",
            "}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Skipped", true, loadClasses(c)));
        assertEquals(ctor.newInstance(1, 2), ctor.newInstance(1, 3));
        assertNotEquals(ctor.newInstance(1, 2), ctor.newInstance(9, 2));
    }

    /**
     * The method really is called: the two instances hold different field values
     * and agree only because the included accessor normalises them.
     */
    @Test
    public void includeMarker_addsAZeroArgMethodAsATerm() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Keyed",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsExclude;",
            "import dev.simplified.annotations.EqualsInclude;",
            "@EqualsAndHashCode",
            "public final class Keyed {",
            "    @EqualsExclude private final String name;",
            "    public Keyed(String name) { this.name = name; }",
            "    @EqualsInclude public String key() { return name.toLowerCase(); }",
            "}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Keyed", true, loadClasses(c)));
        assertEquals("the included method is the term, not the field it reads",
            ctor.newInstance("AB"), ctor.newInstance("ab"));
        assertEquals(ctor.newInstance("AB").hashCode(), ctor.newInstance("ab").hashCode());
        assertNotEquals(ctor.newInstance("AB"), ctor.newInstance("zz"));
    }

    @Test
    public void transientIsDroppedByDefault() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Scratched",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Scratched {",
            "    private final int id;",
            "    private final transient int scratch;",
            "    public Scratched(int id, int scratch) { this.id = id; this.scratch = scratch; }",
            "}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Scratched", true, loadClasses(c)));
        assertEquals("a member excluded from serialization is excluded from the relation",
            ctor.newInstance(1, 2), ctor.newInstance(1, 3));
        assertNotEquals(ctor.newInstance(1, 2), ctor.newInstance(9, 2));
    }

    /**
     * A {@code @Lazy} field's storage is a {@code Lazy<T>} wrapper that declares
     * no equality of its own, so two instances agreeing here is the proof it was
     * never selected.
     */
    @Test
    public void lazyFieldIsSkipped() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Deferred",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.Lazy;",
            "@EqualsAndHashCode",
            "public class Deferred {",
            "    private final int id;",
            "    @Lazy String value = \"v\";",
            "    public Deferred(int id) { this.id = id; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Deferred", true, loadClasses(c));
        assertEquals("the field is rewritten to Lazy<T> storage",
            "dev.simplified.lazy.Lazy", type.getDeclaredField("value").getType().getName());
        Constructor<?> ctor = soleConstructor(type);
        assertEquals(ctor.newInstance(1), ctor.newInstance(1));
    }

    // ------------------------------------------------------------------
    // Member selection - what is rejected outright
    // ------------------------------------------------------------------

    @Test
    public void staticIsNeverSelected() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Versioned",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(of = \"VERSION\")",
            "public final class Versioned {",
            "    private static final int VERSION = 1;",
            "    private final int id;",
            "    public Versioned(int id) { this.id = id; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("names 'VERSION', which is not a member this selection reaches");
    }

    @Test
    public void ofAndExcludeTogetherIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Both",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(of = \"a\", exclude = \"b\")",
            "public final class Both {",
            "    private final int a;",
            "    private final int b;",
            "    public Both(int a, int b) { this.a = a; this.b = b; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("sets both 'of' and 'exclude'");
    }

    @Test
    public void excludeNamingNothingIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Renamed",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(exclude = \"labell\")",
            "public final class Renamed {",
            "    private final String label;",
            "    public Renamed(String label) { this.label = label; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("names 'labell', which is not a member this selection reaches");
    }

    @Test
    public void bothMarkersOnOneMemberIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Contradictory",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsExclude;",
            "import dev.simplified.annotations.EqualsInclude;",
            "@EqualsAndHashCode",
            "public final class Contradictory {",
            "    @EqualsExclude @EqualsInclude private final int a;",
            "    public Contradictory(int a) { this.a = a; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("carries both the include and exclude markers");
    }

    @Test
    public void includeMarkerOnAMethodThatCannotContributeIsAnError() {
        Compilation staticMethod = compile(JavaFileObjects.forSourceLines("demo.Fixed",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsInclude;",
            "@EqualsAndHashCode",
            "public final class Fixed {",
            "    private final int a;",
            "    public Fixed(int a) { this.a = a; }",
            "    @EqualsInclude public static int version() { return 1; }",
            "}"));
        assertThat(staticMethod).failed();
        assertThat(staticMethod).hadErrorContaining("is static, so it holds no per-instance value");

        Compilation parameterised = compile(JavaFileObjects.forSourceLines("demo.Applied",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsInclude;",
            "@EqualsAndHashCode",
            "public final class Applied {",
            "    private final int a;",
            "    public Applied(int a) { this.a = a; }",
            "    @EqualsInclude public int scaled(int by) { return a * by; }",
            "}"));
        assertThat(parameterised).failed();
        assertThat(parameterised).hadErrorContaining("takes parameters");

        Compilation returnsVoid = compile(JavaFileObjects.forSourceLines("demo.Silent",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsInclude;",
            "@EqualsAndHashCode",
            "public final class Silent {",
            "    private final int a;",
            "    public Silent(int a) { this.a = a; }",
            "    @EqualsInclude public void touch() { }",
            "}"));
        assertThat(returnsVoid).failed();
        assertThat(returnsVoid).hadErrorContaining("returns void");
    }

    @Test
    public void includeMarkerOnALazyFieldIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Forced",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsInclude;",
            "import dev.simplified.annotations.Lazy;",
            "@EqualsAndHashCode",
            "public class Forced {",
            "    private final int id;",
            "    @EqualsInclude @Lazy String value = \"v\";",
            "    public Forced(int id) { this.id = id; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("is @Lazy, so @EqualsAndHashCode cannot read it directly");
    }

    // ------------------------------------------------------------------
    // cacheHashCode - a memo whose staleness has no compile signal
    // ------------------------------------------------------------------

    @Test
    public void cacheHashCode_memoizesIntoAPrivateTransientField() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Memo",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(cacheHashCode = true)",
            "public final class Memo {",
            "    private final String label;",
            "    private final int id;",
            "    public Memo(String label, int id) { this.label = label; this.id = id; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Memo", true, loadClasses(c));
        Field memo = type.getDeclaredField("$hashCode");
        assertEquals(int.class, memo.getType());
        assertTrue("the memo is nobody else's business", Modifier.isPrivate(memo.getModifiers()));
        assertTrue("a persisted hash is meaningless across JVMs, so the memo is transient",
            Modifier.isTransient(memo.getModifiers()));

        Object a = soleConstructor(type).newInstance("x", 3);
        memo.setAccessible(true);
        assertEquals("nothing is computed before the first call", 0, memo.getInt(a));

        int first = a.hashCode();
        assertEquals("the memo holds what was returned", first, memo.getInt(a));
        assertEquals("and the second call returns it unchanged", first, a.hashCode());
        assertEquals("equal objects still hash equally",
            first, soleConstructor(type).newInstance("x", 3).hashCode());
    }

    /**
     * Zero is the field's own default and therefore the "not computed yet"
     * sentinel, so a hash that legitimately computes to zero would recompute on
     * every call forever. One {@code int} member of -59 lands exactly there:
     * {@code 1 * 59 + -59}.
     */
    @Test
    public void cacheHashCode_normalisesAComputedZero() throws Exception {
        JavaFileObject cached = JavaFileObjects.forSourceLines("demo.Zeroed",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(cacheHashCode = true)",
            "public final class Zeroed {",
            "    private final int value;",
            "    public Zeroed(int value) { this.value = value; }",
            "}");
        JavaFileObject plain = JavaFileObjects.forSourceLines("demo.Uncached",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Uncached {",
            "    private final int value;",
            "    public Uncached(int value) { this.value = value; }",
            "}");
        Compilation c = compile(cached, plain);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> zeroed = Class.forName("demo.Zeroed", true, cl);
        assertEquals("the accumulator really does reach zero here",
            0, soleConstructor(Class.forName("demo.Uncached", true, cl)).newInstance(-59).hashCode());

        Object a = soleConstructor(zeroed).newInstance(-59);
        assertEquals("a computed zero is moved off the sentinel", Integer.MIN_VALUE, a.hashCode());
        Field memo = zeroed.getDeclaredField("$hashCode");
        memo.setAccessible(true);
        assertEquals("so the second call is a read rather than a recompute",
            Integer.MIN_VALUE, memo.getInt(a));
    }

    @Test
    public void cacheHashCode_onARecordIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Frozen",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(cacheHashCode = true)",
            "public record Frozen(int x) {}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("a record body cannot declare the instance field the memo needs");
    }

    @Test
    public void cacheHashCode_withAMutableMemberWarns() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Drifting",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(cacheHashCode = true)",
            "public final class Drifting {",
            "    private int count;",
            "    public Drifting(int count) { this.count = count; }",
            "    public void set(int count) { this.count = count; }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("with the mutable member 'count'");
    }

    @Test
    public void cacheHashCode_withCallSuperWarns() {
        Compilation c = compile(parent(), child("(cacheHashCode = true)"));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("(cacheHashCode) with callSuper");
    }

    // ------------------------------------------------------------------
    // useAccessors - reading through what the author wrote
    // ------------------------------------------------------------------

    @Test
    public void useAccessors_readsThroughTheDeclaredAccessor() throws Exception {
        JavaFileObject through = JavaFileObjects.forSourceLines("demo.Padded",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(useAccessors = true)",
            "public final class Padded {",
            "    private final String name;",
            "    public Padded(String name) { this.name = name; }",
            "    public String getName() { return name.trim(); }",
            "}");
        JavaFileObject direct = JavaFileObjects.forSourceLines("demo.Raw",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Raw {",
            "    private final String name;",
            "    public Raw(String name) { this.name = name; }",
            "    public String getName() { return name.trim(); }",
            "}");
        Compilation c = compile(through, direct);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Constructor<?> padded = soleConstructor(Class.forName("demo.Padded", true, cl));
        assertEquals("the accessor's normalisation is what is compared",
            padded.newInstance("a "), padded.newInstance(" a"));
        assertEquals(padded.newInstance("a ").hashCode(), padded.newInstance(" a").hashCode());

        Constructor<?> raw = soleConstructor(Class.forName("demo.Raw", true, cl));
        assertNotEquals("and the default reads the field, accessor or no accessor",
            raw.newInstance("a "), raw.newInstance(" a"));
    }

    /**
     * A read of the wrong type would be dispatched down the wrong emission row
     * silently, so only an accessor returning the member's own type is taken.
     */
    @Test
    public void useAccessors_ignoresAnAccessorOfTheWrongTypeWithANote() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Mistyped",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(useAccessors = true)",
            "public final class Mistyped {",
            "    private final String name;",
            "    public Mistyped(String name) { this.name = name; }",
            "    public int getName() { return name.length(); }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("found no declared accessor for 'name' returning its own type");

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Mistyped", true, loadClasses(c)));
        assertNotEquals("two names of one length are still two names",
            ctor.newInstance("ab"), ctor.newInstance("cd"));
    }

    @Test
    public void useAccessors_fallsBackToTheFieldWhenTheGetterIsSuppressed() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Suppressed",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.Getter;",
            "@EqualsAndHashCode(useAccessors = true)",
            "public final class Suppressed {",
            "    @Getter(AccessLevel.NONE) private final String name;",
            "    public Suppressed(String name) { this.name = name; }",
            "    public String getName() { return name.trim(); }",
            "}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Suppressed", true, loadClasses(c)));
        assertNotEquals("a field whose accessor is suppressed is read directly",
            ctor.newInstance("a "), ctor.newInstance(" a"));
    }

    /**
     * An accessor {@code @Getter} minted in the same round is never in the
     * element model, so reading only the model turns {@code useAccessors} beside
     * it into the field reads it exists to avoid.
     *
     * <p>The two reads compile to the same bytes on the annotated class itself,
     * which is why the assertion is on a <b>subclass overriding the generated
     * accessor</b>: only a call dispatches, so the override's answer is visible
     * from {@code equals} exactly when the accessor route was taken.
     */
    @Test
    public void useAccessors_readsThroughAnAccessorGeneratedInTheSameRound() throws Exception {
        JavaFileObject base = JavaFileObjects.forSourceLines("demo.Tagged",
            "package demo;",
            "import dev.simplified.annotations.CallSuper;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.NamingStyle;",
            "@Getter(style = NamingStyle.FLUENT)",
            "@EqualsAndHashCode(useAccessors = true,",
            "    identity = EqualsAndHashCode.Identity.INSTANCE_OF, callSuper = CallSuper.NO)",
            "public class Tagged {",
            "    private final String tag;",
            "    public Tagged(String tag) { this.tag = tag; }",
            "}");
        JavaFileObject proxy = JavaFileObjects.forSourceLines("demo.TaggedProxy",
            "package demo;",
            "public class TaggedProxy extends Tagged {",
            "    public TaggedProxy(String tag) { super(tag); }",
            "    @Override public String tag() { return \"resolved\"; }",
            "}");
        Compilation c = compile(base, proxy);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Object plain = soleConstructor(Class.forName("demo.Tagged", true, cl))
            .newInstance("resolved");
        Object overridden = soleConstructor(Class.forName("demo.TaggedProxy", true, cl))
            .newInstance("raw");

        assertEquals("the override's answer is what is compared, not the field it hides",
            plain, overridden);
        assertEquals(plain.hashCode(), overridden.hashCode());
        assertNoneContaining(c.notes(), "found no declared accessor for 'tag'");
    }

    /**
     * The accessor route may only take an accessor the <b>accessor pass</b>
     * minted, because that pass alone builds the return type out of the field
     * the body returns.
     *
     * <p>Several other passes inject a zero-arg instance method, and the read
     * candidates end with the bare field name, so a field named after one of
     * them collides: {@code mutate} is the builder's own round-trip method,
     * which returns a {@code Builder}. Matching generated authorship rather than
     * the pass reads the field through it, compares two builders by identity,
     * and two objects holding equal state come out unequal.
     */
    @Test
    public void useAccessors_refusesAGeneratedMethodTheAccessorPassDidNotMint() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Named",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@ClassBuilder",
            "@EqualsAndHashCode(useAccessors = true)",
            "public class Named {",
            "    private String mutate;",
            "    private int size;",
            "}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Named", true, loadClasses(c));
        Object a = named(type, "tag", 1);
        Object b = named(type, "tag", 1);
        assertEquals("the field is what is compared, not the builder mutate() returns", a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, named(type, "other", 1));
        assertThat(c).hadNoteContaining("found no declared accessor for 'mutate'");
    }

    /** One {@code demo.Named}, assembled through its builder. */
    private static Object named(Class<?> type, String mutate, int size) throws Exception {
        Object builder = type.getMethod("builder").invoke(null);
        Class<?> builderType = builder.getClass();
        builder = builderType.getMethod("mutate", String.class).invoke(builder, mutate);
        builder = builderType.getMethod("size", int.class).invoke(builder, size);
        return builderType.getMethod("build").invoke(builder);
    }

    /**
     * The fallback still has to fire: nothing declares an accessor and nothing
     * generated one, so the note is the only warning an author gets that the
     * attribute did not take.
     */
    @Test
    public void useAccessors_notesAndReadsTheFieldWhenNoAccessorExistsAtAll() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Bare",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(useAccessors = true)",
            "public final class Bare {",
            "    private final String name;",
            "    public Bare(String name) { this.name = name; }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("found no declared accessor for 'name' returning its own type");

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Bare", true, loadClasses(c)));
        assertEquals(ctor.newInstance("a"), ctor.newInstance("a"));
        assertNotEquals(ctor.newInstance("a "), ctor.newInstance(" a"));
    }

    // ------------------------------------------------------------------
    // The limits no emission can close
    // ------------------------------------------------------------------

    /**
     * A container delegates to its element's {@code equals}, and an array
     * inherits {@code Object}'s - so the wrapped array compares by reference and
     * nothing generated can see through the wrapper.
     */
    @Test
    public void containerOfArrays_warnsOnEachWrapper() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Wrapped",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import java.util.List;",
            "import java.util.Map;",
            "import java.util.Optional;",
            "@EqualsAndHashCode",
            "public final class Wrapped {",
            "    private final List<byte[]> chunks;",
            "    private final Optional<byte[]> maybe;",
            "    private final Map<String, int[]> lookup;",
            "    public Wrapped(List<byte[]> chunks, Optional<byte[]> maybe,",
            "                   Map<String, int[]> lookup) {",
            "        this.chunks = chunks; this.maybe = maybe; this.lookup = lookup;",
            "    }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("'chunks' is a java.util.List<byte[]>");
        assertThat(c).hadWarningContaining("'maybe' is a java.util.Optional<byte[]>");
        assertThat(c).hadWarningContaining("'lookup' is a java.util.Map<");
        assertThat(c).hadWarningContaining("the int[] elements compare by identity, not content");
    }

    /**
     * The failure is an array behind a type parameter, not array nesting. A
     * warning on a plain array of any depth would train the reader to ignore the
     * one case that is genuinely unreachable.
     */
    @Test
    public void plainArraysOfAnyDepthDoNotWarn() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Plain",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Plain {",
            "    private final byte[] flat;",
            "    private final int[][] nested;",
            "    private final String[] refs;",
            "    public Plain(byte[] flat, int[][] nested, String[] refs) {",
            "        this.flat = flat; this.nested = nested; this.refs = refs;",
            "    }",
            "}"));
        assertThat(c).succeeded();
        assertNoneContaining(c.warnings(), "compare by identity, not content");
    }

    @Test
    public void bareCollectionWarns() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Loose",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import java.util.Collection;",
            "@EqualsAndHashCode",
            "public final class Loose {",
            "    private final Collection<String> items;",
            "    public Loose(Collection<String> items) { this.items = items; }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("which specifies no equals contract at all");
    }

    @Test
    public void memberTypeOverridingNoEqualsIsANote() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Matching",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import java.util.regex.Pattern;",
            "@EqualsAndHashCode",
            "public final class Matching {",
            "    private final Pattern pattern;",
            "    public Matching(Pattern pattern) { this.pattern = pattern; }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("which declares no equals of its own");
    }

    /**
     * A member whose type is annotated is the composition this feature exists
     * for, and its pair is injected into a tree the element model cannot see -
     * so a walk of declared members alone reports the exact opposite of what the
     * round is about to emit. Both types are compiled together here, since
     * compiling them apart hides the blind spot entirely.
     */
    @Test
    public void memberTypeCarryingTheAnnotationItselfIsSilent() {
        JavaFileObject money = JavaFileObjects.forSourceLines("demo.Money",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Money {",
            "    private final long cents;",
            "    public Money(long cents) { this.cents = cents; }",
            "}");
        JavaFileObject order = JavaFileObjects.forSourceLines("demo.Order",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import java.util.regex.Pattern;",
            "@EqualsAndHashCode",
            "public final class Order {",
            "    private final Money amount;",
            "    private final Pattern route;",
            "    public Order(Money amount, Pattern route) {",
            "        this.amount = amount; this.route = route;",
            "    }",
            "}");
        Compilation c = compile(money, order);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("'route' is a java.util.regex.Pattern");
        assertNoneContaining(c.notes(), "'amount' is a");
    }

    /**
     * An interface says nothing about what an implementation supplies, so the
     * note would be a guess. {@code Runnable} is the fixture because - unlike
     * every collection interface - it redeclares nothing, which is what makes
     * the decision reachable at all.
     */
    @Test
    public void memberTypedAsAnInterfaceIsSilent() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Scheduled",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Scheduled {",
            "    private final Runnable task;",
            "    public Scheduled(Runnable task) { this.task = task; }",
            "}"));
        assertThat(c).succeeded();
        assertNoneContaining(c.notes(), "declares no equals of its own");
    }

    // ------------------------------------------------------------------
    // Target shapes
    // ------------------------------------------------------------------

    /**
     * {@code equals} takes an {@code Object}, so there is nothing to bind the
     * target's own parameters to and the cast has to be wildcarded. A raw cast
     * would compile and erase every member on the way through.
     */
    @Test
    public void genericTarget_comparesThroughTheWildcardCast() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Box",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Box<T> {",
            "    private final T value;",
            "    private final T[] items;",
            "    private final long stamp;",
            "    public Box(T value, T[] items, long stamp) {",
            "        this.value = value; this.items = items; this.stamp = stamp;",
            "    }",
            "}"));
        assertThat(c).succeeded();

        Constructor<?> ctor = soleConstructor(Class.forName("demo.Box", true, loadClasses(c)));
        Object a = ctor.newInstance(new Object[]{"v", new String[]{"a", "b"}, 7L});
        Object b = ctor.newInstance(new Object[]{"v", new String[]{"a", "b"}, 7L});

        assertEquals("a T[] erases to an Object[], so it takes the deep row", a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, ctor.newInstance(new Object[]{"w", new String[]{"a", "b"}, 7L}));
        assertNotEquals(a, ctor.newInstance(new Object[]{"v", new String[]{"a", "c"}, 7L}));
        assertNotEquals(a, ctor.newInstance(new Object[]{"v", new String[]{"a", "b"}, 8L}));
    }

    @Test
    public void staticNestedTarget_isSupported() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Holder",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "public class Holder {",
            "    @EqualsAndHashCode",
            "    public static final class Key {",
            "        private final String id;",
            "        public Key(String id) { this.id = id; }",
            "    }",
            "}"));
        assertThat(c).succeeded();

        Constructor<?> ctor =
            soleConstructor(Class.forName("demo.Holder$Key", true, loadClasses(c)));
        assertEquals(ctor.newInstance("a"), ctor.newInstance("a"));
        assertNotEquals(ctor.newInstance("a"), ctor.newInstance("b"));
    }

    /**
     * The outer-instance link is not a member the author declared, and javac has
     * not synthesised it yet when the round runs - so two inner instances from
     * different outers compare on their own state alone.
     */
    @Test
    public void innerClass_comparesItsOwnStateAndNotItsOuterInstance() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Outer",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "public class Outer {",
            "    private final int tag;",
            "    public Outer(int tag) { this.tag = tag; }",
            "    @EqualsAndHashCode",
            "    public final class Inner {",
            "        private final int value;",
            "        public Inner(int value) { this.value = value; }",
            "    }",
            "}"));
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> outerType = Class.forName("demo.Outer", true, cl);
        Class<?> innerType = Class.forName("demo.Outer$Inner", true, cl);
        Constructor<?> outerCtor = soleConstructor(outerType);
        Constructor<?> innerCtor = innerType.getConstructor(outerType, int.class);

        Object left = innerCtor.newInstance(outerCtor.newInstance(1), 5);
        Object right = innerCtor.newInstance(outerCtor.newInstance(2), 5);
        assertEquals("only the declared member is compared", left, right);
        assertNotEquals(left, innerCtor.newInstance(outerCtor.newInstance(1), 6));
    }

    private static JavaFileObject genericOuter(String identity) {
        return JavaFileObjects.forSourceLines("demo.Outer",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "public class Outer<T> {",
            "    private final T tag;",
            "    public Outer(T tag) { this.tag = tag; }",
            "    @EqualsAndHashCode(identity = EqualsAndHashCode.Identity." + identity + ")",
            "    public class Inner {",
            "        private final int v;",
            "        public Inner(int v) { this.v = v; }",
            "    }",
            "    @EqualsAndHashCode(identity = EqualsAndHashCode.Identity." + identity + ")",
            "    public class Pocket<U> {",
            "        private final U held;",
            "        public Pocket(U held) { this.held = held; }",
            "    }",
            "}");
    }

    /**
     * An inner class declares no parameters of its own but is still written in
     * its outer's, so its bare simple name denotes {@code Outer<T>.Inner} - which
     * is neither castable from {@code Object} nor usable in an
     * {@code instanceof}, and javac says so on the inner class's own line. Every
     * identity constant reaches one or the other, and a generic inner class has
     * to keep its own wildcards on top of the ones it inherits.
     */
    @Test
    public void innerClassOfAGenericOuter_comparesUnderEveryIdentity() throws Exception {
        for (String identity : new String[]{"EXACT_CLASS", "INSTANCE_OF", "INSTANCE_OF_CANEQUAL"}) {
            Compilation c = compile(genericOuter(identity));
            assertThat(c).succeeded();

            ClassLoader cl = loadClasses(c);
            Class<?> outerType = Class.forName("demo.Outer", true, cl);
            Object host = soleConstructor(outerType).newInstance("tag");

            Class<?> innerType = Class.forName("demo.Outer$Inner", true, cl);
            Constructor<?> inner = innerType.getConstructor(outerType, int.class);
            Object left = inner.newInstance(host, 5);
            assertEquals(identity, left, inner.newInstance(host, 5));
            assertEquals(identity, left.hashCode(), inner.newInstance(host, 5).hashCode());
            assertNotEquals(identity, left, inner.newInstance(host, 6));
            assertFalse(identity, left.equals("Inner"));

            Class<?> pocketType = Class.forName("demo.Outer$Pocket", true, cl);
            Constructor<?> pocket = pocketType.getConstructor(outerType, Object.class);
            Object held = pocket.newInstance(host, "held");
            assertEquals(identity, held, pocket.newInstance(host, "held"));
            assertNotEquals(identity, held, pocket.newInstance(host, "other"));
        }
    }

    @Test
    public void targetWithNoSelectedMembers_comparesEqualAndSaysSo() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Empty",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Empty {",
            "    public Empty() { }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("every instance of it compares equal to every other");

        Class<?> type = Class.forName("demo.Empty", true, loadClasses(c));
        Object a = soleConstructor(type).newInstance();
        assertEquals(a, soleConstructor(type).newInstance());
        assertEquals("the accumulator's seed, with nothing folded into it", 1, a.hashCode());
    }

    @Test
    public void enumInterfaceAndAnnotationTargetsAreRejected() {
        Compilation anEnum = compile(JavaFileObjects.forSourceLines("demo.Suit",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public enum Suit { HEARTS }"));
        assertThat(anEnum).failed();
        assertThat(anEnum).hadErrorContaining("an enum constant is already unique");

        Compilation anInterface = compile(JavaFileObjects.forSourceLines("demo.Named",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public interface Named { String name(); }"));
        assertThat(anInterface).failed();
        assertThat(anInterface).hadErrorContaining("which declares no state to compare");

        Compilation anAnnotation = compile(JavaFileObjects.forSourceLines("demo.Flagged",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public @interface Flagged { }"));
        assertThat(anAnnotation).failed();
        assertThat(anAnnotation).hadErrorContaining("only classes and records carry the instance state");
    }

    // ------------------------------------------------------------------
    // Alongside @ClassBuilder
    // ------------------------------------------------------------------

    /**
     * The two passes run over one tree and neither knows about the other, so the
     * question worth asking is the one an author asks: does the object a builder
     * hands back compare equal to the one it was seeded from. It only does if
     * the seeding ladder reaches every member the relation reads.
     */
    @Test
    public void classBuilderRoundTrip_comparesEqualToTheOriginal() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Payload",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@ClassBuilder @EqualsAndHashCode",
            "public final class Payload {",
            "    private final String name;",
            "    private final int size;",
            "    private final byte[] blob;",
            "    public Payload(String name, int size, byte[] blob) {",
            "        this.name = name; this.size = size; this.blob = blob;",
            "    }",
            "}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Payload", true, loadClasses(c));
        Constructor<?> ctor = type.getConstructor(String.class, int.class, byte[].class);
        Object original = ctor.newInstance("p", 3, new byte[]{1, 2});
        Object rebuilt = roundTrip(type, original);

        assertEquals("from(instance).build() must reproduce the object", original, rebuilt);
        assertEquals(original.hashCode(), rebuilt.hashCode());
        assertNotEquals(original, ctor.newInstance("p", 4, new byte[]{1, 2}));
    }

    @Test
    public void classBuilderRecordRoundTrip_comparesEqualToTheOriginal() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Ticket",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@ClassBuilder @EqualsAndHashCode",
            "public record Ticket(String code, int seat, byte[] stub) {}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Ticket", true, loadClasses(c));
        Constructor<?> ctor = type.getConstructor(String.class, int.class, byte[].class);
        Object original = ctor.newInstance("t", 7, new byte[]{3, 4});
        Object rebuilt = roundTrip(type, original);

        assertEquals("a record seeds through its canonical accessors", original, rebuilt);
        assertEquals(original.hashCode(), rebuilt.hashCode());
        assertNotEquals(original, ctor.newInstance("t", 8, new byte[]{3, 4}));
    }

    // ------------------------------------------------------------------
    // Markers on the emitted members
    // ------------------------------------------------------------------

    @Test
    public void emittedPairCarriesItsContractsAndTheGeneratedMarker() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Contracted",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode",
            "public final class Contracted {",
            "    private final int x;",
            "    public Contracted(int x) { this.x = x; }",
            "}"));
        assertThat(c).succeeded();

        Marked marked = read(c, "demo.Contracted");
        Ann equalsContract = annotationOn(marked.methods, "equals(Ljava/lang/Object;)Z", XCONTRACT);
        assertNotNull("equals carries a contract", equalsContract);
        assertEquals("null -> false", equalsContract.values.get("value"));
        assertEquals(Boolean.TRUE, equalsContract.values.get("pure"));

        Ann hashContract = annotationOn(marked.methods, "hashCode()I", XCONTRACT);
        assertNotNull("hashCode carries a contract", hashContract);
        assertNull("with nothing to say about its arguments", hashContract.values.get("value"));
        assertEquals(Boolean.TRUE, hashContract.values.get("pure"));

        assertNotNull(annotationOn(marked.methods, "equals(Ljava/lang/Object;)Z", GENERATED));
        assertNotNull(annotationOn(marked.methods, "hashCode()I", GENERATED));
        assertNull("the author's own constructor is not claimed",
            annotationOn(marked.methods, "<init>(I)V", GENERATED));
    }

    /**
     * A memoizing {@code hashCode} writes to a field, so the purity claim comes
     * off rather than being emitted alongside the write that contradicts it.
     */
    @Test
    public void cacheHashCode_dropsThePurityContractAndMarksTheMemo() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Hooked",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL,",
            "                   cacheHashCode = true)",
            "public class Hooked {",
            "    private final int x;",
            "    public Hooked(int x) { this.x = x; }",
            "}"));
        assertThat(c).succeeded();

        Marked marked = read(c, "demo.Hooked");
        assertNull("a memoizing hashCode is not pure",
            annotationOn(marked.methods, "hashCode()I", XCONTRACT));
        assertNotNull("but it is still generated",
            annotationOn(marked.methods, "hashCode()I", GENERATED));

        Ann hook = annotationOn(marked.methods, "canEqual(Ljava/lang/Object;)Z", XCONTRACT);
        assertNotNull("the hook carries the same contract equals does", hook);
        assertEquals("null -> false", hook.values.get("value"));
        assertEquals(Boolean.TRUE, hook.values.get("pure"));
        assertNotNull(annotationOn(marked.methods, "canEqual(Ljava/lang/Object;)Z", GENERATED));
        assertNotNull("the memo field is generated too",
            annotationOn(marked.fields, "$hashCode", GENERATED));
    }

    @Test
    public void emitContractsAndEmitGeneratedFalse_attachNothing() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Bare",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@EqualsAndHashCode(emitContracts = false, emitGenerated = false)",
            "public final class Bare {",
            "    private final int x;",
            "    public Bare(int x) { this.x = x; }",
            "}"));
        assertThat(c).succeeded();

        Marked marked = read(c, "demo.Bare");
        for (String key : new String[]{"equals(Ljava/lang/Object;)Z", "hashCode()I"}) {
            assertNull("no contract on " + key, annotationOn(marked.methods, key, XCONTRACT));
            assertNull("no marker on " + key, annotationOn(marked.methods, key, GENERATED));
        }
    }

    // ------------------------------------------------------------------
    // Reading class-retention annotations back off the bytes
    // ------------------------------------------------------------------

    /**
     * {@code @XContract} and {@code @Generated} both have {@code @Retention(CLASS)},
     * so reflection cannot observe either. Parsing the class file reaches the
     * {@code RuntimeInvisibleAnnotations} attribute where they actually live.
     */
    private static Marked read(Compilation c, String className) throws IOException {
        byte[] bytes = findClassBytes(c, className);
        assertNotNull("expected class-file output for " + className, bytes);
        Marked out = new Marked();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                List<Ann> slot = new ArrayList<>();
                out.methods.put(name + descriptor, slot);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        return collect(slot, annotation);
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                List<Ann> slot = new ArrayList<>();
                out.fields.put(name, slot);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        return collect(slot, annotation);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return out;
    }

    private static AnnotationVisitor collect(List<Ann> slot, String descriptor) {
        Ann ann = new Ann(descriptor);
        slot.add(ann);
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                ann.values.put(name, value);
            }
        };
    }

    private static Ann annotationOn(Map<String, List<Ann>> members, String key, String descriptor) {
        List<Ann> found = members.get(key);
        assertNotNull("no member '" + key + "'; saw " + members.keySet(), found);
        for (Ann ann : found) {
            if (ann.descriptor.equals(descriptor)) return ann;
        }
        return null;
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

    /** Every annotation found on one class's methods and fields, keyed by member. */
    private static final class Marked {
        final Map<String, List<Ann>> methods = new LinkedHashMap<>();
        final Map<String, List<Ann>> fields = new LinkedHashMap<>();
    }

    /** One annotation, with whatever attributes it was written with. */
    private static final class Ann {
        final String descriptor;
        final Map<String, Object> values = new LinkedHashMap<>();

        Ann(String descriptor) { this.descriptor = descriptor; }
    }

}
