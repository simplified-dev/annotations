package dev.simplified.classbuilder.editor;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.BuilderParityFixture;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's half of the declared-builder merge, with the annotation bare.
 *
 * <p>This is the drift in its most literal form if the halves disagree: the
 * build appends every generated setter to the author's declared builder, and an
 * editor that did not would offer only what the author wrote - completion
 * missing the whole generated surface on a class that compiles.
 */
public class DeclaredBuilderMergeParityTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        addAnnotationSources();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder {
                BuilderNames builder() default @BuilderNames;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                SetterNames setters() default @SetterNames;
                String factoryMethod() default "";
                boolean retainInit() default true;
                boolean generateCopyConstructor() default true;
                boolean validate() default true;
                boolean emitContracts() default true;
                boolean emitGenerated() default true;
                boolean generateImpl() default true;
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                AccessLevel builderConstructorAccess() default AccessLevel.PACKAGE;
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/SetterNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface SetterNames {
                String INHERIT = "";
                String NONE = "-";
                String set() default INHERIT;
                String flag() default INHERIT;
                String add() default INHERIT;
                String put() default INHERIT;
                String compute() default INHERIT;
                String clear() default INHERIT;
                String remove() default INHERIT;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({})
            public @interface BuilderNames {
                String INHERIT = "";
                String NONE = "-";
                String type() default INHERIT;
                String builder() default INHERIT;
                String build() default INHERIT;
                String from() default INHERIT;
                String toBuilder() default INHERIT;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderSeed.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.PARAMETER)
            public @interface BuilderSeed { }
            """);
    }

    public void testMerge_offersTheGeneratedSettersOnTheDeclaredBuilder() {
        List<String> names = declaredBuilderMethodsOf("Settings",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Settings {
                private String name;
                private boolean prettyPrint;
                public static class Builder {
                    public Builder apply(Runnable r) { return this; }
                }
            }
            """);
        assertTrue("the author's member is still there: " + names, names.contains("apply"));
        assertTrue("and the generated setters beside it: " + names, names.contains("name"));
        assertTrue("including the boolean pair: " + names, names.contains("prettyPrint"));
        assertTrue("and the flag form: " + names, names.contains("isPrettyPrint"));
        assertTrue("and the terminal method: " + names, names.contains("build"));
    }

    /** A hand-written setter wins in the editor exactly as it does in the build. */
    public void testMerge_doesNotDuplicateAHandWrittenSetter() {
        List<String> names = declaredBuilderMethodsOf("Doc",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Doc {
                private String fileName;
                private int pages;
                public static class Builder {
                    public Builder fileName(String fileName) { return this; }
                }
            }
            """);
        assertEquals("exactly one fileName setter: " + names, 1, count(names, "fileName"));
        assertTrue("the other slot is generated: " + names, names.contains("pages"));
    }

    /** A declared build() wins too, so the editor must not offer a second. */
    public void testMerge_doesNotDuplicateADeclaredBuild() {
        List<String> names = declaredBuilderMethodsOf("Boxed",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Boxed {
                private int size;
                public static class Builder {
                    public Boxed build() { return null; }
                }
            }
            """);
        assertEquals("exactly one build(): " + names, 1, count(names, "build"));
    }

    /** A nested class that is not the builder is not a merge target. */
    public void testAnUnrelatedNestedClass_isLeftAlone() {
        PsiFile file = myFixture.configureByText("Holder.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Holder {
                private String name;
                public static class Helper { }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        for (PsiClass nested : target.getInnerClasses()) {
            if (!"Helper".equals(nested.getName())) continue;
            List<String> names = new ArrayList<>();
            for (PsiMethod method : nested.getMethods()) names.add(method.getName());
            assertFalse("an unrelated nested class gets nothing: " + names, names.contains("name"));
            return;
        }
        fail("expected the Helper nested class to be present");
    }

    /**
     * The processor merges into a nested type of the builder's name on a plain
     * standalone target and emits all three entry points onto it, so an editor
     * withholding any of them, or any merged member, is red over source that
     * builds. The declaration used to suppress the whole pass on both halves.
     */
    public void testDeclaredBuilder_offersTheEntryPointsAndTheMergedMembers() {
        assertParity(BuilderParityFixture.load("standalone-declared-builder"));
    }

    /**
     * A link's declared builder is merged into: its setter and the concrete pair
     * land beside the author's verb, and all three entry points on the target.
     * The chain branch used to return ahead of the declared-builder check, and
     * the case pinned that nothing merged.
     */
    public void testADeclaredLinkBuilder_isMergedInto() {
        assertParity(BuilderParityFixture.load("chain-link-declared-builder"));
    }

    /** The same merge one role up, where the pair is abstract and there are no entry points. */
    public void testADeclaredRootBuilder_isMergedInto() {
        assertParity(BuilderParityFixture.load("chain-root-declared-builder"));
    }

    /**
     * A root's build method returning the root is merged into, and a link's
     * builder chains the root's setters into its own {@code build()}, as the
     * processor's twin runs it. The shape check refused the root, which took
     * the root's merged members with it.
     */
    public void testAMergedRootWhoseBuildReturnsTheRoot_offersItsMembersToALink() {
        myFixture.addFileToProject("demo/Shape.java",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                private String name;
                public String getName() { return name; }
                public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {
                    public abstract Shape build();
                }
            }
            """);
        myFixture.addFileToProject("demo/Circle.java",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Circle extends Shape {
                private int radius;
                public int getRadius() { return radius; }
            }
            """);
        myFixture.configureByText("UseShape.java",
            """
            import demo.Circle;
            public class UseShape {
                String go() {
                    Circle c = Circle.builder().name("a").radius(2).build();
                    return c.getName() + "/" + c.getRadius();
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * The chain's copy constructor takes the builder javac emits, which on a
     * root declaring its own is the author's class - so a hand-written subclass
     * passing that class to {@code super(b)} resolves.
     */
    public void testAMergedRoot_copyConstructorTakesTheDeclaredBuilder() {
        myFixture.configureByText("Rooted.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Rooted {
                private String label;
                public abstract static class Builder<T extends Rooted, B extends Builder<T, B>> { }
            }
            class Manual extends Rooted {
                Manual(Rooted.Builder<?, ?> b) { super(b); }
            }
            """);
        assertNoErrors();
    }

    /**
     * A link's entry points instantiate its declared builder, so one declaring
     * only constructors that take parameters loses all three, as the processor
     * skips them with a note - while the merge and the copy constructor still
     * run.
     */
    public void testAMergedLinkWhoseBuilderTakesParameters_offersNoEntryPoints() {
        PsiFile file = myFixture.configureByText("Link.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Link extends Base {
                private String extra;
                public String getExtra() { return extra; }
                public static class Builder extends Base.Builder<Link, Builder> {
                    public Builder(String extra) { this.extra = extra; }
                }
            }
            @ClassBuilder
            abstract class Base { private String label; }
            class Caller {
                String make() { return new Link.Builder("x").label("l").build().getExtra(); }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> names = methodNamesOf(target);
        assertFalse("no builder() without a constructor it can call: " + names, names.contains("builder"));
        assertFalse("nor from(T): " + names, names.contains("from"));
        assertFalse("nor mutate(): " + names, names.contains("mutate"));
        assertEquals("the copy constructor stays: " + names, 1, target.getConstructors().length);
        assertNoErrors();
    }

    /**
     * A chain role's refused declaration stops the pass ahead of the copy
     * constructor, so the editor offers neither that constructor nor a merged
     * member - where a class target keeps the all-args constructor the
     * processor decides before the merge.
     */
    public void testARefusedRootShape_offersNoCopyConstructorAndNoMembers() {
        PsiFile file = myFixture.configureByText("Rooted.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Rooted {
                private String label;
                public static class Builder { }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        assertEquals("no copy constructor beside a refused declaration", 0,
            target.getConstructors().length);
        List<String> nested = methodNamesOf(nestedOf(target, "Builder"));
        assertFalse("and nothing merged into it: " + nested, nested.contains("label"));
    }

    /**
     * A link whose ancestor's declared builder cannot take the extends clause is
     * refused before its own declaration is looked at, so nothing is merged into
     * that declaration - even one whose own shape, read by names, would pass.
     */
    public void testADeclaringLinkOverABlockingAncestor_getsNothingMerged() {
        PsiFile file = myFixture.configureByText("Leaf.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Leaf extends Rooted {
                private String b;
                public static class Builder extends Rooted.Builder<Leaf, Builder> { }
            }
            @ClassBuilder
            class Rooted { public static class Builder { } }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> nested = methodNamesOf(nestedOf(target, "Builder"));
        assertFalse("the refused link merges nothing: " + nested,
            nested.contains("b") || nested.contains("build"));
    }

    /**
     * An interface's builder is a sibling file and the processor never looks at
     * a class nested in the interface body, so nothing is appended to it under
     * a bare annotation. With the merge running on every declared builder, an
     * editor that did not ask whether the owner is an interface type target
     * would list setters and a build method on that class which javac never
     * emits there.
     */
    public void testAClassNestedInAnInterface_isNotMergedInto() {
        PsiFile file = myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public interface Shape {
                String name();
                class Builder {
                    public Builder apply(Runnable r) { return this; }
                }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> nested = methodNamesOf(nestedOf(target, "Builder"));
        assertTrue("the author's member stays: " + nested, nested.contains("apply"));
        assertFalse("and nothing is merged beside it: " + nested, nested.contains("name"));
        assertFalse("not even a build(): " + nested, nested.contains("build"));
    }

    /**
     * The merge appends the slot fields into the author's builder and the editor
     * contributed none, so a reference to one inside the author's own verb was
     * red over source that builds - which lands on exactly the hand-written verb
     * the merge exists to allow.
     */
    public void testMergedBuilder_slotFieldsResolveInsideAnAuthorVerb() {
        myFixture.configureByText("Settings.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Settings {
                private String name;
                private int size;
                public static class Builder {
                    public Builder shout() {
                        this.name = this.name.toUpperCase();
                        this.size = this.size + 1;
                        return this;
                    }
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * An initialised slot whose initializer reads nothing of the instance is
     * merged as its declared type, and the processor's build runs the author's
     * verb over it. The editor left every initialised slot out, so the verb was
     * red over source that builds.
     */
    public void testMergedBuilder_anInitialisedSlotResolvesInsideAnAuthorVerb() {
        myFixture.configureByText("Titled.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Titled {
                private String name = "untitled";
                public String getName() { return name; }
                public static class Builder {
                    public Builder shout() { this.name = this.name.toUpperCase(); return this; }
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * An initializer reading the instance holds its slot as a supplier, and an
     * author's verb assigning one compiles, as the apt twin runs it.
     */
    public void testMergedBuilder_aSlotWhoseInitializerReadsTheInstanceIsASupplier() {
        myFixture.configureByText("Labelled.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Labelled {
                private String name;
                private String label = name + "!";
                public String getLabel() { return label; }
                public static class Builder {
                    public Builder preset() { this.label = () -> "preset"; return this; }
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * A {@code @Collector} slot whose default reads the instance is held in a
     * plain {@code java.util} scratch container, which the apt twin's author
     * verb adds to. The editor contributed no field for it, so the verb was red
     * over source that builds.
     */
    public void testMergedBuilder_aCollectedSlotWhoseInitializerReadsTheInstanceIsItsScratchContainer() {
        addCollectorAnnotation();
        PsiFile file = myFixture.configureByText("Tagged.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder
            public class Tagged {
                private String name;
                @Collector private ArrayList<String> tags = new ArrayList<>(List.of(String.valueOf(name)));
                public List<String> getTags() { return tags; }
                public static class Builder {
                    public Builder foo() { this.tags.add("foo"); return this; }
                }
            }
            """);
        assertNoErrors();
        PsiField tags = nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder").findFieldByName("tags", false);
        assertNotNull("the scratch slot is contributed", tags);
        assertEquals("held in the java.util container, not the declared type",
            "java.util.List<java.lang.String>", tags.getType().getCanonicalText());
    }

    /**
     * An initializer calling a getter {@code @Getter} generates reads the
     * instance, so its slot is a supplier, as the apt twin runs it. Both halves
     * held it as declared, and javac refused the class for the static provider
     * the default was hoisted into while the editor was green.
     */
    public void testMergedBuilder_aSlotWhoseInitializerCallsAGeneratedGetterIsASupplier() {
        addGetterAnnotation();
        myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Getter;
            @ClassBuilder
            @Getter
            public class Named {
                private String name;
                private String label = getName() + "!";
                public static class Builder {
                    public Builder preset() { this.label = () -> "preset"; return this; }
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * A varargs parameter's merged slot is the array javac declares, so an
     * author's verb reads its length and assigns it to an array local, as the
     * apt twin runs it. The field was contributed with the parameter's ellipsis
     * type.
     */
    public void testMergeOnAVarargsParameter_theSlotFieldIsAnArray() {
        PsiFile file = myFixture.configureByText("Tags.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Tags {
                private final String[] values;
                @ClassBuilder
                Tags(String... values) { this.values = values; }
                public static final class Builder {
                    public int size() { String[] copy = this.values; return copy.length; }
                }
            }
            """);
        assertNoErrors();
        PsiField values = nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder").findFieldByName("values", false);
        assertNotNull("the slot is contributed", values);
        assertEquals("the array javac declares", "String[]", values.getType().getPresentableText());
    }

    /**
     * The processor skips all three entry points where the declared builder has
     * constructors and no nullary one, every entry point instantiating it. An
     * editor still offering them would be the same divergence in a narrower
     * shape, so the two withhold together.
     */
    public void testMergedBuilderWithNoNullaryConstructor_offersNoEntryPoints() {
        PsiFile file = myFixture.configureByText("Seeded.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Seeded {
                private String name;
                public static class Builder {
                    private final String origin;
                    public Builder(String origin) { this.origin = origin; }
                }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> names = methodNamesOf(target);
        assertFalse("nothing can call new on it: " + names, names.contains("builder"));
        assertFalse("nor seed one: " + names, names.contains("from"));
        assertFalse("nor read one back: " + names, names.contains("mutate"));
        assertTrue("but the setters are still merged in: "
                + methodNamesOf(nestedOf(target, "Builder")),
            methodNamesOf(nestedOf(target, "Builder")).contains("name"));
    }

    /**
     * A constructor target is never in a chain, so its enclosing class's
     * supertype says nothing about whether a builder is generated - the
     * processor's third path never looks for an annotated super at all. Asking
     * anyway withheld a builder and an entry point the build emits.
     */
    public void testAConstructorTargetUnderADeclaringSuper_keepsItsBuilder() {
        PsiFile file = myFixture.configureByText("Child.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            class Parent { public static class Builder { } }
            public class Child extends Parent {
                private final String a;
                @ClassBuilder
                public Child(String a) { this.a = a; }
            }
            """);
        PsiClass child = ((PsiJavaFile) file).getClasses()[1];
        assertEquals("Child", child.getName());
        assertTrue("its entry point is emitted: " + methodNamesOf(child),
            methodNamesOf(child).contains("builder"));
        assertNotNull("and its builder with it", nestedOf(child, "Builder"));
    }

    /**
     * The skip withholds those three and nothing else. The merge still runs and
     * the target still gets the all-args constructor {@code build()} calls, so
     * answering the whole request empty took that constructor with it and put a
     * same-package {@code new Target(...)} red over source that builds.
     */
    public void testMergedBuilderWithNoNullaryConstructor_keepsTheAllArgsConstructor() {
        PsiFile file = myFixture.configureByText("Seeded.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Seeded {
                private String name;
                public static class Builder {
                    private final String origin;
                    public Builder(String origin) { this.origin = origin; }
                }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        PsiMethod[] constructors = target.getConstructors();
        assertEquals("the constructor build() calls is still there: "
            + constructors.length, 1, constructors.length);
        assertEquals(1, constructors[0].getParameterList().getParametersCount());
    }

    /** A nullary constructor beside a seeded one keeps them. */
    public void testMergedBuilderWithANullaryConstructor_keepsItsEntryPoints() {
        PsiFile file = myFixture.configureByText("Both.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Both {
                private String name;
                public static class Builder {
                    public Builder() { }
                    public Builder(String origin) { }
                }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertTrue("one constructor serves them: " + names, names.contains("builder"));
    }

    // ------------------------------------------------------------------
    // The declared builder's constructor
    //
    // The processor retypes javac's default constructor on a declared builder
    // that declares none to builderConstructorAccess, on a class, record,
    // constructor or factory target. PSI has no default to retype, so the
    // editor contributes one at that access; the editor used to leave PSI's
    // implicit default at the class's access, public here.
    // ------------------------------------------------------------------

    /** One constructor, package-private at the attribute's default. */
    public void testADeclaredBuilderWithNoConstructor_getsOneAtBuilderConstructorAccess() {
        PsiFile file = myFixture.configureByText("Probe.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Probe {
                private String name;
                public static class Builder { }
            }
            """);
        PsiMethod[] constructors = nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder").getConstructors();
        assertEquals("javac's retyped default: " + constructors.length, 1, constructors.length);
        assertEquals(0, constructors[0].getParameterList().getParametersCount());
        assertFalse(constructors[0].hasModifierProperty(PsiModifier.PUBLIC));
        assertFalse(constructors[0].hasModifierProperty(PsiModifier.PROTECTED));
        assertFalse(constructors[0].hasModifierProperty(PsiModifier.PRIVATE));
    }

    /** The configured access, not merely package-private. */
    public void testADeclaredBuilderWithNoConstructor_takesTheConfiguredAccess() {
        PsiFile file = myFixture.configureByText("Sealed.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
            public class Sealed {
                private String name;
                public static class Builder { }
            }
            """);
        PsiMethod[] constructors = nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder").getConstructors();
        assertEquals("javac's retyped default: " + constructors.length, 1, constructors.length);
        assertTrue(constructors[0].hasModifierProperty(PsiModifier.PRIVATE));
    }

    /**
     * The apt suite's cross-package case: the entry point resolves and the
     * direct construction is refused, as javac refuses it.
     */
    public void testADeclaredBuilderWithNoConstructor_isClosedToAnotherPackage() {
        myFixture.addFileToProject("p/Probe.java",
            """
            package p;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Probe {
                private String name;
                public String getName() { return name; }
                public static class Builder { }
            }
            """);
        myFixture.configureByText("UseProbe.java",
            """
            import p.Probe;
            public class UseProbe {
                String go() {
                    return Probe.builder().name("x").build().getName()
                        + new Probe.Builder().name("y").build().getName();
                }
            }
            """);
        List<String> errors = errors();
        assertEquals("only the direct construction is refused: " + errors, 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("Builder()"));
    }

    /**
     * A generated builder's package-private constructor is refused from another
     * package too, as javac refuses it. The light constructor used to carry no
     * access modifier at all, which the platform's access check reads as
     * public, so the call resolved.
     */
    public void testAGeneratedBuildersConstructor_isClosedToAnotherPackage() {
        myFixture.addFileToProject("p/Widget.java",
            """
            package p;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget {
                private String name;
                public String getName() { return name; }
            }
            """);
        myFixture.configureByText("UseWidget.java",
            """
            import p.Widget;
            public class UseWidget {
                String go() {
                    return Widget.builder().name("x").build().getName()
                        + new Widget.Builder().name("y").build().getName();
                }
            }
            """);
        List<String> errors = errors();
        assertEquals("only the direct construction is refused: " + errors, 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("Builder()"));
    }

    /** An author's constructor is theirs, and nothing is contributed beside it. */
    public void testADeclaredBuilderWithItsOwnConstructor_keepsItAlone() {
        PsiFile file = myFixture.configureByText("Open.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Open {
                private String name;
                public static class Builder {
                    public Builder() { }
                }
            }
            """);
        PsiMethod[] constructors = nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder").getConstructors();
        assertEquals("the author's alone: " + constructors.length, 1, constructors.length);
        assertTrue(constructors[0].hasModifierProperty(PsiModifier.PUBLIC));
    }

    /**
     * {@code @NoArgsConstructor} written on the declared builder appends a
     * constructor before the merge runs, and the processor retypes javac's
     * default only beside no other constructor, so the builder javac emits has
     * exactly one no-argument constructor. The editor read only the author's
     * constructors and contributed the retyped one beside the annotation's.
     */
    public void testADeclaredBuilderWithANoArgsConstructorAnnotation_hasOneNullaryConstructor() {
        addNoArgsConstructorAnnotation();
        PsiFile file = myFixture.configureByText("Held.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.NoArgsConstructor;
            @ClassBuilder
            public class Held {
                private String name;
                public String getName() { return name; }
                @NoArgsConstructor
                public static class Builder { }
                static String go() {
                    return new Held.Builder().name("a").build().getName() + Held.builder().name("b").build().getName();
                }
            }
            """);
        List<String> nullary = new ArrayList<>();
        for (PsiMethod ctor : nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder").getConstructors()) {
            if (ctor.getParameterList().isEmpty()) nullary.add(ctor.getModifierList().getText());
        }
        assertEquals("javac emits one public Builder(): " + nullary, 1, nullary.size());
        assertNoErrors();
    }

    /** A constructor target's declared builder is retyped as a type target's is. */
    public void testAConstructorTargetsDeclaredBuilderWithNoConstructor_getsOneAtBuilderConstructorAccess() {
        PsiFile file = myFixture.configureByText("Step.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Step {
                private final String key;
                @ClassBuilder
                Step(String key) { this.key = key; }
                public static class Builder { }
            }
            """);
        PsiMethod[] constructors = nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder").getConstructors();
        assertEquals("javac's retyped default: " + constructors.length, 1, constructors.length);
        assertEquals(0, constructors[0].getParameterList().getParametersCount());
        assertFalse(constructors[0].hasModifierProperty(PsiModifier.PUBLIC));
    }

    /**
     * A chain role's declared builder keeps javac's default at the class's
     * access, so nothing is contributed and a caller in another package
     * constructs it.
     */
    public void testADeclaredLinkBuilder_keepsTheImplicitDefault() {
        myFixture.addFileToProject("q/Base.java",
            """
            package q;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Base {
                String label;
                public String getLabel() { return label; }
            }
            """);
        myFixture.addFileToProject("q/Link.java",
            """
            package q;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Link extends Base {
                String extra;
                public String getExtra() { return extra; }
                public static class Builder extends Base.Builder<Link, Builder> { }
            }
            """);
        myFixture.configureByText("UseLink.java",
            """
            import q.Link;
            public class UseLink {
                String go() { return new Link.Builder().extra("x").build().getExtra(); }
            }
            """);
        assertNoErrors();
    }

    /** And a field the author declared themselves is not doubled. */
    public void testMergedBuilder_doesNotDuplicateADeclaredSlotField() {
        PsiFile file = myFixture.configureByText("Doc.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Doc {
                private String fileName;
                private int pages;
                public static class Builder {
                    private String fileName = "untitled";
                }
            }
            """);
        PsiClass builder = nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder");
        List<String> names = new ArrayList<>();
        for (PsiField field : builder.getFields()) names.add(field.getName());
        assertEquals("exactly one fileName: " + names, 1, count(names, "fileName"));
        assertTrue("and the other slot is contributed: " + names, names.contains("pages"));
    }

    /**
     * javac's entry points return the declared builder, which carries the
     * author's verbs beside the merged setters. The editor typed all three
     * against the synthesised builder it withholds from the target, whose
     * members are the generated set alone, so a call chaining a generated setter
     * into an author's verb and on to {@code build()} was red on the author's
     * verb over source that builds.
     */
    public void testEntryPoints_returnTheDeclaredBuilder_soAnAuthorVerbChains() {
        myFixture.configureByText("Settings.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Settings {
                private String name;
                private int size;
                public static class Builder {
                    public Builder apply(Runnable task) { task.run(); return this; }
                }
            }
            class Caller {
                Settings make() { return Settings.builder().name("x").apply(() -> { }).size(3).build(); }
                Settings copy(Settings s) { return Settings.from(s).apply(() -> { }).build(); }
                Settings again(Settings s) { return s.mutate().apply(() -> { }).build(); }
            }
            """);
        assertNoErrors();
    }

    /**
     * The entry points are cached on the target, and the cache was reused
     * whenever the annotation read the same - so an author typing the builder
     * class into a target whose entry points were already built kept them typed
     * against the synthesised class, and their own verb stayed red until
     * something else invalidated it.
     */
    public void testDeclaringTheBuilderLater_retypesTheEntryPoints() {
        myFixture.configureByText("Settings.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Settings {
                private String name;
                <caret>
            }
            class Caller {
                Settings make() { return Settings.builder().name("x").apply(() -> { }).build(); }
            }
            """);
        List<String> before = errors();
        assertFalse("no builder declared yet, so there is no apply: " + before, before.isEmpty());

        WriteCommandAction.runWriteCommandAction(getProject(), () ->
            myFixture.getEditor().getDocument().insertString(myFixture.getCaretOffset(),
                "public static class Builder { public Builder apply(Runnable task) { return this; } }"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertNoErrors();
    }

    /**
     * The processor reports a refused shape and returns before the entry points,
     * so none of the three is emitted, while the all-args constructor it decided
     * ahead of the merge still is. The editor withheld the merged members but
     * still offered all three entry points, green at a call site javac rejects.
     */
    public void testARejectedShape_offersNoEntryPointsAndNoMembers() {
        PsiFile file = myFixture.configureByText("Inner.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Inner {
                private String name;
                public class Builder { }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> names = methodNamesOf(target);
        assertFalse("no builder() beside a refused declaration: " + names, names.contains("builder"));
        assertFalse("nor from(T): " + names, names.contains("from"));
        assertFalse("nor mutate(): " + names, names.contains("mutate"));
        List<String> nested = methodNamesOf(nestedOf(target, "Builder"));
        assertFalse("and nothing merged into it: " + nested, nested.contains("name"));
        PsiMethod[] constructors = target.getConstructors();
        assertEquals("the all-args constructor is decided ahead of the merge and kept: "
            + constructors.length, 1, constructors.length);
        assertEquals(1, constructors[0].getParameterList().getParametersCount());
    }

    // ------------------------------------------------------------------
    // The executable merge
    // ------------------------------------------------------------------

    /**
     * A constructor target merges into its enclosing type's declared builder, as
     * the processor does: the parameter's setter and {@code build()} land beside
     * the author's verb, and {@code builder()} is offered on the target. The
     * declaration used to withhold all of it.
     */
    public void testMergeOnAConstructorTarget_offersTheSlotSetters() {
        PsiFile file = myFixture.configureByText("Action.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Action {
                private final String key;
                @ClassBuilder
                Action(String key) { this.key = key; }
                public static class Builder {
                    public Builder apply(Runnable task) { task.run(); return this; }
                }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> nested = methodNamesOf(nestedOf(target, "Builder"));
        assertTrue("the author's verb stays: " + nested, nested.contains("apply"));
        assertTrue("the parameter's setter is merged in: " + nested, nested.contains("key"));
        assertTrue("and the terminal method: " + nested, nested.contains("build"));
        List<String> entryPoints = methodNamesOf(target);
        assertTrue("the entry point is offered: " + entryPoints, entryPoints.contains("builder"));
        assertFalse("and nothing the executable path never emits: " + entryPoints,
            entryPoints.contains("from") || entryPoints.contains("mutate"));
    }

    /** The entry point returns the declared builder, so a chain through the author's verb resolves. */
    public void testMergeOnAConstructorTarget_entryPointReturnsTheDeclaredBuilder() {
        myFixture.configureByText("Action.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Action {
                private final String key;
                @ClassBuilder
                Action(String key) { this.key = key; }
                public static class Builder {
                    public Builder apply(Runnable task) { task.run(); return this; }
                }
            }
            class Caller {
                Action make() { return Action.builder().key("k").apply(() -> { }).build(); }
            }
            """);
        assertNoErrors();
    }

    /**
     * A slot here is a parameter, so an enclosing-type field of the same name
     * carrying an initializer says nothing about how the merge holds it. Reading
     * that field left the merged slot uncontributed, and the author's verb
     * referencing it red over source that builds.
     */
    public void testMergeOnAConstructorTarget_aParameterSharingAnInitialisedFieldsName_resolves() {
        myFixture.configureByText("Tag.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Tag {
                private String label = "none";
                @ClassBuilder
                Tag(String label) { this.label = label; }
                public static class Builder {
                    public Builder shout() { this.label = this.label + "!"; return this; }
                }
            }
            class Caller {
                Tag make() { return Tag.builder().label("hi").shout().build(); }
            }
            """);
        assertNoErrors();
    }

    /**
     * A static factory's builder re-declares the factory's own type parameters,
     * which is the list the processor measures the declaration against. Reading
     * the enclosing type's refused this shape in the editor while javac merged
     * into it.
     */
    public void testMergeOnAStaticFactory_readsTheFactorysOwnTypeParameters() {
        PsiFile file = myFixture.configureByText("Box.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Box<V> {
                private final V value;
                private Box(V value) { this.value = value; }
                @ClassBuilder
                public static <T> Box<T> of(T value) { return new Box<>(value); }
                public V getValue() { return value; }
                public static class Builder<T> {
                    public Builder<T> apply(Runnable task) { task.run(); return this; }
                }
            }
            class Caller {
                String make() { return Box.<String>builder().value("v").apply(() -> { }).build().getValue(); }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> nested = methodNamesOf(nestedOf(target, "Builder"));
        assertTrue("the factory's parameter is merged in: " + nested, nested.contains("value"));
        assertNoErrors();
    }

    /**
     * {@code builder(seed)} passes its seed to the builder's constructor, so a
     * declared builder taking one argument keeps it - the processor compares the
     * declared arities with the seed count, where the type path's count is zero.
     * The seed's field is merged in too, and the author's constructor assigns it.
     */
    public void testMergeOnASeededConstructor_whereTheBuilderTakesTheSeed_keepsTheEntryPoint() {
        PsiFile file = myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String origin;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public static class Builder {
                    public Builder(String origin) { this.origin = origin.trim(); }
                }
            }
            class Caller {
                Order make() { return Order.builder(" web ").item("tea").build(); }
            }
            """);
        List<String> entryPoints = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertTrue("one constructor takes the seed: " + entryPoints, entryPoints.contains("builder"));
        assertNoErrors();
    }

    /**
     * A declared builder whose only constructor takes nothing cannot be reached
     * by a seeded {@code builder(seed)}, so the processor skips it - and only it:
     * the setters are still merged, and the author's constructor fills the seed.
     */
    public void testMergeOnASeededConstructor_whereTheBuilderTakesNoSeed_offersNoEntryPoint() {
        PsiFile file = myFixture.configureByText("Ticket.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Ticket {
                private final String origin;
                private final String item;
                @ClassBuilder
                Ticket(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public static class Builder {
                    public Builder() { this.origin = "desk"; }
                }
            }
            class Caller {
                Ticket make() { return new Ticket.Builder().item("tea").build(); }
            }
            """);
        List<String> entryPoints = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertFalse("no constructor takes the seed: " + entryPoints, entryPoints.contains("builder"));
        assertNoErrors();
    }

    /**
     * The merged seed field is {@code final}, as javac declares it, so a verb
     * writing over it is refused in the editor as it is in the build.
     */
    public void testMergeOnASeededConstructor_theSeedFieldIsFinal() {
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { }
                public static class Builder {
                    public Builder(String origin) { this.origin = origin; }
                    public Builder reroute(String to) { this.origin = to; return this; }
                }
            }
            """);
        List<String> errors = errors();
        assertEquals("only the write over the committed seed is refused: " + errors,
            List.of("Cannot assign a value to final variable 'origin'"), errors);
    }

    /**
     * A refused shape on a constructor target is an error in the build, which
     * then emits no entry point and merges nothing - so the editor offers
     * neither.
     */
    public void testARejectedShapeOnAConstructorTarget_offersNoEntryPointAndNoMembers() {
        PsiFile file = myFixture.configureByText("Hook.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Hook {
                private final String key;
                @ClassBuilder
                Hook(String key) { this.key = key; }
                public class Builder { }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> entryPoints = methodNamesOf(target);
        assertFalse("no builder() beside a refused declaration: " + entryPoints,
            entryPoints.contains("builder"));
        List<String> nested = methodNamesOf(nestedOf(target, "Builder"));
        assertFalse("and nothing merged into it: " + nested, nested.contains("key"));
    }

    // ------------------------------------------------------------------
    // Reviewed reproductions: what a declared member covers, and the shapes
    // whose entry points javac never emits
    // ------------------------------------------------------------------

    /**
     * An author method sharing a slot setter's name and arity but taking
     * another type is an overload, and the generated setter is offered beside
     * it as the processor appends it. The editor dropped it on the name and
     * arity, and the processor's {@code from(T)} then failed on it unreported.
     */
    public void testMerge_anAuthorMethodTakingAnotherTypeUnderASettersName_keepsTheGeneratedSetter() {
        PsiFile file = myFixture.configureByText("Server.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Server {
                int port;
                public static class Builder {
                    public Builder port(String text) { this.port = Integer.parseInt(text); return this; }
                }
                static Server copy(Server s) { return Server.from(s).port(9090).port("1").build(); }
            }
            """);
        List<String> names = methodNamesOf(nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder"));
        assertEquals("the author's port(String) and the generated port(int): " + names, 2, count(names, "port"));
        assertNoErrors();
    }

    /** One author method covers only the {@code Optional} overload it spells. */
    public void testMerge_anAuthorMethodCoveringOneOptionalOverload_keepsTheOther() {
        PsiFile file = myFixture.configureByText("Labelled.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.Optional;
            @ClassBuilder
            public class Labelled {
                Optional<String> label;
                public static class Builder {
                    public Builder label(String l) { this.label = Optional.ofNullable(l); return this; }
                }
                static Labelled copy(Labelled l) { return Labelled.from(l).label(Optional.of("b")).build(); }
            }
            """);
        List<String> names = methodNamesOf(nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder"));
        assertEquals("the author's label(String) and the generated label(Optional): " + names,
            2, count(names, "label"));
        assertNoErrors();
    }

    /**
     * A builder bounding a re-declared parameter otherwise than the target is
     * refused, and javac then emits no entry point beside it.
     */
    public void testABuilderBoundingATypeParameterOtherwise_offersNoEntryPoints() {
        PsiFile file = myFixture.configureByText("Box.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Box<T extends Number> {
                T value;
                public static class Builder<T> { }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertFalse("javac emits no builder(): " + names, names.contains("builder"));
        assertFalse("nor from(T): " + names, names.contains("from"));
        assertFalse("nor mutate(): " + names, names.contains("mutate"));
    }

    /**
     * A nested record named for the builder is refused, so nothing is merged
     * into it and no entry point is offered. The editor read its implicit
     * {@code static}, merged a setter, a {@code build()} and a constructor into
     * the record, and offered all three entry points.
     */
    public void testANestedRecordBuilder_offersNoEntryPointsAndNoMembers() {
        PsiFile file = myFixture.configureByText("Note.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Note {
                String text;
                record Builder(int unused) { }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> names = methodNamesOf(target);
        assertFalse("javac emits no builder(): " + names, names.contains("builder"));
        List<String> merged = methodNamesOf(nestedOf(target, "Builder"));
        assertFalse("nothing is merged into the record: " + merged, merged.contains("text"));
        assertFalse("nor a build(): " + merged, merged.contains("build"));
    }

    /**
     * A no-argument builder constructor declaring a throws clause serves no
     * entry point, so all three are withheld while the setters stay merged in.
     */
    public void testMergedBuilderWhoseNoArgConstructorThrows_offersNoEntryPoints() {
        PsiFile file = myFixture.configureByText("Conn.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Conn {
                String host;
                public static class Builder {
                    Builder() throws java.io.IOException { }
                }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> names = methodNamesOf(target);
        assertFalse("javac emits no builder(): " + names, names.contains("builder"));
        assertFalse("nor from(T): " + names, names.contains("from"));
        assertFalse("nor mutate(): " + names, names.contains("mutate"));
        assertTrue("the setters are still merged in: " + methodNamesOf(nestedOf(target, "Builder")),
            methodNamesOf(nestedOf(target, "Builder")).contains("host"));
    }

    /**
     * A no-argument builder constructor whose throws clause names only known
     * unchecked exceptions serves the entry points, which javac emits. The
     * editor withheld all three over any throws clause, as the processor did.
     */
    public void testMergedBuilderWhoseNoArgConstructorThrowsOnlyUnchecked_offersTheEntryPoints() {
        PsiFile file = myFixture.configureByText("Conn.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Conn {
                String host;
                public static class Builder {
                    Builder() throws IllegalStateException, java.util.NoSuchElementException { }
                }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        List<String> names = methodNamesOf(target);
        assertTrue("javac emits builder(): " + names, names.contains("builder"));
        assertTrue("and from(T): " + names, names.contains("from"));
        assertTrue("and mutate(): " + names, names.contains("mutate"));
    }

    // ------------------------------------------------------------------
    // Reviewed reproductions: the constructor and factory path
    // ------------------------------------------------------------------

    /**
     * A static factory inside an interface is an executable target, and javac
     * merges its slots into the class the interface body declares. The editor
     * read every interface owner as an interface type target, whose builder is a
     * sibling file, merged nothing and typed {@code builder()} against the
     * synthesised class - so the author's own verb and the generated setter were
     * both red over source that runs.
     */
    public void testAStaticFactoryInAnInterface_mergesIntoItsDeclaredBuilder() {
        myFixture.addFileToProject("Circle.java", "public record Circle(double radius) { }");
        PsiFile file = myFixture.configureByText("Shapes.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public interface Shapes {
                @ClassBuilder
                static Circle circle(double radius) { return new Circle(radius); }
                class Builder {
                    public Builder doubled() { this.radius = radius * 2; return this; }
                }
            }
            class UseShapes {
                static double go() { return new Shapes.Builder().radius(1.5).doubled().build().radius(); }
                static double entry() { return Shapes.builder().radius(1.5).doubled().build().radius(); }
            }
            """);
        List<String> nested = methodNamesOf(nestedOf(((PsiJavaFile) file).getClasses()[0], "Builder"));
        assertTrue("the factory's parameter is merged in: " + nested, nested.contains("radius"));
        assertNoErrors();
    }

    /**
     * The same site with a builder whose only constructor takes a parameter:
     * javac skips {@code builder()} with a note, so the editor offers none.
     */
    public void testAStaticFactoryInAnInterface_whoseBuilderTakesParameters_offersNoEntryPoint() {
        myFixture.addFileToProject("Circle.java", "public record Circle(double radius) { }");
        PsiFile file = myFixture.configureByText("Shapes.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public interface Shapes {
                @ClassBuilder
                static Circle circle(double radius) { return new Circle(radius); }
                class Builder { Builder(int n) { } }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertFalse("javac emits no builder(): " + names, names.contains("builder"));
    }

    /**
     * The same site with an abstract builder, which javac refuses before the
     * entry point - so the editor offers none.
     */
    public void testAStaticFactoryInAnInterface_withAnAbstractBuilder_offersNoEntryPoint() {
        myFixture.addFileToProject("Circle.java", "public record Circle(double radius) { }");
        PsiFile file = myFixture.configureByText("Shapes.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public interface Shapes {
                @ClassBuilder
                static Circle circle(double radius) { return new Circle(radius); }
                abstract class Builder { }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertFalse("javac emits no builder(): " + names, names.contains("builder"));
    }

    /**
     * A builder constructor of the seed count that takes another type is not one
     * {@code builder(seed)} can call, so javac skips the entry point and the
     * editor offers none. Both halves counted the parameters only, and javac then
     * failed on the class line.
     */
    public void testMergeOnASeededConstructor_whoseBuilderTakesAnotherTypeAtTheSeedsArity_offersNoEntryPoint() {
        PsiFile file = myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String origin;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public static final class Builder {
                    Builder(int code) { this.origin = "code-" + code; }
                }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertFalse("javac emits no builder(String): " + names, names.contains("builder"));
    }

    /** The seeds are passed in parameter order, so a constructor taking them swapped serves neither. */
    public void testMergeOnTwoSeeds_whoseBuilderTakesThemInAnotherOrder_offersNoEntryPoint() {
        PsiFile file = myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String origin;
                private final int qty;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, @BuilderSeed int qty, String item) {
                    this.origin = origin; this.qty = qty; this.item = item;
                }
                public static final class Builder {
                    Builder(int qty, String origin) { this.qty = qty; this.origin = origin; }
                }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertFalse("javac emits no builder(String, int): " + names, names.contains("builder"));
    }

    /**
     * An author's own {@code builder} of the seed count wins on the executable
     * path as on the type path: javac skips its entry point with a note, so a
     * call passing the seed's type resolves against the author's method alone and
     * is red. The editor offered a second {@code builder(String)} beside it.
     */
    public void testAnExecutableTarget_besideAnAuthorBuilderOfTheSeedCount_offersNoSecondBuilder() {
        PsiFile file = myFixture.configureByText("Ticket.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Ticket {
                private final String origin;
                private final String item;
                @ClassBuilder
                Ticket(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public String origin() { return origin; }
                public static Builder builder(int code) { return new Builder("code-" + code); }
                public static final class Builder {
                    Builder(String origin) { this.origin = origin; }
                }
            }
            class UseTicket {
                static String go() { return Ticket.builder("web").item("x").build().origin(); }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertEquals("javac emits only the author's builder(int): " + names, 1, count(names, "builder"));
        assertFalse("and the call passing a String is red", errors().isEmpty());
    }

    /**
     * The same collision where the author's method has the generated one's
     * signature, which javac compiles and runs. The editor's second copy made
     * the author's method a duplicate and the call ambiguous.
     */
    public void testAnExecutableTarget_besideAnAuthorBuilderOfTheSameSignature_isGreen() {
        PsiFile file = myFixture.configureByText("Ticket.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Ticket {
                private final String origin;
                private final String item;
                @ClassBuilder
                Ticket(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public String origin() { return origin; }
                public static Builder builder(String origin) { return new Builder(origin); }
                public static final class Builder {
                    Builder(String origin) { this.origin = origin; }
                }
            }
            class UseTicket {
                static String go() { return Ticket.builder("web").item("x").build().origin(); }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertEquals("the author's builder(String) alone: " + names, 1, count(names, "builder"));
        assertNoErrors();
    }

    // ------------------------------------------------------------------
    // The constructors the entry points are counted against
    // ------------------------------------------------------------------

    /**
     * The constructor {@code @AllArgsConstructor} appends onto the declared
     * builder takes parameters, so javac skips {@code builder()},
     * {@code from(T)} and {@code mutate()}. The editor counted only the
     * author's constructors and offered all three, green over a call javac
     * rejects; the appended constructor itself stays callable.
     */
    public void testADeclaredBuilderWhoseOnlyConstructorAnAllArgsAnnotationAppends_offersNoEntryPoints() {
        addArgsConstructorAnnotation("AllArgsConstructor");
        PsiFile file = myFixture.configureByText("Held.java",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Held {
                private String name;
                public String getName() { return name; }
                @AllArgsConstructor
                public static class Builder {
                    private String tag;
                }
                static String go() { return new Held.Builder("t").name("a").build().getName(); }
            }
            """);
        List<String> names = methodNamesOf(((PsiJavaFile) file).getClasses()[0]);
        assertFalse("javac emits no builder(): " + names, names.contains("builder"));
        assertFalse("nor from(T): " + names, names.contains("from"));
        assertFalse("nor mutate(): " + names, names.contains("mutate"));
        assertNoErrors();
    }

    /**
     * The no-argument constructor {@code @NoArgsConstructor} appends beside the
     * author's parameterised one serves the entry points, so javac emits them.
     * The editor withheld them, red over source that builds.
     */
    public void testANoArgsAnnotationBesideAParameterConstructor_offersTheEntryPoints() {
        addNoArgsConstructorAnnotation();
        myFixture.configureByText("Held.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.NoArgsConstructor;
            @ClassBuilder
            public class Held {
                private String name;
                public String getName() { return name; }
                @NoArgsConstructor
                public static class Builder {
                    public Builder(String tag) { }
                }
                static String go() {
                    return Held.builder().name("a").build().getName()
                        + Held.from(new Held.Builder("t").name("b").build()).build().getName();
                }
            }
            """);
        assertNoErrors();
    }

    /**
     * On a constructor target the constructor {@code @AllArgsConstructor}
     * appends over the author's seed field takes the seed, so javac emits
     * {@code builder(origin)}. The editor saw no constructor and withheld it.
     */
    public void testAConstructorTargetWhoseAllArgsBuilderTakesTheSeed_offersTheEntryPoint() {
        addArgsConstructorAnnotation("AllArgsConstructor");
        myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String origin;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public String origin() { return origin; }
                @AllArgsConstructor
                public static final class Builder {
                    private final String origin;
                }
                static String go() { return Order.builder("web").item("x").build().origin(); }
            }
            """);
        assertNoErrors();
    }

    /**
     * An {@code Order} whose one seed is {@code origin} of {@code seedType},
     * whose declared builder carries {@code constructors}, entered through
     * {@code builder(argument)}.
     */
    private void configureSeededOrder(String seedType, String constructors, String argument) {
        myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final %1$s origin;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed %1$s origin, String item) { this.origin = origin; this.item = item; }
                public String describe() { return origin + ":" + item; }
                public static final class Builder {
                    %2$s
                }
                static String go() { return Order.builder(%3$s).item("x").build().describe(); }
            }
            """.formatted(seedType, constructors, argument));
    }

    /**
     * javac's {@code builder(seed)} reaches a constructor taking the seed's box.
     * Only equal types were counted, so the editor withheld it, red over source
     * that builds.
     */
    public void testMergeOnAPrimitiveSeedWhoseBuilderTakesItsBox_offersTheEntryPoint() {
        configureSeededOrder("int", "Builder(Integer origin) { this.origin = origin; }", "3");
        assertNoErrors();
    }

    /** A boxed seed reaches a constructor taking its primitive. */
    public void testMergeOnABoxedSeedWhoseBuilderTakesItsPrimitive_offersTheEntryPoint() {
        configureSeededOrder("Integer", "Builder(int origin) { this.origin = origin; }", "3");
        assertNoErrors();
    }

    /** A primitive seed reaches a constructor taking a wider primitive. */
    public void testMergeOnAPrimitiveSeedWhoseBuilderTakesAWiderPrimitive_offersTheEntryPoint() {
        configureSeededOrder("int", "Builder(long origin) { this.origin = (int) (origin * 2); }", "3");
        assertNoErrors();
    }

    /** A reference seed reaches a constructor taking {@code Object}. */
    public void testMergeOnAReferenceSeedWhoseBuilderTakesObject_offersTheEntryPoint() {
        configureSeededOrder("String", "Builder(java.lang.Object origin) { this.origin = origin + \"!\"; }",
            "\"web\"");
        assertNoErrors();
    }

    /**
     * Any other supertype stays unmatched on both halves, so the editor
     * withholds {@code builder(seed)} as javac skips it.
     */
    public void testMergeOnAReferenceSeedWhoseBuilderTakesAnotherSupertype_offersNoEntryPoint() {
        configureSeededOrder("String", "Builder(CharSequence origin) { this.origin = origin.toString(); }",
            "\"web\"");
        assertTrue("javac emits no builder(String): " + errors(),
            errors().contains("Cannot resolve method 'builder' in 'Order'"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Adds a constructor annotation of the given simple name that appends a constructor. */
    private void addArgsConstructorAnnotation(String name) {
        myFixture.addFileToProject("dev/simplified/annotations/" + name + ".java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.TYPE)
            public @interface %s {
                AccessLevel access() default AccessLevel.PUBLIC;
                boolean emitGenerated() default true;
            }
            """.formatted(name));
    }

    private void addNoArgsConstructorAnnotation() {
        myFixture.addFileToProject("dev/simplified/annotations/NoArgsConstructor.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.TYPE)
            public @interface NoArgsConstructor {
                AccessLevel access() default AccessLevel.PUBLIC;
                boolean force() default false;
                boolean emitGenerated() default true;
            }
            """);
    }

    private void addCollectorAnnotation() {
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
            }
            """);
    }

    private void addGetterAnnotation() {
        myFixture.addFileToProject("dev/simplified/annotations/Getter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Getter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
            }
            """);
    }

    private void assertNoErrors() {
        List<String> errors = errors();
        assertTrue("expected no editor errors, got: " + errors, errors.isEmpty());
    }

    private List<String> errors() {
        List<String> errors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.ERROR) errors.add(info.getDescription());
        }
        return errors;
    }

    /**
     * Asserts every claim the shared case makes about what both halves produce.
     *
     * @param fixture the case, read off the root the apt suite reads too
     */
    private void assertParity(BuilderParityFixture fixture) {
        PsiFile file = myFixture.addFileToProject(fixture.path(), fixture.source());
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        assertEquals("the case's type line names the file's first type",
            fixture.simpleName(), target.getName());

        List<String> mismatches =
            new ArrayList<>(fixture.mismatches(BuilderParityFixture.TARGET, methodNamesOf(target)));
        if (!fixture.forOwner(BuilderParityFixture.BUILDER).isEmpty()) {
            mismatches.addAll(fixture.mismatches(BuilderParityFixture.BUILDER,
                methodNamesOf(nestedOf(target, fixture.builderName()))));
        }
        assertTrue(String.join("\n", mismatches), mismatches.isEmpty());
    }

    /** Reads the augment-aware member list, which is what a call site resolves against. */
    private static List<String> methodNamesOf(PsiClass owner) {
        List<String> out = new ArrayList<>();
        for (PsiMethod method : owner.getMethods()) out.add(method.getName());
        return out;
    }

    private static PsiClass nestedOf(PsiClass target, String name) {
        for (PsiClass nested : target.getInnerClasses()) {
            if (name.equals(nested.getName())) return nested;
        }
        throw new AssertionError("expected a declared " + name + " on " + target.getName());
    }

    private List<String> declaredBuilderMethodsOf(String className, String source) {
        PsiFile file = myFixture.configureByText(className + ".java", source);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        for (PsiClass nested : target.getInnerClasses()) {
            if (!"Builder".equals(nested.getName())) continue;
            List<String> out = new ArrayList<>();
            for (PsiMethod method : nested.getMethods()) out.add(method.getName());
            return out;
        }
        throw new AssertionError("expected a declared Builder on " + className);
    }

    private static int count(List<String> names, String name) {
        int found = 0;
        for (String candidate : names) {
            if (candidate.equals(name)) found++;
        }
        return found;
    }

}
