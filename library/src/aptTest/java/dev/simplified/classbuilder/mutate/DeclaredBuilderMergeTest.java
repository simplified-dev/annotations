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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The declared-builder merge - generated members landing beside author-written
 * ones inside a {@code Builder} the target declares, with the annotation bare.
 *
 * <p>The point of it is that one member the generator cannot express should not
 * cost every member it can. What the tests pin is the boundary: the author wins
 * wherever they wrote something, and the generator fills in everything else.
 */
public class DeclaredBuilderMergeTest {

    /** The note for a constructor target's {@code builder(origin)} skipped for want of a constructor. */
    private static final String SEED_SKIPPED = "@ClassBuilder merged into 'Builder' but no single constructor "
        + "it declares takes the seed 'builder' passes as its own type, its box or primitive, a wider primitive "
        + "or Object, so 'builder' was not added - declare a constructor taking (origin) or write it";

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
                "@ClassBuilder(validate = false)",
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
                "@ClassBuilder(validate = false)",
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
                "@ClassBuilder(validate = false)",
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
     * An empty declared builder is merged into like any other, with nothing
     * written beside the annotation. The declaration used to suppress every
     * generated member unless an attribute asked for the merge, so the setter
     * called here did not exist.
     *
     * <p>An empty builder spells nothing, so nothing is reported as the author's.
     * The note used to claim a {@code Builder()} there, naming the javac default
     * the generated constructor's access is now retyped onto.
     */
    @Test
    public void anEmptyDeclaredBuilder_isMergedInto() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Untouched",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Untouched {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public static class Builder { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseUntouched",
                "package demo;",
                "public class UseUntouched {",
                "    public static String go() {",
                "        return new Untouched.Builder().name(\"x\").build().getName();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        for (var note : c.notes()) {
            assertFalse("the author spelled nothing: " + note.getMessage(null),
                String.valueOf(note.getMessage(null)).contains("already spells"));
        }
        assertEquals("x", runGo(c, "demo.UseUntouched"));
    }

    /** The note names what the author's version won, rather than leaving it silent. */
    @Test
    public void merge_reportsWhatTheAuthorAlreadySpells() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Noted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
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
                "@ClassBuilder(validate = false)",
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
                "@ClassBuilder(validate = false)",
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
                "@ClassBuilder(validate = false)",
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
     * wrote. The sentence is the one {@code DeclaredBuilderShapeInspectionTest}
     * asserts at the same shape.
     */
    @Test
    public void merge_ontoAMistypedSlot_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Mistyped",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Mistyped {",
                "    private int size;",
                "    Mistyped(int size) { this.size = size; }",
                "    public static class Builder {",
                "        private String size;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'size' declared as "
            + "String, and the slot it stands for is int - the generated setter has nothing to assign it to");
    }

    /**
     * The storage type is printed in one spelling on both halves. javac rendered
     * a parameterised slot type with a bare comma between its arguments where
     * the editor's rendering carries a space, so the two halves printed
     * different sentences for one shape.
     */
    @Test
    public void merge_ontoAMistypedGenericSlot_printsTheEditorsSentence() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tally",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.List;",
                "import java.util.Map;",
                "@ClassBuilder(validate = false)",
                "public class Tally {",
                "    private Map<String, Integer> counts;",
                "    Tally(Map<String, Integer> counts) { this.counts = counts; }",
                "    public static class Builder {",
                "        private List<String> counts;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'counts' declared as "
            + "List<String>, and the slot it stands for is java.util.Map<java.lang.String, java.lang.Integer>"
            + " - the generated setter has nothing to assign it to");
    }

    /**
     * A declared field sharing its slot's erasure and differing in a type
     * argument cannot take what the generated setter assigns either. The check
     * compared erasures alone and passed it, so javac failed on the generated
     * setter - reported on the class, a line the author never wrote - and the
     * author's field carried nothing. {@code DeclaredBuilderShapeInspectionTest}
     * reports the same sentence on the field.
     */
    @Test
    public void merge_ontoASlotDifferingInATypeArgument_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tagged",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Tagged {",
                "    private List<String> tags;",
                "    public List<String> getTags() { return tags; }",
                "    public static class Builder {",
                "        private List<Integer> tags;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'tags' declared as "
            + "List<Integer>, and the slot it stands for is java.util.List<java.lang.String> - the "
            + "generated setter has nothing to assign it to");
    }

    /**
     * A raw field takes the parameterised value the setter assigns and hands it
     * back to {@code build()} with no more than an unchecked warning, so a field
     * written without its slot's arguments is merged into as written.
     */
    @Test
    public void merge_ontoARawSlotField_isAccepted() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tagged",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Tagged {",
                "    private List<String> tags;",
                "    public List<String> getTags() { return tags; }",
                "    public static class Builder {",
                "        @SuppressWarnings(\"rawtypes\") private List tags;",
                "        public int count() { return this.tags == null ? 0 : this.tags.size(); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseTagged",
                "package demo;",
                "public class UseTagged {",
                "    public static Object go() {",
                "        return Tagged.builder().tags(java.util.List.of(\"a\", \"b\")).count();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(2, runGo(c, "demo.UseTagged"));
    }

    /**
     * An initialised slot whose initializer reads nothing of the instance is
     * held as declared, and its field is judged as any other slot's.
     * {@code DeclaredBuilderShapeInspectionTest} asserts the same sentence at the
     * same shape.
     */
    @Test
    public void merge_ontoAMistypedSlotWithALiteralInitializer_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Titled",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Titled {",
                "    private String name = \"untitled\";",
                "    public String getName() { return name; }",
                "    public static class Builder {",
                "        private int name;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'name' declared as "
            + "int, and the slot it stands for is java.lang.String - the generated setter has nothing to "
            + "assign it to");
    }

    /**
     * A slot whose retained initializer reads instance state is held as a
     * supplier, so a declared field of the slot's declared type is refused in
     * the sentence naming the supplier. {@code DeclaredBuilderShapeInspectionTest}
     * asserts the same sentence at the same shape.
     */
    @Test
    public void merge_ontoASlotWhoseInitializerReadsTheInstance_namesTheSupplier() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Labelled",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Labelled {",
                "    private String name;",
                "    private String label = name + \"!\";",
                "    public String getLabel() { return label; }",
                "    public static class Builder {",
                "        private String label;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'label' declared as "
            + "String, and the slot it stands for is java.util.function.Supplier<java.lang.String> - the "
            + "generated setter has nothing to assign it to. A slot whose retained initializer reads "
            + "instance state is held in the builder as a supplier of its declared type");
    }

    /**
     * The merged slot of an initializer that reads instance state is a
     * supplier, and an author's verb assigning one compiles and wins over the
     * default. {@code DeclaredBuilderMergeParityTest} resolves the same verb.
     */
    @Test
    public void merge_aSlotWhoseInitializerReadsTheInstance_isASupplierToAnAuthorVerb() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Labelled",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Labelled {",
                "    private String name;",
                "    private String label = name + \"!\";",
                "    public String getLabel() { return label; }",
                "    public static class Builder {",
                "        public Builder preset() { this.label = () -> \"preset\"; return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLabelled",
                "package demo;",
                "public class UseLabelled {",
                "    public static String go() {",
                "        return Labelled.builder().name(\"n\").preset().build().getLabel() + \"/\"",
                "            + Labelled.builder().name(\"n\").build().getLabel();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("preset/n!", runGo(c, "demo.UseLabelled"));
    }

    /**
     * Every entry point instantiates the builder, and a declared builder's
     * constructors are the author's throughout. One that declares constructors
     * and no nullary one leaves all three with nothing to call - which used to
     * be a generated line javac rejects rather than something said out loud.
     */
    @Test
    public void merge_whereTheAuthorsBuilderConstructorTakesParameters_skipsTheBootstrapsWithANote()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Seeded",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Seeded {",
                "    private String name;",
                "    Seeded(String name) { this.name = name; }",
                "    public String getName() { return name; }",
                "    public static class Builder {",
                "        private final String origin;",
                "        public Builder(String origin) { this.origin = origin; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSeeded",
                "package demo;",
                "public class UseSeeded {",
                "    public static String go() {",
                "        return new Seeded.Builder(\"x\").name(\"n\").build().getName();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining(
            "@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder', 'from' and 'mutate' were not added");
        assertEquals("the setters are still merged in", "n", runGo(c, "demo.UseSeeded"));
    }

    /**
     * The skip withholds the three entry points and nothing else: a target that
     * declares no constructor still gets the all-args one the merged
     * {@code build()} calls, which a same-package caller can reach directly. The
     * declaration used to suppress that constructor along with everything else.
     */
    @Test
    public void merge_whereTheAuthorsBuilderConstructorTakesParameters_keepsTheAllArgsConstructor()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Unseeded",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Unseeded {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public static class Builder {",
                "        private final String origin;",
                "        public Builder(String origin) { this.origin = origin; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseUnseeded",
                "package demo;",
                "public class UseUnseeded {",
                "    public static String go() {",
                "        return new Unseeded(\"direct\").getName() + \"/\"",
                "            + new Unseeded.Builder(\"x\").name(\"built\").build().getName();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("so 'builder', 'from' and 'mutate' were not added");
        assertEquals("direct/built", runGo(c, "demo.UseUnseeded"));
    }

    /**
     * With every entry point named {@code NONE} there is nothing to skip, so a
     * declared builder whose constructors all take parameters draws no note. The
     * note used to be printed anyway, reporting an empty list as not added.
     */
    @Test
    public void merge_whereEveryEntryPointIsNamedNone_notesNothingSkipped() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Closed",
                "package demo;",
                "import dev.simplified.annotations.BuilderNames;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, builder = @BuilderNames(builder = BuilderNames.NONE,",
                "    from = BuilderNames.NONE, toBuilder = BuilderNames.NONE))",
                "public class Closed {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public static class Builder {",
                "        public Builder(String name) { this.name = name; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseClosed",
                "package demo;",
                "public class UseClosed {",
                "    public static String go() {",
                "        return new Closed.Builder(\"x\").build().getName();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        for (var note : c.notes()) {
            assertFalse("no entry point is emitted, so none is skipped: " + note.getMessage(null),
                String.valueOf(note.getMessage(null)).contains("were not added"));
        }
        assertEquals("x", runGo(c, "demo.UseClosed"));
    }

    /** A builder declaring a nullary constructor beside a seeded one keeps its entry points. */
    @Test
    public void merge_whereTheAuthorAlsoDeclaresANullaryConstructor_keepsTheBootstraps()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Both",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Both {",
                "    private String name;",
                "    Both(String name) { this.name = name; }",
                "    public String getName() { return name; }",
                "    public static class Builder {",
                "        public Builder() { }",
                "        public Builder(String origin) { }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBoth",
                "package demo;",
                "public class UseBoth {",
                "    public static String go() { return Both.builder().name(\"n\").build().getName(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("n", runGo(c, "demo.UseBoth"));
    }

    /**
     * A declared builder that declares no constructor has javac's default
     * retyped to {@code builderConstructorAccess}, package-private by default,
     * so {@code builder()} stays the one way in from another package exactly as
     * it is on a generated builder. javac's default used to keep the declared
     * class's own access, publishing {@code new Target.Builder()} beside it.
     */
    @Test
    public void merge_whereTheBuilderDeclaresNoConstructor_retypesTheDefault() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Probe",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Probe {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public static class Builder { }",
                "}"),
            JavaFileObjects.forSourceLines("other.UseProbe",
                "package other;",
                "import demo.Probe;",
                "public class UseProbe {",
                "    public static String go() {",
                "        return Probe.builder().name(\"x\").build().getName()",
                "            + new Probe.Builder().name(\"y\").build().getName();",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot be accessed from outside package");
        assertEquals("the entry point itself stays reachable: " + c.errors(), 1, c.errors().size());
    }

    /**
     * The retype takes the configured access, not merely package-private: a
     * private one refuses a same-package {@code new Target.Builder()} while the
     * entry point inside the target still instantiates it. javac's default kept
     * the declared class's access, so the same-package call compiled.
     */
    @Test
    public void merge_whereTheBuilderDeclaresNoConstructor_takesTheConfiguredAccess() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Sealed",
                "package demo;",
                "import dev.simplified.annotations.AccessLevel;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, builderConstructorAccess = AccessLevel.PRIVATE)",
                "public class Sealed {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public static class Builder { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSealed",
                "package demo;",
                "public class UseSealed {",
                "    public static String go() {",
                "        return Sealed.builder().name(\"x\").build().getName()",
                "            + new Sealed.Builder().name(\"y\").build().getName();",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("has private access");
        assertEquals("the entry point itself stays reachable: " + c.errors(), 1, c.errors().size());
    }

    /**
     * An author's own constructor is never retyped, whatever its access: a
     * public one stays reachable from another package with the attribute at its
     * default.
     */
    @Test
    public void merge_whereTheBuilderDeclaresAPublicConstructor_keepsItPublic() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Open",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Open {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public static class Builder {",
                "        public Builder() { }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("other.UseOpen",
                "package other;",
                "import demo.Open;",
                "public class UseOpen {",
                "    public static String go() { return new Open.Builder().name(\"x\").build().getName(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x", runGo(c, "other.UseOpen"));
    }

    /**
     * {@code builderConstructorAccess} written beside an author's constructor
     * changes nothing, the author's constructor winning, and says so at the
     * annotation. The attribute used to be accepted there in silence.
     */
    @Test
    public void merge_whereTheBuilderDeclaresItsOwnConstructor_warnsThatTheAttributeHasNoEffect() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Owned",
                "package demo;",
                "import dev.simplified.annotations.AccessLevel;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, builderConstructorAccess = AccessLevel.PRIVATE)",
                "public class Owned {",
                "    private String name;",
                "    public static class Builder {",
                "        public Builder() { }",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("@ClassBuilder(builderConstructorAccess) has no effect - "
            + "the declared 'Builder' declares its own constructor, which keeps the access it is "
            + "written with. Write the access on that constructor, or drop the attribute");
    }

    /**
     * A constructor target's declared builder with no constructor of its own is
     * retyped as a type target's is, its entry point instantiating it in the
     * enclosing type. javac's default used to keep the class's access there too.
     */
    @Test
    public void merge_onAConstructorTargetWhoseBuilderDeclaresNoConstructor_retypesTheDefault() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Step",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Step {",
                "    private final String key;",
                "    @ClassBuilder",
                "    Step(String key) { this.key = key; }",
                "    public String getKey() { return key; }",
                "    public static class Builder { }",
                "}"),
            JavaFileObjects.forSourceLines("other.UseStep",
                "package other;",
                "import demo.Step;",
                "public class UseStep {",
                "    public static String go() {",
                "        return Step.builder().key(\"a\").build().getKey()",
                "            + new Step.Builder().key(\"b\").build().getKey();",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot be accessed from outside package");
        assertEquals("the entry point itself stays reachable: " + c.errors(), 1, c.errors().size());
    }

    /**
     * A chain role's declared builder keeps javac's default at the class's own
     * access, as the builder the chain generates does, so a subclass builder or
     * caller in another package reaches it.
     */
    @Test
    public void merge_onADeclaredLinkBuilder_keepsJavacsDefault() throws Exception {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "    public static class Builder extends Base.Builder<Link, Builder> { }",
                "}"),
            JavaFileObjects.forSourceLines("other.UseLink",
                "package other;",
                "import demo.Link;",
                "public class UseLink {",
                "    public static String go() { return new Link.Builder().extra(\"x\").build().getExtra(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x", runGo(c, "other.UseLink"));
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
                "@ClassBuilder(validate = false)",
                "public class Sealed {",
                "    private String name;",
                "    Sealed(String name) { this.name = name; }",
                "    public abstract static class Builder { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("it is what builder() instantiates, so it cannot be abstract");
    }

    /**
     * Standing alone, nothing generated calls {@code build()} - the author's is
     * kept and reported as kept - so a build method returning something else is
     * theirs to write. Refusing it here rejected source javac accepts.
     */
    @Test
    public void merge_ontoABuildMethodReturningSomethingElse_isAccepted() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Wrong",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Wrong {",
                "    private String name;",
                "    Wrong(String name) { this.name = name; }",
                "    public static class Builder {",
                "        public Object build() { return null; }",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("already spells");
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
                "@ClassBuilder(validate = false)",
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
     * to accept and leave to fail on a generated line. The whole sentence is
     * asserted, the storage type included, because the inspection asserts the
     * same one at the same shape.
     */
    @Test
    public void merge_whereALazySlotIsDeclaredWithItsNaturalType_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Natural",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Natural {",
                "    @Lazy private String note = compute();",
                "    private static String compute() { return \"computed\"; }",
                "    public static class Builder {",
                "        private String note;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'note' declared as "
            + "String, and the slot it stands for is java.util.function.Supplier<java.lang.String> - the "
            + "generated setter has nothing to assign it to. A @Lazy field is held in the builder as a "
            + "supplier of its declared type");
    }

    /**
     * A primitive lazy slot is held as a supplier of the boxed type. The sentence
     * used to print {@code Supplier<int>}, which names no type the author could
     * write.
     */
    @Test
    public void merge_whereALazyPrimitiveSlotIsDeclaredWithItsNaturalType_namesTheBoxedSupplier() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Counted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "@ClassBuilder(validate = false)",
                "public class Counted {",
                "    @Lazy private int count = compute();",
                "    private static int compute() { return 7; }",
                "    public static class Builder {",
                "        private int count;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("finds 'count' declared as int, and the slot it stands for is "
            + "java.util.function.Supplier<java.lang.Integer> - the generated setter");
    }

    // ------------------------------------------------------------------
    // The executable merge
    //
    // A constructor or static factory target whose enclosing type declares the
    // builder class merges into it exactly as a type target does. The
    // declaration used to turn the whole pass off with a note, so none of these
    // compiled.
    // ------------------------------------------------------------------

    /**
     * The constructor-target shape of the feature: the author's verb stays, and
     * the parameter's setter, {@code build()} and the entry point are generated
     * around it.
     */
    @Test
    public void merge_onAConstructorTarget_appendsToTheAuthorsBuilder() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Action",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Action {",
                "    private final String key;",
                "    @ClassBuilder",
                "    Action(String key) { this.key = key; }",
                "    public String getKey() { return key; }",
                "    public static class Builder {",
                "        private int applied;",
                "        public Builder apply(Runnable task) { task.run(); applied++; return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseAction",
                "package demo;",
                "public class UseAction {",
                "    public static String go() {",
                "        return Action.builder().key(\"k\").apply(() -> { }).build().getKey();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("k", runGo(c, "demo.UseAction"));
    }

    /**
     * A slot here is a parameter, so a field of the enclosing type sharing its
     * name - initialised or not - is no part of it, and the merged field is what
     * an author's verb reads.
     */
    @Test
    public void merge_onAConstructorTarget_whoseParameterSharesAnInitialisedFieldsName() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tag",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Tag {",
                "    private String label = \"none\";",
                "    @ClassBuilder",
                "    Tag(String label) { this.label = label; }",
                "    public String getLabel() { return label; }",
                "    public static class Builder {",
                "        public Builder shout() { this.label = this.label + \"!\"; return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseTag",
                "package demo;",
                "public class UseTag {",
                "    public static String go() {",
                "        return Tag.builder().label(\"hi\").shout().build().getLabel();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("hi!", runGo(c, "demo.UseTag"));
    }

    /**
     * A static factory runs under its own type parameters and cannot name the
     * enclosing type's, so the declared builder re-declares the factory's - the
     * same list the generated members are written in.
     */
    @Test
    public void merge_onAStaticFactory_expectsTheFactorysOwnTypeParameters() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Box",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Box<V> {",
                "    private final V value;",
                "    private Box(V value) { this.value = value; }",
                "    @ClassBuilder",
                "    public static <T> Box<T> of(T value) { return new Box<>(value); }",
                "    public V getValue() { return value; }",
                "    public static class Builder<T> {",
                "        public Builder<T> apply(Runnable task) { task.run(); return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBox",
                "package demo;",
                "public class UseBox {",
                "    public static String go() {",
                "        return Box.<String>builder().value(\"v\").apply(() -> { }).build().getValue();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("v", runGo(c, "demo.UseBox"));
    }

    /**
     * The inverse: a builder re-declaring the enclosing type's parameters where
     * the factory has its own is refused, naming the factory's list as the one
     * required. {@code DeclaredBuilderShapeInspectionTest} asserts the same
     * sentence.
     */
    @Test
    public void merge_onAStaticFactory_intoABuilderRedeclaringTheEnclosingTypesParameters_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Crate",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Crate<V> {",
                "    private final V value;",
                "    private Crate(V value) { this.value = value; }",
                "    @ClassBuilder",
                "    public static <T> Crate<T> of(T value) { return new Crate<>(value); }",
                "    public static class Builder<V> { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("re-declare the target's type parameters <T>, and this one "
            + "declares <V>");
    }

    /**
     * A declared field sharing a parameter slot's name is judged against the
     * parameter's type, whatever the enclosing type's field of that name
     * carries. {@code DeclaredBuilderShapeInspectionTest} asserts the same
     * sentence.
     */
    @Test
    public void merge_onAConstructorTarget_ontoAMistypedParameterSlot_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Gauge",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Gauge {",
                "    private int size = 3;",
                "    @ClassBuilder",
                "    Gauge(int size) { this.size = size; }",
                "    public static class Builder {",
                "        private String size;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'size' declared as "
            + "String, and the slot it stands for is int - the generated setter has nothing to assign it to");
    }

    /**
     * A seeded entry point passes its seeds to the builder's constructor, so a
     * declared builder taking exactly that many keeps {@code builder(seed)} - the
     * inverse of the type path, where the arity that serves is zero. The seed's
     * final field is merged in and the author's constructor assigns it.
     */
    @Test
    public void merge_onASeededConstructor_whereTheBuilderTakesTheSeed_keepsTheEntryPoint() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Order",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Order {",
                "    private final String origin;",
                "    private final String item;",
                "    @ClassBuilder",
                "    Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }",
                "    public String describe() { return origin + \":\" + item; }",
                "    public static class Builder {",
                "        public Builder(String origin) { this.origin = origin.trim(); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseOrder",
                "package demo;",
                "public class UseOrder {",
                "    public static String go() {",
                "        return Order.builder(\" web \").item(\"tea\").build().describe();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("web:tea", runGo(c, "demo.UseOrder"));
    }

    /**
     * A declared builder whose only constructor takes no arguments has nothing a
     * seeded {@code builder(seed)} can call, so the entry point is skipped with a
     * note naming the arity it needed - while the setters are still merged in,
     * and the author's constructor supplies the seed's value itself.
     */
    @Test
    public void merge_onASeededConstructor_whereTheBuilderTakesNoSeed_skipsTheEntryPointWithANote()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Ticket",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Ticket {",
                "    private final String origin;",
                "    private final String item;",
                "    @ClassBuilder",
                "    Ticket(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }",
                "    public String describe() { return origin + \":\" + item; }",
                "    public static class Builder {",
                "        public Builder() { this.origin = \"desk\"; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseTicket",
                "package demo;",
                "public class UseTicket {",
                "    public static String go() {",
                "        return new Ticket.Builder().item(\"tea\").build().describe();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining(SEED_SKIPPED);
        assertEquals("desk:tea", runGo(c, "demo.UseTicket"));
    }

    /**
     * The seed's merged field is {@code final}, as it is on a builder the
     * generator writes whole, so an author's verb writing over a committed seed
     * is refused.
     */
    @Test
    public void merge_onASeededConstructor_keepsTheSeedFieldFinal() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Slip",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Slip {",
                "    @ClassBuilder",
                "    Slip(@BuilderSeed String origin, String item) { }",
                "    public static class Builder {",
                "        public Builder(String origin) { this.origin = origin; }",
                "        public Builder reroute(String to) { this.origin = to; return this; }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot assign a value to final variable origin");
    }

    /**
     * The merge appends the seed as a {@code final} field and never the
     * constructor that assigns it, so a constructor of the author's that leaves
     * it unassigned is refused by javac on that constructor - and only that one:
     * the one assigning it and the one delegating to it are accepted.
     * {@code DeclaredBuilderShapeInspectionTest} reports the same constructor.
     */
    @Test
    public void merge_onASeededConstructor_whoseBuilderConstructorLeavesTheSeedUnassigned_fails() {
        JavaFileObject slip = JavaFileObjects.forSourceLines("demo.Slip",
            "package demo;",
            "import dev.simplified.annotations.BuilderSeed;",
            "import dev.simplified.annotations.ClassBuilder;",
            "public final class Slip {",
            "    @ClassBuilder",
            "    Slip(@BuilderSeed String origin, String item) { }",
            "    public static class Builder {",
            "        public Builder(String origin) { this.origin = origin; }",
            "        public Builder(int copies) { this(String.valueOf(copies)); }",
            "        public Builder(long ignored) { }",
            "    }",
            "}");
        Compilation c = compile(slip);
        assertThat(c).failed();
        assertThat(c).hadErrorCount(1);
        assertThat(c).hadErrorContaining("variable origin might not have been initialized")
            .inFile(slip).onLine(10);
    }

    /**
     * An instance initializer that assigns nothing of the seed leaves the
     * constructor as responsible for it as it was, and javac refuses that
     * constructor. {@code DeclaredBuilderShapeInspectionTest} reports the same
     * constructor.
     */
    @Test
    public void merge_onASeededConstructor_leavingTheSeedUnassignedBesideAnInitializer_fails() {
        JavaFileObject slip = JavaFileObjects.forSourceLines("demo.Slip",
            "package demo;",
            "import dev.simplified.annotations.BuilderSeed;",
            "import dev.simplified.annotations.ClassBuilder;",
            "public final class Slip {",
            "    @ClassBuilder",
            "    Slip(@BuilderSeed String origin, String item) { }",
            "    public static class Builder {",
            "        private int count;",
            "        { count = 1; }",
            "        public Builder(String origin) { }",
            "    }",
            "}");
        Compilation c = compile(slip);
        assertThat(c).failed();
        assertThat(c).hadErrorCount(1);
        assertThat(c).hadErrorContaining("variable origin might not have been initialized")
            .inFile(slip).onLine(10);
    }

    /**
     * An instance initializer that assigns the seed assigns it for every
     * constructor, so a constructor that does not is accepted.
     */
    @Test
    public void merge_onASeededConstructor_whoseInitializerAssignsTheSeed_compiles() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Slip",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Slip {",
                "    private final String item;",
                "    @ClassBuilder",
                "    Slip(@BuilderSeed String origin, String item) { this.item = origin + \":\" + item; }",
                "    public String getItem() { return item; }",
                "    public static class Builder {",
                "        { origin = \"desk\"; }",
                "        public Builder(String ignored) { }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSlip",
                "package demo;",
                "public class UseSlip {",
                "    public static String go() { return Slip.builder(\"x\").item(\"tea\").build().getItem(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("desk:tea", runGo(c, "demo.UseSlip"));
    }

    /**
     * A builder declaring no constructor has javac's default retyped rather than
     * replaced, and that constructor assigns nothing, so the seed is reported
     * unassigned on it. Before the retype javac reported the same failure as
     * one of its own default constructor.
     */
    @Test
    public void merge_onASeededConstructor_whoseBuilderDeclaresNoConstructor_fails() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Stub",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Stub {",
                "    @ClassBuilder",
                "    Stub(@BuilderSeed String origin, String item) { }",
                "    public static class Builder { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("variable origin might not have been initialized");
    }

    /**
     * A refused shape on an executable target is an error, reported on the
     * constructor the author annotated, as every other diagnostic on that path
     * is.
     */
    @Test
    public void merge_onAConstructorTargetIntoANonStaticBuilder_isRejectedOnTheConstructor() {
        JavaFileObject hook = JavaFileObjects.forSourceLines("demo.Hook",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "public final class Hook {",
            "    private final String key;",
            "    @ClassBuilder",
            "    Hook(String key) { this.key = key; }",
            "    public class Builder { }",
            "}");
        Compilation c = compile(hook);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - an inner class "
                + "captures the enclosing instance, so builder() has nothing to create it from")
            .inFile(hook).onLine(6);
    }

    /**
     * The note for an entry point the declared builder cannot serve sits on the
     * constructor the author annotated, where the editor's weak warning sits and
     * every other diagnostic on that path is reported. It used to land on the
     * enclosing type's declaration.
     */
    @Test
    public void merge_onAConstructorTarget_notesTheSkippedEntryPointOnTheConstructor() {
        JavaFileObject gate = JavaFileObjects.forSourceLines("demo.Gate",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "public final class Gate {",
            "    private final String key;",
            "    @ClassBuilder",
            "    Gate(String key) { this.key = key; }",
            "    public static class Builder {",
            "        public Builder(String preset) { this.key = preset; }",
            "    }",
            "}");
        Compilation c = compile(gate);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but every constructor it "
                + "declares takes parameters, so 'builder' was not added")
            .inFile(gate).onLine(6);
    }

    // ------------------------------------------------------------------
    // What a declared member covers, and the shapes it cannot take
    //
    // Each case is a reviewed reproduction: the merge accepted the shape and
    // javac then failed on a generated line the author never wrote, or refused
    // a shape whose hand expansion compiles.
    // ------------------------------------------------------------------

    /**
     * An author method sharing a slot setter's name and arity but taking
     * another type is an overload rather than the setter, so the generated
     * setter is appended beside it. It used to be skipped on the name and arity
     * alone, and {@code from(T)} and {@code mutate()} then passed the slot's
     * {@code int} to the author's {@code port(String)} on the class line.
     */
    @Test
    public void merge_anAuthorMethodTakingAnotherTypeUnderASettersName_keepsTheGeneratedSetter()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Server",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Server {",
                "    int port;",
                "    public static class Builder {",
                "        public Builder port(String text) {",
                "            this.port = Integer.parseInt(text);",
                "            return this;",
                "        }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseServer",
                "package demo;",
                "public class UseServer {",
                "    public static String go() {",
                "        Server fresh = Server.builder().port(\"80\").build();",
                "        Server copy = Server.from(fresh).port(9090).build();",
                "        Server edit = copy.mutate().port(\"1\").build();",
                "        return fresh.port + \":\" + copy.port + \":\" + edit.port;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("80:9090:1", runGo(c, "demo.UseServer"));
    }

    /**
     * One author method covers only the generated overload it spells: an
     * {@code Optional} slot's {@code label(Optional<String>)} is still appended
     * beside the author's {@code label(String)}, and it is the one
     * {@code from(T)} passes the slot to. Both used to be dropped for the one.
     */
    @Test
    public void merge_anAuthorMethodCoveringOneOptionalOverload_keepsTheOther() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Labelled",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.Optional;",
                "@ClassBuilder",
                "public class Labelled {",
                "    Optional<String> label;",
                "    public static class Builder {",
                "        public Builder label(String l) { this.label = Optional.ofNullable(l); return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLabelled",
                "package demo;",
                "public class UseLabelled {",
                "    public static String go() {",
                "        Labelled first = Labelled.builder().label(\"a\").build();",
                "        return Labelled.from(first).build().label.orElse(\"none\");",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a", runGo(c, "demo.UseLabelled"));
    }

    /**
     * A {@code final} field under a slot's name cannot take what the generated
     * setter assigns, so it is refused on the author's declaration. It passed
     * every check, and javac then reported {@code cannot assign a value to final
     * variable items} on the class line. {@code DeclaredBuilderShapeInspectionTest}
     * asserts the same sentence.
     */
    @Test
    public void merge_ontoAFinalSlotField_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Bag",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "@ClassBuilder",
                "public class Bag {",
                "    List<String> items;",
                "    public static class Builder {",
                "        private final List<String> items = new ArrayList<>();",
                "        public Builder item(String item) {",
                "            items.add(item);",
                "            return this;",
                "        }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'items' declared "
            + "final, and the generated setter assigns it");
    }

    /**
     * A standalone builder re-declaring the target's type parameters has to
     * bound them as the target does, in either direction: a looser bound fails
     * the generated {@code build()}, a narrower one the generated
     * {@code builder()}. Only the names were compared, and javac reported
     * {@code type argument T is not within bounds of type-variable T} on the
     * class line.
     */
    @Test
    public void merge_ontoABuilderBoundingATypeParameterOtherwise_isRejected() {
        Compilation looser = compile(
            JavaFileObjects.forSourceLines("demo.Box",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Box<T extends Number> {",
                "    T value;",
                "    public static class Builder<T> {",
                "        public Builder<T> twice(T v) { this.value = v; return this; }",
                "    }",
                "}"));
        assertThat(looser).failed();
        assertThat(looser).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - a static "
            + "nested builder has to bound the target's type parameters as <T extends Number>, and this "
            + "one declares <T>");

        Compilation narrower = compile(
            JavaFileObjects.forSourceLines("demo.Box",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Box<T> {",
                "    T value;",
                "    public static class Builder<T extends Number> {",
                "        public Builder<T> twice(T v) { this.value = v; return this; }",
                "    }",
                "}"));
        assertThat(narrower).failed();
        assertThat(narrower).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - a static "
            + "nested builder has to bound the target's type parameters as <T>, and this one declares "
            + "<T extends Number>");
    }

    /** The same bound spelled with its qualifier is the same bound. */
    @Test
    public void merge_ontoABuilderBoundingATypeParameterAsTheTargetDoes_isMergedInto() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Box",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Box<T extends Number> {",
                "    T value;",
                "    public static class Builder<T extends java.lang.Number> {",
                "        public Builder<T> twice(T v) { this.value = v; return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBox",
                "package demo;",
                "public class UseBox {",
                "    public static Object go() { return Box.<Integer>builder().value(1).build().value; }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(1, runGo(c, "demo.UseBox"));
    }

    /**
     * A record, an enum and an interface are each implicitly static, and none
     * can be a builder: a record takes no instance field, an enum no
     * {@code new}, an interface neither. Each was refused as an inner class the
     * editor saw as static, and written {@code static} each was merged into and
     * failed on a line the author never wrote.
     */
    @Test
    public void merge_intoANestedRecordEnumOrInterface_isRejectedAsNotAClass() {
        String[][] shapes = {
            {"record Builder(int unused) { }", "a record"},
            {"static record Builder(int unused) { }", "a record"},
            {"enum Builder { ; }", "an enum"},
            {"static enum Builder { ; }", "an enum"},
            {"interface Builder { }", "an interface"},
        };
        for (String[] shape : shapes) {
            Compilation c = compile(
                JavaFileObjects.forSourceLines("demo.Note",
                    "package demo;",
                    "import dev.simplified.annotations.ClassBuilder;",
                    "@ClassBuilder",
                    "public class Note {",
                    "    String text;",
                    "    " + shape[0],
                    "}"));
            assertThat(c).failed();
            assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - it is declared "
                + "as " + shape[1] + ", and only a class can hold the builder's fields and the "
                + "constructor builder() calls");
            assertThat(c).hadErrorCount(1);
        }
    }

    /**
     * A boxed field over a primitive slot is refused: left unset, it reaches
     * the primitive constructor parameter as {@code null} and {@code build()}
     * throws. It was accepted, and an unset builder threw
     * {@code NullPointerException} where a generated one passes {@code 0}.
     * {@code DeclaredBuilderShapeInspectionTest} asserts the same sentence.
     */
    @Test
    public void merge_ontoABoxedFieldOverAPrimitiveSlot_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Counter",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Counter {",
                "    int count;",
                "    public static class Builder {",
                "        private Integer count;",
                "        public Builder bump() {",
                "            this.count = count == null ? 1 : count + 1;",
                "            return this;",
                "        }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'count' declared as "
            + "Integer, and the slot it stands for is int - an unset Integer field reaches the primitive "
            + "constructor parameter as null");
    }

    /**
     * A primitive field over a boxed slot takes every value the generated
     * members assign, under unboxing, and is never {@code null} to hand on, so
     * it is merged into.
     */
    @Test
    public void merge_ontoAPrimitiveFieldOverABoxedSlot_isMergedInto() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Counter",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Counter {",
                "    Integer count;",
                "    public static class Builder {",
                "        private int count;",
                "        public Builder bump() {",
                "            this.count++;",
                "            return this;",
                "        }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseCounter",
                "package demo;",
                "public class UseCounter {",
                "    public static Object go() {",
                "        Counter first = Counter.builder().bump().bump().build();",
                "        return Counter.from(first).bump().build().count;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(3, runGo(c, "demo.UseCounter"));
    }

    /**
     * A {@code final} field whose slot's every generated setter the author
     * spells is left for the author's own members to assign - nothing the merge
     * appends writes it - so it is merged into. It was refused whatever the
     * author wrote, though the builder compiles. The author's setter here hands
     * back a fresh builder rather than assigning.
     */
    @Test
    public void merge_ontoAFinalSlotEverySetterOfWhichTheAuthorSpells_isMergedInto() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Server",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Server {",
                "    int port;",
                "    public static class Builder {",
                "        private final int port;",
                "        public Builder() { this(80); }",
                "        private Builder(int port) { this.port = port; }",
                "        public Builder port(int port) { return new Builder(port); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseServer",
                "package demo;",
                "public class UseServer {",
                "    public static Object go() { return Server.builder().port(9).build().port; }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(9, runGo(c, "demo.UseServer"));
    }

    /**
     * A {@code final} field some of whose slot's setters the author leaves to
     * the generator is still refused - the generated {@code enabled()} assigns
     * it.
     */
    @Test
    public void merge_ontoAFinalSlotOneOfWhoseSettersIsLeftGenerated_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Switch",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Switch {",
                "    boolean enabled;",
                "    public static class Builder {",
                "        private final boolean enabled;",
                "        public Builder() { this(false); }",
                "        private Builder(boolean enabled) { this.enabled = enabled; }",
                "        public Builder enabled(boolean enabled) { return new Builder(enabled); }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'enabled' declared "
            + "final, and the generated setter assigns it");
    }

    /**
     * An author method covering a generated setter under the method key while
     * taking another parameterisation of the same generic type cannot take the
     * slot's own type, which {@code from(T)} and {@code mutate()} pass it, so it
     * is refused on the class. It covered the setter with nothing said, and
     * javac failed on the generated {@code from(T)}.
     * {@code DeclaredBuilderShapeInspectionTest} asserts the same sentence.
     */
    @Test
    public void merge_anAuthorMethodCoveringASetterWithOtherTypeArguments_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Bag",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "@ClassBuilder",
                "public class Bag {",
                "    List<String> items;",
                "    public static class Builder {",
                "        public Builder items(List<Integer> codes) {",
                "            this.items = new ArrayList<>();",
                "            for (Integer code : codes) this.items.add(\"#\" + code);",
                "            return this;",
                "        }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds items(List<Integer>) "
            + "standing in for the generated items(List<String>), and 'from' and 'mutate' pass it the "
            + "slot's List<String>, which its List<Integer> parameter cannot take");
    }

    /**
     * The same author method on a constructor target, where no {@code from(T)}
     * or {@code mutate()} is emitted to pass it the slot, compiles and is left
     * alone.
     */
    @Test
    public void merge_onAConstructorTarget_anAuthorMethodCoveringASetterWithOtherTypeArguments_compiles()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Bag",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "public class Bag {",
                "    final List<String> items;",
                "    @ClassBuilder",
                "    Bag(List<String> items) { this.items = items; }",
                "    public static class Builder {",
                "        public Builder items(List<Integer> codes) {",
                "            this.items = new ArrayList<>();",
                "            for (Integer code : codes) this.items.add(\"#\" + code);",
                "            return this;",
                "        }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBag",
                "package demo;",
                "public class UseBag {",
                "    public static Object go() { return Bag.builder().items(java.util.List.of(1)).build().items; }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(List.of("#1"), runGo(c, "demo.UseBag"));
    }

    /**
     * A no-argument constructor declaring a throws clause is not one the entry
     * points can call, each of them calling it with nothing to handle what it
     * throws, so they are skipped with a note - the setters are still merged
     * in. javac used to report {@code unreported exception java.io.IOException
     * in default constructor} on the class line.
     */
    @Test
    public void merge_intoABuilderWhoseNoArgConstructorThrows_skipsTheEntryPointsWithANote() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Conn",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Conn {",
                "    String host;",
                "    public static class Builder {",
                "        Builder() throws java.io.IOException { }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseConn",
                "package demo;",
                "public class UseConn {",
                "    Conn make() throws java.io.IOException { return new Conn.Builder().host(\"h\").build(); }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but its no-argument "
            + "constructor declares a throws clause naming an exception not known to be unchecked, so "
            + "'builder', 'from' and 'mutate' were not added - declare one throwing only unchecked "
            + "exceptions or write them");
    }

    /**
     * A throws clause naming only known unchecked exceptions, simple or
     * qualified, leaves a constructor the entry points can call, so all three
     * are emitted. Any throws clause skipped them, over source whose entry
     * points compile.
     */
    @Test
    public void merge_intoABuilderWhoseNoArgConstructorThrowsOnlyUncheckedExceptions_keepsTheEntryPoints()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Conn",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Conn {",
                "    String host;",
                "    public static class Builder {",
                "        Builder() throws IllegalStateException, java.util.NoSuchElementException { }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseConn",
                "package demo;",
                "public class UseConn {",
                "    public static Object go() {",
                "        Conn first = Conn.builder().host(\"h\").build();",
                "        return Conn.from(first).build().host + first.mutate().host(\"i\").build().host;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("hi", runGo(c, "demo.UseConn"));
    }

    /**
     * A throws clause naming a type neither half can tell is unchecked - here
     * the author's own, which is - still skips the entry points: the name is
     * treated as checked, which is the answer that never emits a call javac
     * refuses.
     */
    @Test
    public void merge_intoABuilderWhoseNoArgConstructorThrowsAnUnknownName_skipsTheEntryPoints() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Conn",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Conn {",
                "    String host;",
                "    public static class Builder {",
                "        Builder() throws Failure { }",
                "    }",
                "    static class Failure extends RuntimeException { }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but its no-argument "
            + "constructor declares a throws clause");
    }

    // ------------------------------------------------------------------
    // The chain merge
    //
    // A root, a concrete link or a chained abstract whose builder is declared
    // has the role's members merged into it. The chain path used to abort on
    // the declaration with a note, so none of these compiled.
    // ------------------------------------------------------------------

    /** The abstract {@code Base} most chain cases hang a link below. */
    private static JavaFileObject base() {
        return JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public abstract class Base {",
            "    private String label;",
            "    public String getLabel() { return label; }",
            "}");
    }

    /** A root's author verb calls a generated setter and returns the merged self type. */
    @Test
    public void merge_onAnAbstractRoot_keepsTheAuthorsVerbs() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {",
                "        public B named(String first, String last) { return name(first + \" \" + last); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Circle",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Circle extends Shape {",
                "    private int radius;",
                "    public int getRadius() { return radius; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static String go() {",
                "        Circle c = Circle.builder().named(\"a\", \"b\").radius(2).build();",
                "        return c.getName() + \"/\" + c.getRadius();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a b/2", runGo(c, "demo.UseShape"));
    }

    /** A link's author verb reads a merged slot field and chains into the inherited setter. */
    @Test
    public void merge_onAConcreteLink_appendsToTheAuthorsBuilder() throws Exception {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "    public static class Builder extends Base.Builder<Link, Builder> {",
                "        public Builder shout() { this.extra = this.extra.toUpperCase(); return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLink",
                "package demo;",
                "public class UseLink {",
                "    public static String go() {",
                "        Link link = Link.builder().extra(\"x\").shout().label(\"l\").build();",
                "        return link.getLabel() + \"/\" + link.getExtra();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("l/X", runGo(c, "demo.UseLink"));
    }

    /**
     * A link declaring its builder and its own copy constructor, spelled
     * {@code Link.Builder} as a migrated {@code @SuperBuilder} class writes it,
     * keeps that constructor alone. The processor matched only the simple
     * spelling and appended a second, and javac reported
     * {@code constructor Link(demo.Link.Builder) is already defined}.
     */
    @Test
    public void merge_onAConcreteLink_keepsAQualifiedCopyConstructorAlone() throws Exception {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "    protected Link(Link.Builder b) { super(b); this.extra = b.extra + \"!\"; }",
                "    public static class Builder extends Base.Builder<Link, Builder> { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLink",
                "package demo;",
                "public class UseLink {",
                "    public static String go() {",
                "        Link link = Link.builder().label(\"l\").extra(\"x\").build();",
                "        return link.getLabel() + \"/\" + link.getExtra();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("l/x!", runGo(c, "demo.UseLink"));
    }

    /**
     * The same on a root declaring its builder, whose copy constructor takes
     * the wildcard form {@code Shape.Builder<?, ?>}. javac reported
     * {@code constructor Shape(demo.Shape.Builder<?,?>) is already defined}.
     */
    @Test
    public void merge_onAnAbstractRoot_keepsAQualifiedCopyConstructorAlone() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    protected Shape(Shape.Builder<?, ?> b) { this.name = b.name + \"!\"; }",
                "    public abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Circle",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Circle extends Shape {",
                "    private int radius;",
                "    public int getRadius() { return radius; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static String go() {",
                "        Circle c = Circle.builder().name(\"c\").radius(1).build();",
                "        return c.getName() + \"/\" + c.getRadius();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("c!/1", runGo(c, "demo.UseShape"));
    }

    /**
     * A chained abstract keeps its builder abstract and is given the setters
     * only - {@code self()} and {@code build()} stay the root's, inherited, so
     * the declared class carries neither.
     */
    @Test
    public void merge_onAChainedAbstract_keepsTheAuthorsVerbsAndStaysAbstract() throws Exception {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Mid",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Mid extends Base {",
                "    private String kind;",
                "    public String getKind() { return kind; }",
                "    public abstract static class Builder<T extends Mid, B extends Builder<T, B>>",
                "            extends Base.Builder<T, B> {",
                "        public B shapeless() { return kind(\"none\"); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Mid {",
                "    private int size;",
                "    public int getSize() { return size; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLeaf",
                "package demo;",
                "public class UseLeaf {",
                "    public static String go() {",
                "        Leaf leaf = Leaf.builder().label(\"l\").shapeless().size(3).build();",
                "        return leaf.getLabel() + \"/\" + leaf.getKind() + \"/\" + leaf.getSize();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("l/none/3", runGo(c, "demo.UseLeaf"));
        Class<?> builder = Class.forName("demo.Mid$Builder", false, loadClasses(c));
        assertTrue("the declared class stays abstract",
            java.lang.reflect.Modifier.isAbstract(builder.getModifiers()));
        List<String> declared = new ArrayList<>();
        for (java.lang.reflect.Method method : builder.getDeclaredMethods()) declared.add(method.getName());
        assertTrue("the setter is merged in: " + declared, declared.contains("kind"));
        assertFalse("and neither of the root's pair: " + declared,
            declared.contains("self") || declared.contains("build"));
    }

    /**
     * The trailing pair is spelled in the author's names, so every merged setter
     * returns the author's builder parameter rather than the generator's
     * {@code B} - a name the declaration does not have.
     */
    @Test
    public void merge_onARootWhoseSelfTypesAreNamedByTheAuthor_generatesSettersReturningThatName()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Node",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Node {",
                "    private String tag;",
                "    public String getTag() { return tag; }",
                "    public abstract static class Builder<R extends Node, S extends Builder<R, S>> {",
                "        public S tagged() { return tag(\"t\"); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Node {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseNode",
                "package demo;",
                "public class UseNode {",
                "    public static String go() {",
                "        Leaf leaf = Leaf.builder().tagged().extra(\"x\").build();",
                "        return leaf.getTag() + \"/\" + leaf.getExtra();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("t/x", runGo(c, "demo.UseNode"));
        Class<?> builder = Class.forName("demo.Node$Builder", false, loadClasses(c));
        assertEquals("the setter returns the author's parameter", "S",
            builder.getDeclaredMethod("tag", String.class).getGenericReturnType().getTypeName());
        assertEquals("and build() the author's other one", "R",
            builder.getDeclaredMethod("build").getGenericReturnType().getTypeName());
    }

    /**
     * A generic root declaring {@code T} itself leaves the generator's names
     * dodging it, and the author's pair is spelled however the author spells
     * it - the merged members take the declaration's names, not the dodge.
     */
    @Test
    public void merge_onARootDeclaringATypeParameterNamedT_doesNotCollide() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Holder",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Holder<T> {",
                "    private T value;",
                "    public T getValue() { return value; }",
                "    public abstract static class Builder<T, H extends Holder<T>, B extends Builder<T, H, B>> {",
                "        public B cleared() { return value(null); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.SHolder",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class SHolder extends Holder<String> {",
                "    private int n;",
                "    public int getN() { return n; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseHolder",
                "package demo;",
                "public class UseHolder {",
                "    public static String go() {",
                "        SHolder h = SHolder.builder().cleared().value(\"v\").n(1).build();",
                "        return h.getValue() + \"/\" + h.getN();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("v/1", runGo(c, "demo.UseHolder"));
    }

    /**
     * A root spelling its own abstract {@code build()} keeps it, and nothing
     * generated lands beside it; the link below still gets its one override.
     * Counted over the declared methods, since a lookup cannot tell an
     * inherited method from a redeclared one.
     */
    @Test
    public void merge_onARootDeclaringBuild_linksDoNotGenerateASecond() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {",
                "        public abstract T build();",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Circle",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Circle extends Shape {",
                "    private int radius;",
                "    public int getRadius() { return radius; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static String go() {",
                "        return Circle.builder().name(\"c\").radius(1).build().getName();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("Builder already spells build(0 args)");
        assertEquals("c", runGo(c, "demo.UseShape"));
        ClassLoader loader = loadClasses(c);
        assertEquals("one build() on the root's builder", 1,
            countDeclared(Class.forName("demo.Shape$Builder", false, loader), "build"));
        assertEquals("and one on the link's", 1,
            countDeclared(Class.forName("demo.Circle$Builder", false, loader), "build"));
    }

    /**
     * A root's {@code build()} returning the root itself is one every link's
     * generated {@code build()} overrides, each link being a subtype of the
     * root, so it stands in for the generated one. The shape check refused it
     * for not returning the self type, over a merge the hand-written equivalent
     * of which compiles.
     */
    @Test
    public void merge_onARootDeclaringBuildReturningTheRoot_isMergedInto() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {",
                "        public abstract Shape build();",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Circle",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Circle extends Shape {",
                "    private int radius;",
                "    public int getRadius() { return radius; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static String go() {",
                "        Circle c = Circle.builder().name(\"a\").radius(2).build();",
                "        return c.getName() + \"/\" + c.getRadius();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a/2", runGo(c, "demo.UseShape"));
    }

    /**
     * A link's author writing {@code self()} and {@code build()} keeps both, and
     * the merge adds neither beside them.
     */
    @Test
    public void merge_onALink_doesNotRedeclareSelfOrBuild() throws Exception {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "    public static class Builder extends Base.Builder<Link, Builder> {",
                "        @Override protected Builder self() { return this; }",
                "        @Override public Link build() { return new Link(this); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLink",
                "package demo;",
                "public class UseLink {",
                "    public static String go() {",
                "        return Link.builder().label(\"l\").extra(\"x\").build().getExtra();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("Builder already spells self(0 args), build(0 args)");
        assertEquals("x", runGo(c, "demo.UseLink"));
        Class<?> builder = Class.forName("demo.Link$Builder", false, loadClasses(c));
        assertEquals("one self()", 1, countDeclared(builder, "self"));
        assertEquals("one build()", 1, countDeclared(builder, "build"));
    }

    /**
     * A root's builder carries the abstract pair, so a concrete one cannot hold
     * the merge. This is the shape the root's parity case held while a chain
     * did not merge.
     */
    @Test
    public void merge_onARootWhoseDeclaredBuilderIsNotSelfTyped_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Rooted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public abstract class Rooted {",
                "    private String label;",
                "    public String getLabel() { return label; }",
                "    public static class Builder {",
                "        public Builder apply(Runnable task) { task.run(); return this; }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - the builder "
            + "of Rooted carries an abstract self() and build(), so the class holding them has to "
            + "be abstract too");
    }

    /**
     * An unbounded pair leaves a setter returning something with no members.
     * The sentence shows the bounds the pair needs beside the ones it has.
     */
    @Test
    public void merge_whereTheTrailingPairIsUnbounded_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private String name;",
                "    public abstract static class Builder<T, B> { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - its trailing "
            + "pair has to be bounded as <T extends Shape, B extends Builder<T, B>> for the generated "
            + "setters to return the caller's own builder type, and this one declares <T, B>");
    }

    /**
     * A link's builder inherits the ancestor's setters through its extends
     * clause. This is the shape the link's parity case held while a chain did
     * not merge.
     */
    @Test
    public void merge_onALinkWhoseDeclaredBuilderOmitsTheExtendsClause_isRejected() {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public static class Builder {",
                "        public Builder apply(Runnable task) { task.run(); return this; }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - the builder of "
            + "a chained target has to extend Base.Builder, and this one extends nothing");
    }

    /**
     * Another type's builder of the same simple name is not the ancestor's. Read
     * by erased simple name alone the clause passed as {@code Builder}, and the
     * build failed on the generated {@code super(b)}, whose parameter is the
     * ancestor's builder.
     */
    @Test
    public void merge_onALinkExtendingAnotherTypesBuilder_isRejected() {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Other",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Other {",
                "    private String note;",
                "    public abstract static class Builder<T extends Object, B extends Builder<T, B>> { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public static class Builder extends Other.Builder<Link, Builder> { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - the builder of "
            + "a chained target has to extend Base.Builder, and this one extends Other.Builder");
    }

    /**
     * The build method a link inherits is the one its role declares, so the
     * author's has to return the link.
     */
    @Test
    public void merge_onALinkDeclaringBuildReturningSomethingElse_isRejected() {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public static class Builder extends Base.Builder<Link, Builder> {",
                "        public Object build() { return null; }",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - its build "
            + "method returns Object where this role builds Link, so it cannot stand in for the "
            + "generated one");
    }

    /**
     * The extends clause's arguments are the ones the generated members are
     * typed against, so a pair written the wrong way round is named on the
     * author's line rather than left to fail inside the generated members.
     */
    @Test
    public void merge_onALinkPassingItsPairReversed_isRejected() {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public static class Builder extends Base.Builder<Builder, Link> { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - the builder of "
            + "a chained target has to pass Base.Builder the arguments <Link, Builder>, and this "
            + "one passes <Builder, Link>");
    }

    /**
     * A root that is not generic still declares the self-typed pair, and the
     * sentence names the pair rather than the target's parameters.
     */
    @Test
    public void merge_onARootWhoseBuilderDeclaresNoPair_namesTheSelfTypedPair() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private String name;",
                "    public abstract static class Builder { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - a static nested "
            + "builder for an abstract target in a builder chain has to declare the self-typed pair "
            + "<T, B>, and this one declares none");
    }

    /** A slot field on a root's declared builder is judged as on any other. */
    @Test
    public void merge_onAnAbstractRoot_ontoAMistypedSlot_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Shape {",
                "    private int size;",
                "    public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {",
                "        private String size;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'size' declared as "
            + "String, and the slot it stands for is int - the generated setter has nothing to "
            + "assign it to");
    }

    /**
     * A link's entry points instantiate its declared builder, so one declaring
     * only constructors that take parameters loses all three with the note the
     * type path gives - rather than a {@code builder()} whose {@code new Builder()}
     * fails on a generated line.
     */
    @Test
    public void merge_onAConcreteLinkWhoseBuilderTakesParameters_skipsTheEntryPointsWithANote()
        throws Exception {
        Compilation c = compile(base(),
            JavaFileObjects.forSourceLines("demo.Link",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Link extends Base {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "    public static class Builder extends Base.Builder<Link, Builder> {",
                "        public Builder(String extra) { this.extra = extra; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLink",
                "package demo;",
                "public class UseLink {",
                "    public static String go() {",
                "        return new Link.Builder(\"x\").label(\"l\").build().getExtra();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but every constructor "
            + "it declares takes parameters, so 'builder', 'from' and 'mutate' were not added");
        assertEquals("x", runGo(c, "demo.UseLink"));
    }

    /**
     * A link declaring its own builder is still asked whether its ancestor's
     * can take the extends clause, the processor asking ahead of the merge. A
     * concrete ancestor's declared builder binds nothing and cannot, so the
     * link is refused on the ancestor rather than merged into.
     */
    @Test
    public void aDeclaringLinkOverAConcreteAncestor_isRefusedOnTheAncestor() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Rooted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Rooted { public static class Builder { } }"),
            JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Rooted {",
                "    private String b;",
                "    public static class Builder { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder generates no builder on 'Leaf' - its "
            + "annotated supertype 'Rooted' declares its own nested builder");
    }

    /** Declared methods of that name, bridges excluded. */
    private static long countDeclared(Class<?> type, String name) {
        long found = 0;
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && !method.isBridge()) found++;
        }
        return found;
    }

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
     * target that declares its own nested builder compiles, and reaches both the
     * author's member and the merged ones, which is what makes the editor
     * withholding any of them a divergence rather than a preference. The
     * declaration used to suppress all three.
     */
    @Test
    public void declaredBuilder_emitsTheEntryPoints() throws Exception {
        BuilderParityFixture fixture = BuilderParityFixture.load("standalone-declared-builder");
        Compilation c = compile(
            parity(fixture),
            JavaFileObjects.forSourceLines("demo.UseUntouchedEntryPoints",
                "package demo;",
                "public class UseUntouchedEntryPoints {",
                "    public static String go() {",
                "        Untouched u = Untouched.builder().name(\"x\").apply(() -> { }).build();",
                "        return Untouched.from(u).build().getName() + \"/\" + u.mutate().build().getName();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        for (var diagnostic : c.diagnostics()) {
            assertFalse("the author's apply is not a generated member, so it is never reported: "
                    + diagnostic.getMessage(null),
                String.valueOf(diagnostic.getMessage(null)).contains("apply"));
        }
        assertEquals("x/x", runGo(c, "demo.UseUntouchedEntryPoints"));
    }

    /**
     * Writing the attribute that once asked for the merge is a compile error:
     * the merge runs on every declared builder, so there is nothing left for it
     * to ask.
     */
    @Test
    public void theAttribute_noLongerExists() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Asked",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false, mergeDeclaredBuilder = true)",
                "public class Asked {",
                "    private String name;",
                "    public static class Builder { }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("mergeDeclaredBuilder");
    }

    /**
     * A root's declared builder is merged into, so a link generated below it
     * extends the author's class and reaches the merged setter, the author's
     * verb and the merged {@code self()} it calls. The chain path used to abort
     * on the declaration with a note, leaving the class exactly as written.
     */
    @Test
    public void aDeclaredRootBuilder_isMergedInto() throws Exception {
        BuilderParityFixture fixture =
            BuilderParityFixture.load("chain-root-declared-builder");
        Compilation c = compile(
            parity(fixture),
            JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Rooted {",
                "    private String extra;",
                "    public String getExtra() { return extra; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRooted",
                "package demo;",
                "public class UseRooted {",
                "    public static String go() {",
                "        Leaf leaf = Leaf.builder().label(\"l\").apply(() -> { }).extra(\"x\").build();",
                "        return leaf.getLabel() + \"/\" + leaf.getExtra();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("l/x", runGo(c, "demo.UseRooted"));
    }

    /**
     * A link's extends clause names its ancestor's builder and passes it the
     * ancestor's own arguments plus the self-typed pair. Where the ancestor's
     * author wrote that class with none of them it cannot take the clause, and
     * the clause used to be emitted anyway and fail at attribution on a line
     * nobody wrote. The ancestor's declaration is itself refused as a root
     * shape, so the compilation carries both errors: the cause on the root and
     * its consequence on the link.
     */
    @Test
    public void aLinkWhoseAnnotatedSuperDeclaresItsOwnBuilder_isRejected() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Rooted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public abstract class Rooted {",
                "    private String label;",
                "    public String getLabel() { return label; }",
                "    public static class Builder {",
                "        public Builder apply(Runnable task) { task.run(); return this; }",
                "    }",
                "}"),
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
        assertThat(c).hadErrorContaining("@ClassBuilder cannot merge into 'Builder' - the builder "
            + "of Rooted carries an abstract self() and build(), so the class holding them has to "
            + "be abstract too");
    }

    /**
     * A chain over an ancestor whose builder was generated rather than written
     * is not the refused shape. The index reads any nested class of the builder's
     * name, so without asking which of the two it is, an ordinary chain over a
     * concrete link was refused and the ancestor's author blamed for a class
     * they never wrote.
     */
    @Test
    public void aChainOverAGeneratedAncestorBuilder_isNotRefused() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Base",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Base {",
                "    private String a;",
                "    public String getA() { return a; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Mid",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Mid extends Base {",
                "    private String b;",
                "    public String getB() { return b; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Leafy",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leafy extends Mid {",
                "    private String c;",
                "    public String getC() { return c; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseLeafy",
                "package demo;",
                "public class UseLeafy {",
                "    public static String go() {",
                "        return Leafy.builder().a(\"x\").b(\"y\").c(\"z\").build().getA();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x", runGo(c, "demo.UseLeafy"));
    }

    /**
     * A concrete class extending another concrete annotated class is a shape the
     * generator cannot express - the parent's builder binds its self types and
     * cannot be extended - and it failed to compile before this work and after
     * it. What must not happen is blaming the parent's author for declaring a
     * builder they never wrote: the index reads any nested class of the name,
     * and the parent's was generated.
     */
    @Test
    public void aChainOverAConcreteAncestor_isNotBlamedOnADeclarationThatDoesNotExist() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Outer",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Outer {",
                "    private String a;",
                "    public String getA() { return a; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Inner2",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Inner2 extends Outer {",
                "    private String b;",
                "    public String getB() { return b; }",
                "}"));
        for (var diagnostic : c.diagnostics()) {
            assertFalse("the parent declares no builder, so it must not be blamed for one: "
                    + diagnostic.getMessage(null),
                String.valueOf(diagnostic.getMessage(null))
                    .contains("declares its own nested builder"));
        }
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
     *
     * <p>The ancestor is compiled without the processor, which is the only form
     * in which a declared, unmerged root builder still exists: with the
     * processor on, the root's own declaration is merged into or refused.
     */
    @Test
    public void aLinkWhoseCompiledSuperDeclaresItsOwnBuilder_isRejected() throws Exception {
        Compilation ancestor = Compiler.javac().compile(
            JavaFileObjects.forSourceLines("demo.Rooted",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public abstract class Rooted {",
                "    private String label;",
                "    public String getLabel() { return label; }",
                "    public static class Builder {",
                "        public Builder apply(Runnable task) { task.run(); return this; }",
                "    }",
                "}"));
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
     * A link's declared builder is merged into: its own setter and the concrete
     * pair land beside the author's verb, the ancestor's setter arrives through
     * the author's extends clause, and all three entry points return the
     * author's class. The chain path used to abort on the declaration, so a
     * consumer calling a generated setter on it failed.
     */
    @Test
    public void aDeclaredLinkBuilder_isMergedInto() throws Exception {
        BuilderParityFixture fixture =
            BuilderParityFixture.load("chain-link-declared-builder");
        Compilation c = compile(
            parity(fixture),
            JavaFileObjects.forSourceLines("demo.UseLink",
                "package demo;",
                "public class UseLink {",
                "    public static String go() {",
                "        Link link = Link.builder().label(\"l\").apply(() -> { }).extra(\"x\").build();",
                "        Link copy = Link.from(link).apply(() -> { }).build();",
                "        return copy.getLabel() + \"/\" + link.mutate().extra(\"y\").build().getExtra();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("l/y", runGo(c, "demo.UseLink"));
    }

    // ------------------------------------------------------------------
    // Reviewed reproductions: the constructor and factory path
    // ------------------------------------------------------------------

    /**
     * {@code builder(seed)} passes the seed to the builder's constructor, so a
     * constructor of the seed count taking another type is not one it can call.
     * The count alone was compared, the entry point was emitted against
     * {@code Builder(int)}, and javac failed on the class line with
     * {@code String cannot be converted to int}. It is skipped with the note
     * instead, and the rest of the merge compiles.
     */
    @Test
    public void merge_onASeededConstructorWhoseBuilderTakesAnotherTypeAtTheSeedsArity_skipsTheEntryPoint() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Order",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Order {",
                "    private final String origin;",
                "    private final String item;",
                "    @ClassBuilder",
                "    Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }",
                "    public String origin() { return origin; }",
                "    public static final class Builder {",
                "        Builder(int code) { this.origin = \"code-\" + code; }",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining(SEED_SKIPPED);
    }

    /** The seeds are passed in parameter order, so a constructor taking them swapped is not one either. */
    @Test
    public void merge_onTwoSeedsWhoseBuilderTakesThemInAnotherOrder_skipsTheEntryPoint() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Order",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Order {",
                "    private final String origin;",
                "    private final int qty;",
                "    private final String item;",
                "    @ClassBuilder",
                "    Order(@BuilderSeed String origin, @BuilderSeed int qty, String item) {",
                "        this.origin = origin; this.qty = qty; this.item = item;",
                "    }",
                "    public String origin() { return origin; }",
                "    public static final class Builder {",
                "        Builder(int qty, String origin) { this.qty = qty; this.origin = origin; }",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but no single constructor it "
            + "declares takes the 2 seeds 'builder' passes as their own types, their boxes or primitives, wider "
            + "primitives or Object, so 'builder' was not added - declare a constructor taking (origin, qty) or "
            + "write it");
    }

    /**
     * The seeds' types in order, written in any spelling of them, still serve -
     * a qualified name, a type argument and a type variable included.
     */
    @Test
    public void merge_onSeedsWhoseBuilderTakesTheirTypesInAnotherSpelling_keepsTheEntryPoint() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Slot",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.List;",
                "public final class Slot<V> {",
                "    private final String origin;",
                "    private final List<V> values;",
                "    private final String item;",
                "    @ClassBuilder",
                "    Slot(@BuilderSeed String origin, @BuilderSeed List<V> values, String item) {",
                "        this.origin = origin; this.values = values; this.item = item;",
                "    }",
                "    public String describe() { return origin + values + item; }",
                "    public static final class Builder<V> {",
                "        Builder(java.lang.String origin, java.util.List<V> values) {",
                "            this.origin = origin; this.values = values;",
                "        }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSlot",
                "package demo;",
                "public class UseSlot {",
                "    public static String go() {",
                "        return Slot.<Integer>builder(\"a\", java.util.List.of(1)).item(\"b\").build().describe();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a[1]b", runGo(c, "demo.UseSlot"));
    }

    /**
     * An {@code Order} whose one seed is {@code origin} of {@code seedType},
     * whose declared builder carries {@code constructors}, and a
     * {@code demo.UseOrder.go()} entering it through {@code builder(argument)}.
     */
    private static JavaFileObject[] seededOrder(String seedType, String constructors, String argument) {
        return new JavaFileObject[]{
            JavaFileObjects.forSourceLines("demo.Order",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Order {",
                "    private final " + seedType + " origin;",
                "    private final String item;",
                "    @ClassBuilder",
                "    Order(@BuilderSeed " + seedType + " origin, String item) {",
                "        this.origin = origin; this.item = item;",
                "    }",
                "    public String describe() { return origin + \":\" + item; }",
                "    public static final class Builder {",
                "        " + constructors,
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseOrder",
                "package demo;",
                "public class UseOrder {",
                "    public static String go() {",
                "        return Order.builder(" + argument + ").item(\"x\").build().describe();",
                "    }",
                "}")
        };
    }

    /**
     * {@code builder(seed)} reaches a constructor taking the seed's box, as
     * javac's own call would. Only equal types were counted, so the entry point
     * was skipped and the caller failed with {@code cannot find symbol}.
     */
    @Test
    public void merge_onAPrimitiveSeedWhoseBuilderTakesItsBox_keepsTheEntryPoint() throws Exception {
        Compilation c = compile(seededOrder("int",
            "Builder(Integer origin) { this.origin = origin; }", "3"));
        assertThat(c).succeeded();
        assertEquals("3:x", runGo(c, "demo.UseOrder"));
    }

    /** A boxed seed reaches a constructor taking its primitive. */
    @Test
    public void merge_onABoxedSeedWhoseBuilderTakesItsPrimitive_keepsTheEntryPoint() throws Exception {
        Compilation c = compile(seededOrder("Integer",
            "Builder(int origin) { this.origin = origin; }", "3"));
        assertThat(c).succeeded();
        assertEquals("3:x", runGo(c, "demo.UseOrder"));
    }

    /** A primitive seed reaches a constructor taking a wider primitive. */
    @Test
    public void merge_onAPrimitiveSeedWhoseBuilderTakesAWiderPrimitive_keepsTheEntryPoint() throws Exception {
        Compilation c = compile(seededOrder("int",
            "Builder(long origin) { this.origin = (int) (origin * 2); }", "3"));
        assertThat(c).succeeded();
        assertEquals("6:x", runGo(c, "demo.UseOrder"));
    }

    /** A reference seed reaches a constructor taking {@code Object}. */
    @Test
    public void merge_onAReferenceSeedWhoseBuilderTakesObject_keepsTheEntryPoint() throws Exception {
        Compilation c = compile(seededOrder("String",
            "Builder(java.lang.Object origin) { this.origin = origin + \"!\"; }", "\"web\""));
        assertThat(c).succeeded();
        assertEquals("web!:x", runGo(c, "demo.UseOrder"));
    }

    /**
     * Any other supertype is one names cannot vouch for, so the entry point is
     * skipped with the note, which says what is counted.
     */
    @Test
    public void merge_onAReferenceSeedWhoseBuilderTakesAnotherSupertype_skipsTheEntryPointWithTheNote() {
        JavaFileObject[] sources = seededOrder("String",
            "Builder(CharSequence origin) { this.origin = origin.toString(); }", "\"web\"");
        Compilation c = compile(sources[0]);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining(SEED_SKIPPED);
    }

    /**
     * Two constructors reached by widening, neither more specific than the
     * other, are ambiguous to javac, so no single one serves and the entry
     * point is skipped rather than emitted onto {@code reference to Builder is
     * ambiguous}.
     */
    @Test
    public void merge_onSeedsTwoBuilderConstructorsTakeAmbiguously_skipsTheEntryPoint() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Grid",
                "package demo;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Grid {",
                "    private final int x;",
                "    private final int y;",
                "    private final String label;",
                "    @ClassBuilder",
                "    Grid(@BuilderSeed int x, @BuilderSeed int y, String label) {",
                "        this.x = x; this.y = y; this.label = label;",
                "    }",
                "    public static final class Builder {",
                "        Builder(long x, int y) { this.x = (int) x; this.y = y; }",
                "        Builder(int x, long y) { this.x = x; this.y = (int) y; }",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but no single constructor it "
            + "declares takes the 2 seeds 'builder' passes");
    }

    /**
     * javac selects a widening before a boxing, so where the widened
     * constructor throws, the entry point would call it with nothing to handle
     * the exception - skipped with the throws note, though a boxed constructor
     * throwing nothing sits beside it.
     */
    @Test
    public void merge_onASeedWhoseSelectedWidenedConstructorThrows_skipsTheEntryPointWithTheThrowsNote() {
        JavaFileObject[] sources = seededOrder("int",
            "Builder(long origin) throws Exception { this.origin = (int) origin; } "
                + "Builder(Integer origin) { this.origin = origin; }", "3");
        Compilation c = compile(sources[0]);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but its constructor taking the "
            + "seed 'builder' passes declares a throws clause naming an exception not known to be unchecked, so "
            + "'builder' was not added - declare one throwing only unchecked exceptions or write it");
    }

    /**
     * A {@code Held} target whose declared builder carries {@code annotation}
     * and {@code body}, and a {@code demo.UseHeld.go()} returning {@code use}.
     */
    private static JavaFileObject[] heldWith(String annotation, String body, String use) {
        return new JavaFileObject[]{
            JavaFileObjects.forSourceLines("demo.Held",
                "package demo;",
                "import dev.simplified.annotations.*;",
                "@ClassBuilder",
                "public class Held {",
                "    private String name;",
                "    public String getName() { return name; }",
                "    " + annotation,
                "    public static class Builder {",
                "        " + body,
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseHeld",
                "package demo;",
                "public class UseHeld {",
                "    public static String go() { return " + use + "; }",
                "}")
        };
    }

    /**
     * The constructor pass runs before the merge, so a constructor an
     * {@code @AllArgsConstructor} on the declared builder appends is one the
     * entry points are counted against: taking parameters, it leaves them
     * nothing to call and they are skipped with the note. The javac twin of the
     * editor case, which counted only the author's constructors.
     */
    @Test
    public void merge_intoABuilderWhoseOnlyConstructorAnAllArgsAnnotationAppends_skipsTheEntryPoints()
        throws Exception {
        Compilation c = compile(heldWith("@AllArgsConstructor", "private String tag;",
            "new Held.Builder(\"t\").name(\"a\").build().getName()"));
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but every constructor it declares "
            + "takes parameters, so 'builder', 'from' and 'mutate' were not added - declare a no-argument "
            + "constructor or write them");
        assertEquals("a", runGo(c, "demo.UseHeld"));
    }

    /** The same for {@code @RequiredArgsConstructor} over a final field. */
    @Test
    public void merge_intoABuilderWhoseOnlyConstructorARequiredArgsAnnotationAppends_skipsTheEntryPoints() {
        Compilation c = compile(heldWith("@RequiredArgsConstructor", "private final String tag;",
            "Held.builder().name(\"a\").build().getName()"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot find symbol");
        assertThat(c).hadNoteContaining("@ClassBuilder merged into 'Builder' but every constructor it declares "
            + "takes parameters");
    }

    /**
     * A no-argument constructor {@code @NoArgsConstructor} appends beside the
     * author's parameterised one is one the entry points can call, so they are
     * emitted.
     */
    @Test
    public void merge_intoABuilderWithANoArgsAnnotationBesideAParameterConstructor_keepsTheEntryPoints()
        throws Exception {
        Compilation c = compile(heldWith("@NoArgsConstructor", "public Builder(String tag) { }",
            "Held.builder().name(\"a\").build().getName() + Held.from(new Held.Builder(\"t\").name(\"b\").build())"
                + ".build().getName()"));
        assertThat(c).succeeded();
        assertEquals("ab", runGo(c, "demo.UseHeld"));
    }

    /**
     * On a constructor target the constructor {@code @AllArgsConstructor}
     * appends over the author's seed field takes the seed, so
     * {@code builder(seed)} is emitted and calls it.
     */
    @Test
    public void merge_onAConstructorTargetWhoseAllArgsBuilderTakesTheSeed_keepsTheEntryPoint() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Order",
                "package demo;",
                "import dev.simplified.annotations.AllArgsConstructor;",
                "import dev.simplified.annotations.BuilderSeed;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Order {",
                "    private final String origin;",
                "    private final String item;",
                "    @ClassBuilder",
                "    Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }",
                "    public String describe() { return origin + \":\" + item; }",
                "    @AllArgsConstructor",
                "    public static final class Builder {",
                "        private final String origin;",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseOrder",
                "package demo;",
                "public class UseOrder {",
                "    public static String go() { return Order.builder(\"web\").item(\"x\").build().describe(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("web:x", runGo(c, "demo.UseOrder"));
    }

    /**
     * A static factory inside an interface is an executable target, and the
     * class the interface body declares is merged into and entered through
     * {@code builder()}. The javac twin of the editor case, which read every
     * interface owner as an interface type target.
     */
    @Test
    public void merge_onAStaticFactoryInAnInterface_mergesIntoItsDeclaredBuilder() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Circle",
                "package demo;",
                "public record Circle(double radius) { }"),
            JavaFileObjects.forSourceLines("demo.Shapes",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public interface Shapes {",
                "    @ClassBuilder",
                "    static Circle circle(double radius) { return new Circle(radius); }",
                "    class Builder {",
                "        public Builder doubled() { this.radius = radius * 2; return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseShapes",
                "package demo;",
                "public class UseShapes {",
                "    public static double go() {",
                "        return new Shapes.Builder().radius(1.5).doubled().build().radius()",
                "            + Shapes.builder().radius(1).doubled().build().radius();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(5.0, runGo(c, "demo.UseShapes"));
    }

    /**
     * A varargs parameter's slot is the array it is, so a declared {@code String[]}
     * field of its name holds it. The javac twin of the editor case, which
     * rendered the slot's ellipsis type and reported the field.
     */
    @Test
    public void merge_aVarargsParameterSlotOverAnArrayField_isMergedInto() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tags",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Tags {",
                "    private final String[] values;",
                "    @ClassBuilder",
                "    Tags(String... values) { this.values = values; }",
                "    public int count() { return values.length; }",
                "    public static final class Builder {",
                "        private String[] values = new String[0];",
                "        public Builder none() { this.values = new String[0]; return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseTags",
                "package demo;",
                "public class UseTags {",
                "    public static int go() { return Tags.builder().values(\"a\", \"b\").build().count(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(2, runGo(c, "demo.UseTags"));
    }

    /**
     * A declared builder whose {@code build()} the author wrote calls the
     * author's own constructor, which leaves a {@code final} field to its
     * initializer. The initializer was lifted off the field all the same, for a
     * generated constructor that is never emitted, and the author's constructor
     * failed with {@code variable retries might not have been initialized}.
     */
    @Test
    public void merge_besideAnAuthorBuild_keepsAFinalInitializerTheAuthorsConstructorLeaves()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Config",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Config {",
                "    private final String name;",
                "    private final int retries = 3;",
                "    public Config(String name) { this.name = name; }",
                "    public String name() { return name; }",
                "    public int retries() { return retries; }",
                "    public static class Builder {",
                "        private String name;",
                "        public Builder name(String name) { this.name = name; return this; }",
                "        public Config build() { return new Config(name); }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseConfig",
                "package demo;",
                "public class UseConfig {",
                "    public static String go() {",
                "        Config c = Config.builder().name(\"x\").retries(5).build();",
                "        return c.name() + c.retries();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x3", runGo(c, "demo.UseConfig"));
    }

    /**
     * The chain twin: a root's declared builder is merged into and the author's
     * copy constructor is kept, which assigns one {@code final} field and leaves
     * the other to its initializer. The lift took that initializer off too, and
     * the copy constructor failed with {@code variable sides might not have
     * been initialized}.
     */
    @Test
    public void merge_onAnAbstractRoot_keepsAFinalInitializerTheAuthorsCopyConstructorLeaves()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public abstract class Shape {",
                "    private final String name;",
                "    private final int sides = 0;",
                "    protected Shape(Builder<?, ?> b) { this.name = b.name; }",
                "    public String name() { return name; }",
                "    public int sides() { return sides; }",
                "    public abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Square",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public class Square extends Shape {",
                "    private int size;",
                "    public int size() { return size; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static String go() {",
                "        Square s = Square.builder().name(\"sq\").sides(4).size(2).build();",
                "        return s.name() + s.sides() + s.size();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("sq02", runGo(c, "demo.UseShape"));
    }

    /**
     * A {@code @Collector} slot whose default reads the instance is held in a
     * plain {@code java.util} scratch container, which an author's verb adds to
     * before the constructor folds it onto the default.
     * {@code DeclaredBuilderMergeParityTest} resolves the same verb against the
     * same container.
     */
    @Test
    public void merge_aCollectedSlotWhoseInitializerReadsTheInstance_isItsScratchContainerToAnAuthorVerb()
        throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tagged",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Tagged {",
                "    private String name;",
                "    @Collector private ArrayList<String> tags = new ArrayList<>(List.of(String.valueOf(name)));",
                "    public List<String> getTags() { return tags; }",
                "    public static class Builder {",
                "        public Builder foo() { this.tags.add(\"foo\"); return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseTagged",
                "package demo;",
                "public class UseTagged {",
                "    public static Object go() {",
                "        return Tagged.builder().name(\"n\").foo().build().getTags().toString();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("[n, foo]", runGo(c, "demo.UseTagged"));
    }

    /**
     * A declared field of the collected slot's declared type is not the scratch
     * container the merge holds it in, and is refused in the sentence naming the
     * container - a list's and a map's. {@code DeclaredBuilderShapeInspectionTest}
     * asserts the same sentences.
     */
    @Test
    public void merge_ontoACollectedSlotWhoseInitializerReadsTheInstance_namesTheScratchContainer() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tagged",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.ArrayList;",
                "import java.util.LinkedHashMap;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Tagged {",
                "    private String name;",
                "    @Collector private ArrayList<String> tags = new ArrayList<>(List.of(String.valueOf(name)));",
                "    @Collector private LinkedHashMap<String, Integer> counts = seed(name);",
                "    private static LinkedHashMap<String, Integer> seed(String name) {",
                "        return new LinkedHashMap<>();",
                "    }",
                "    public static class Builder {",
                "        private ArrayList<String> tags;",
                "        private LinkedHashMap<String, Integer> counts;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'tags' declared as "
            + "ArrayList<String>, and the slot it stands for is java.util.List<java.lang.String> - the "
            + "generated setter has nothing to assign it to");
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'counts' declared as "
            + "LinkedHashMap<String, Integer>, and the slot it stands for is "
            + "java.util.Map<java.lang.String, java.lang.Integer> - the generated setter has nothing to "
            + "assign it to");
    }

    /**
     * An initializer calling a getter {@code @Getter} generates reads the
     * instance, so its slot is a supplier and a declared field of the slot's
     * declared type is refused in the sentence naming it. The detector listed
     * only the members the target declared before the accessor pass ran, so the
     * default was hoisted into the static provider and javac refused the class
     * with {@code non-static method getName() cannot be referenced from a static
     * context}. {@code DeclaredBuilderShapeInspectionTest} asserts the same
     * sentence.
     */
    @Test
    public void merge_ontoASlotWhoseInitializerCallsAGeneratedGetter_namesTheSupplier() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Named",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Getter;",
                "@ClassBuilder(validate = false)",
                "@Getter",
                "public class Named {",
                "    private String name;",
                "    private String label = getName() + \"!\";",
                "    public static class Builder {",
                "        private String label;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorCount(1);
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'label' declared as "
            + "String, and the slot it stands for is java.util.function.Supplier<java.lang.String> - the "
            + "generated setter has nothing to assign it to. A slot whose retained initializer reads "
            + "instance state is held in the builder as a supplier of its declared type");
    }

    /**
     * A setter {@code @Setter} generates and a getter {@code @Lazy} generates
     * are instance methods too, each named through the scheme its annotation
     * writes, so an initializer calling either holds its slot as a supplier. The
     * detector listed neither, and javac refused each hoisted default with
     * {@code non-static method ... cannot be referenced from a static context}.
     * {@code DeclaredBuilderShapeInspectionTest} asserts the same sentences.
     */
    @Test
    public void merge_ontoSlotsWhoseInitializersCallAGeneratedSetterOrLazyGetter_nameTheSupplier() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Named",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Lazy;",
                "import dev.simplified.annotations.Setter;",
                "@ClassBuilder(validate = false)",
                "public class Named {",
                "    @Setter private String name;",
                "    @Lazy(name = \"load{}\") private String heavy = compute();",
                "    private Runnable reset = () -> setName(\"x\");",
                "    private String label = loadHeavy() + \"!\";",
                "    private static String compute() { return \"h\"; }",
                "    public static class Builder {",
                "        private Runnable reset;",
                "        private String label;",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorCount(2);
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'reset' declared as "
            + "Runnable, and the slot it stands for is java.util.function.Supplier<java.lang.Runnable> - the "
            + "generated setter has nothing to assign it to. A slot whose retained initializer reads "
            + "instance state is held in the builder as a supplier of its declared type");
        assertThat(c).hadErrorContaining("@ClassBuilder merged into 'Builder' finds 'label' declared as "
            + "String, and the slot it stands for is java.util.function.Supplier<java.lang.String> - the "
            + "generated setter has nothing to assign it to. A slot whose retained initializer reads "
            + "instance state is held in the builder as a supplier of its declared type");
    }

    /**
     * The same slot is a supplier to an author's verb, and an unset one takes
     * the default the generated getter computes on the built instance.
     * {@code DeclaredBuilderMergeParityTest} resolves the same verb.
     */
    @Test
    public void merge_aSlotWhoseInitializerCallsAGeneratedGetter_isASupplierToAnAuthorVerb() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Named",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Getter;",
                "@ClassBuilder(validate = false)",
                "@Getter",
                "public class Named {",
                "    private String name;",
                "    private String label = getName() + \"!\";",
                "    public static class Builder {",
                "        public Builder preset() { this.label = () -> \"preset\"; return this; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseNamed",
                "package demo;",
                "public class UseNamed {",
                "    public static String go() {",
                "        return Named.builder().name(\"n\").preset().build().getLabel() + \"/\"",
                "            + Named.builder().name(\"n\").build().getLabel();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("preset/n!", runGo(c, "demo.UseNamed"));
    }

    /**
     * A seed an instance initializer assigns cannot be assigned again by the
     * author's constructor, the merge appending it {@code final}, and javac
     * refuses that constructor. {@code DeclaredBuilderShapeInspectionTest}
     * reports the same constructor.
     */
    @Test
    public void merge_onASeededConstructor_whoseInitializerAndConstructorBothAssignTheSeed_fails() {
        JavaFileObject slip = JavaFileObjects.forSourceLines("demo.Slip",
            "package demo;",
            "import dev.simplified.annotations.BuilderSeed;",
            "import dev.simplified.annotations.ClassBuilder;",
            "public final class Slip {",
            "    private final String item;",
            "    @ClassBuilder",
            "    Slip(@BuilderSeed String origin, String item) { this.item = origin + \":\" + item; }",
            "    public String getItem() { return item; }",
            "    public static class Builder {",
            "        { origin = \"desk\"; }",
            "        public Builder(String origin) { this.origin = origin; }",
            "    }",
            "}");
        Compilation c = compile(slip);
        assertThat(c).failed();
        assertThat(c).hadErrorCount(1);
        assertThat(c).hadErrorContaining("variable origin might already have been assigned")
            .inFile(slip).onLine(11);
    }

    /**
     * A varargs parameter's merged slot is an array field, so an author's verb
     * reads its length and assigns it to an array local.
     * {@code DeclaredBuilderMergeParityTest} resolves the same verb against the
     * same array type.
     */
    @Test
    public void merge_aVarargsParameterSlot_isAnArrayToAnAuthorVerb() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Tags",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public final class Tags {",
                "    private final String[] values;",
                "    @ClassBuilder",
                "    Tags(String... values) { this.values = values; }",
                "    public static final class Builder {",
                "        public int size() { String[] copy = this.values; return copy.length; }",
                "    }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseTags",
                "package demo;",
                "public class UseTags {",
                "    public static int go() { return Tags.builder().values(\"a\", \"b\").size(); }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(2, runGo(c, "demo.UseTags"));
    }

}
