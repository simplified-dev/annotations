package dev.simplified.classbuilder.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's half of a declared chain builder as the builders generated below
 * it see it - the bounds of a root's self-typed pair, its {@code self()}, its
 * access and its no-argument constructor, and the extends clause a link names
 * it in.
 *
 * <p>Each refusal asserts the sentence its processor twin in
 * {@code ChainAncestorBuilderTest} asserts, on the file javac reports it in: the
 * root's on the root's builder, the link's on the link's annotation.
 */
public class ChainAncestorBuilderInspectionTest extends BasePlatformTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return LightJavaCodeInsightFixtureTestCase.JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(DeclaredBuilderShapeInspection.class);
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder {
                boolean retainInit() default true;
                boolean validate() default true;
                AccessLevel builderConstructorAccess() default AccessLevel.PACKAGE;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    /** Adds an abstract root in {@code demo} whose body ends with the given declaration. */
    private void addShape(String builderDeclaration) {
        myFixture.addFileToProject("demo/Shape.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public abstract class Shape {
                private String name;
                public String getName() { return name; }
                %s
            }
            """.formatted(builderDeclaration));
    }

    /** Adds a concrete link below {@code demo.Shape}, in the same package. */
    private void addCircle() {
        myFixture.addFileToProject("demo/Circle.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Circle extends Shape {
                private int radius;
                public int getRadius() { return radius; }
            }
            """);
    }

    /** Adds a concrete link below {@code demo.Shape}, in package {@code other}, importing it. */
    private void addOtherCircle() {
        myFixture.addFileToProject("other/Circle.java", """
            package other;
            import dev.simplified.annotations.ClassBuilder;
            import demo.Shape;
            @ClassBuilder(validate = false)
            public class Circle extends Shape {
                private int radius;
                public int getRadius() { return radius; }
            }
            """);
    }

    /** The sentence for a root whose pair should read as {@code required}. */
    private static String boundsRefusal(String required, String written) {
        return "@ClassBuilder cannot merge into 'Builder' - its trailing pair has to be bounded as " + required
            + " for the generated setters to return the caller's own builder type, and this one declares "
            + written;
    }

    /** The link's refusal of an ancestor builder it cannot reach, for the reason given. */
    private static String linkRefusal(String link, String ancestorBuilder, String reason) {
        return "@ClassBuilder generates no builder on '" + link + "' - '" + ancestorBuilder
            + "', which its builder has to extend, " + reason;
    }

    // ------------------------------------------------------------------
    // The bounds a root's self-typed pair is declared with
    // ------------------------------------------------------------------

    /**
     * A built type bounded by another type than the root. Only the presence of a
     * bound was asked, and the editor was silent in both files over source javac
     * rejects on the link's generated extends clause.
     */
    public void testARootBoundingItsBuiltTypeByAnotherType_isReported() {
        addShape("public abstract static class Builder<T extends String, B extends Builder<T, B>> { }");
        addCircle();
        assertEquals(List.of(boundsRefusal("<T extends Shape, B extends Builder<T, B>>",
            "<T extends String, B extends Builder<T, B>>")), errorsIn("demo/Shape.java"));
    }

    /** The pair written builder first. */
    public void testARootDeclaringItsPairSwapped_isReported() {
        addShape("public abstract static class Builder<B extends Builder<B, T>, T extends Shape> { }");
        addCircle();
        assertEquals(List.of(boundsRefusal("<B extends Shape, T extends Builder<B, T>>",
            "<B extends Builder<B, T>, T extends Shape>")), errorsIn("demo/Shape.java"));
    }

    /** The builder bound naming the builder, with the pair's own names in the wrong order. */
    public void testARootBoundingItsBuilderWithThePairReversed_isReported() {
        addShape("public abstract static class Builder<T extends Shape, B extends Builder<B, T>> { }");
        addCircle();
        assertEquals(List.of(boundsRefusal("<T extends Shape, B extends Builder<T, B>>",
            "<T extends Shape, B extends Builder<B, T>>")), errorsIn("demo/Shape.java"));
    }

    /** Qualified spellings of the root and of its builder are the root and its builder. */
    public void testARootBoundingThePairQualified_isNotReported() {
        addShape("public abstract static class Builder<T extends demo.Shape, B extends Shape.Builder<T, B>> { }");
        addCircle();
        assertEquals(List.of(), errorsIn("demo/Shape.java"));
        assertEquals(List.of(), errorsIn("demo/Circle.java"));
    }

    /** A generic root's own parameters lead the builder bound's arguments, the pair last. */
    public void testAGenericRootBoundingThePairBehindItsOwnParameters_isNotReported() {
        myFixture.addFileToProject("demo/Holder.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public abstract class Holder<V> {
                private V value;
                public V getValue() { return value; }
                public abstract static class Builder<V, H extends Holder<V>, B extends Builder<V, H, B>> { }
            }
            """);
        assertEquals(List.of(), errorsIn("demo/Holder.java"));
    }

    // ------------------------------------------------------------------
    // The root's self(), which every link overrides
    // ------------------------------------------------------------------

    /**
     * A final {@code self()} on the root is refused on the root's builder. The
     * editor was silent while contributing a link override javac rejects.
     */
    public void testARootDeclaringAFinalSelf_isReported() {
        addShape("""
            public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {
                    @SuppressWarnings("unchecked") protected final B self() { return (B) this; }
                }""");
        addCircle();
        assertEquals(List.of("@ClassBuilder merged into 'Builder' but its self() is final, so no builder "
            + "generated below 'Shape' can override it"), errorsIn("demo/Shape.java"));
    }

    /**
     * A public {@code self()} on the root makes the link's override public, as
     * javac's is. It was protected, a weaker override javac refuses.
     */
    public void testALinkBelowAPublicSelf_overridesItPublicly() {
        addShape("public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ public abstract B self(); }");
        addCircle();
        PsiClass builder = builderOf(findClass("demo.Circle"));
        assertTrue("the link's self() is public", selfOf(builder).hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(List.of(), errorsIn("demo/Shape.java"));
    }

    /** A leaf below a chained abstract whose builder is generated follows the root's public self(). */
    public void testALeafBelowAGeneratedChainedAbstract_followsTheRootsPublicSelf() {
        addShape("public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ public abstract B self(); }");
        myFixture.addFileToProject("demo/Polygon.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public abstract class Polygon extends Shape {
                private int sides;
            }
            """);
        myFixture.addFileToProject("demo/Square.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Square extends Polygon {
                private int edge;
            }
            """);
        PsiClass builder = builderOf(findClass("demo.Square"));
        assertTrue("the leaf's self() is public", selfOf(builder).hasModifierProperty(PsiModifier.PUBLIC));
    }

    /** A root leaving {@code self()} to the generator keeps the link's override protected. */
    public void testALinkBelowTheGeneratedSelf_overridesItProtected() {
        addShape("public abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }");
        addCircle();
        PsiMethod self = selfOf(builderOf(findClass("demo.Circle")));
        assertTrue("protected", self.hasModifierProperty(PsiModifier.PROTECTED));
        assertFalse("not public", self.hasModifierProperty(PsiModifier.PUBLIC));
    }

    // ------------------------------------------------------------------
    // The root's access and its no-argument constructor
    // ------------------------------------------------------------------

    /**
     * A root builder declaring no no-argument constructor is refused on the root
     * and on the link. Neither file showed anything, over a link javac rejects on
     * its generated builder's implicit {@code super()}.
     */
    public void testARootBuilderWithOnlyAParameterisedConstructor_isReportedOnRootAndLink() {
        addShape("public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ protected Builder(String origin) { } }");
        addCircle();
        assertEquals(List.of("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so no builder generated below 'Shape' has one to call - declare a no-argument "
                + "constructor"), errorsIn("demo/Shape.java"));
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder", "declares no constructor taking no parameters")),
            errorsIn("demo/Circle.java"));
    }

    /** A chained abstract's declared builder is refused as a root's is, and so is the leaf below it. */
    public void testAChainedAbstractBuilderWithOnlyAParameterisedConstructor_isReportedOnItAndTheLeaf() {
        myFixture.addFileToProject("demo/Base.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public abstract class Base {
                private String label;
            }
            """);
        myFixture.addFileToProject("demo/Mid.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public abstract class Mid extends Base {
                private String kind;
                public abstract static class Builder<T extends Mid, B extends Builder<T, B>>
                        extends Base.Builder<T, B> {
                    protected Builder(String o) { }
                }
            }
            """);
        myFixture.addFileToProject("demo/Leaf.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Leaf extends Mid {
                private int size;
            }
            """);
        assertEquals(List.of("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so no builder generated below 'Mid' has one to call - declare a no-argument "
                + "constructor"), errorsIn("demo/Mid.java"));
        assertEquals(List.of(linkRefusal("Leaf", "Mid.Builder", "declares no constructor taking no parameters")),
            errorsIn("demo/Leaf.java"));
    }

    /** A private root builder is refused on the root and on the link. */
    public void testAPrivateRootBuilder_isReportedOnRootAndLink() {
        addShape("private abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }");
        addCircle();
        assertEquals(List.of("@ClassBuilder merged into 'Builder' but it is private, so no builder generated "
            + "below 'Shape' can extend it"), errorsIn("demo/Shape.java"));
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder", "is private")), errorsIn("demo/Circle.java"));
    }

    /**
     * A package-private root builder is refused on a link in another package,
     * and the link's builder is withheld as javac withholds it. The editor
     * resolved {@code Circle.builder().name(..)} green through a builder the
     * link cannot extend.
     */
    public void testAPackagePrivateRootBuilder_isReportedOnALinkInAnotherPackage() {
        addShape("abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }");
        addOtherCircle();
        assertEquals(List.of(), errorsIn("demo/Shape.java"));
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder",
            "is package-private, and 'Circle' is in another package")), errorsIn("other/Circle.java"));
        myFixture.addFileToProject("other/UseCircle.java", """
            package other;
            public class UseCircle {
                public static String go() { return Circle.builder().name("x").radius(1).build().getName(); }
            }
            """);
        assertFalse("the entry point is withheld as javac withholds it",
            allErrorsIn("other/UseCircle.java").isEmpty());
    }

    /** A package-private no-argument constructor is refused on a link in another package. */
    public void testAPackagePrivateNoArgumentConstructor_isReportedOnALinkInAnotherPackage() {
        addShape("public abstract static class Builder<T extends Shape, B extends Builder<T, B>> { Builder() { } }");
        addOtherCircle();
        assertEquals(List.of(), errorsIn("demo/Shape.java"));
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder",
                "has a package-private no-argument constructor, and 'Circle' is in another package")),
            errorsIn("other/Circle.java"));
    }

    /** A private no-argument constructor is refused on a link in the root's own package. */
    public void testAPrivateNoArgumentConstructor_isReportedOnALinkInTheSamePackage() {
        addShape("public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ private Builder() { } protected Builder(String origin) { } }");
        addCircle();
        assertEquals(List.of(), errorsIn("demo/Shape.java"));
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder", "has a private no-argument constructor")),
            errorsIn("demo/Circle.java"));
    }

    /** A protected no-argument constructor is reached by a link's builder in another package. */
    public void testAProtectedNoArgumentConstructor_isNotReportedBelowALinkInAnotherPackage() {
        addShape("public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ protected Builder() { } }");
        addOtherCircle();
        myFixture.addFileToProject("other/UseCircle.java", """
            package other;
            public class UseCircle {
                public static String go() { return Circle.builder().name("x").radius(1).build().getName(); }
            }
            """);
        assertEquals(List.of(), errorsIn("demo/Shape.java"));
        assertEquals(List.of(), errorsIn("other/Circle.java"));
        assertEquals(List.of(), allErrorsIn("other/UseCircle.java"));
    }

    /** A package-private root builder is reached by a link in its own package. */
    public void testAPackagePrivateRootBuilder_isNotReportedBelowALinkInTheSamePackage() {
        addShape("abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }");
        addCircle();
        assertEquals(List.of(), errorsIn("demo/Shape.java"));
        assertEquals(List.of(), errorsIn("demo/Circle.java"));
    }

    // ------------------------------------------------------------------
    // The extends clause a link spells its root's builder in
    // ------------------------------------------------------------------

    /**
     * A link naming its root fully qualified from another package gets the
     * root's builder as its builder's supertype, the class itself rather than a
     * name to resolve, and a consumer's chain resolves as it builds.
     */
    public void testALinkNamingItsRootFullyQualified_extendsTheRootsBuilder() {
        addShape("");
        myFixture.addFileToProject("other/Circle.java", """
            package other;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Circle extends demo.Shape {
                private int radius;
                public int getRadius() { return radius; }
            }
            """);
        myFixture.addFileToProject("other/UseCircle.java", """
            package other;
            public class UseCircle {
                public static String go() { return Circle.builder().name("x").radius(1).build().getName(); }
            }
            """);
        assertEquals("the link's builder extends the root's", builderOf(findClass("demo.Shape")),
            builderOf(findClass("other.Circle")).getSuperClass());
        assertEquals(List.of(), allErrorsIn("other/UseCircle.java"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The {@code @ClassBuilder} errors highlighted in a project file. */
    private List<String> errorsIn(String path) {
        myFixture.configureFromTempProjectFile(path);
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() != HighlightSeverity.ERROR) continue;
            String description = info.getDescription();
            if (description != null && description.startsWith("@ClassBuilder")) out.add(description);
        }
        return out;
    }

    /** Every error highlighted in a project file, whatever reports it. */
    private List<String> allErrorsIn(String path) {
        myFixture.configureFromTempProjectFile(path);
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.ERROR) out.add(info.getDescription());
        }
        return out;
    }

    /** A project class by its qualified name. */
    private PsiClass findClass(String qualifiedName) {
        PsiClass found = JavaPsiFacade.getInstance(getProject())
            .findClass(qualifiedName, GlobalSearchScope.allScope(getProject()));
        assertNotNull("expected " + qualifiedName, found);
        return found;
    }

    /** The builder the editor lists on a class. */
    private static PsiClass builderOf(PsiClass target) {
        for (PsiClass nested : target.getInnerClasses()) {
            if ("Builder".equals(nested.getName())) return nested;
        }
        throw new AssertionError("expected a Builder on " + target.getName());
    }

    /** The {@code self()} a builder declares, light or written. */
    private static PsiMethod selfOf(PsiClass builder) {
        PsiMethod[] found = builder.findMethodsByName("self", false);
        assertEquals("one self() on " + builder.getQualifiedName(), 1, found.length);
        return found[0];
    }

}
