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
     * a class nested in the interface body, so nothing is appended to it - and
     * that holds even where the annotation still writes the attribute that once
     * asked for the merge, which is the source an upgrading author has open.
     * The editor used to read that attribute and merge into the interface's
     * nested class, listing setters and a build method javac never emits there;
     * with the merge running on every declared builder, an editor that did not
     * ask whether the owner is an interface would do the same with or without
     * it.
     */
    public void testAnInterfacesNestedBuilder_isNotMergedIntoUnderAStaleAttribute() {
        PsiFile file = myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(mergeDeclaredBuilder = true)
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
    // Helpers
    // ------------------------------------------------------------------

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
