package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A declared chain builder as the builders generated below it see it.
 *
 * <p>Every link generates a builder that names its ancestor's builder in its
 * extends clause, calls that builder's no-argument constructor through its
 * implicit {@code super()}, and overrides its {@code self()}. Whatever the
 * ancestor's author wrote is read from the tree when the ancestor is in the same
 * round and from the class file when it was compiled before, so each case that
 * reads the ancestor runs both ways. Each refusal is pinned to be the only kind
 * of error the compilation reports: a refusal followed by javac failing on a
 * generated line is the defect the refusal exists to replace.
 */
public class ChainAncestorBuilderTest {

    /** The annotation every source here writes. */
    private static final String CB = "@ClassBuilder(validate = false)";

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    /**
     * Compiles against an already-compiled ancestor.
     *
     * @param compiled the directory holding the ancestor's class files
     * @param sources the sources of this stage
     * @return the compilation
     */
    private static Compilation compileAgainst(Path compiled, JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .withClasspath(classpathWith(compiled))
            .compile(sources);
    }

    /** The running classpath with the first stage's class files on the front. */
    private static List<File> classpathWith(Path compiled) {
        List<File> classpath = new ArrayList<>();
        classpath.add(compiled.toFile());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) classpath.add(new File(entry));
        }
        return classpath;
    }

    /** Compiles a first stage, asserts it built, and writes its class files out. */
    private static Path compiledAncestor(JavaFileObject... sources) throws Exception {
        Compilation stage = compile(sources);
        assertThat(stage).succeeded();
        return classesOf(stage);
    }

    /** Writes a compilation's class files to a directory a later compile or loader can read. */
    private static Path classesOf(Compilation compilation) throws Exception {
        Path out = Files.createTempDirectory("chain-ancestor-builder");
        for (JavaFileObject file : compilation.generatedFiles()) {
            if (file.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = file.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            String relative = anchor >= 0 ? uri.substring(anchor + "CLASS_OUTPUT/".length()) : file.getName();
            Path destination = out.resolve(relative);
            Files.createDirectories(destination.getParent());
            try (InputStream in = file.openInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                Files.write(destination, bytes.toByteArray());
            }
        }
        return out;
    }

    /** A loader over the compilation's classes, and a first stage's when there is one. */
    private static ClassLoader loaderOf(Compilation compilation, Path... earlier) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(classesOf(compilation).toUri().toURL());
        for (Path path : earlier) urls.add(path.toUri().toURL());
        return new URLClassLoader(urls.toArray(new URL[0]), ChainAncestorBuilderTest.class.getClassLoader());
    }

    private static Object runGo(String consumer, Compilation compilation, Path... earlier) throws Exception {
        return Class.forName(consumer, true, loaderOf(compilation, earlier)).getMethod("go").invoke(null);
    }

    private static JavaFileObject src(String name, String... lines) {
        return JavaFileObjects.forSourceLines(name, lines);
    }

    /** Every error the compilation reports, each on one line. */
    private static List<String> errors(Compilation compilation) {
        List<String> out = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> error : compilation.errors())
            out.add(error.getMessage(Locale.ROOT).replace('\n', ' '));
        return out;
    }

    /**
     * Asserts the compilation failed, reported each sentence as an error, and
     * reported no error but the processor's own.
     */
    private static void assertRefusedWith(Compilation compilation, String... expected) {
        assertThat(compilation).failed();
        for (String sentence : expected)
            assertTrue("expected the error '" + sentence + "', got " + errors(compilation),
                errors(compilation).contains(sentence));
        assertTrue("no error javac reports on a generated line: " + errors(compilation),
            errors(compilation).stream().allMatch(error -> error.startsWith("@ClassBuilder")));
    }

    /** Asserts the compilation built, printing its errors when it did not. */
    private static void assertBuilt(Compilation compilation) {
        assertEquals("the compilation builds: " + errors(compilation), Compilation.Status.SUCCESS,
            compilation.status());
    }

    /** An abstract root in {@code demo} whose body ends with the given declaration. */
    private static JavaFileObject shapeWith(String builderDeclaration) {
        return src("demo.Shape",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            CB,
            "public abstract class Shape {",
            "    private String name;",
            "    public String getName() { return name; }",
            "    " + builderDeclaration,
            "}");
    }

    /** A concrete link below {@code demo.Shape}, in the same package. */
    private static JavaFileObject circle() {
        return src("demo.Circle",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            CB,
            "public class Circle extends Shape {",
            "    private int radius;",
            "    public int getRadius() { return radius; }",
            "}");
    }

    /** A consumer of {@link #circle()}, returning the name it built with. */
    private static JavaFileObject useShape() {
        return src("demo.UseShape",
            "package demo;",
            "public class UseShape {",
            "    public static String go() { return Circle.builder().name(\"c\").radius(1).build().getName(); }",
            "}");
    }

    /** A concrete link below {@code demo.Shape}, in package {@code other}, importing it. */
    private static JavaFileObject otherCircle() {
        return src("other.Circle",
            "package other;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import demo.Shape;",
            CB,
            "public class Circle extends Shape {",
            "    private int radius;",
            "    public int getRadius() { return radius; }",
            "}");
    }

    /** A consumer of {@link #otherCircle()}, returning the name it built with. */
    private static JavaFileObject useOtherCircle() {
        return src("other.UseCircle",
            "package other;",
            "public class UseCircle {",
            "    public static String go() { return Circle.builder().name(\"x\").radius(1).build().getName(); }",
            "}");
    }

    // ------------------------------------------------------------------
    // The bounds a root's self-typed pair is declared with
    // ------------------------------------------------------------------

    /** The sentence for a root whose pair should read {@code <T extends Shape, B extends Builder<T, B>>}. */
    private static String boundsRefusal(String required, String written) {
        return "@ClassBuilder cannot merge into 'Builder' - its trailing pair has to be bounded as " + required
            + " for the generated setters to return the caller's own builder type, and this one declares "
            + written;
    }

    /**
     * A built type bounded by another type than the root. Only the presence of a
     * bound was asked, so the merge went ahead and javac failed on the generated
     * extends clause of the link below: {@code type argument demo.Circle is not
     * within bounds of type-variable T}.
     */
    @Test
    public void rootBoundingItsBuiltTypeByAnotherType_isRefused() {
        Compilation c = compile(shapeWith(
            "public abstract static class Builder<T extends String, B extends Builder<T, B>> { }"), circle());
        assertRefusedWith(c, boundsRefusal("<T extends Shape, B extends Builder<T, B>>",
            "<T extends String, B extends Builder<T, B>>"));
    }

    /** The pair written builder first, whose first parameter is then bounded by the builder. */
    @Test
    public void rootDeclaringItsPairSwapped_isRefused() {
        Compilation c = compile(shapeWith(
            "public abstract static class Builder<B extends Builder<B, T>, T extends Shape> { }"), circle());
        assertRefusedWith(c, boundsRefusal("<B extends Shape, T extends Builder<B, T>>",
            "<B extends Builder<B, T>, T extends Shape>"));
    }

    /** The builder bound naming the builder, with the pair's own names in the wrong order. */
    @Test
    public void rootBoundingItsBuilderWithThePairReversed_isRefused() {
        Compilation c = compile(shapeWith(
            "public abstract static class Builder<T extends Shape, B extends Builder<B, T>> { }"), circle());
        assertRefusedWith(c, boundsRefusal("<T extends Shape, B extends Builder<T, B>>",
            "<T extends Shape, B extends Builder<B, T>>"));
    }

    /** Qualified spellings of the root and of its builder are the root and its builder. */
    @Test
    public void rootBoundingThePairQualified_buildsAndRuns() throws Exception {
        Compilation c = compile(shapeWith(
            "public abstract static class Builder<T extends demo.Shape, B extends Shape.Builder<T, B>> { }"),
            circle(), useShape());
        assertBuilt(c);
        assertEquals("c", runGo("demo.UseShape", c));
    }

    /** A generic root's own parameters lead the builder bound's arguments, the pair last. */
    @Test
    public void genericRootBoundingThePairBehindItsOwnParameters_buildsAndRuns() throws Exception {
        Compilation c = compile(
            src("demo.Holder",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                CB,
                "public abstract class Holder<V> {",
                "    private V value;",
                "    public V getValue() { return value; }",
                "    public abstract static class Builder<V, H extends Holder<V>, B extends Builder<V, H, B>> { }",
                "}"),
            src("demo.Text",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                CB,
                "public class Text extends Holder<String> {",
                "    private int size;",
                "    public int getSize() { return size; }",
                "}"),
            src("demo.UseText",
                "package demo;",
                "public class UseText {",
                "    public static String go() { return Text.builder().value(\"v\").size(2).build().getValue(); }",
                "}"));
        assertBuilt(c);
        assertEquals("v", runGo("demo.UseText", c));
    }

    // ------------------------------------------------------------------
    // The root's self(), which every link overrides
    // ------------------------------------------------------------------

    /** The root builder declaring {@code public abstract B self()}. */
    private static final String PUBLIC_SELF_ROOT =
        "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> { public abstract B self(); }";

    /** Whether the named class declares a public {@code self()}. */
    private static boolean selfIsPublic(ClassLoader loader, String builder) throws Exception {
        return Modifier.isPublic(Class.forName(builder, false, loader).getDeclaredMethod("self").getModifiers());
    }

    /**
     * Every link overrides {@code self()}, so a final one on the root is refused
     * on the root's builder. It went unreported and javac failed on the link:
     * {@code self() in demo.Circle.Builder cannot override self() in
     * demo.Shape.Builder - overridden method is final}.
     */
    @Test
    public void rootDeclaringAFinalSelf_isRefused() {
        Compilation c = compile(shapeWith(
            "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {"
                + " @SuppressWarnings(\"unchecked\") protected final B self() { return (B) this; } }"),
            circle());
        assertRefusedWith(c, "@ClassBuilder merged into 'Builder' but its self() is final, so no builder "
            + "generated below 'Shape' can override it");
    }

    /**
     * A public {@code self()} on the root makes the link's override public. It
     * was protected, and javac refused it: {@code attempting to assign weaker
     * access privileges; was public}.
     */
    @Test
    public void rootDeclaringAPublicSelf_linkOverridesItPublicly() throws Exception {
        Compilation c = compile(shapeWith(PUBLIC_SELF_ROOT), circle(), useShape());
        assertBuilt(c);
        assertEquals("c", runGo("demo.UseShape", c));
        assertTrue("the link's self() is public", selfIsPublic(loaderOf(c), "demo.Circle$Builder"));
    }

    /** The same with the root compiled before the link, read from its class file. */
    @Test
    public void compiledRootDeclaringAPublicSelf_linkOverridesItPublicly() throws Exception {
        Path root = compiledAncestor(shapeWith(PUBLIC_SELF_ROOT));
        Compilation c = compileAgainst(root, circle(), useShape());
        assertBuilt(c);
        assertEquals("c", runGo("demo.UseShape", c, root));
        assertTrue("the link's self() is public", selfIsPublic(loaderOf(c, root), "demo.Circle$Builder"));
    }

    /**
     * A leaf below a chained abstract whose builder is generated follows the
     * root's public {@code self()} two levels up.
     */
    @Test
    public void leafBelowAGeneratedChainedAbstract_followsTheRootsPublicSelf() throws Exception {
        Compilation c = compile(shapeWith(PUBLIC_SELF_ROOT),
            src("demo.Polygon",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                CB,
                "public abstract class Polygon extends Shape {",
                "    private int sides;",
                "    public int getSides() { return sides; }",
                "}"),
            src("demo.Square",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                CB,
                "public class Square extends Polygon {",
                "    private int edge;",
                "    public int getEdge() { return edge; }",
                "}"),
            src("demo.UseSquare",
                "package demo;",
                "public class UseSquare {",
                "    public static String go() { return Square.builder().name(\"s\").sides(4).edge(1).build().getName(); }",
                "}"));
        assertBuilt(c);
        assertEquals("s", runGo("demo.UseSquare", c));
        assertTrue("the leaf's self() is public", selfIsPublic(loaderOf(c), "demo.Square$Builder"));
    }

    /** A root leaving {@code self()} to the generator keeps every link's override protected. */
    @Test
    public void rootLeavingSelfToTheGenerator_linkOverridesItProtected() throws Exception {
        Compilation c = compile(shapeWith(
            "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }"),
            circle(), useShape());
        assertBuilt(c);
        assertFalse("the link's self() stays protected", selfIsPublic(loaderOf(c), "demo.Circle$Builder"));
        assertTrue(Modifier.isProtected(Class.forName("demo.Circle$Builder", false, loaderOf(c))
            .getDeclaredMethod("self").getModifiers()));
    }

    // ------------------------------------------------------------------
    // The root's access and its no-argument constructor
    // ------------------------------------------------------------------

    /** The root's refusal of a builder whose every constructor takes parameters. */
    private static final String ROOT_NO_NO_ARGUMENT_CONSTRUCTOR = "@ClassBuilder merged into 'Builder' but every "
        + "constructor it declares takes parameters, so no builder generated below 'Shape' has one to call - "
        + "declare a no-argument constructor";

    /** The root's refusal of a private builder. */
    private static final String ROOT_PRIVATE = "@ClassBuilder merged into 'Builder' but it is private, so no "
        + "builder generated below 'Shape' can extend it";

    /** The link's refusal of an ancestor builder it cannot reach, for the reason given. */
    private static String linkRefusal(String link, String ancestorBuilder, String reason) {
        return "@ClassBuilder generates no builder on '" + link + "' - '" + ancestorBuilder
            + "', which its builder has to extend, " + reason;
    }

    /** The root builder whose only constructor takes a parameter. */
    private static final String PARAMETERISED_ROOT = "public abstract static class Builder<T extends Shape, "
        + "B extends Builder<T, B>> { protected Builder(String origin) { } }";

    /** The root builder, public, whose no-argument constructor is package-private. */
    private static final String PACKAGE_CONSTRUCTOR_ROOT = "public abstract static class Builder<T extends Shape, "
        + "B extends Builder<T, B>> { Builder() { } }";

    /** The root builder, package-private, with javac's default constructor. */
    private static final String PACKAGE_ROOT =
        "abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }";

    /**
     * A generated link builder calls its ancestor's no-argument constructor
     * through its implicit {@code super()}, so a root builder declaring none is
     * refused on the root and on the link. Both went unreported, and javac
     * failed on the link: {@code constructor Builder in class
     * demo.Shape.Builder<T,B> cannot be applied to given types}.
     */
    @Test
    public void rootBuilderWithOnlyAParameterisedConstructor_isRefusedOnRootAndLink() {
        Compilation c = compile(shapeWith(PARAMETERISED_ROOT), circle());
        assertRefusedWith(c, ROOT_NO_NO_ARGUMENT_CONSTRUCTOR,
            linkRefusal("Circle", "Shape.Builder", "declares no constructor taking no parameters"));
    }

    /** A chained abstract's declared builder is refused as a root's is, and so is the leaf below it. */
    @Test
    public void chainedAbstractBuilderWithOnlyAParameterisedConstructor_isRefusedOnItAndTheLeaf() {
        Compilation c = compile(
            src("demo.Base",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                CB,
                "public abstract class Base {",
                "    private String label;",
                "    public String getLabel() { return label; }",
                "}"),
            src("demo.Mid",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                CB,
                "public abstract class Mid extends Base {",
                "    private String kind;",
                "    public String getKind() { return kind; }",
                "    public abstract static class Builder<T extends Mid, B extends Builder<T, B>>",
                "            extends Base.Builder<T, B> {",
                "        protected Builder(String o) { }",
                "    }",
                "}"),
            src("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                CB,
                "public class Leaf extends Mid {",
                "    private int size;",
                "    public int getSize() { return size; }",
                "}"));
        assertRefusedWith(c, "@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so no builder generated below 'Mid' has one to call - declare a no-argument constructor",
            linkRefusal("Leaf", "Mid.Builder", "declares no constructor taking no parameters"));
    }

    /**
     * A private root builder cannot be named by any link's extends clause. It
     * went unreported, and javac failed on the link: {@code demo.Shape.Builder
     * has private access in demo.Shape}.
     */
    @Test
    public void privateRootBuilder_isRefusedOnRootAndLink() {
        Compilation c = compile(shapeWith(
            "private abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }"), circle());
        assertRefusedWith(c, ROOT_PRIVATE, linkRefusal("Circle", "Shape.Builder", "is private"));
    }

    /**
     * A package-private root builder is refused on a link in another package.
     * javac failed there: {@code demo.Shape.Builder is not public in
     * demo.Shape; cannot be accessed from outside package}.
     */
    @Test
    public void packagePrivateRootBuilder_isRefusedOnALinkInAnotherPackage() {
        Compilation c = compile(shapeWith(PACKAGE_ROOT), otherCircle());
        assertRefusedWith(c, linkRefusal("Circle", "Shape.Builder",
            "is package-private, and 'Circle' is in another package"));
    }

    /** The same with the root compiled before the link. */
    @Test
    public void compiledPackagePrivateRootBuilder_isRefusedOnALinkInAnotherPackage() throws Exception {
        Path root = compiledAncestor(shapeWith(PACKAGE_ROOT));
        Compilation c = compileAgainst(root, otherCircle());
        assertRefusedWith(c, linkRefusal("Circle", "Shape.Builder",
            "is package-private, and 'Circle' is in another package"));
    }

    /**
     * A package-private no-argument constructor on a public root builder is
     * refused on a link in another package. javac failed there: {@code Builder()
     * is not public in demo.Shape.Builder; cannot be accessed from outside
     * package}.
     */
    @Test
    public void packagePrivateNoArgumentConstructor_isRefusedOnALinkInAnotherPackage() {
        Compilation c = compile(shapeWith(PACKAGE_CONSTRUCTOR_ROOT), otherCircle());
        assertRefusedWith(c, linkRefusal("Circle", "Shape.Builder",
            "has a package-private no-argument constructor, and 'Circle' is in another package"));
    }

    /** The same with the root compiled before the link. */
    @Test
    public void compiledPackagePrivateNoArgumentConstructor_isRefusedOnALinkInAnotherPackage() throws Exception {
        Path root = compiledAncestor(shapeWith(PACKAGE_CONSTRUCTOR_ROOT));
        Compilation c = compileAgainst(root, otherCircle());
        assertRefusedWith(c, linkRefusal("Circle", "Shape.Builder",
            "has a package-private no-argument constructor, and 'Circle' is in another package"));
    }

    /**
     * A private no-argument constructor is out of every other class's reach, so
     * the link is refused in the root's own package too. javac failed there:
     * {@code Builder() has private access in demo.Shape.Builder}.
     */
    @Test
    public void privateNoArgumentConstructor_isRefusedOnALinkInTheSamePackage() throws Exception {
        String root = "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ private Builder() { } protected Builder(String origin) { } }";
        Compilation c = compile(shapeWith(root), circle());
        assertRefusedWith(c, linkRefusal("Circle", "Shape.Builder", "has a private no-argument constructor"));
        Path compiled = compiledAncestor(shapeWith(root));
        assertRefusedWith(compileAgainst(compiled, circle()),
            linkRefusal("Circle", "Shape.Builder", "has a private no-argument constructor"));
    }

    /**
     * A root compiled with no processor at all, so its builder's shape was never
     * judged, is read from its class file: every constructor takes parameters,
     * and the link is refused.
     */
    @Test
    public void unprocessedCompiledRootWithOnlyAParameterisedConstructor_isRefusedOnTheLink() throws Exception {
        Compilation stage = Compiler.javac().withOptions("-proc:none").compile(shapeWith(PARAMETERISED_ROOT));
        assertThat(stage).succeeded();
        Path root = classesOf(stage);
        assertRefusedWith(compileAgainst(root, circle()),
            linkRefusal("Circle", "Shape.Builder", "declares no constructor taking no parameters"));
    }

    /**
     * A protected no-argument constructor on a public root builder is reached
     * through {@code super()} from a link's builder in another package, which is
     * a subclass.
     */
    @Test
    public void protectedNoArgumentConstructor_buildsBelowALinkInAnotherPackage() throws Exception {
        String root = "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ protected Builder() { } }";
        Compilation c = compile(shapeWith(root), otherCircle(), useOtherCircle());
        assertBuilt(c);
        assertEquals("x", runGo("other.UseCircle", c));
        Path compiled = compiledAncestor(shapeWith(root));
        Compilation against = compileAgainst(compiled, otherCircle(), useOtherCircle());
        assertBuilt(against);
        assertEquals("x", runGo("other.UseCircle", against, compiled));
    }

    /** A package-private root builder is reached by a link in its own package. */
    @Test
    public void packagePrivateRootBuilder_buildsBelowALinkInTheSamePackage() throws Exception {
        Compilation c = compile(shapeWith(PACKAGE_ROOT), circle(), useShape());
        assertBuilt(c);
        assertEquals("c", runGo("demo.UseShape", c));
        Path compiled = compiledAncestor(shapeWith(PACKAGE_ROOT));
        Compilation against = compileAgainst(compiled, circle(), useShape());
        assertBuilt(against);
        assertEquals("c", runGo("demo.UseShape", against, compiled));
    }

    // ------------------------------------------------------------------
    // The extends clause a link spells its root's builder in
    // ------------------------------------------------------------------

    /** A link in package {@code other} naming {@code demo.Shape} fully qualified, with no import. */
    private static JavaFileObject qualifiedOtherCircle() {
        return src("other.Circle",
            "package other;",
            "import dev.simplified.annotations.ClassBuilder;",
            CB,
            "public class Circle extends demo.Shape {",
            "    private int radius;",
            "    public int getRadius() { return radius; }",
            "}");
    }

    /**
     * The generated extends clause names the root's builder by its canonical
     * name, whatever the link wrote. It spelled it {@code Shape.Builder}, which
     * nothing in the link's file imports: {@code package Shape does not exist}.
     */
    @Test
    public void linkNamingItsRootFullyQualifiedFromAnotherPackage_buildsAndRuns() throws Exception {
        Compilation c = compile(shapeWith(""), qualifiedOtherCircle(), useOtherCircle());
        assertBuilt(c);
        assertEquals("x", runGo("other.UseCircle", c));
    }

    /** The same with the root compiled before the link. */
    @Test
    public void linkNamingACompiledRootFullyQualifiedFromAnotherPackage_buildsAndRuns() throws Exception {
        Path root = compiledAncestor(shapeWith(""));
        Compilation c = compileAgainst(root, qualifiedOtherCircle(), useOtherCircle());
        assertBuilt(c);
        assertEquals("x", runGo("other.UseCircle", c, root));
    }

    /** A link importing its root from another package, which built before and still does. */
    @Test
    public void linkImportingItsRootFromAnotherPackage_buildsAndRuns() throws Exception {
        Compilation c = compile(shapeWith(""), otherCircle(), useOtherCircle());
        assertBuilt(c);
        assertEquals("x", runGo("other.UseCircle", c));
    }

    /**
     * A root nested in another class, named by a link through that class. The
     * clause spelled {@code Shape.Builder}, which the link's scope does not
     * name.
     */
    @Test
    public void linkBelowARootNestedInAnotherClass_buildsAndRuns() throws Exception {
        Compilation c = compile(
            src("demo.Outer",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "public class Outer {",
                "    " + CB,
                "    public abstract static class Shape {",
                "        private String name;",
                "        public String getName() { return name; }",
                "    }",
                "}"),
            src("demo.Circle",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                CB,
                "public class Circle extends Outer.Shape {",
                "    private int radius;",
                "    public int getRadius() { return radius; }",
                "}"),
            useShape());
        assertBuilt(c);
        assertEquals("c", runGo("demo.UseShape", c));
    }

}
