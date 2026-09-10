package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.BuilderParityFixture;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()},
            DeclaredBuilderMergeTest.class.getClassLoader());
    }

    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
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
