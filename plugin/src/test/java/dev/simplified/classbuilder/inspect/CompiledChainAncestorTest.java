package dev.simplified.classbuilder.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.CompiledLibrary;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The editor's half of a chain whose root was compiled before the link - read
 * from its class file, as a dependency's is.
 *
 * <p>Each root here is compiled in the test by javac, with the library's
 * processor or without it, and attached to the fixture as a library, so the
 * link's editor reads the root's builder through its class file: the
 * constructors javac wrote into it, its access, and the {@code Generated}
 * annotation on a builder or member the processor wrote. Each case is the
 * editor twin of a class-file case in {@code ChainAncestorBuilderTest}, and
 * asserts what that two-stage compile gives on the same shape: the sentence the
 * processor reports on the link, or the link building and its builder's members
 * resolving.
 */
public class CompiledChainAncestorTest extends BasePlatformTestCase {

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
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel builderConstructorAccess() default AccessLevel.PACKAGE;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NoArgsConstructor.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.TYPE)
            public @interface NoArgsConstructor {
                AccessLevel access() default AccessLevel.PUBLIC;
                boolean force() default false;
            }
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

    // ------------------------------------------------------------------
    // A root builder the author declared, read from its class file
    // ------------------------------------------------------------------

    /**
     * A public {@code self()} on a compiled root's declared builder makes the
     * link's override public, as javac's is, and the link's chain resolves.
     */
    public void testACompiledRootDeclaringAPublicSelf_linkOverridesItPublicly() throws Exception {
        compiledShape(true, "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ public abstract B self(); }");
        addCircle();
        addUseShape();
        assertTrue("the link's self() is public",
            selfOf(builderOf(findClass("demo.Circle"))).hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(List.of(), errorsIn("demo/Circle.java"));
        assertEquals(List.of(), allErrorsIn("demo/UseShape.java"));
    }

    /**
     * A compiled root's package-private declared builder is refused on a link in
     * another package, and the link's builder is withheld as javac withholds it.
     */
    public void testACompiledPackagePrivateRootBuilder_isReportedOnALinkInAnotherPackage() throws Exception {
        compiledShape(true, "abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }");
        addOtherCircle();
        addUseOtherCircle();
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder",
            "is package-private, and 'Circle' is in another package")), errorsIn("other/Circle.java"));
        assertFalse("the entry point is withheld as javac withholds it",
            allErrorsIn("other/UseCircle.java").isEmpty());
    }

    /** A package-private no-argument constructor in a compiled root's builder is refused from another package. */
    public void testACompiledPackagePrivateNoArgumentConstructor_isReportedOnALinkInAnotherPackage()
        throws Exception {
        compiledShape(true, "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ Builder() { } }");
        addOtherCircle();
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder",
                "has a package-private no-argument constructor, and 'Circle' is in another package")),
            errorsIn("other/Circle.java"));
    }

    /** A private no-argument constructor in a compiled root's builder is refused in the root's own package. */
    public void testACompiledPrivateNoArgumentConstructor_isReportedOnALinkInTheSamePackage() throws Exception {
        compiledShape(true, "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ private Builder() { } protected Builder(String origin) { } }");
        addCircle();
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder", "has a private no-argument constructor")),
            errorsIn("demo/Circle.java"));
    }

    /**
     * A root compiled with no processor, whose builder's constructors all take
     * parameters, is read from its class file and the link is refused.
     */
    public void testAnUnprocessedCompiledRootWithOnlyAParameterisedConstructor_isReportedOnTheLink()
        throws Exception {
        compiledShape(false, "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ protected Builder(String origin) { } }");
        addCircle();
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder", "declares no constructor taking no parameters")),
            errorsIn("demo/Circle.java"));
    }

    /**
     * A compiled builder carries the constructors javac wrote into its class
     * file and nothing an annotation on it still names: compiled with no
     * processor, a {@code @NoArgsConstructor} on it appended nothing, and the
     * link is refused as the processor refuses it reading the same class file.
     * Asked as a source builder is, the annotation reads as a no-argument
     * constructor the link can call.
     */
    public void testAnUnprocessedCompiledRootBuilderNamingANoArgsConstructor_isReportedOnTheLink()
        throws Exception {
        compiledShape(false, "@dev.simplified.annotations.NoArgsConstructor "
            + "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ protected Builder(String origin) { } }");
        addCircle();
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder", "declares no constructor taking no parameters")),
            errorsIn("demo/Circle.java"));
    }

    /** A protected no-argument constructor in a compiled root's builder is reached from another package. */
    public void testACompiledProtectedNoArgumentConstructor_isNotReportedBelowALinkInAnotherPackage()
        throws Exception {
        compiledShape(true, "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ protected Builder() { } }");
        addOtherCircle();
        addUseOtherCircle();
        assertEquals(List.of(), errorsIn("other/Circle.java"));
        assertEquals(List.of(), allErrorsIn("other/UseCircle.java"));
    }

    /** A compiled root's package-private builder is reached by a link in its own package. */
    public void testACompiledPackagePrivateRootBuilder_isNotReportedBelowALinkInTheSamePackage() throws Exception {
        compiledShape(true, "abstract static class Builder<T extends Shape, B extends Builder<T, B>> { }");
        addCircle();
        addUseShape();
        assertEquals(List.of(), errorsIn("demo/Circle.java"));
        assertEquals(List.of(), allErrorsIn("demo/UseShape.java"));
    }

    // ------------------------------------------------------------------
    // A root builder the processor generated, read from its class file
    // ------------------------------------------------------------------

    /**
     * A link in another package naming a compiled root fully qualified extends
     * the builder the processor generated into the root's class file, overrides
     * its {@code self()} protected, and its chain resolves.
     */
    public void testALinkBelowACompiledRootsGeneratedBuilder_extendsItAndResolves() throws Exception {
        compiledShape(true, "");
        myFixture.addFileToProject("other/Circle.java", """
            package other;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Circle extends demo.Shape {
                private int radius;
                public int getRadius() { return radius; }
            }
            """);
        addUseOtherCircle();
        PsiClass rootBuilder = builderOf(findClass("demo.Shape"));
        assertTrue("the root's builder is the one in its class file", rootBuilder instanceof PsiCompiledElement);
        PsiClass builder = builderOf(findClass("other.Circle"));
        assertEquals("the link's builder extends it", rootBuilder, builder.getSuperClass());
        PsiMethod self = selfOf(builder);
        assertTrue("protected", self.hasModifierProperty(PsiModifier.PROTECTED));
        assertFalse("not public", self.hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(List.of(), errorsIn("other/Circle.java"));
        assertEquals(List.of(), allErrorsIn("other/UseCircle.java"));
    }

    /**
     * A builder the processor generated package-private into a compiled root is
     * out of reach of a link in another package, read from the access its class
     * file carries, and the link is refused and gets no builder, as the
     * processor refuses it reading the same class file. The editor read the
     * {@code Generated} marker as the end of the question, reported nothing and
     * contributed the link's builder.
     */
    public void testACompiledRootsGeneratedPackagePrivateBuilder_isReportedOnALinkInAnotherPackage()
        throws Exception {
        compiled(true, "demo/Shape.java", """
            package demo;
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, access = AccessLevel.PACKAGE)
            public abstract class Shape {
                private String name;
                public String getName() { return name; }
            }
            """);
        addOtherCircle();
        assertEquals(List.of(linkRefusal("Circle", "Shape.Builder",
            "is package-private, and 'Circle' is in another package")), errorsIn("other/Circle.java"));
        assertEquals("the link's builder is withheld, as the processor generates none", 0,
            findClass("other.Circle").getInnerClasses().length);
    }

    /**
     * A public {@code self()} a compiled root's builder inherits from a
     * supertype, which its class file carries no override of, makes the link's
     * override public, as javac's is, and the link's chain resolves.
     */
    public void testACompiledRootInheritingAPublicSelf_linkOverridesItPublicly() throws Exception {
        compiled(true, Map.of(
            "demo/Fluent.java", """
                package demo;
                public abstract class Fluent<B> {
                    public abstract B self();
                }
                """,
            "demo/Shape.java", """
                package demo;
                import dev.simplified.annotations.ClassBuilder;
                @ClassBuilder(validate = false)
                public abstract class Shape {
                    private String name;
                    public String getName() { return name; }
                    public abstract static class Builder<T extends Shape, B extends Builder<T, B>>
                            extends Fluent<B> { }
                }
                """));
        addCircle();
        addUseShape();
        assertTrue("the link's self() is public",
            selfOf(builderOf(findClass("demo.Circle"))).hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(List.of(), errorsIn("demo/Circle.java"));
        assertEquals(List.of(), allErrorsIn("demo/UseShape.java"));
    }

    /**
     * A leaf below a compiled chained abstract whose builder was generated
     * follows the compiled root's public {@code self()} two levels up, the
     * generated builder between them saying nothing.
     */
    public void testALeafBelowACompiledGeneratedChainedAbstract_followsTheRootsPublicSelf() throws Exception {
        compiled(true, Map.of(
            "demo/Shape.java", """
                package demo;
                import dev.simplified.annotations.ClassBuilder;
                @ClassBuilder(validate = false)
                public abstract class Shape {
                    private String name;
                    public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {
                        public abstract B self();
                    }
                }
                """,
            "demo/Polygon.java", """
                package demo;
                import dev.simplified.annotations.ClassBuilder;
                @ClassBuilder(validate = false)
                public abstract class Polygon extends Shape {
                    private int sides;
                }
                """));
        myFixture.addFileToProject("demo/Square.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Square extends Polygon {
                private int edge;
            }
            """);
        assertTrue("the leaf's self() is public",
            selfOf(builderOf(findClass("demo.Square"))).hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(List.of(), errorsIn("demo/Square.java"));
    }

    // ------------------------------------------------------------------
    // A link extending a compiled generic root without its type arguments
    // ------------------------------------------------------------------

    /**
     * A link extending a compiled generic root raw is refused on its
     * annotation, in the sentence the processor reports, and gets no builder.
     * The root's builder is the generator's, read off the class file's marker;
     * the editor compared its type-parameter count with the raw clause first and
     * reported that 'Box' declares its own nested builder, which its author
     * never wrote.
     */
    public void testALinkExtendingACompiledGenericRootRaw_isReported() throws Exception {
        compiledBox();
        addBoxLink("Box");
        assertEquals(List.of("@ClassBuilder generates no builder on 'Circle' - its annotated supertype 'Box' is "
            + "generic, so the extends clause has to give its type arguments"), errorsIn("demo/Circle.java"));
        assertEquals("the link's builder is withheld, as the processor generates none", 0,
            findClass("demo.Circle").getInnerClasses().length);
    }

    /** A link giving a compiled generic root its type arguments extends its builder, and the chain resolves. */
    public void testALinkExtendingACompiledGenericRootWithItsTypeArguments_resolves() throws Exception {
        compiledBox();
        addBoxLink("Box<String>");
        myFixture.addFileToProject("demo/UseBox.java", """
            package demo;
            public class UseBox {
                public static String go() { return Circle.builder().value("v").radius(1).build().getValue(); }
            }
            """);
        assertEquals(List.of(), errorsIn("demo/Circle.java"));
        assertEquals(List.of(), allErrorsIn("demo/UseBox.java"));
    }

    /**
     * A compiled root's builder its author wrote with none of the type
     * parameters the clause passes keeps the sentence naming the builder the
     * supertype declares.
     */
    public void testACompiledRootBuilderTheAuthorWroteWithoutThePair_isReportedAsDeclaringItsOwn() throws Exception {
        compiledShape(false, "public abstract static class Builder { }");
        addCircle();
        assertEquals(List.of("@ClassBuilder generates no builder on 'Circle' - its annotated supertype 'Shape' "
            + "declares its own nested builder"), errorsIn("demo/Circle.java"));
    }

    // ------------------------------------------------------------------
    // A final self() on a root the processor never judged
    // ------------------------------------------------------------------

    /** The link's refusal of a final {@code self()} on a root the processor never judged. */
    private static final String LINK_FINAL_SELF = "@ClassBuilder generates no builder on 'Circle' - the self() of "
        + "'Shape.Builder' is final, so its builder cannot override it";

    /**
     * A root compiled with no processor whose builder declares a final
     * {@code self()} is refused on the link's annotation, in the sentence the
     * processor reports reading the same class file, and the link gets no
     * builder. The editor was silent and contributed the link's override javac
     * refuses as overriding a final method.
     */
    public void testAnUnprocessedCompiledRootDeclaringAFinalSelf_isReportedOnTheLink() throws Exception {
        compiledShape(false, "public abstract static class Builder<T extends Shape, B extends Builder<T, B>> "
            + "{ @SuppressWarnings(\"unchecked\") protected final B self() { return (B) this; } }");
        addCircle();
        assertEquals(List.of(LINK_FINAL_SELF), errorsIn("demo/Circle.java"));
        assertEquals("the link's builder is withheld, as the processor generates none", 0,
            findClass("demo.Circle").getInnerClasses().length);
    }

    /** The same with the final {@code self()} one the unprocessed root's builder inherits. */
    public void testAnUnprocessedCompiledRootInheritingAFinalSelf_isReportedOnTheLink() throws Exception {
        compiled(false, Map.of(
            "demo/Fluent.java", """
                package demo;
                public abstract class Fluent<B> {
                    @SuppressWarnings("unchecked") public final B self() { return (B) this; }
                }
                """,
            "demo/Shape.java", """
                package demo;
                import dev.simplified.annotations.ClassBuilder;
                @ClassBuilder(validate = false)
                public abstract class Shape {
                    private String name;
                    public String getName() { return name; }
                    public abstract static class Builder<T extends Shape, B extends Builder<T, B>>
                            extends Fluent<B> { }
                }
                """));
        addCircle();
        assertEquals(List.of(LINK_FINAL_SELF), errorsIn("demo/Circle.java"));
        assertEquals("the link's builder is withheld, as the processor generates none", 0,
            findClass("demo.Circle").getInnerClasses().length);
    }

    /**
     * A public, non-final {@code self()} on an unprocessed root is still
     * followed: the link overrides it publicly. The root declares no field and
     * spells the copy constructor the link's calls, neither of which a
     * processor wrote for it.
     */
    public void testAnUnprocessedCompiledRootDeclaringAPublicSelf_linkOverridesItPublicly() throws Exception {
        compiled(false, "demo/Shape.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public abstract class Shape {
                protected Shape(Builder<?, ?> builder) { }
                public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {
                    public abstract B self();
                }
            }
            """);
        addCircle();
        assertTrue("the link's self() is public",
            selfOf(builderOf(findClass("demo.Circle"))).hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(List.of(), errorsIn("demo/Circle.java"));
    }

    // ------------------------------------------------------------------
    // A self() a root inherits from a compiled supertype
    // ------------------------------------------------------------------

    /** Compiles {@code demo.Fluent}, declaring a protected abstract {@code self()}, and attaches it. */
    private void compiledFluent() throws Exception {
        compiled(false, "demo/Fluent.java", """
            package demo;
            public abstract class Fluent<B> {
                protected abstract B self();
            }
            """);
    }

    /** Adds a source root whose builder extends the compiled {@code Fluent} with the given type argument. */
    private void addFluentShape(String fluentArgument) {
        myFixture.addFileToProject("demo/Shape.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public abstract class Shape {
                private String name;
                public String getName() { return name; }
                public abstract static class Builder<T extends Shape, B extends Builder<T, B>>
                        extends Fluent<%s> { }
            }
            """.formatted(fluentArgument));
    }

    /**
     * A {@code self()} a source root's builder inherits from a compiled
     * supertype, returning another type than the pair's {@code B}, is reported
     * on the root's builder in the processor's sentence. The editor was silent.
     */
    public void testARootInheritingASelfOfAnotherTypeFromACompiledSupertype_isReported() throws Exception {
        compiledFluent();
        addFluentShape("String");
        assertEquals(List.of("@ClassBuilder merged into 'Builder' finds self() inherited from Fluent returning "
            + "String, where the generated setters need it to return B"), errorsIn("demo/Shape.java"));
    }

    /** A compiled supertype's {@code self()} returning the pair's {@code B} is followed, and the chain resolves. */
    public void testARootInheritingItsBuilderTypeFromACompiledSupertype_isNotReported() throws Exception {
        compiledFluent();
        addFluentShape("B");
        addCircle();
        addUseShape();
        assertEquals(List.of(), errorsIn("demo/Shape.java"));
        assertEquals(List.of(), allErrorsIn("demo/UseShape.java"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Compiles a generic abstract root {@code demo.Box<V>} with the processor, and attaches it. */
    private void compiledBox() throws Exception {
        compiled(true, "demo/Box.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public abstract class Box<V> {
                private V value;
                public V getValue() { return value; }
            }
            """);
    }

    /** Adds a concrete link {@code demo.Circle} extending the compiled box as written. */
    private void addBoxLink(String extendsClause) {
        myFixture.addFileToProject("demo/Circle.java", """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Circle extends %s {
                private int radius;
                public int getRadius() { return radius; }
            }
            """.formatted(extendsClause));
    }

    /** Compiles an abstract root in {@code demo} whose body ends with the declaration, and attaches it. */
    private void compiledShape(boolean processed, String builderDeclaration) throws Exception {
        compiled(processed, "demo/Shape.java", """
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

    /** Compiles one source and attaches its class files. */
    private void compiled(boolean processed, String path, String source) throws Exception {
        compiled(processed, Map.of(path, source));
    }

    /** Compiles sources in one javac run and attaches their class files. */
    private void compiled(boolean processed, Map<String, String> sources) throws Exception {
        CompiledLibrary.attach(getTestRootDisposable(), getModule(), CompiledLibrary.compile(processed, sources));
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

    /** Adds a consumer of {@link #addCircle()}'s link, returning the name it built with. */
    private void addUseShape() {
        myFixture.addFileToProject("demo/UseShape.java", """
            package demo;
            public class UseShape {
                public static String go() { return Circle.builder().name("c").radius(1).build().getName(); }
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

    /** Adds a consumer of {@link #addOtherCircle()}'s link, returning the name it built with. */
    private void addUseOtherCircle() {
        myFixture.addFileToProject("other/UseCircle.java", """
            package other;
            public class UseCircle {
                public static String go() { return Circle.builder().name("x").radius(1).build().getName(); }
            }
            """);
    }

    /** The link's refusal of an ancestor builder it cannot reach, for the reason given. */
    private static String linkRefusal(String link, String ancestorBuilder, String reason) {
        return "@ClassBuilder generates no builder on '" + link + "' - '" + ancestorBuilder
            + "', which its builder has to extend, " + reason;
    }

    /** The {@code @ClassBuilder} errors highlighted in a project file. */
    private List<String> errorsIn(String path) {
        List<String> out = new ArrayList<>();
        for (String description : allErrorsIn(path)) {
            if (description.startsWith("@ClassBuilder")) out.add(description);
        }
        return out;
    }

    /** Every error highlighted in a project file. */
    private List<String> allErrorsIn(String path) {
        myFixture.configureFromTempProjectFile(path);
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.ERROR && info.getDescription() != null)
                out.add(info.getDescription());
        }
        return out;
    }

    private PsiClass findClass(String qualifiedName) {
        PsiClass found = JavaPsiFacade.getInstance(getProject())
            .findClass(qualifiedName, GlobalSearchScope.allScope(getProject()));
        assertNotNull("no class " + qualifiedName, found);
        return found;
    }

    /** The class's nested builder, a contributed one included. */
    private static PsiClass builderOf(PsiClass owner) {
        for (PsiClass nested : owner.getInnerClasses()) {
            if ("Builder".equals(nested.getName())) return nested;
        }
        throw new AssertionError("no Builder on " + owner.getQualifiedName());
    }

    /** The builder's no-argument {@code self()}, a contributed one included. */
    private static PsiMethod selfOf(PsiClass builder) {
        for (PsiMethod method : builder.findMethodsByName("self", false)) {
            if (method.getParameterList().isEmpty()) return method;
        }
        throw new AssertionError("no self() on " + builder.getQualifiedName());
    }

}
