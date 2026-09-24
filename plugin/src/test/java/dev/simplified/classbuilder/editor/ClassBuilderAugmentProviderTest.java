package dev.simplified.classbuilder.editor;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Exercises {@link ClassBuilderAugmentProvider}: a {@code @ClassBuilder}
 * class should surface synthetic {@code builder()}, {@code from(T)}, and
 * {@code mutate()} methods to the PSI layer, plus a nested {@code Builder}
 * class whose setter matrix mirrors the APT mutator output.
 */
public class ClassBuilderAugmentProviderTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        // A real JDK so InheritanceUtil resolves custom-collection hierarchies
        // (Bag extends java.util.List extends Collection) in the supertype walk;
        // the default mock JDK stubs java.util without those supertype links.
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

    /** Adds the annotation stubs the tests reference onto the fixture's source path. */
    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder {
                BuilderNames builder() default @BuilderNames;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                SetterNames setters() default @SetterNames;
                String factoryMethod() default "";
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/SetterNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({})
            public @interface SetterNames {
                String INHERIT = "";
                String NONE = "-";
                String set() default INHERIT;
                String flag() default INHERIT;
                String add() default INHERIT;
                String put() default INHERIT;
                String compute() default INHERIT;
                String clear() default INHERIT;
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
        myFixture.addFileToProject("dev/simplified/annotations/Negate.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Negate { String value(); }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Formattable.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Formattable { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
            }
            """);
    }

    // ------------------------------------------------------------------
    // Bootstrap-method tests (unchanged from v1)
    // ------------------------------------------------------------------

    public void testBootstrapMethodsSynthesized() {
        PsiFile file = myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget {
                String name;
                public Widget(String name) { this.name = name; }
            }
            """);
        PsiClass widget = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];

        PsiMethod[] builders = widget.findMethodsByName("builder", false);
        PsiMethod[] froms = widget.findMethodsByName("from", false);
        PsiMethod[] mutates = widget.findMethodsByName("mutate", false);

        assertEquals("builder() must be synthesised", 1, builders.length);
        assertEquals("from() must be synthesised", 1, froms.length);
        assertEquals("mutate() must be synthesised", 1, mutates.length);

        assertTrue("builder() is static", builders[0].hasModifierProperty(PsiModifier.STATIC));
        assertTrue("from() is static", froms[0].hasModifierProperty(PsiModifier.STATIC));
        assertFalse("mutate() is instance", mutates[0].hasModifierProperty(PsiModifier.STATIC));

        assertTrue("builder() carries generated marker",
            GeneratedMemberMarker.isGenerated(builders[0]));
        assertTrue("from() carries generated marker",
            GeneratedMemberMarker.isGenerated(froms[0]));
        assertTrue("mutate() carries generated marker",
            GeneratedMemberMarker.isGenerated(mutates[0]));
    }

    // ------------------------------------------------------------------
    // All-args constructor synthesis
    // ------------------------------------------------------------------

    public void testAllArgsConstructorSynthesized() {
        PsiFile file = myFixture.configureByText("Gadget.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Gadget {
                String name;
                int count;
            }
            """);
        PsiClass gadget = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];

        PsiMethod[] ctors = gadget.getConstructors();
        assertEquals("all-args constructor must be synthesised", 1, ctors.length);
        assertEquals("parameter count must match the field list", 2, ctors[0].getParameterList().getParametersCount());
        assertTrue("constructor carries generated marker", GeneratedMemberMarker.isGenerated(ctors[0]));
        assertFalse("constructor must be package-private by default",
            ctors[0].hasModifierProperty(PsiModifier.PUBLIC));
        assertFalse("constructor must be package-private by default",
            ctors[0].hasModifierProperty(PsiModifier.PRIVATE));
    }

    public void testConstructorAccess_honoured() {
        PsiFile file = myFixture.configureByText("Sealed.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(constructorAccess = AccessLevel.PRIVATE)
            public class Sealed {
                String name;
            }
            """);
        PsiClass sealed = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiMethod[] ctors = sealed.getConstructors();
        assertEquals(1, ctors.length);
        assertTrue("constructorAccess must drive the modifier",
            ctors[0].hasModifierProperty(PsiModifier.PRIVATE));
    }

    public void testExplicitConstructor_noSynthesis() {
        PsiFile file = myFixture.configureByText("Manual.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Manual {
                String name;
                int count;
                public Manual(String name, int count) { this.name = name; this.count = count; }
            }
            """);
        PsiClass manual = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiMethod[] ctors = manual.getConstructors();
        assertEquals("author's constructor must be the only one", 1, ctors.length);
        assertFalse("author's constructor must not carry the generated marker",
            GeneratedMemberMarker.isGenerated(ctors[0]));
    }

    public void testFactoryMethod_noConstructorSynthesis() {
        PsiFile file = myFixture.configureByText("Factoried.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(factoryMethod = "of")
            public class Factoried {
                String name;
                public static Factoried of(String name) { return null; }
            }
            """);
        PsiClass factoried = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertEquals("a set factoryMethod suppresses synthesis", 0, factoried.getConstructors().length);
    }

    /**
     * An abstract target takes the chain's copy constructor and never the
     * all-args form. Asserted on which constructor is there rather than on there
     * being none: the copy constructor is emitted by the processor above the
     * gate that withholds the entry points, so an editor contributing nothing at
     * all left an author's own {@code super(builder)} red over source that
     * builds.
     */
    public void testAbstractClass_takesTheCopyConstructorAndNotTheAllArgsOne() {
        PsiFile file = myFixture.configureByText("Base.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Base {
                String name;
            }
            """);
        PsiClass base = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiMethod[] constructors = base.getConstructors();
        assertEquals("exactly one, and it is the builder-taking one",
            1, constructors.length);
        assertEquals(1, constructors[0].getParameterList().getParametersCount());
        assertEquals("the wildcard form, so any subclass builder is accepted",
            "Builder<?, ?>",
            constructors[0].getParameterList().getParameters()[0].getType().getPresentableText());
    }

    public void testRecord_noAllArgsConstructor() {
        PsiFile file = myFixture.configureByText("Coord.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public record Coord(String name, int count) {}
            """);
        PsiClass coord = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        for (PsiMethod ctor : coord.getConstructors()) {
            assertFalse("record must keep only its canonical constructor",
                GeneratedMemberMarker.isGenerated(ctor));
        }
    }

    /**
     * The user-facing payoff: a same-package {@code new Target(...)} must resolve
     * in the editor before the first {@code javac} round rather than showing as
     * an unresolved constructor.
     */
    public void testHighlighting_samePackageNewResolves() {
        myFixture.configureByText("Consumer.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            class Target {
                String name;
                int count;
            }
            public class Consumer {
                static Target make() { return new Target("a", 1); }
            }
            """);
        myFixture.checkHighlighting(false, false, false);
    }

    public void testNonAnnotatedClass_noAugment() {
        PsiFile file = myFixture.configureByText("Plain.java",
            """
            public class Plain {
                String name;
            }
            """);
        PsiClass plain = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertEquals(0, plain.findMethodsByName("builder", false).length);
        assertEquals(0, plain.findMethodsByName("from", false).length);
        assertEquals(0, plain.findMethodsByName("mutate", false).length);
    }

    public void testAbstractClass_noBootstraps() {
        PsiFile file = myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                String color;
            }
            """);
        PsiClass shape = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertEquals(0, shape.findMethodsByName("builder", false).length);
        assertEquals(0, shape.findMethodsByName("from", false).length);
        assertEquals(0, shape.findMethodsByName("mutate", false).length);
    }

    /**
     * Mirrors what users actually see when typing {@code Target.builder().<caret>}:
     * the IDE's completion engine, not just {@code findMethodsByName}. If this
     * passes but the live IDE shows nothing, the bug is in plugin.xml wiring
     * or daemon state, not in the augment provider.
     */
    public void testCompletion_setterMethodsVisibleAfterBuilderCall() {
        myFixture.configureByText("Doc.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Doc {
                String title;
                int pages;

                public static void main(String[] args) {
                    Doc.builder().<caret>;
                }
            }
            """);
        myFixture.completeBasic();
        java.util.List<String> lookup = myFixture.getLookupElementStrings();
        assertNotNull("completion popup must populate", lookup);
        assertTrue("withTitle should show up: actual=" + lookup, lookup.contains("title"));
        assertTrue("withPages should show up: actual=" + lookup, lookup.contains("pages"));
        assertTrue("build should show up: actual=" + lookup, lookup.contains("build"));
    }

    /**
     * Direct check of the {@code GeneratedBuilderClass.getMethods()} override
     * used by the Lombok-style lazy materialisation pattern. If this returns
     * empty, the augment-provider re-entry chain is broken.
     */
    public void testGetMethods_routesThroughAugmentProvider() {
        PsiFile file = myFixture.configureByText("Page.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Page { String title; }
            """);
        PsiClass page = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiClass synthBuilder = page.getInnerClasses()[0];
        PsiMethod[] methods = synthBuilder.getMethods();
        assertTrue("getMethods() must return setters via augment provider, got " + methods.length,
            methods.length >= 2);  // withTitle + build, at minimum
    }

    /**
     * Same-file repro mirroring what the user types: in-class call to
     * builder().withX(...).build(). User reports "cannot access T.Builder.X"
     * for setters but build() works. Highlighter must not produce any
     * "Cannot access" errors on the synth Builder or its members.
     *
     * <p>Uses primitive int instead of String so the mock JDK's missing
     * java.lang.String doesn't pollute the assertion with unrelated errors.
     */
    public void testSetterInvocation_passesHighlighter_sameFile() {
        myFixture.configureByText("Doc.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Doc {
                int rank;
                public static Doc make() {
                    return Doc.builder().rank(7).build();
                }
            }
            """);
        java.util.List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights =
            myFixture.doHighlighting();
        for (com.intellij.codeInsight.daemon.impl.HighlightInfo info : highlights) {
            String desc = info.getDescription();
            if (desc != null && desc.contains("Cannot access")) {
                fail("synth Builder/setter should be accessible, got: " + desc);
            }
        }
    }

    /**
     * Highlighting a target whose <b>field</b> carries an annotation, which is
     * a different path from one whose fields are bare - the platform's folding
     * pass resolves that annotation, and the resolve walks the class's nested
     * types, which is augment-aware and enters this provider mid-resolve.
     *
     * <p>Coverage rather than a regression pin: this shape alone does not go
     * deep enough to trip the platform's limit. What does is the same shape with
     * a second provider in the chain, which
     * {@code LazyFieldInspectionTest.testClassBuilderSuppliesTheValue} holds.
     */
    public void testHighlighting_targetWithAnAnnotatedFieldSynthesisesNormally() {
        myFixture.configureByText("Doc.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Negate;
            @ClassBuilder
            public class Doc {
                @Negate("hidden") boolean visible;
                int rank;
                public static Doc make() {
                    return Doc.builder().isHidden().rank(7).build();
                }
            }
            """);
        java.util.List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights =
            myFixture.doHighlighting();
        for (com.intellij.codeInsight.daemon.impl.HighlightInfo info : highlights) {
            String desc = info.getDescription();
            if (desc != null && (desc.contains("Cannot access") || desc.contains("Cannot resolve"))) {
                fail("an annotated field must not cost the target its builder, got: " + desc);
            }
        }
    }

    /**
     * Cross-package variant - exercises {@link ClassBuilderElementFinder}'s
     * bridge from {@link JavaPsiFacade#findClass} to the
     * augmented inner class. Without that finder the highlighter calls
     * {@code findClass("a.Doc.Builder", scope)} which returns null (augment
     * providers don't participate in the global class index), and reports
     * "Cannot access a.Doc.Builder" on every method-chain entry.
     */
    public void testSetterInvocation_passesHighlighter_crossPackage() {
        myFixture.addFileToProject("a/Doc.java",
            """
            package a;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Doc {
                int rank;
            }
            """);
        myFixture.configureByText("Caller.java",
            """
            import a.Doc;
            public class Caller {
                public static Doc make() {
                    return Doc.builder().rank(7).build();
                }
            }
            """);
        java.util.List<com.intellij.codeInsight.daemon.impl.HighlightInfo> highlights =
            myFixture.doHighlighting();
        for (com.intellij.codeInsight.daemon.impl.HighlightInfo info : highlights) {
            String desc = info.getDescription();
            if (desc != null && desc.contains("Cannot access")) {
                fail("cross-package synth Builder must be accessible, got: " + desc);
            }
        }
    }

    /** The synth Builder class itself must report PUBLIC + STATIC. */
    public void testBuilderClass_isPublicStatic() {
        myFixture.configureByText("Doc.java",
            """
            package a;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder public class Doc { String title; }
            """);
        PsiClass target = ((com.intellij.psi.PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiClass builder = target.getInnerClasses()[0];

        assertTrue("Builder must be PUBLIC",
            builder.hasModifierProperty(PsiModifier.PUBLIC));
        assertTrue("Builder must be STATIC",
            builder.hasModifierProperty(PsiModifier.STATIC));
        assertNotNull("Builder modifier list must exist",
            builder.getModifierList());
        assertTrue("modifier list must directly report PUBLIC",
            builder.getModifierList().hasModifierProperty(PsiModifier.PUBLIC));
    }

    public void testSetters_publicAndAccessibleFromCallSite() {
        myFixture.configureByText("Caller.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Caller {
                String title;
                int rank;
            }
            """);
        com.intellij.psi.PsiClass target =
            ((com.intellij.psi.PsiJavaFile) myFixture.getFile()).getClasses()[0];
        com.intellij.psi.PsiClass builder = target.getInnerClasses()[0];

        for (PsiMethod m : builder.getMethods()) {
            // The constructor is the one member deliberately not public:
            // builder() is the entry point, and testBuilderConstructor_* pins
            // that. Everything a caller reaches through the builder is.
            if (m.isConstructor()) continue;
            assertTrue(m.getName() + " must be PUBLIC",
                m.hasModifierProperty(PsiModifier.PUBLIC));
            com.intellij.psi.PsiClass containing = m.getContainingClass();
            assertNotNull(m.getName() + " must have a containing class", containing);
            assertSame(m.getName() + " must report synth Builder as containing class",
                builder, containing);
        }
    }

    public void testNestedBuilderClassSynthesized() {
        PsiFile file = myFixture.configureByText("Card.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Card {
                String label;
                int rank;
            }
            """);
        PsiClass card = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiClass[] inner = card.getInnerClasses();
        assertEquals("Builder must be synthesised", 1, inner.length);
        PsiClass builder = inner[0];
        assertEquals("Builder", builder.getName());
        assertTrue("synthesised Builder is static",
            builder.hasModifierProperty(PsiModifier.STATIC));
        assertTrue("synthesised Builder is public",
            builder.hasModifierProperty(PsiModifier.PUBLIC));
        assertTrue("synthesised Builder carries generated marker",
            GeneratedMemberMarker.isGenerated(builder));

        assertEquals(1, builder.findMethodsByName("label", false).length);
        assertEquals(1, builder.findMethodsByName("rank", false).length);
        assertEquals(1, builder.findMethodsByName("build", false).length);
    }

    public void testHandWrittenBuilder_skipsSynthesis() {
        PsiFile file = myFixture.configureByText("Manual.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Manual {
                String x;
                public static class Builder {
                    public Manual build() { return null; }
                }
            }
            """);
        PsiClass manual = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiClass[] inner = manual.getInnerClasses();
        assertEquals("hand-written Builder must win", 1, inner.length);
        assertFalse("hand-written Builder must not be marked synthesised",
            GeneratedMemberMarker.isGenerated(inner[0]));
    }

    public void testForeignTypedFrom_doesNotSuppressTheCopyFactory() {
        // The editor has always shown from(Doc) here; javac used to skip it,
        // because its collision check matched on arity alone and a from(String)
        // parser is also arity one. This pins the side the processor moved to.
        PsiFile file = myFixture.configureByText("Doc.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Doc {
                String body;
                public static Doc from(String raw) { return null; }
            }
            """);
        PsiClass doc = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];

        PsiMethod[] from = doc.findMethodsByName("from", false);
        assertEquals("the author's parser and the copy factory coexist", 2, from.length);

        long copyFactories = java.util.Arrays.stream(from)
            .filter(GeneratedMemberMarker::isGenerated)
            .count();
        assertEquals("exactly one of them is synthesised", 1, copyFactories);

        PsiMethod copy = java.util.Arrays.stream(from)
            .filter(GeneratedMemberMarker::isGenerated)
            .findFirst()
            .orElseThrow();
        assertEquals("Doc", copy.getParameterList().getParameters()[0].getType().getPresentableText());
    }

    public void testBuilderConstructor_isPackagePrivateByDefault() {
        // builder() is the entry point. An implicit constructor would take the
        // builder class's own access and publish `new Target.Builder()` beside
        // it - and the processor no longer does, so the editor must not either.
        PsiClass builder = builderFor("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget {
                String label;
            }
            """);
        PsiMethod[] ctors = builder.getConstructors();
        assertEquals("the constructor is declared, not implicit", 1, ctors.length);
        assertFalse(ctors[0].hasModifierProperty(PsiModifier.PUBLIC));
        assertFalse(ctors[0].hasModifierProperty(PsiModifier.PROTECTED));
        assertFalse(ctors[0].hasModifierProperty(PsiModifier.PRIVATE));
        assertTrue("the builder class itself stays reachable as a type",
            builder.hasModifierProperty(PsiModifier.PUBLIC));
    }

    public void testBuilderConstructor_widenedOnRequest() {
        PsiClass builder = builderFor("Widget",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PUBLIC)
            public class Widget {
                String label;
            }
            """);
        PsiMethod[] ctors = builder.getConstructors();
        assertEquals(1, ctors.length);
        assertTrue(ctors[0].hasModifierProperty(PsiModifier.PUBLIC));
    }

    /**
     * {@code NONE} is reported at the annotation, and the processor generates
     * as under the default beside the error - a package-private constructor and
     * the entry points - which is what is contributed here.
     */
    public void testBuilderConstructorAccessNone_contributesWhatTheDefaultDoes() {
        PsiFile file = myFixture.configureByText("Closed.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.NONE)
            public class Closed {
                String label;
            }
            """);
        PsiClass target = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertEquals("the entry point is there", 1, target.findMethodsByName("builder", false).length);
        PsiMethod[] ctors = target.getInnerClasses()[0].getConstructors();
        assertEquals(1, ctors.length);
        assertFalse(ctors[0].hasModifierProperty(PsiModifier.PUBLIC));
        assertFalse(ctors[0].hasModifierProperty(PsiModifier.PROTECTED));
        assertFalse(ctors[0].hasModifierProperty(PsiModifier.PRIVATE));
    }

    public void testHandWrittenBootstrap_isNotDuplicated() {
        PsiFile file = myFixture.configureByText("Manual2.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Manual2 {
                String x;
                public static Manual2.Builder builder() { return null; }
            }
            """);
        PsiClass manual = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertEquals("javac skips its bootstrap here, so the editor must too",
            1, manual.findMethodsByName("builder", false).length);
    }

    public void testCustomBootstrapNames_respected() {
        PsiFile file = myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(builder = "make", from = "of", toBuilder = "edit"))
            public class Named {
                String tag;
            }
            """);
        PsiClass named = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertEquals(1, named.findMethodsByName("make", false).length);
        assertEquals(1, named.findMethodsByName("of", false).length);
        assertEquals(1, named.findMethodsByName("edit", false).length);
        assertEquals(0, named.findMethodsByName("builder", false).length);
    }

    // ------------------------------------------------------------------
    // Setter shape parity tests
    // ------------------------------------------------------------------

    /** Boolean fields get the zero-arg + typed pair. */
    public void testBooleanPair() {
        PsiClass builder = builderFor("Toggle",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Toggle { boolean active; }
            """);
        // The typed setter is the ordinary `set` role, so it takes the bare
        // field name; only the zero-arg convenience keeps the `is` prefix.
        PsiMethod[] zeroArg = builder.findMethodsByName("isActive", false);
        assertEquals("zero-arg flag setter", 1, zeroArg.length);
        assertEquals("isActive() takes no argument", 0, zeroArg[0].getParameterList().getParametersCount());

        PsiMethod[] typed = builder.findMethodsByName("active", false);
        assertEquals("typed setter", 1, typed.length);
        assertEquals("active(boolean) takes one argument", 1, typed[0].getParameterList().getParametersCount());
    }

    /** {@code @Negate("name")} on a boolean field produces a second zero-arg + typed pair. */
    public void testNegateInversePair() {
        PsiClass builder = builderFor("Door",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Negate;
            @ClassBuilder
            public class Door {
                @Negate("closed") boolean open;
            }
            """);
        assertEquals("primary flag", 1, builder.findMethodsByName("isOpen", false).length);
        assertEquals("primary typed", 1, builder.findMethodsByName("open", false).length);
        assertEquals("inverse flag", 1, builder.findMethodsByName("isClosed", false).length);
        assertEquals("inverse typed", 1, builder.findMethodsByName("closed", false).length);
    }

    /** {@code style = LOMBOK} renames the builder, the seed method, and the collector shapes. */
    public void testLombokStyleNaming() {
        PsiClass builder = builderFor("Card",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import dev.simplified.annotations.NamingStyle;
            import java.util.List;
            @ClassBuilder(style = NamingStyle.LOMBOK)
            public class Card {
                boolean shiny;
                @Collector(singular = true, clearable = true) List<String> tags;
            }
            """, "CardBuilder");
        assertEquals("bare-name boolean setter", 1, builder.findMethodsByName("shiny", false).length);
        assertEquals("no zero-arg boolean form", 0, builder.findMethodsByName("isShiny", false).length);
        assertEquals("bare singular add", 1, builder.findMethodsByName("tag", false).length);
        assertEquals("addX is the SIMPLIFIED name", 0, builder.findMethodsByName("addTag", false).length);
        assertEquals("clear is unchanged", 1, builder.findMethodsByName("clearTags", false).length);
    }

    /** A per-role override renames the collector's add and clear independently. */
    public void testSetterNamesRoleOverride() {
        PsiClass builder = builderFor("Basket",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import dev.simplified.annotations.SetterNames;
            import java.util.List;
            @ClassBuilder(setters = @SetterNames(add = "append{}", clear = "reset{}"))
            public class Basket {
                @Collector(singular = true, clearable = true) List<String> items;
            }
            """);
        assertEquals(1, builder.findMethodsByName("appendItem", false).length);
        assertEquals(1, builder.findMethodsByName("resetItems", false).length);
        assertEquals(0, builder.findMethodsByName("addItem", false).length);
        assertEquals(0, builder.findMethodsByName("clearItems", false).length);
    }

    /** {@code Optional<T>} fields get nullable-raw + wrapped setters. */
    public void testOptionalDualSetter() {
        PsiClass builder = builderFor("Box",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.Optional;
            @ClassBuilder
            public class Box { Optional<String> label; }
            """);
        PsiMethod[] withLabel = builder.findMethodsByName("label", false);
        assertEquals("nullable-raw + wrapped", 2, withLabel.length);
    }

    /**
     * Pins the one call shape the dual setter cannot serve, and - just as
     * importantly - the four it can.
     *
     * <p>A bare {@code label(null)} is ambiguous by the language rule, both
     * {@code String} and {@code Optional<String>} accepting null with neither
     * more specific (JLS 15.12.2.5). That is javac's verdict, and this test
     * asserts the editor reaches the same one: the augment provider has to
     * model both overloads faithfully for the resolver to see the ambiguity at
     * all, so a regression that dropped or mistyped an overload would show up
     * here as the error disappearing.
     *
     * <p>The ambiguity is a feature rather than a defect. On an
     * {@code Optional} field, {@code label(null)} is ambiguous in intent too -
     * absent, or present-and-null? - and telling those apart is the reason to
     * declare the field {@code Optional} in the first place.
     */
    public void testOptionalDualSetter_onlyBareNullIsAmbiguous() {
        myFixture.configureByText("Consumer.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.Optional;
            @ClassBuilder
            class Box { Optional<String> label; }
            public class Consumer {
                static void go(String s) {
                    Box.builder().label(s);                 // nullable raw, by static type
                    Box.builder().label(Optional.empty());  // explicitly absent
                    Box.builder().label("x");               // literal value
                    Box.builder().label((String) null);     // cast disambiguates
                    Box.builder().label(null);              // the one ambiguous form
                }
            }
            """);
        List<String> errors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity().myVal >= HighlightSeverity.ERROR.myVal) {
                errors.add(info.getText() + " :: " + info.getDescription());
            }
        }
        assertEquals("only the bare null call may fail to resolve, got " + errors, 1, errors.size());
        // The highlighted range is the argument list, so the text is "(null)".
        assertTrue("the ambiguity must be reported on the null argument, got " + errors.get(0),
            errors.get(0).startsWith("(null) ::"));
        assertTrue("the editor must name both overloads, got " + errors.get(0),
            errors.get(0).contains("Ambiguous method call")
                && errors.get(0).contains("label(String)")
                && errors.get(0).contains("label(Optional<String>)"));
    }

    /** The intention rewrites the one ambiguous form into the one worth writing. */
    public void testOptionalNullIntention_rewritesToOptionalEmpty() {
        myFixture.configureByText("Consumer.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.Optional;
            @ClassBuilder
            class Box { Optional<String> label; }
            public class Consumer {
                static void go() {
                    Box.builder().label(nu<caret>ll);
                }
            }
            """);
        IntentionAction fix = myFixture.findSingleIntention("Replace 'null' with 'Optional.empty()'");
        myFixture.launchAction(fix);
        myFixture.checkResult(
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.Optional;
            @ClassBuilder
            class Box { Optional<String> label; }
            public class Consumer {
                static void go() {
                    Box.builder().label(Optional.empty());
                }
            }
            """);
    }

    /**
     * The intention is keyed on the candidate shape, so it must stay off an
     * ambiguity that has nothing to do with an Optional dual setter.
     */
    public void testOptionalNullIntention_absentOnUnrelatedAmbiguity() {
        myFixture.configureByText("Other.java",
            """
            public class Other {
                static void pick(String s) {}
                static void pick(Integer i) {}
                static void go() { pick(nu<caret>ll); }
            }
            """);
        assertEmpty(myFixture.filterAvailableIntentions("Replace 'null' with 'Optional.empty()'"));
    }

    /** A resolvable call is not the intention's business either. */
    public void testOptionalNullIntention_absentWhenTheCallResolves() {
        myFixture.configureByText("Consumer.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.Optional;
            @ClassBuilder
            class Box { Optional<String> label; }
            public class Consumer {
                static void go(String s) { Box.builder().label(<caret>s); }
            }
            """);
        assertEmpty(myFixture.filterAvailableIntentions("Replace 'null' with 'Optional.empty()'"));
    }

    /** {@code Optional<String>} with {@code @Formattable} gets a third overload. */
    public void testOptionalFormattableOverload() {
        PsiClass builder = builderFor("Card",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Formattable;
            import java.util.Optional;
            @ClassBuilder
            public class Card {
                @Formattable Optional<String> description;
            }
            """);
        // raw(String), wrapped(Optional<String>), formattable(String, Object...)
        assertEquals(3, builder.findMethodsByName("description", false).length);
    }

    /** String {@code @Formattable} adds a {@code (String, Object...)} overload. */
    public void testStringFormattableOverload() {
        PsiClass builder = builderFor("Greeting",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Formattable;
            @ClassBuilder
            public class Greeting {
                @Formattable String message;
            }
            """);
        PsiMethod[] withMessage = builder.findMethodsByName("message", false);
        assertEquals("plain + formattable", 2, withMessage.length);
        boolean sawVarargs = false;
        for (PsiMethod m : withMessage) {
            if (m.getParameterList().getParametersCount() == 2
                && m.getParameterList().getParameter(1).isVarArgs()) {
                sawVarargs = true;
            }
        }
        assertTrue("varargs overload must be present", sawVarargs);
    }

    /** Array fields get a single varargs setter. */
    public void testArrayVarargsSetter() {
        PsiClass builder = builderFor("Tags",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Tags { String[] labels; }
            """);
        PsiMethod[] labels = builder.findMethodsByName("labels", false);
        assertEquals(1, labels.length);
        assertTrue("single-param varargs",
            labels[0].getParameterList().getParameter(0).isVarArgs());
    }

    /**
     * Bare {@code @Collector} on a list: only varargs + iterable (no singular
     * add / clear since those are opt-in via attributes).
     */
    public void testCollectorList_bulkOnlyByDefault() {
        PsiClass builder = builderFor("Cart",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            @ClassBuilder
            public class Cart {
                @Collector List<String> items;
            }
            """);
        PsiMethod[] withItems = builder.findMethodsByName("items", false);
        assertEquals("varargs-replace + iterable-replace", 2, withItems.length);
        assertEquals("no singular add without @Collector(singular=true)",
            0, builder.findMethodsByName("addItem", false).length);
        assertEquals("no clear without @Collector(clearable=true)",
            0, builder.findMethodsByName("clearItems", false).length);
    }

    /**
     * {@code @Collector(singular, clearable)} on a list adds the single-
     * element setter and clear method to the bulk overloads.
     */
    public void testCollectorList_singularAndClearableOptIn() {
        PsiClass builder = builderFor("Cart",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            @ClassBuilder
            public class Cart {
                @Collector(singular = true, clearable = true) List<String> items;
            }
            """);
        assertEquals("varargs + iterable", 2, builder.findMethodsByName("items", false).length);
        assertEquals("add single", 1, builder.findMethodsByName("addItem", false).length);
        assertEquals("clear", 1, builder.findMethodsByName("clearItems", false).length);
    }

    /**
     * {@code @Collector} on a map with all opt-ins: replace + put + clear +
     * putIfAbsent.
     */
    public void testCollectorMap_fullOptIn() {
        PsiClass builder = builderFor("Bag",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.Map;
            @ClassBuilder
            public class Bag {
                @Collector(singular = true, clearable = true, compute = true) Map<String, Integer> counts;
            }
            """);
        assertEquals("replace", 1, builder.findMethodsByName("counts", false).length);
        assertEquals("put", 1, builder.findMethodsByName("putCount", false).length);
        assertEquals("putIfAbsent", 1, builder.findMethodsByName("putCountIfAbsent", false).length);
        assertEquals("clear", 1, builder.findMethodsByName("clearCounts", false).length);
    }

    /**
     * {@code @Collector(singularMethodName = "flavor")} overrides the derived
     * singular name on the single-element setter.
     */
    public void testCollectorCustomSingularName() {
        PsiClass builder = builderFor("Shop",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            @ClassBuilder
            public class Shop {
                @Collector(singular = true, singularMethodName = "flavor") List<String> flavors;
            }
            """);
        // Default empty prefix + explicit singular "flavor" -> addFlavor.
        assertEquals(1, builder.findMethodsByName("addFlavor", false).length);
        // The varargs-replace on the plural field name is still withFlavors;
        // neither that nor withFlavor collapses to a mistakenly doubled name.
        assertEquals(0, builder.findMethodsByName("addFlavorss", false).length);
    }

    /**
     * A project-specific collection type (an interface built via a factory,
     * the {@code ConcurrentList} shape) recognised by the supertype walk gets
     * the same {@code @Collector} bulk API in autocomplete as a java.util list.
     */
    public void testCustomCollectionCollector_surfacesBulkApi() {
        addCustomBagSources();
        PsiClass builder = builderFor("Shelf",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import demo.Bag;
            import demo.Bags;
            @ClassBuilder
            public class Shelf {
                @Collector(singular = true, clearable = true) Bag<String> tags = Bags.newBag();
            }
            """);
        assertEquals("varargs + iterable replace on the custom container", 2,
            builder.findMethodsByName("tags", false).length);
        assertEquals("singular add", 1, builder.findMethodsByName("addTag", false).length);
        assertEquals("clear", 1, builder.findMethodsByName("clearTags", false).length);
    }

    /** Custom map recognised via the supertype walk gets replace + put + clear. */
    public void testCustomMapCollector_surfacesBulkApi() {
        addCustomBagSources();
        PsiClass builder = builderFor("Book",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import demo.Ledger;
            import demo.Ledgers;
            @ClassBuilder
            public class Book {
                @Collector(singular = true, clearable = true) Ledger<String, Integer> entries = Ledgers.newLedger();
            }
            """);
        assertEquals("replace", 1, builder.findMethodsByName("entries", false).length);
        assertEquals("put", 1, builder.findMethodsByName("putEntry", false).length);
        assertEquals("clear", 1, builder.findMethodsByName("clearEntries", false).length);
    }

    /**
     * A custom-container {@code @Collector} field with no initializer can't be
     * built by the APT (plain replace setter + NOTE), so the augment provider
     * mirrors that and does not advertise the bulk API.
     */
    public void testCustomCollectionCollector_noInitializer_plainSetterOnly() {
        addCustomBagSources();
        PsiClass builder = builderFor("Shelf",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import demo.Bag;
            @ClassBuilder
            public class Shelf {
                @Collector(singular = true, clearable = true) Bag<String> tags;
            }
            """);
        assertEquals("single plain replace setter", 1, builder.findMethodsByName("tags", false).length);
        assertEquals("no singular add without an initializer",
            0, builder.findMethodsByName("addTag", false).length);
        assertEquals("no clear without an initializer",
            0, builder.findMethodsByName("clearTags", false).length);
    }

    // ------------------------------------------------------------------
    // Interface targets: the entry points typed to the sibling builder
    // ------------------------------------------------------------------

    /**
     * An interface target is entered through {@code builder()}, {@code from(T)}
     * and {@code mutate()} on the interface itself, each returning the sibling
     * builder the processor writes beside it. The editor used to withhold all
     * three, reading every interface as abstract, so the calls javac compiles
     * and runs were unresolved.
     */
    public void testAnInterfaceTarget_offersItsEntryPointsTypedToTheSibling() {
        addShapeSources("@ClassBuilder", "from");
        myFixture.configureByText("UseShape.java",
            """
            import demo.Shape;
            public class UseShape {
                static String go() {
                    Shape s = Shape.builder().name("tri").build();
                    return Shape.from(s).build().name() + s.mutate().name("q").build().name();
                }
            }
            """);
        List<String> errors = errors();
        assertTrue("javac compiles and runs these calls; editor errors: " + errors, errors.isEmpty());

        PsiClass shape = myFixture.findClass("demo.Shape");
        PsiMethod builder = single(shape, "builder");
        PsiMethod from = single(shape, "from");
        PsiMethod mutate = single(shape, "mutate");
        assertTrue(builder.hasModifierProperty(PsiModifier.STATIC));
        assertTrue(builder.hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(0, builder.getParameterList().getParametersCount());
        assertTrue(from.hasModifierProperty(PsiModifier.STATIC));
        assertTrue(from.hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(1, from.getParameterList().getParametersCount());
        assertTrue(mutate.hasModifierProperty(PsiModifier.DEFAULT));
        assertTrue(mutate.hasModifierProperty(PsiModifier.PUBLIC));
        assertFalse(mutate.hasModifierProperty(PsiModifier.STATIC));
        assertEquals("demo.ShapeBuilder", builder.getReturnType().getCanonicalText());
        assertEquals("demo.ShapeBuilder", from.getReturnType().getCanonicalText());
        assertEquals("demo.ShapeBuilder", mutate.getReturnType().getCanonicalText());
        assertTrue(GeneratedMemberMarker.isGenerated(builder));
    }

    /**
     * A generic interface's static entry points re-declare its type parameter,
     * so a witness binds the sibling builder's argument, and {@code mutate()}
     * returns the builder over the interface's own.
     */
    public void testAGenericInterfaceTarget_bindsTheSiblingsTypeArgument() {
        myFixture.addFileToProject("demo/Repo.java",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public interface Repo<T> {
                T head();
            }
            """);
        myFixture.addFileToProject("demo/RepoBuilder.java",
            """
            package demo;
            public class RepoBuilder<T> {
                private T head;
                public RepoBuilder<T> head(T head) {
                    this.head = head;
                    return this;
                }
                public static <T> RepoBuilder<T> from(Repo<T> instance) {
                    RepoBuilder<T> b = new RepoBuilder<>();
                    b.head = instance.head();
                    return b;
                }
                public Repo<T> build() {
                    return new RepoImpl<>(head);
                }
            }
            """);
        myFixture.addFileToProject("demo/RepoImpl.java",
            """
            package demo;
            final class RepoImpl<T> implements Repo<T> {
                private final T head;
                RepoImpl(T head) { this.head = head; }
                @Override public T head() { return this.head; }
            }
            """);
        myFixture.configureByText("UseRepo.java",
            """
            import demo.Repo;
            public class UseRepo {
                static String go() {
                    Repo<String> r = Repo.<String>builder().head("h").build();
                    String viaFrom = Repo.from(r).build().head();
                    String viaMutate = r.mutate().head("m").build().head();
                    return r.head().length() + viaFrom + viaMutate;
                }
            }
            """);
        List<String> errors = errors();
        assertTrue("javac compiles and runs these calls; editor errors: " + errors, errors.isEmpty());
    }

    /** A {@code @BuilderNames} rename moves each entry point, as the processor names them. */
    public void testAnInterfaceTarget_renamedEntryPointsMove() {
        addShapeSources("@ClassBuilder(builder = @BuilderNames(builder = \"create\", from = \"copyOf\", "
            + "toBuilder = \"edit\"))", "copyOf");
        myFixture.configureByText("UseShape.java",
            """
            import demo.Shape;
            public class UseShape {
                static String go() {
                    Shape s = Shape.create().name("tri").build();
                    return Shape.copyOf(s).build().name() + s.edit().name("q").build().name();
                }
            }
            """);
        List<String> errors = errors();
        assertTrue("javac compiles and runs these calls; editor errors: " + errors, errors.isEmpty());
        PsiClass shape = myFixture.findClass("demo.Shape");
        assertEquals(0, shape.findMethodsByName("builder", false).length);
        assertEquals(0, shape.findMethodsByName("from", false).length);
        assertEquals(0, shape.findMethodsByName("mutate", false).length);
    }

    /** A member named {@code NONE} is not emitted on an interface, and not offered either. */
    public void testAnInterfaceTarget_aMemberNamedNoneIsAbsent() {
        addShapeSources("@ClassBuilder(builder = @BuilderNames(toBuilder = BuilderNames.NONE))", "from");
        PsiClass shape = myFixture.findClass("demo.Shape");
        assertEquals(1, shape.findMethodsByName("builder", false).length);
        assertEquals(1, shape.findMethodsByName("from", false).length);
        assertEquals(0, shape.findMethodsByName("mutate", false).length);
    }

    /**
     * An interface target's builder is the sibling {@code ShapeBuilder}, and
     * javac declares no nested class on the interface, so the editor contributes
     * none and {@code Shape.Builder} is unresolved. The editor used to list a
     * synthesised {@code Builder} among the interface's inner classes, so the
     * reference resolved over source javac rejects.
     */
    public void testAnInterfaceTarget_contributesNoNestedBuilder() {
        addShapeSources("@ClassBuilder", "from");
        PsiClass shape = myFixture.findClass("demo.Shape");
        List<String> inner = new ArrayList<>();
        for (PsiClass nested : shape.getInnerClasses()) inner.add(nested.getName());
        assertEquals(List.of(), inner);

        myFixture.configureByText("UseShape.java",
            """
            import demo.Shape;
            public class UseShape {
                Shape.Builder b;
            }
            """);
        List<String> errors = errors();
        assertEquals("javac: cannot find symbol Shape.Builder; editor: " + errors, 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("Builder"));
    }

    /**
     * {@code from = NONE} on an interface withholds {@code from(T)} and keeps
     * {@code mutate()}, whose body javac seeds inline, so both halves offer the
     * same two entry points.
     */
    public void testAnInterfaceTargetWithFromNone_stillOffersMutate() {
        addShapeSources("@ClassBuilder(builder = @BuilderNames(from = BuilderNames.NONE))", "unused");
        myFixture.configureByText("UseShape.java",
            """
            import demo.Shape;
            public class UseShape {
                static String go() {
                    Shape s = Shape.builder().name("tri").build();
                    return s.mutate().build().name();
                }
            }
            """);
        List<String> errors = errors();
        assertTrue("javac compiles and runs these calls; editor errors: " + errors, errors.isEmpty());
        PsiClass shape = myFixture.findClass("demo.Shape");
        assertEquals(0, shape.findMethodsByName("from", false).length);
        PsiMethod mutate = single(shape, "mutate");
        assertTrue(mutate.hasModifierProperty(PsiModifier.DEFAULT));
        assertEquals("demo.ShapeBuilder", mutate.getReturnType().getCanonicalText());
    }

    // ------------------------------------------------------------------
    // Naming attributes written as constants
    // ------------------------------------------------------------------

    /**
     * {@code from = BuilderNames.NONE} withholds {@code from(T)}, as javac
     * withholds it. The editor used to read only a literal, so it took the
     * constant for an unwritten name and offered the copy factory.
     */
    public void testFromNamedNoneByConstant_offersNoFrom() {
        myFixture.configureByText("Config.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(from = BuilderNames.NONE))
            public class Config {
                private String name;
                static Object go(Config c) { return Config.from(c); }
            }
            """);
        List<String> errors = errors();
        assertEquals("javac: cannot find symbol from(Config); editor: " + errors, 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).contains("from"));
        PsiClass config = myFixture.findClass("Config");
        assertEquals(1, config.findMethodsByName("builder", false).length);
        assertEquals(1, config.findMethodsByName("mutate", false).length);
    }

    /** The constant spelled through its fully qualified class name reads the same. */
    public void testToBuilderNamedNoneByQualifiedConstant_offersNoMutate() {
        PsiFile file = myFixture.configureByText("Config.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @dev.simplified.annotations.BuilderNames(
                toBuilder = dev.simplified.annotations.BuilderNames.NONE))
            public class Config {
                private String name;
            }
            """);
        PsiClass config = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertEquals(0, config.findMethodsByName("mutate", false).length);
        assertEquals(1, config.findMethodsByName("from", false).length);
    }

    /** A {@code NONE} brought in by a single static import is the constant too. */
    public void testFromNamedNoneByStaticImport_offersNoFrom() {
        PsiFile file = myFixture.configureByText("Config.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            import static dev.simplified.annotations.BuilderNames.NONE;
            @ClassBuilder(builder = @BuilderNames(from = NONE))
            public class Config {
                private String name;
            }
            """);
        PsiClass config = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertEquals(0, config.findMethodsByName("from", false).length);
        assertEquals(1, config.findMethodsByName("builder", false).length);
    }

    /**
     * A setter role suppressed through an on-demand static import of
     * {@code SetterNames} drops the member, as {@code flag = NONE} does for the
     * processor.
     */
    public void testFlagNamedNoneByOnDemandStaticImport_dropsTheZeroArgSetter() {
        PsiClass builder = builderFor("Toggle",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            import static dev.simplified.annotations.SetterNames.*;
            @ClassBuilder(setters = @SetterNames(flag = NONE))
            public class Toggle {
                boolean active;
            }
            """);
        assertEquals("no zero-arg flag setter", 0, builder.findMethodsByName("isActive", false).length);
        assertEquals("the typed setter stays", 1, builder.findMethodsByName("active", false).length);
    }

    /**
     * A {@code String} constant the target declares names the member as javac
     * names it, through the platform's constant evaluator.
     */
    public void testBuildNamedByATargetConstant_renamesBuild() {
        myFixture.configureByText("Config.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(build = Config.FINISH))
            public class Config {
                static final String FINISH = "make";
                private String name;
                static Config go() { return Config.builder().name("x").make(); }
            }
            """);
        List<String> errors = errors();
        assertTrue("javac renames build() to make(); editor errors: " + errors, errors.isEmpty());
        PsiClass builder = myFixture.findClass("Config").getInnerClasses()[0];
        assertEquals(0, builder.findMethodsByName("build", false).length);
    }

    /**
     * A constant built from another constant the target declares names the
     * member too, and evaluating it resolves no field's declared type from
     * inside the augment pass - which the platform reports as a recursion,
     * failing this fixture, when a resolve of that very type started the pass.
     */
    public void testBuildNamedByAConcatenatedConstant_renamesBuild() {
        myFixture.configureByText("Config.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(build = Config.FINISH))
            public class Config {
                static final String PREFIX = "ma";
                static final String FINISH = PREFIX + "ke";
                private String name;
                static Config go() { return Config.builder().name("x").make(); }
            }
            """);
        List<String> errors = errors();
        assertTrue("javac renames build() to make(); editor errors: " + errors, errors.isEmpty());
    }

    // ------------------------------------------------------------------
    // access = NONE
    // ------------------------------------------------------------------

    /**
     * {@code access = NONE} is reported at the annotation, and the processor
     * generates as under the default beside the error - public entry points and
     * a public builder class - which is what is contributed here.
     */
    public void testAccessNone_contributesAtTheDefaultAccess() {
        PsiFile file = myFixture.configureByText("Closed.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(access = AccessLevel.NONE)
            public class Closed {
                String label;
            }
            """);
        PsiClass target = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        assertTrue(single(target, "builder").hasModifierProperty(PsiModifier.PUBLIC));
        assertTrue(single(target, "from").hasModifierProperty(PsiModifier.PUBLIC));
        assertTrue(single(target, "mutate").hasModifierProperty(PsiModifier.PUBLIC));
        assertTrue(target.getInnerClasses()[0].hasModifierProperty(PsiModifier.PUBLIC));
    }

    /**
     * {@code constructorAccess = NONE} is reported on the annotation, and the
     * all-args constructor is contributed at the default beside it, as javac
     * generates it: package-private, so a same-package {@code new Acc("x")}
     * resolves.
     */
    public void testConstructorAccessNone_contributesTheConstructorAtTheDefault() {
        PsiFile file = myFixture.configureByText("Acc.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(constructorAccess = AccessLevel.NONE)
            public class Acc {
                String label;
                static Acc direct() { return new Acc("x"); }
            }
            """);
        PsiClass target = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiMethod[] constructors = target.getConstructors();
        assertEquals(1, constructors.length);
        assertEquals(1, constructors[0].getParameterList().getParametersCount());
        assertTrue(constructors[0].hasModifierProperty(PsiModifier.PACKAGE_LOCAL));
        assertEquals(List.of(), errors());
    }

    // ------------------------------------------------------------------
    // Package-private members from another package
    // ------------------------------------------------------------------

    /**
     * The all-args constructor at the default {@code constructorAccess} is
     * package-private, so {@code new p.Widget("x")} from another package is
     * refused, as javac refuses it. The light constructor carried no access
     * modifier, which the platform's access check reads as public.
     */
    public void testAllArgsConstructorAtDefaultAccess_isClosedToAnotherPackage() {
        addWidget("@ClassBuilder");
        List<String> errors = errorsIn("q/UseWidget.java",
            """
            package q;
            public class UseWidget {
                String go() { return new p.Widget("x").getName(); }
            }
            """);
        assertEquals("javac: cannot be accessed from outside package; editor: " + errors, 1, errors.size());
    }

    /** {@code builder()} at {@code access = PACKAGE} is closed to another package. */
    public void testEntryPointAtPackageAccess_isClosedToAnotherPackage() {
        addWidget("@ClassBuilder(access = AccessLevel.PACKAGE)");
        List<String> errors = errorsIn("q/UseWidget.java",
            """
            package q;
            public class UseWidget {
                Object go() { return p.Widget.builder(); }
            }
            """);
        assertEquals("javac: cannot be accessed from outside package; editor: " + errors, 1, errors.size());
    }

    /** The builder class at {@code access = PACKAGE} is closed to another package. */
    public void testBuilderClassAtPackageAccess_isClosedToAnotherPackage() {
        addWidget("@ClassBuilder(access = AccessLevel.PACKAGE)");
        List<String> errors = errorsIn("q/UseWidget.java",
            """
            package q;
            public class UseWidget {
                Object go() { p.Widget.Builder b = null; return b; }
            }
            """);
        assertEquals("javac: Builder is not public in p.Widget; editor: " + errors, 1, errors.size());
    }

    /** From its own package every one of those members stays reachable. */
    public void testPackagePrivateMembers_resolveFromTheirOwnPackage() {
        addWidget("@ClassBuilder(access = AccessLevel.PACKAGE)");
        List<String> errors = errorsIn("p/UseWidget.java",
            """
            package p;
            public class UseWidget {
                String go() {
                    Widget.Builder b = Widget.builder();
                    return b.name("x").build().getName() + new Widget("y").getName();
                }
            }
            """);
        assertTrue("javac compiles this; editor errors: " + errors, errors.isEmpty());
    }

    // ------------------------------------------------------------------
    // The generated builder's slot fields
    // ------------------------------------------------------------------

    /**
     * The generated builder declares one private field per slot, and the target
     * encloses it, so a static helper in the target reads a slot off a builder;
     * javac builds and runs it. The editor's generated builder carried no
     * fields, and the read was {@code Cannot resolve symbol 'text'}.
     */
    public void testAStaticHelperReadingAGeneratedBuildersSlot_resolves() {
        myFixture.configureByText("Note.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Note {
                String text;
                static String peek(Builder b) { return b.text; }
                public static String go() { return peek(Note.builder().text("hi")); }
            }
            """);
        assertEquals("javac compiles this", List.of(), errors());
    }

    /**
     * Each slot field carries the type the processor declares it in: an
     * {@code Optional} and a collection as written, a {@code @Lazy} field as a
     * supplier of its declared type, every one private.
     */
    public void testTheGeneratedBuildersSlotFields_carryTheProcessorsStorageTypes() {
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
        PsiClass builder = builderFor("Holder",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            import java.util.List;
            import java.util.Optional;
            @ClassBuilder
            public class Holder {
                Optional<String> nick;
                List<String> tags;
                @Lazy String heavy = "h";
            }
            """);
        List<String> fields = new ArrayList<>();
        for (PsiField field : builder.getFields()) {
            fields.add((field.hasModifierProperty(PsiModifier.PRIVATE) ? "private " : "")
                + field.getType().getCanonicalText() + " " + field.getName());
        }
        assertEquals(List.of(
            "private java.util.Optional<java.lang.String> nick",
            "private java.util.List<java.lang.String> tags",
            "private java.util.function.Supplier<java.lang.String> heavy"), fields);
        assertNotNull("the lookup the platform resolves a reference through",
            builder.findFieldByName("heavy", false));
    }

    /**
     * A primitive {@code @Lazy} field's supplier setter and all-args constructor
     * parameter take the boxed supplier the processor declares. Both read
     * {@code Supplier<int>}, a type javac refuses to name.
     */
    public void testAPrimitiveLazyFieldsSupplierSetterAndConstructorParameter_areBoxed() {
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
        PsiClass builder = builderFor("Counted",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            @ClassBuilder
            public class Counted {
                @Lazy private int count = compute();
                private static int compute() { return 7; }
            }
            """);
        List<String> setters = new ArrayList<>();
        for (PsiMethod setter : builder.findMethodsByName("count", false))
            setters.add(setter.getParameterList().getParameters()[0].getType().getCanonicalText());
        assertEquals(List.of("int", "java.util.function.Supplier<java.lang.Integer>"), setters);

        PsiClass target = builder.getContainingClass();
        assertNotNull(target);
        List<String> parameters = new ArrayList<>();
        for (PsiMethod constructor : target.getConstructors()) {
            for (var parameter : constructor.getParameterList().getParameters())
                parameters.add(parameter.getType().getCanonicalText());
        }
        assertEquals(List.of("java.util.function.Supplier<java.lang.Integer>"), parameters);
    }

    /**
     * A read of the builder's slot is a read of the builder's field, not of the
     * target's, so Find Usages on the target's field still finds its own reads
     * alone, as javac binds them.
     */
    public void testFindUsagesOnTheTargetsField_findsItsOwnReadsAlone() {
        myFixture.configureByText("Note.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Note {
                String text;
                String echo() { return text; }
                static String peek(Builder b) { return b.text; }
            }
            """);
        PsiClass note = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiField text = note.findFieldByName("text", false);
        assertNotNull(text);
        List<String> usages = new ArrayList<>();
        for (var usage : myFixture.findUsages(text)) {
            PsiMethod in = PsiTreeUtil.getParentOfType(usage.getElement(), PsiMethod.class);
            usages.add(in == null ? "?" : in.getName());
        }
        assertEquals(List.of("echo"), usages);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Adds the interface {@code demo.Shape} under the given annotation, and its
     * sibling builder and implementation as the processor writes them, less the
     * nullness and contract annotations.
     */
    private void addShapeSources(String annotation, String fromName) {
        myFixture.addFileToProject("demo/Shape.java",
            """
            package demo;
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            %s
            public interface Shape {
                String name();
            }
            """.formatted(annotation));
        myFixture.addFileToProject("demo/ShapeBuilder.java",
            """
            package demo;
            public class ShapeBuilder {
                private String name;
                public ShapeBuilder name(String name) {
                    this.name = name;
                    return this;
                }
                public static ShapeBuilder %s(Shape instance) {
                    ShapeBuilder b = new ShapeBuilder();
                    b.name = instance.name();
                    return b;
                }
                public Shape build() {
                    return new ShapeImpl(name);
                }
            }
            """.formatted(fromName));
        myFixture.addFileToProject("demo/ShapeImpl.java",
            """
            package demo;
            final class ShapeImpl implements Shape {
                private final String name;
                ShapeImpl(String name) { this.name = name; }
                @Override public String name() { return this.name; }
            }
            """);
    }

    /** Adds {@code p.Widget} under the given annotation. */
    private void addWidget(String annotation) {
        myFixture.addFileToProject("p/Widget.java",
            """
            package p;
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            %s
            public class Widget {
                private String name;
                public String getName() { return name; }
            }
            """.formatted(annotation));
    }

    /** Opens a new file at the given path and returns the errors highlighted in it. */
    private List<String> errorsIn(String path, String source) {
        myFixture.addFileToProject(path, source);
        myFixture.configureFromTempProjectFile(path);
        return errors();
    }

    /** The error descriptions highlighted in the open file. */
    private List<String> errors() {
        List<String> errors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.ERROR) errors.add(info.getDescription());
        }
        return errors;
    }

    /** The one method of this name, augmented members included. */
    private static PsiMethod single(PsiClass owner, String name) {
        PsiMethod[] found = owner.findMethodsByName(name, false);
        assertEquals("exactly one " + name + " on " + owner.getName(), 1, found.length);
        return found[0];
    }

    /** Registers custom interface collection/map types plus their factories. */
    private void addCustomBagSources() {
        myFixture.addFileToProject("demo/Bag.java",
            """
            package demo;
            public interface Bag<E> extends java.util.List<E> {}
            """);
        myFixture.addFileToProject("demo/Bags.java",
            """
            package demo;
            public final class Bags {
                public static <E> Bag<E> newBag() { return null; }
            }
            """);
        myFixture.addFileToProject("demo/Ledger.java",
            """
            package demo;
            public interface Ledger<K, V> extends java.util.Map<K, V> {}
            """);
        myFixture.addFileToProject("demo/Ledgers.java",
            """
            package demo;
            public final class Ledgers {
                public static <K, V> Ledger<K, V> newLedger() { return null; }
            }
            """);
    }

    /** Shortcut: configure a file, return the synthesised nested Builder PsiClass. */
    private PsiClass builderFor(String className, String source) {
        PsiFile file = myFixture.configureByText(className + ".java", source);
        PsiClass target = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiClass[] inner = target.getInnerClasses();
        assertEquals("expected exactly one synthesised Builder", 1, inner.length);
        return inner[0];
    }

    /** As {@link #builderFor(String, String)}, additionally pinning the builder's simple name. */
    private PsiClass builderFor(String className, String source, String expectedBuilderName) {
        PsiClass builder = builderFor(className, source);
        assertEquals("builder class name", expectedBuilderName, builder.getName());
        return builder;
    }

}
