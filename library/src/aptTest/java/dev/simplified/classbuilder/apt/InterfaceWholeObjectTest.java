package dev.simplified.classbuilder.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The whole-object members on the interface target path, which are written
 * straight into the emitted {@code <Name>Impl} rather than mutated into it by a
 * later round. That class is the only one a caller ever holds, so an
 * {@code @EqualsAndHashCode} or {@code @ToString} on the interface either
 * reaches it here or does nothing at all.
 *
 * <p>Source assertions cover the shapes; the reflective ones cover the two
 * claims a string match cannot make - that an array-carrying impl is equal to
 * its twin, and that its hash agrees.
 */
public class InterfaceWholeObjectTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static String generatedSource(Compilation compilation, String fqn) {
        Optional<JavaFileObject> out = compilation.generatedSourceFile(fqn);
        if (out.isEmpty()) {
            throw new AssertionError("expected generated source '" + fqn + "' - generated files: "
                + compilation.generatedFiles());
        }
        try {
            return out.get().getCharContent(false).toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("interface-whole-object-test");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            // URI shape: mem:///CLASS_OUTPUT/demo/ShapeImpl.class
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
            InterfaceWholeObjectTest.class.getClassLoader());
    }

    /**
     * Fails when any diagnostic carries the needle.
     *
     * <p>Not a zero count: javac always emits its own "source version
     * RELEASE_17" warning for this processor, so an absence assertion has to be
     * scoped to the diagnostic under test.
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
    // Neither annotation: the members the path has always emitted
    // ------------------------------------------------------------------

    @Test
    public void neitherAnnotation_emitsEveryAccessorNamedInSquareBrackets() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Shape {",
            "    String name();",
            "    int sides();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.ShapeImpl");
        assertTrue(impl, impl.contains(
            "if (!java.util.Objects.equals(this.name, other.name)) return false;"));
        assertTrue(impl, impl.contains("if (this.sides != other.sides) return false;"));
        assertTrue(impl, impl.contains("        return \"ShapeImpl[\"\n"
            + "            + \"name=\" + this.name\n"
            + "            + \", sides=\" + this.sides\n"
            + "            + \"]\";"));
        // The accumulator the mutation path emits, not a second hash.
        assertTrue(impl, impl.contains("final int PRIME = 59;"));
        assertTrue(impl, impl.contains("int result = 1;"));
        assertTrue(impl, impl.contains("result = result * PRIME + this.sides;"));
    }

    // ------------------------------------------------------------------
    // Arrays: read by content in all three members
    // ------------------------------------------------------------------

    @Test
    public void arrayAccessor_readByContentInEveryMember() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Frame",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Frame {",
            "    byte[] data();",
            "    String[][] rows();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.FrameImpl");
        // Fully qualified and unimported, so a field of the same simple name
        // cannot obscure the type - see helpersAreFullyQualified below.
        assertFalse(impl, impl.contains("import java.util.Arrays;"));
        assertTrue(impl, impl.contains(
            "if (!java.util.Arrays.equals(this.data, other.data)) return false;"));
        assertTrue(impl, impl.contains(
            "if (!java.util.Arrays.deepEquals(this.rows, other.rows)) return false;"));
        assertTrue(impl, impl.contains(
            "result = result * PRIME + java.util.Arrays.hashCode(this.data);"));
        assertTrue(impl, impl.contains(
            "result = result * PRIME + java.util.Arrays.deepHashCode(this.rows);"));
        assertTrue(impl, impl.contains("+ \"data=\" + java.util.Arrays.toString(this.data)"));
        assertTrue(impl, impl.contains("+ \", rows=\" + java.util.Arrays.deepToString(this.rows)"));
        // The reference comparison this phase replaced.
        assertFalse(impl, impl.contains("Objects.equals(this.data"));
    }

    /**
     * A field name obscures a type of the same simple name, and these field
     * names are the interface's accessor names verbatim - so every helper the
     * three members call has to be written out in full.
     */
    @Test
    public void helpersAreFullyQualified_soAnAccessorMayCarryTheirNames() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Shadow",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Shadow {",
            "    byte[] Arrays();",
            "    String Objects();",
            "    float Float();",
            "    double Double();",
            "}");
        Compilation c = compile(src);
        // The claim is the compile itself: every one of these read as a field
        // access on the obscuring member before, and none of the four resolved.
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.ShadowImpl");
        assertTrue(impl, impl.contains("java.util.Arrays.equals(this.Arrays, other.Arrays)"));
        assertTrue(impl, impl.contains("java.util.Objects.equals(this.Objects, other.Objects)"));
        assertTrue(impl, impl.contains("java.lang.Float.compare(this.Float, other.Float) != 0"));
        assertTrue(impl, impl.contains("java.lang.Double.compare(this.Double, other.Double) != 0"));
        assertTrue(impl, impl.contains("java.lang.Float.floatToIntBits(this.Float)"));
        assertTrue(impl, impl.contains("java.lang.Double.doubleToLongBits(this.Double)"));
    }

    // ------------------------------------------------------------------
    // Generic targets
    // ------------------------------------------------------------------

    /**
     * The {@code instanceof} pattern needs one wildcard per type parameter. A
     * single {@code <?>} on a two-parameter interface is a wrong-arity
     * reference, and the file it lands in is one a consumer cannot edit.
     */
    @Test
    public void twoParameterInterface_castsThroughOneWildcardPerParameter() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Pair",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public interface Pair<K, V> {",
                "    K key();",
                "    V value();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UsePair",
                "package demo;",
                "public class UsePair {",
                "    public static Pair<String, Integer> of(String k, Integer v) {",
                "        return Pair.<String, Integer>builder().key(k).value(v).build();",
                "    }",
                "}"));
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.PairImpl");
        assertTrue(impl, impl.contains("if (!(o instanceof PairImpl<?, ?> other)) return false;"));

        ClassLoader cl = loadClasses(c);
        Method of = Class.forName("demo.UsePair", true, cl)
            .getMethod("of", String.class, Integer.class);
        assertEquals(of.invoke(null, "a", 1), of.invoke(null, "a", 1));
        assertFalse(of.invoke(null, "a", 1).equals(of.invoke(null, "a", 2)));
        assertEquals("PairImpl[key=a, value=1]", of.invoke(null, "a", 1).toString());
    }

    @Test
    public void arrayAccessor_twoImplsFromEqualBuildersAgree() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Frame",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public interface Frame {",
                "    byte[] data();",
                "    double scale();",
                "    String label();",
                "}"),
            // Driven from compiled source rather than reflection: the Impl is
            // package-private, and the builder's own setters are the shapes
            // under test rather than something to reproduce by hand.
            JavaFileObjects.forSourceLines("demo.UseFrame",
                "package demo;",
                "public class UseFrame {",
                "    public static Frame of(byte[] data, double scale, String label) {",
                "        return Frame.builder().data(data).scale(scale).label(label).build();",
                "    }",
                "}"));
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Method of = Class.forName("demo.UseFrame", true, cl)
            .getMethod("of", byte[].class, double.class, String.class);
        Object first = of.invoke(null, new byte[]{1, 2, 3}, 2.5d, "left");
        Object second = of.invoke(null, new byte[]{1, 2, 3}, 2.5d, "left");
        Object third = of.invoke(null, new byte[]{1, 2, 4}, 2.5d, "left");

        assertEquals("distinct arrays of equal content must compare equal", first, second);
        assertEquals("equal instances must agree on their hash", first.hashCode(), second.hashCode());
        assertFalse("differing content must compare unequal", first.equals(third));
        assertEquals("FrameImpl[data=[1, 2, 3], scale=2.5, label=left]", first.toString());
    }

    // ------------------------------------------------------------------
    // Narrowing: of, exclude, and the markers
    // ------------------------------------------------------------------

    /**
     * An accessor name is the {@code FieldSpec} name is the {@code Impl} field
     * name, so a narrowing attribute written against the interface lands on the
     * generated field with no translation.
     */
    @Test
    public void of_andTheToStringMarker_narrowIndependently() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Node",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.ToStringExclude;",
            "@ClassBuilder(validate = false)",
            "@EqualsAndHashCode(of = \"id\")",
            "public interface Node {",
            "    String id();",
            "    @ToStringExclude String secret();",
            "    int weight();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.NodeImpl");
        // Every accessor is still a field - narrowing governs the members, not
        // the state the builder populates.
        assertTrue(impl, impl.contains("private final String secret;"));

        assertTrue(impl, impl.contains(
            "if (!java.util.Objects.equals(this.id, other.id)) return false;"));
        assertFalse("'of' must drop weight from equals\n" + impl,
            impl.contains("this.weight != other.weight"));
        assertFalse("'of' must drop secret from hashCode\n" + impl,
            impl.contains("PRIME + (this.secret"));

        assertTrue("the marker is @ToString's alone, so id still prints\n" + impl,
            impl.contains("+ \"id=\" + this.id"));
        assertTrue("and so does weight\n" + impl, impl.contains("+ \", weight=\" + this.weight"));
        assertFalse("but the marked accessor does not\n" + impl, impl.contains("secret=\""));
    }

    @Test
    public void exclude_andTheEqualsMarker_removeMembers() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Entry",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsExclude;",
            "import dev.simplified.annotations.ToString;",
            "@ClassBuilder(validate = false)",
            "@EqualsAndHashCode",
            "@ToString(exclude = \"cached\")",
            "public interface Entry {",
            "    String key();",
            "    @EqualsExclude long stamp();",
            "    String cached();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.EntryImpl");
        assertTrue(impl, impl.contains(
            "if (!java.util.Objects.equals(this.key, other.key)) return false;"));
        assertFalse("the marker must drop stamp from equals\n" + impl,
            impl.contains("this.stamp != other.stamp"));
        assertTrue("but it stays printed\n" + impl, impl.contains("+ \", stamp=\" + this.stamp"));
        assertFalse("'exclude' must drop cached from toString\n" + impl,
            impl.contains("cached=\""));
        assertTrue("and leave it compared\n" + impl,
            impl.contains("if (!java.util.Objects.equals(this.cached, other.cached)) return false;"));
    }

    /**
     * A name left behind by a rename is the defect the check exists for: the
     * member it used to reach silently rejoins or leaves the relation.
     */
    @Test
    public void of_namingNoAccessor_isAnError() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Stale",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@ClassBuilder(validate = false)",
            "@EqualsAndHashCode(of = \"identifier\")",
            "public interface Stale {",
            "    String id();",
            "}");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("@EqualsAndHashCode(of) names 'identifier'");
    }

    // ------------------------------------------------------------------
    // The include marker
    // ------------------------------------------------------------------

    /**
     * Inclusion cannot add a member here - every accessor is one already - but
     * the two attributes {@code @ToStringInclude} carries are pure formatting
     * and reach the Impl unchanged. Dropping them would leave the same
     * annotation meaning two different things by target kind.
     */
    @Test
    public void toStringInclude_renamesAndReorders() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Ping",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.ToString;",
                "import dev.simplified.annotations.ToStringInclude;",
                "@ClassBuilder(validate = false)",
                "@ToString",
                "public interface Ping {",
                "    String host();",
                "    @ToStringInclude(name = \"motd\", rank = 10) String message();",
                "    int players();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UsePing",
                "package demo;",
                "public class UsePing {",
                "    public static Ping of(String host, String message, int players) {",
                "        return Ping.builder().host(host).message(message).players(players).build();",
                "    }",
                "}"));
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Object ping = Class.forName("demo.UsePing", true, cl)
            .getMethod("of", String.class, String.class, int.class)
            .invoke(null, "localhost", "hi", 3);
        assertEquals("PingImpl[motd=hi, host=localhost, players=3]", ping.toString());
    }

    /**
     * The marker alone configures nothing on this path, which is exactly why it
     * has to be read: without it the interface takes the unconfigured plan and
     * the rename is dropped with no annotation on the type to hint otherwise.
     */
    @Test
    public void toStringInclude_isReadWithoutTheAnnotationItself() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Solo",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.ToStringInclude;",
            "@ClassBuilder(validate = false)",
            "public interface Solo {",
            "    @ToStringInclude(name = \"ident\") String id();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertTrue(generatedSource(c, "demo.SoloImpl").contains("+ \"ident=\" + this.id"));
    }

    /**
     * A marker that changed nothing is reported rather than honoured silently -
     * an author who wrote it to add a member back is owed the news that the
     * member was never out.
     */
    @Test
    public void includeMarkerOnAnAccessor_isANoteRatherThanAnError() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bare",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsInclude;",
            "import dev.simplified.annotations.ToStringInclude;",
            "@ClassBuilder(validate = false)",
            "public interface Bare {",
            "    @EqualsInclude @ToStringInclude String id();",
            "    int size();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@EqualsInclude on 'id' selects nothing new");
        assertThat(c).hadNoteContaining("@ToStringInclude on 'id' selects nothing new");
        // Still both members, and still under their own names.
        String impl = generatedSource(c, "demo.BareImpl");
        assertTrue(impl, impl.contains("+ \"id=\" + this.id"));
        assertTrue(impl, impl.contains("+ \", size=\" + this.size"));
    }

    /**
     * A derived value is the one thing inclusion genuinely adds on a class, and
     * the Impl holds no field for it. Reported rather than dropped: there is no
     * reading of the request the generated class fulfils.
     */
    @Test
    public void includeMarkerOnADerivedMethod_isAnError() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Derived",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsInclude;",
            "import dev.simplified.annotations.ToString;",
            "import dev.simplified.annotations.ToStringInclude;",
            "@ClassBuilder(validate = false)",
            "@ToString",
            "public interface Derived {",
            "    int width();",
            "    int height();",
            "    @ToStringInclude @EqualsInclude default int area() { return width() * height(); }",
            "}");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining(
            "'area' is not among the accessors DerivedImpl is built from, so @ToString cannot "
                + "include it");
        assertThat(c).hadErrorContaining(
            "'area' is not among the accessors DerivedImpl is built from, so @EqualsAndHashCode "
                + "cannot include it");
    }

    /** The shapes the class path refuses outright, refused here in its words. */
    @Test
    public void includeMarkerOnAnUnusableMethod_mirrorsTheClassPathsRefusal() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Odd",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.ToString;",
            "import dev.simplified.annotations.ToStringInclude;",
            "@ClassBuilder(validate = false)",
            "@ToString",
            "public interface Odd {",
            "    int width();",
            "    @ToStringInclude default void wipe() { }",
            "    @ToStringInclude default int scaled(int by) { return width() * by; }",
            "    @ToStringInclude static int origin() { return 0; }",
            "}"));
        assertThat(c).hadErrorContaining("'wipe' returns void, so it produces no value for @ToString");
        assertThat(c).hadErrorContaining("'scaled' takes parameters, so @ToString has nothing to pass it");
        assertThat(c).hadErrorContaining("'origin' is static, so it holds no per-instance value");
    }

    /**
     * Two markers saying opposite things about one member. The class path fails
     * the build; resolving it silently here would make the same pair mean two
     * different things by target kind.
     */
    @Test
    public void bothMarkersOnOneAccessor_isTheSameErrorTheClassPathRaises() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Clash",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.EqualsExclude;",
            "import dev.simplified.annotations.EqualsInclude;",
            "@ClassBuilder(validate = false)",
            "@EqualsAndHashCode",
            "public interface Clash {",
            "    String kept();",
            "    @EqualsExclude @EqualsInclude String both();",
            "}");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining(
            "'both' carries both the include and exclude markers for @EqualsAndHashCode - keep one");
    }

    // ------------------------------------------------------------------
    // The limits of generated equality
    // ------------------------------------------------------------------

    /**
     * The one limitation of the feature a reader cannot infer from the
     * annotation, and it holds an interface accessor exactly as it holds a
     * field. Silence here would mean the same member type warned on a class and
     * not on the interface it was extracted from.
     */
    @Test
    public void containerOfArrayAccessor_warnsAsTheFieldWould() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Warn",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import java.util.Collection;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "@EqualsAndHashCode",
            "public interface Warn {",
            "    List<byte[]> chunks();",
            "    Collection<String> bare();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("'chunks' is a java.util.List<byte[]>");
        assertThat(c).hadWarningContaining("'bare' is declared java.util.Collection");
    }

    /** An interface carrying neither annotation stays quiet, as a plain class does. */
    @Test
    public void containerOfArrayAccessor_staysQuietWhenNothingWasAnnotated() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Quiet",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public interface Quiet {",
            "    List<byte[]> chunks();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertNoneContaining(c.warnings(), "'chunks' is a");
    }

    // ------------------------------------------------------------------
    // @ToString formatting
    // ------------------------------------------------------------------

    @Test
    public void toStringStyleAndFieldNames_reachTheImpl() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Point",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.ToString;",
                "@ClassBuilder(validate = false)",
                "@ToString(style = ToString.Style.LOMBOK, includeFieldNames = false)",
                "public interface Point {",
                "    int x();",
                "    int y();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UsePoint",
                "package demo;",
                "public class UsePoint {",
                "    public static Point of(int x, int y) {",
                "        return Point.builder().x(x).y(y).build();",
                "    }",
                "}"));
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.PointImpl");
        assertTrue(impl, impl.contains("        return \"PointImpl(\"\n"
            + "            + this.x\n"
            + "            + \", \" + this.y\n"
            + "            + \")\";"));

        ClassLoader cl = loadClasses(c);
        Object point = Class.forName("demo.UsePoint", true, cl)
            .getMethod("of", int.class, int.class).invoke(null, 3, 4);
        assertEquals("PointImpl(3, 4)", point.toString());
    }

    // ------------------------------------------------------------------
    // Attributes an interface target cannot honour
    // ------------------------------------------------------------------

    /**
     * Four attributes describe a relationship the emitted Impl does not have.
     * Each is reported rather than dropped, since a written attribute that
     * changes nothing is exactly what a reader will not think to check.
     */
    @Test
    public void inapplicableAttributes_areReported() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Tag",
            "package demo;",
            "import dev.simplified.annotations.CallSuper;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "@ClassBuilder(validate = false)",
            "@EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF,",
            "    callSuper = CallSuper.YES, useAccessors = true, cacheHashCode = true)",
            "public interface Tag {",
            "    String label();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        assertThat(c).hadNoteContaining("@EqualsAndHashCode(identity) does not apply");
        assertThat(c).hadNoteContaining("@EqualsAndHashCode(callSuper) does not apply");
        assertThat(c).hadNoteContaining("@EqualsAndHashCode(useAccessors) does not apply");
        assertThat(c).hadNoteContaining("@EqualsAndHashCode(cacheHashCode) does not apply");

        // The Impl is emitted regardless, on the relation all three identities
        // agree on for a final class extending Object.
        String impl = generatedSource(c, "demo.TagImpl");
        assertTrue(impl, impl.contains("if (!(o instanceof TagImpl other)) return false;"));
        assertFalse("nothing may call up to Object.equals\n" + impl, impl.contains("super.equals"));
        assertFalse("and no memo field is declared\n" + impl, impl.contains("$hashCode"));
    }

    /**
     * Both annotations, so each row of the report is pinned to its own label -
     * a mistake confined to one of them survives a fixture carrying the other.
     */
    @Test
    public void generateImplFalse_reportsThatTheAnnotationHasNowhereToLand() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Handle",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.ToString;",
            "@ClassBuilder(validate = false, generateImpl = false, factoryMethod = \"create\")",
            "@EqualsAndHashCode",
            "@ToString",
            "public interface Handle {",
            "    String id();",
            "    static Handle create(String id) {",
            "        return new Handle() { public String id() { return id; } };",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining(
            "@ToString has no effect on Handle, which sets @ClassBuilder(generateImpl = false)");
        assertThat(c).hadWarningContaining(
            "@EqualsAndHashCode has no effect on Handle, which sets "
                + "@ClassBuilder(generateImpl = false)");
    }

    /**
     * The Impl is emitted here and still never constructed: a set
     * factoryMethod diverts {@code build()} whatever {@code generateImpl} says,
     * so the configured members sit on a class no caller holds. The author's
     * only symptom otherwise is an identity hash where they configured a
     * rendering.
     */
    @Test
    public void factoryMethod_reportsThatTheAnnotationHasNowhereToLand() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Token",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.ToString;",
            "@ClassBuilder(validate = false, factoryMethod = \"create\")",
            "@EqualsAndHashCode",
            "@ToString(style = ToString.Style.LOMBOK)",
            "public interface Token {",
            "    String id();",
            "    static Token create(String id) {",
            "        return new Token() { public String id() { return id; } };",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining(
            "@ToString has no effect on Token, which sets @ClassBuilder(factoryMethod = "
                + "\"create\"), so build() returns what that factory produces rather than TokenImpl");
        assertThat(c).hadWarningContaining("@EqualsAndHashCode has no effect on Token");
        // Emitted regardless - the author's own factory may construct it.
        assertTrue(generatedSource(c, "demo.TokenImpl").contains("final class TokenImpl"));
    }

    /** No factory, no suppression, nothing to report. */
    @Test
    public void theOrdinaryInterface_reportsNothingAboutWhereTheAnnotationLands() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Plain",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.ToString;",
            "@ClassBuilder(validate = false)",
            "@ToString",
            "public interface Plain {",
            "    String id();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertNoneContaining(c.warnings(), "has no effect on Plain");
    }

}
