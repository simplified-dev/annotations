package dev.simplified.tostring.mutate;

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

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round trips for the {@code @ToString} mutation pass.
 *
 * <p>Every assertion is on the produced string rather than on the emitted tree,
 * because the string is the whole contract: the member's name is fixed by the
 * language, nothing resolves against its signature, and the only thing an
 * author can observe is what comes out of it.
 */
public class ToStringTest {

    private static final String XCONTRACT_DESCRIPTOR = "Ldev/simplified/annotations/XContract;";
    private static final String GENERATED_DESCRIPTOR = "Ldev/simplified/annotations/Generated;";

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("tostring-mutate-test");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            // URI shape: mem:///CLASS_OUTPUT/demo/Bean.class
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, ToStringTest.class.getClassLoader());
    }

    /** Loads a compiled target, instantiates it through its single public constructor, and renders. */
    private static String rendered(Compilation compilation, String binaryName, Object... args)
        throws Exception {
        Class<?> type = Class.forName(binaryName, true, loadClasses(compilation));
        return type.getConstructors()[0].newInstance(args).toString();
    }

    /** {@code from(instance)} then {@code build()} - the round trip a builder promises. */
    private static Object roundTrip(Class<?> target, Object instance) throws Exception {
        Object builder = target.getMethod("from", target).invoke(null, instance);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    // ------------------------------------------------------------------
    // The default shape
    // ------------------------------------------------------------------

    @Test
    public void defaultShape_printsEveryMemberWithItsName() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Name",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString",
            "public class Name {",
            "    private final int a;",
            "    private final String b;",
            "    private final Object c;",
            "    public Name(int a, String b, Object c) { this.a = a; this.b = b; this.c = c; }",
            "}"));
        assertThat(c).succeeded();

        assertEquals("Name[a=1, b=hello, c=null]", rendered(c, "demo.Name", 1, "hello", null));
    }

    // ------------------------------------------------------------------
    // Arrays
    // ------------------------------------------------------------------

    private static JavaFileObject arrays() {
        return JavaFileObjects.forSourceLines("demo.Arrays3",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString",
            "public class Arrays3 {",
            "    private final byte[] flat;",
            "    private final int[][] nested;",
            "    private final String[] refs;",
            "    public Arrays3(byte[] flat, int[][] nested, String[] refs) {",
            "        this.flat = flat; this.nested = nested; this.refs = refs;",
            "    }",
            "}");
    }

    @Test
    public void arrayMembers_printTheirContentsRatherThanAnIdentityHash() throws Exception {
        Compilation c = compile(arrays());
        assertThat(c).succeeded();

        String out = rendered(c, "demo.Arrays3",
            new byte[]{1, 2},
            new int[][]{{3, 4}, {5}},
            new String[]{"x", "y"});
        assertEquals("Arrays3[flat=[1, 2], nested=[[3, 4], [5]], refs=[x, y]]", out);
    }

    @Test
    public void nullArrayMembers_printNullRatherThanThrowing() throws Exception {
        Compilation c = compile(arrays());
        assertThat(c).succeeded();

        // Arrays.toString and Arrays.deepToString both answer "null" for a null
        // argument, so the array rows need no null guard of their own - and a
        // regression that reached for .length instead would throw here.
        assertEquals("Arrays3[flat=null, nested=null, refs=null]",
            rendered(c, "demo.Arrays3", null, null, null));
    }

    // ------------------------------------------------------------------
    // Selection: transient kept, static never
    // ------------------------------------------------------------------

    /**
     * The one place the two annotations sharing a selector genuinely differ, so
     * both halves are asserted on one type: the {@code transient} member must
     * appear in the rendered string and must not affect equality.
     *
     * <p>Written this way deliberately. A later change that hands this pass the
     * equality predicate fails the first assertion, and one that hands the
     * equality pass this predicate fails the second - either direction is a
     * silent correctness bug with no compile signal.
     */
    @Test
    public void transientMemberIsKeptHereAndDroppedFromEquality() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Session",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.ToString;",
            "@ToString @EqualsAndHashCode",
            "public class Session {",
            "    static String SHARED = \"shared\";",
            "    private final int id;",
            "    private final transient String note;",
            "    public Session(int id, String note) { this.id = id; this.note = note; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Session", true, loadClasses(c));
        Object a = type.getConstructors()[0].newInstance(1, "hello");
        Object b = type.getConstructors()[0].newInstance(1, "goodbye");

        assertEquals("the transient member belongs in the dump and the static one never does",
            "Session[id=1, note=hello]", a.toString());
        assertEquals("Session[id=1, note=goodbye]", b.toString());
        assertEquals("equality must not see the transient member this dump prints", a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ------------------------------------------------------------------
    // Rendered shape
    // ------------------------------------------------------------------

    private static JavaFileObject pair(String annotation) {
        return JavaFileObjects.forSourceLines("demo.Pair",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            annotation,
            "public class Pair {",
            "    private final int left;",
            "    private final String right;",
            "    public Pair(int left, String right) { this.left = left; this.right = right; }",
            "}");
    }

    @Test
    public void includeFieldNamesFalse_printsBareValues() throws Exception {
        Compilation c = compile(pair("@ToString(includeFieldNames = false)"));
        assertThat(c).succeeded();

        assertEquals("Pair[1, two]", rendered(c, "demo.Pair", 1, "two"));
    }

    @Test
    public void styleLombok_swapsTheBracketsForParentheses() throws Exception {
        Compilation c = compile(pair("@ToString(style = ToString.Style.LOMBOK)"));
        assertThat(c).succeeded();

        assertEquals("Pair(left=1, right=two)", rendered(c, "demo.Pair", 1, "two"));
    }

    // ------------------------------------------------------------------
    // callSuper
    // ------------------------------------------------------------------

    @Test
    public void callSuperAuto_resolvesNoOnAnObjectSubclass() throws Exception {
        Compilation c = compile(pair("@ToString"));
        assertThat(c).succeeded();
        // Deliberately silent. The note exists because AUTO can flip from NO to
        // YES when a superclass in another artifact gains the annotation, and a
        // type with no superclass to gain it can never flip - so reporting here
        // would put a message on nearly every annotated type in a codebase. The
        // rendered output is what proves the resolution: a super call would
        // prepend Object's own toString.
        for (var note : c.notes()) {
            String message = note.getMessage(null);
            if (message != null && message.contains("resolved callSuper")) {
                fail("expected no callSuper resolution note on an Object subclass, but got: "
                    + message);
            }
        }
        assertEquals("Pair[left=1, right=two]", rendered(c, "demo.Pair", 1, "two"));
    }

    @Test
    public void callSuperAuto_resolvesYesWhenTheSuperclassCarriesTheAnnotation() throws Exception {
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Parent",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString",
            "public class Parent {",
            "    private final int a;",
            "    public Parent(int a) { this.a = a; }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Child",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString",
            "public class Child extends Parent {",
            "    private final int b;",
            "    public Child(int a, int b) { super(a); this.b = b; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining(
            "@ToString resolved callSuper = YES - Parent carries @ToString");

        assertEquals("the superclass dump leads, so the inherited state is not silently dropped",
            "Child[super=Parent[a=1], b=2]", rendered(c, "demo.Child", 1, 2));
    }

    @Test
    public void callSuperYesOnAnObjectSubclassIsAnError() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Rootless",
            "package demo;",
            "import dev.simplified.annotations.CallSuper;",
            "import dev.simplified.annotations.ToString;",
            "@ToString(callSuper = CallSuper.YES)",
            "public class Rootless {",
            "    private final int a;",
            "    public Rootless(int a) { this.a = a; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining(
            "@ToString(callSuper = YES) on Rootless, whose superclass supplies no implementation");
    }

    // ------------------------------------------------------------------
    // Narrowing and the marker pair
    // ------------------------------------------------------------------

    private static JavaFileObject triple(String annotation) {
        return JavaFileObjects.forSourceLines("demo.Triple",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            annotation,
            "public class Triple {",
            "    private final int a;",
            "    private final int b;",
            "    private final int c;",
            "    public Triple(int a, int b, int c) { this.a = a; this.b = b; this.c = c; }",
            "}");
    }

    @Test
    public void of_printsOnlyTheNamedMembers() throws Exception {
        Compilation c = compile(triple("@ToString(of = {\"a\", \"c\"})"));
        assertThat(c).succeeded();

        assertEquals("Triple[a=1, c=3]", rendered(c, "demo.Triple", 1, 2, 3));
    }

    @Test
    public void exclude_dropsTheNamedMember() throws Exception {
        Compilation c = compile(triple("@ToString(exclude = \"b\")"));
        assertThat(c).succeeded();

        assertEquals("Triple[a=1, c=3]", rendered(c, "demo.Triple", 1, 2, 3));
    }

    /**
     * The wrong turn the attribute invites: a derived value is reachable, but
     * only once the method carries the marker, and the bare report never said so.
     */
    @Test
    public void of_namingAnUnmarkedMethodNamesTheMarker() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Arealess",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString(of = \"area\")",
            "public class Arealess {",
            "    private final int width;",
            "    public Arealess(int width) { this.width = width; }",
            "    public int area() { return this.width * this.width; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining(
            "@ToString(of) names 'area', which is a method rather than a field - "
                + "mark it @ToStringInclude to make it a member");
    }

    /** A shape the marker could not rescue keeps the report that names no remedy. */
    @Test
    public void of_namingNothingKeepsThePlainReport() {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Missing",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString(of = \"widht\")",
            "public class Missing {",
            "    private final int width;",
            "    public Missing(int width) { this.width = width; }",
            "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining(
            "names 'widht', which is not a member this selection reaches");
    }

    @Test
    public void excludeMarker_dropsTheFieldItSitsOn() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Ping",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "import dev.simplified.annotations.ToStringExclude;",
            "@ToString",
            "public class Ping {",
            "    private final String motd;",
            "    @ToStringExclude private final String favicon;",
            "    public Ping(String motd, String favicon) {",
            "        this.motd = motd; this.favicon = favicon;",
            "    }",
            "}"));
        assertThat(c).succeeded();

        assertEquals("Ping[motd=hi]", rendered(c, "demo.Ping", "hi", "data:image/png;base64,AAAA"));
    }

    @Test
    public void includeMarkerName_relabelsTheMember() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Renamed",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "import dev.simplified.annotations.ToStringInclude;",
            "@ToString",
            "public class Renamed {",
            "    @ToStringInclude(name = \"identifier\") private final int id;",
            "    private final String tag;",
            "    public Renamed(int id, String tag) { this.id = id; this.tag = tag; }",
            "}"));
        assertThat(c).succeeded();

        assertEquals("Renamed[identifier=7, tag=t]", rendered(c, "demo.Renamed", 7, "t"));
    }

    @Test
    public void includeMarkerRank_reordersTheOutput() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Ranked",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "import dev.simplified.annotations.ToStringInclude;",
            "@ToString",
            "public class Ranked {",
            "    private final int a;",
            "    @ToStringInclude(rank = 5) private final int b;",
            "    private final int c;",
            "    public Ranked(int a, int b, int c) { this.a = a; this.b = b; this.c = c; }",
            "}"));
        assertThat(c).succeeded();

        assertEquals("a higher rank leads, and the unranked members keep declaration order",
            "Ranked[b=2, a=1, c=3]", rendered(c, "demo.Ranked", 1, 2, 3));
    }

    @Test
    public void includeMarker_admitsAZeroArgMethod() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Rect",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "import dev.simplified.annotations.ToStringInclude;",
            "@ToString",
            "public class Rect {",
            "    private final int width;",
            "    private final int height;",
            "    public Rect(int width, int height) { this.width = width; this.height = height; }",
            "    @ToStringInclude(name = \"area\") public int computeArea() { return width * height; }",
            "}"));
        assertThat(c).succeeded();

        assertEquals("a derived value prints beside the state it is derived from",
            "Rect[width=2, height=3, area=6]", rendered(c, "demo.Rect", 2, 3));
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    @Test
    public void record_replacesTheImplicitMemberAndPrintsArrayContents() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Frame",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString",
            "public record Frame(byte[] pixels, int width) {}"));
        assertThat(c).succeeded();

        // The implicit member renders a byte[] as its identity hash, so the
        // contents in the expected string are the whole proof of replacement.
        assertEquals("Frame[pixels=[1, 2, 3], width=4]",
            rendered(c, "demo.Frame", new byte[]{1, 2, 3}, 4));
    }

    /**
     * The include marker is applicable to a field and to a method, and the
     * language copies a component's annotations onto both the backing field and
     * the canonical accessor - so the component is rendered twice unless the
     * accessor is skipped, each copy carrying the marker's own overrides. The
     * rank is written here because it makes the second copy impossible to read
     * as anything else.
     */
    @Test
    public void record_componentCarryingTheIncludeMarkerRendersOnce() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Pixels",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "import dev.simplified.annotations.ToStringInclude;",
            "@ToString",
            "public record Pixels(int width, @ToStringInclude(rank = 1) int values) {}"));
        assertThat(c).succeeded();

        assertEquals("the marked component leads, and appears once",
            "Pixels[values=7, width=3]", rendered(c, "demo.Pixels", 3, 7));
    }

    // ------------------------------------------------------------------
    // A target that already declares the member
    // ------------------------------------------------------------------

    @Test
    public void declaredToStringIsKeptWithANoteRatherThanReplaced() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Manual",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString",
            "public class Manual {",
            "    private final int x;",
            "    public Manual(int x) { this.x = x; }",
            "    @Override public String toString() { return \"hand-written:\" + x; }",
            "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining(
            "@ToString on Manual, which already declares toString - the written member is kept");

        assertEquals("the author's member survives, so the note is the whole report",
            "hand-written:9", rendered(c, "demo.Manual", 9));
    }

    /**
     * The refusal has to be the pass's own diagnostic. Without it the emission
     * goes ahead and javac rejects the override instead, on the class
     * declaration line - a message about a member the author never wrote, in a
     * file where nothing looks wrong.
     */
    @Test
    public void supertypeDeclaringToStringFinalIsAnError() {
        JavaFileObject locked = JavaFileObjects.forSourceLines("demo.Locked",
            "package demo;",
            "public class Locked {",
            "    @Override public final String toString() { return \"locked\"; }",
            "}");
        JavaFileObject target = JavaFileObjects.forSourceLines("demo.Below",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString",
            "public final class Below extends Locked {",
            "    private final int x;",
            "    public Below(int x) { this.x = x; }",
            "}");
        Compilation c = compile(locked, target);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("demo.Locked declares it final");
    }

    // ------------------------------------------------------------------
    // Target shapes
    // ------------------------------------------------------------------

    @Test
    public void nestedType_printsItsSimpleName() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Outer",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "public class Outer {",
            "    @ToString",
            "    public static class Inner {",
            "        private final int n;",
            "        public Inner(int n) { this.n = n; }",
            "    }",
            "}"));
        assertThat(c).succeeded();

        assertEquals("Inner[n=5]", rendered(c, "demo.Outer$Inner", 5));
    }

    @Test
    public void genericTarget_printsItsMembers() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Box",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "import java.util.List;",
            "@ToString",
            "public class Box<T> {",
            "    private final T value;",
            "    private final List<T> more;",
            "    public Box(T value, List<T> more) { this.value = value; this.more = more; }",
            "}"));
        assertThat(c).succeeded();

        assertEquals("Box[value=v, more=[x]]", rendered(c, "demo.Box", "v", List.of("x")));
    }

    @Test
    public void targetWithNoSelectedMembers_printsEmptyBrackets() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Empty",
            "package demo;",
            "import dev.simplified.annotations.ToString;",
            "@ToString",
            "public class Empty {",
            "    private static final String SHARED = \"shared\";",
            "    public Empty() {}",
            "}"));
        assertThat(c).succeeded();

        assertEquals("Empty[]", rendered(c, "demo.Empty"));
    }

    // ------------------------------------------------------------------
    // @XContract and @Generated on the emitted member
    // ------------------------------------------------------------------

    @Test
    public void emittedMemberCarriesTheContractAndTheGeneratedMarker() throws Exception {
        Compilation c = compile(pair("@ToString"));
        assertThat(c).succeeded();

        EmittedAnnotations found = toStringAnnotations(c, "demo.Pair");
        assertEquals("-> !null", found.contractValue);
        assertEquals(Boolean.TRUE, found.contractPure);
        assertTrue("the member must be marked so coverage tools skip it", found.generated);
    }

    @Test
    public void emitContractsFalse_leavesTheGeneratedMarkerInPlace() throws Exception {
        Compilation c = compile(pair("@ToString(emitContracts = false)"));
        assertThat(c).succeeded();

        EmittedAnnotations found = toStringAnnotations(c, "demo.Pair");
        assertNull("emitContracts = false must suppress @XContract", found.contractValue);
        assertTrue("the two flags are independent", found.generated);
    }

    @Test
    public void emitGeneratedFalse_leavesTheContractInPlace() throws Exception {
        Compilation c = compile(pair("@ToString(emitGenerated = false)"));
        assertThat(c).succeeded();

        EmittedAnnotations found = toStringAnnotations(c, "demo.Pair");
        assertEquals("the two flags are independent", "-> !null", found.contractValue);
        assertFalse("emitGenerated = false must suppress @Generated", found.generated);
    }

    // ------------------------------------------------------------------
    // Alongside @ClassBuilder
    // ------------------------------------------------------------------

    /**
     * The two passes run over one tree and neither knows about the other. The
     * dump is what makes a disagreement legible: an object the builder seeded
     * from another renders identically only if every member the dump reads was
     * carried across.
     */
    @Test
    public void classBuilderRoundTrip_rendersIdentically() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Payload",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.ToString;",
            "@ClassBuilder @ToString",
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
        Object original = type.getConstructor(String.class, int.class, byte[].class)
            .newInstance("p", 3, new byte[]{1, 2});

        assertEquals("Payload[name=p, size=3, blob=[1, 2]]", original.toString());
        assertEquals("from(instance).build() must render what it was seeded from",
            original.toString(), roundTrip(type, original).toString());
    }

    @Test
    public void classBuilderRecordRoundTrip_rendersIdentically() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Ticket",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.ToString;",
            "@ClassBuilder @ToString",
            "public record Ticket(String code, int seat, byte[] stub) {}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Ticket", true, loadClasses(c));
        Object original = type.getConstructor(String.class, int.class, byte[].class)
            .newInstance("t", 7, new byte[]{3, 4});

        assertEquals("Ticket[code=t, seat=7, stub=[3, 4]]", original.toString());
        assertEquals("a record seeds through its canonical accessors",
            original.toString(), roundTrip(type, original).toString());
    }

    // ------------------------------------------------------------------
    // useAccessors
    // ------------------------------------------------------------------

    /**
     * An accessor {@code @Getter} minted in the same round is never in the
     * element model, so reading only the model turns {@code useAccessors} beside
     * it into the field reads it exists to avoid.
     *
     * <p>Rendered off a subclass overriding the generated accessor, because that
     * is the one place the two reads produce different text: a field read sees
     * the hidden value and a call dispatches.
     */
    @Test
    public void useAccessors_readsThroughAnAccessorGeneratedInTheSameRound() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tagged",
                "package demo;",
                "import dev.simplified.annotations.CallSuper;",
                "import dev.simplified.annotations.Getter;",
                "import dev.simplified.annotations.NamingStyle;",
                "import dev.simplified.annotations.ToString;",
                "@Getter(style = NamingStyle.FLUENT)",
                "@ToString(useAccessors = true, callSuper = CallSuper.NO)",
                "public class Tagged {",
                "    private final String tag;",
                "    public Tagged(String tag) { this.tag = tag; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.TaggedProxy",
                "package demo;",
                "public class TaggedProxy extends Tagged {",
                "    public TaggedProxy(String tag) { super(tag); }",
                "    @Override public String tag() { return \"resolved\"; }",
                "}"));
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Object overridden = Class.forName("demo.TaggedProxy", true, cl)
            .getConstructors()[0].newInstance("raw");
        // The name is the annotated type's, baked in at generation; only the
        // value moves, and it moves only because the read is a call.
        assertEquals("Tagged[tag=resolved]", overridden.toString());
    }

    // ------------------------------------------------------------------
    // Both whole-object annotations on one type
    // ------------------------------------------------------------------

    /**
     * Three members land on one class from two passes over one tree, so the
     * failure this guards against is one pass overwriting the other's marker and
     * silently emitting nothing.
     */
    @Test
    public void toStringAndEqualityOnOneTypeBothEmit() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Both",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.ToString;",
            "@ToString @EqualsAndHashCode",
            "public final class Both {",
            "    private final int left;",
            "    private final String right;",
            "    public Both(int left, String right) { this.left = left; this.right = right; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Both", true, loadClasses(c));
        Object a = type.getConstructors()[0].newInstance(1, "r");
        Object b = type.getConstructors()[0].newInstance(1, "r");

        assertEquals("Both[left=1, right=r]", a.toString());
        assertEquals("Both[left=1, right=r]", b.toString());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotNull(type.getDeclaredMethod("toString"));
        assertNotNull(type.getDeclaredMethod("equals", Object.class));
        assertNotNull(type.getDeclaredMethod("hashCode"));
    }

    // ------------------------------------------------------------------
    // Class-file reading
    // ------------------------------------------------------------------

    /**
     * {@code @XContract} and {@code @Generated} are both {@code @Retention(CLASS)},
     * so reflection cannot see either and the class file's
     * {@code RuntimeInvisibleAnnotations} attribute is read instead.
     */
    private static final class EmittedAnnotations {
        String contractValue;
        Boolean contractPure;
        boolean generated;
    }

    private static EmittedAnnotations toStringAnnotations(Compilation c, String className)
        throws IOException {
        byte[] bytes = findClassBytes(c, className);
        assertNotNull("expected class-file output for " + className, bytes);
        EmittedAnnotations found = new EmittedAnnotations();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"toString".equals(name) || !"()Ljava/lang/String;".equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                        if (GENERATED_DESCRIPTOR.equals(annDesc)) {
                            found.generated = true;
                            return null;
                        }
                        if (!XCONTRACT_DESCRIPTOR.equals(annDesc)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String attribute, Object value) {
                                switch (attribute) {
                                    case "value" -> found.contractValue = (String) value;
                                    case "pure" -> found.contractPure = (Boolean) value;
                                    default -> { }
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return found;
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
        fail("no class-file output for '" + className + "'");
        return null;
    }

}
