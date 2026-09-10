package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.BuilderParityFixture;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * {@code @ClassBuilder(mergeDeclaredBuilder = true)} - generated members landing
 * beside author-written ones inside a {@code Builder} the target declares.
 *
 * <p>The point of it is that one member the generator cannot express should not
 * cost every member it can. What the tests pin is the boundary: the author wins
 * wherever they wrote something, and the generator fills in everything else.
 */
public class DeclaredBuilderMergeTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        return new URLClassLoader(new URL[]{classesOf(compilation).toUri().toURL()},
            DeclaredBuilderMergeTest.class.getClassLoader());
    }

    /**
     * Writes a compilation's class files out, so a second compilation can be run
     * against them as a classpath entry rather than as sources.
     *
     * @param compilation the finished compilation
     * @return the directory holding its class files
     * @throws Exception if a file cannot be written
     */
    private static Path classesOf(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-merge-test");
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
        return tmp;
    }

    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    /**
     * This JVM's own classpath with one more directory on the end.
     *
     * <p>A second-stage compilation resolves the annotations off the running
     * classpath exactly as the first stage does, and the extra entry is what
     * makes the ancestor a compiled type rather than a source one.
     *
     * @param extra the directory holding the first stage's class files
     * @return the classpath to compile the second stage against
     */
    private static List<File> runtimeClasspathPlus(Path extra) {
        List<File> classpath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) classpath.add(new File(entry));
        }
        classpath.add(extra.toFile());
        return classpath;
    }

    /**
     * The shape the feature exists for: one extension point taking the builder
     * itself, and every other setter still generated around it.
     */
    @Test
    public void merge_keepsTheAuthorsMembersAndAddsTheRest() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Settings",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Settings {",
                "    private String name;",
                "    private boolean prettyPrint;",
                "    @Collector(singular = true) private List<String> tags;",
                "    Settings(String name, boolean prettyPrint, List<String> tags) {",
                "        this.name = name; this.prettyPrint = prettyPrint; this.tags = tags;",
                "    }",
                "    public String getName() { return name; }",
                "    public boolean isPrettyPrint() { return prettyPrint; }",
                "    public List<String> getTags() { return tags; }",
                "    public static class Builder {",
                "        private List<String> tags = new ArrayList<>();",
                "        public Builder apply(Contributor contributor) {",
                "            contributor.contribute(this);",
                "            return this;",
                "        }",
                "    }",
                "    public interface Contributor { void contribute(Builder builder); }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSettings",
                "package demo;",
                "public class UseSettings {",
                "    public static String go() {",
                "        Settings s = Settings.builder()",
                "            .name(\"x\")",
                "            .isPrettyPrint()",
                "            .addTag(\"t\")",
                "            .apply(b -> b.addTag(\"contributed\"))",
                "            .build();",
                "        return s.getName() + \"/\" + s.isPrettyPrint() + \"/\" + s.getTags();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x/true/[t, contributed]", runGo(c, "demo.UseSettings"));
    }

    /**
     * A hand-written setter is the case the whole feature is named for, so the
     * generated one of that name and arity must not land beside it.
     */
    @Test
    public void merge_leavesAHandWrittenSetterAlone() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Doc",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Doc {",
                "    private String fileName;",
                "    private int pages;",
                "    Doc(String fileName, int pages) { this.fileName = fileName; this.pages = pages; }",
                "    public String getFileName() { return fileName; }",
                "    public int getPages() { return pages; }",
                "    public static class Builder {",
                "        private String fileName;",
                "        public Builder fileName(String fileName) {",
                "            this.fileName = fileName.endsWith(\".svg\") ? fileName : fileName + \".svg\";",
                "            return this;",
                "        }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseDoc",
                "package demo;",
                "public class UseDoc {",
                "    public static String go() {",
                "        Doc d = Doc.builder().fileName(\"Context\").pages(3).build();",
                "        return d.getFileName() + \"/\" + d.getPages();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("Context.svg/3", runGo(c, "demo.UseDoc"));
    }

    /** A declared {@code build()} wins, so the author decides what is constructed. */
    @Test
    public void merge_leavesADeclaredBuildAlone() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Boxed",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Boxed {",
                "    private int size;",
                "    Boxed(int size) { this.size = size; }",
                "    public int getSize() { return size; }",
                "    public static class Builder {",
                "        private int size;",
                "        public Boxed build() { return new Boxed(this.size * 2); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBoxed",
                "package demo;",
                "public class UseBoxed {",
                "    public static Integer go() { return Boxed.builder().size(4).build().getSize(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(8, runGo(c, "demo.UseBoxed"));
    }

    /**
     * Without the opt-in the declaration still suppresses everything, which is
     * the behaviour every target written before this had.
     */
    @Test
    public void withoutTheOptIn_theDeclarationStillSuppressesEverything() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Untouched",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Untouched {",
                "    private String name;",
                "    public static class Builder { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseUntouched",
                "package demo;",
                "public class UseUntouched {",
                "    public static void go() { new Untouched.Builder().name(\"x\"); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("name");
    }

    /** The note names what the author's version won, rather than leaving it silent. */
    @Test
    public void merge_reportsWhatTheAuthorAlreadySpells() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Noted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Noted {",
                "    private String name;",
                "    Noted(String name) { this.name = name; }",
                "    public static class Builder {",
                "        private String name;",
                "        public Builder name(String name) { this.name = name; return this; }",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("already spells");
    }

    /** A generic target's declared builder has to carry the same parameters. */
    @Test
    public void merge_onAGenericTarget() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Crate",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Crate<T> {",
                "    private T item;",
                "    private int count;",
                "    Crate(T item, int count) { this.item = item; this.count = count; }",
                "    public T getItem() { return item; }",
                "    public int getCount() { return count; }",
                "    public static class Builder<T> {",
                "        private T item;",
                "        public Builder<T> item(T item) { this.item = item; return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseCrate",
                "package demo;",
                "public class UseCrate {",
                "    public static String go() {",
                "        Crate<String> c = Crate.<String>builder().item(\"x\").count(2).build();",
                "        return c.getItem() + \"/\" + c.getCount();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x/2", runGo(c, "demo.UseCrate"));
    }

    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Test
    public void merge_intoANonStaticBuilder_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Inner",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Inner {",
                "    private String name;",
                "    Inner(String name) { this.name = name; }",
                "    public class Builder { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("an inner class captures the enclosing instance");
    }

    @Test
    public void merge_intoABuilderMissingTheTypeParameters_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Raw",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Raw<T> {",
                "    private T item;",
                "    Raw(T item) { this.item = item; }",
                "    public static class Builder { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("re-declare the target's type parameters");
    }

    /**
     * A declared slot of the wrong type is reported at the target rather than
     * left to fail on the generated setter, which is a line the author never
     * wrote.
     */
    @Test
    public void merge_ontoAMistypedSlot_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Mistyped",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Mistyped {",
                "    private int size;",
                "    Mistyped(int size) { this.size = size; }",
                "    public static class Builder {",
                "        private String size;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("the slot it stands for is int");
    }

    /**
     * The entry points call {@code new} on the declared builder, so declaring it
     * abstract leaves them nothing to create - which used to be found by javac
     * on a generated line rather than said here.
     */
    @Test
    public void merge_intoAnAbstractBuilder_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Sealed",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Sealed {",
                "    private String name;",
                "    Sealed(String name) { this.name = name; }",
                "    public abstract static class Builder { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("it is what builder() instantiates, so it cannot be abstract");
    }

    /**
     * A build method the author wrote is kept in place of the generated one, so
     * a return type that cannot stand in for it is refused at the declaration.
     */
    @Test
    public void merge_ontoABuildMethodReturningSomethingElse_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Wrong",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Wrong {",
                "    private String name;",
                "    Wrong(String name) { this.name = name; }",
                "    public static class Builder {",
                "        public Object build() { return null; }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("its build method returns Object where this role builds Wrong");
    }

    /**
     * A lazy slot is held in the builder as a supplier, so the supplier spelling
     * is the one the generated setters can assign - and it was the one rejected.
     *
     * <p>Seeded through the generated setter rather than left to default,
     * because the author's declared field wins whole: the merge appends no field
     * of a name the builder already spells, so the initializer the generated
     * slot would have carried is not there either.
     */
    @Test
    public void merge_whereALazySlotIsDeclaredAsASupplier_isAccepted() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Held",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "import java.util.function.Supplier;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Held {",
                "    @Lazy private String note = compute();",
                "    private static String compute() { return \"computed\"; }",
                "    public static class Builder {",
                "        private Supplier<String> note;",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseHeld",
                "package demo;",
                "public class UseHeld {",
                "    public static String go() { return Held.builder().note(\"set\").build().getNote(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("set", runGo(c, "demo.UseHeld"));
    }

    /**
     * And the natural spelling is the one that cannot work, which the check used
     * to accept and leave to fail on a generated line.
     */
    @Test
    public void merge_whereALazySlotIsDeclaredWithItsNaturalType_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Natural",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Natural {",
                "    @Lazy private String note = compute();",
                "    private static String compute() { return \"computed\"; }",
                "    public static class Builder {",
                "        private String note;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining(
            "A @Lazy field is held in the builder as a supplier of its declared type");
    }

    // ------------------------------------------------------------------
    // The shared parity cases
    //
    // Each of these compiles the same source the editor suite configures and
    // asserts the same claim from the other side. The declared-builder-on-a-
    // chain column was empty on both halves - nothing asserted that the abort
    // fires and nothing would have failed if it stopped - which is what let the
    // editor contribute a whole surface into a class javac never touches.
    // ------------------------------------------------------------------

    private static JavaFileObject parity(BuilderParityFixture fixture) {
        return JavaFileObjects.forSourceString(fixture.qualifiedName(), fixture.source());
    }

    /**
     * The entry points are the claim: a consumer calling {@code builder()} on a
     * target that declares its own nested builder does not compile, which is
     * what makes the editor offering it a divergence rather than a preference.
     */
    @Test
    public void declaredBuilderWithoutTheOptIn_emitsNoEntryPoints() {
        BuilderParityFixture fixture =
            BuilderParityFixture.load("standalone-declared-builder-opt-out");
        Compilation c = compile(
            parity(fixture),
            JavaFileObjects.forSourceLines("demo.UseUntouchedEntryPoints",
                "package demo;",
                "public class UseUntouchedEntryPoints {",
                "    public static Object go() { return Untouched.builder(); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("builder");
        assertThat(c).hadNoteContaining(
            "@ClassBuilder skipped injection: class Untouched already declares a nested 'Builder' type");
    }

    /**
     * The chain path aborts on the same declaration by a different route, and
     * neither half asserted it. Pinned on a stable prefix of the note rather
     * than the whole line, the chain merge having a clause to append to it.
     */
    @Test
    public void aDeclaredChainBuilderWithoutTheOptIn_stillNotesAndSkips() {
        BuilderParityFixture fixture =
            BuilderParityFixture.load("chain-root-declared-builder-opt-out");
        Compilation c = compile(
            parity(fixture),
            JavaFileObjects.forSourceLines("demo.UseRooted",
                "package demo;",
                "public class UseRooted {",
                "    public static Object go() { return new Rooted.Builder(); }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining(
            "@ClassBuilder skipped injection: class Rooted already declares a nested 'Builder' type");
    }

    /**
     * A link's extends clause names its ancestor's builder and passes it the
     * ancestor's own arguments plus the self-typed pair. Where the ancestor's
     * author wrote that class themselves it takes none of them, and the clause
     * used to be emitted anyway and fail at attribution on a line nobody wrote,
     * with the editor silently leaving the child's builder unrooted and saying
     * nothing at all.
     */
    @Test
    public void aLinkWhoseAnnotatedSuperDeclaresItsOwnBuilder_isRejected() {
        BuilderParityFixture fixture =
            BuilderParityFixture.load("chain-root-declared-builder-opt-out");
        Compilation c = compile(
            parity(fixture),
            JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Rooted {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining(
            "its annotated supertype 'Rooted' declares its own nested builder");
    }

    /**
     * The same refusal against an ancestor that is already compiled, which is
     * the other of the two views an ancestor can be in and the one a single-round
     * test cannot reach.
     *
     * <p>Neither view subsumes the other: an ancestor in this round has a tree
     * and no settled element model, one compiled earlier has an element model and
     * no tree. A read that used only the first would say nothing here, and every
     * consumer compiling against a published chain is in exactly this position.
     */
    @Test
    public void aLinkWhoseCompiledSuperDeclaresItsOwnBuilder_isRejected() throws Exception {
        BuilderParityFixture fixture =
            BuilderParityFixture.load("chain-root-declared-builder-opt-out");
        Compilation ancestor = compile(parity(fixture));
        assertThat(ancestor).succeeded();

        Compilation c = Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .withClasspath(runtimeClasspathPlus(classesOf(ancestor)))
            .compile(JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Rooted {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining(
            "its annotated supertype 'Rooted' declares its own nested builder");
    }

    /**
     * The opt-in written on a link reaches nothing, the chain branch returning
     * ahead of the declared-builder check - so a consumer calling a generated
     * setter on the author's builder fails, and the editor listing one is the
     * divergence.
     */
    @Test
    public void aDeclaredChainBuilderWithTheOptIn_isStillSkipped() {
        BuilderParityFixture fixture =
            BuilderParityFixture.load("chain-link-declared-builder-opt-in");
        Compilation c = compile(
            parity(fixture),
            JavaFileObjects.forSourceLines("demo.UseLink",
                "package demo;",
                "public class UseLink {",
                "    public static Object go() { return new Link.Builder().extra(\"x\"); }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("extra");
        assertThat(c).hadNoteContaining(
            "@ClassBuilder skipped injection: class Link already declares a nested 'Builder' type");
    }

}
