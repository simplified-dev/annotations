package dev.sbs.classbuilder.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Exercises {@link ClassBuilderAugmentProvider}: a {@code @ClassBuilder}
 * class should surface synthetic {@code builder()}, {@code from(T)}, and
 * {@code mutate()} methods to the PSI layer, plus a nested {@code Builder}
 * class whose setter matrix mirrors the APT mutator output.
 */
public class ClassBuilderAugmentProviderTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationSources();
    }

    /** Adds the annotation stubs the tests reference onto the fixture's source path. */
    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/sbs/annotation/ClassBuilder.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder {
                String builderName() default "Builder";
                String builderMethodName() default "builder";
                String fromMethodName() default "from";
                String toBuilderMethodName() default "mutate";
                String methodPrefix() default "with";
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/sbs/annotation/Negate.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Negate { String value(); }
            """);
        myFixture.addFileToProject("dev/sbs/annotation/Formattable.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Formattable { }
            """);
        myFixture.addFileToProject("dev/sbs/annotation/Collector.java",
            """
            package dev.sbs.annotation;
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
            import dev.sbs.annotation.ClassBuilder;
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
            import dev.sbs.annotation.ClassBuilder;
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
            import dev.sbs.annotation.ClassBuilder;
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
        assertTrue("withTitle should show up: actual=" + lookup, lookup.contains("withTitle"));
        assertTrue("withPages should show up: actual=" + lookup, lookup.contains("withPages"));
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
            import dev.sbs.annotation.ClassBuilder;
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
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Doc {
                int rank;
                public static Doc make() {
                    return Doc.builder().withRank(7).build();
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
     * Cross-package variant - exercises {@link ClassBuilderElementFinder}'s
     * bridge from {@link com.intellij.psi.JavaPsiFacade#findClass} to the
     * augmented inner class. Without that finder the highlighter calls
     * {@code findClass("a.Doc.Builder", scope)} which returns null (augment
     * providers don't participate in the global class index), and reports
     * "Cannot access a.Doc.Builder" on every method-chain entry.
     */
    public void testSetterInvocation_passesHighlighter_crossPackage() {
        myFixture.addFileToProject("a/Doc.java",
            """
            package a;
            import dev.sbs.annotation.ClassBuilder;
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
                    return Doc.builder().withRank(7).build();
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
            import dev.sbs.annotation.ClassBuilder;
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
            import dev.sbs.annotation.ClassBuilder;
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
            import dev.sbs.annotation.ClassBuilder;
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

        assertEquals(1, builder.findMethodsByName("withLabel", false).length);
        assertEquals(1, builder.findMethodsByName("withRank", false).length);
        assertEquals(1, builder.findMethodsByName("build", false).length);
    }

    public void testHandWrittenBuilder_skipsSynthesis() {
        PsiFile file = myFixture.configureByText("Manual.java",
            """
            import dev.sbs.annotation.ClassBuilder;
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

    public void testCustomBootstrapNames_respected() {
        PsiFile file = myFixture.configureByText("Named.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder(builderMethodName = "make", fromMethodName = "of", toBuilderMethodName = "edit")
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
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Toggle { boolean active; }
            """);
        PsiMethod[] isActive = builder.findMethodsByName("isActive", false);
        assertEquals("zero-arg + typed pair", 2, isActive.length);
        boolean sawZeroArg = false, sawTyped = false;
        for (PsiMethod m : isActive) {
            if (m.getParameterList().getParametersCount() == 0) sawZeroArg = true;
            else sawTyped = true;
        }
        assertTrue("zero-arg isActive()", sawZeroArg);
        assertTrue("typed isActive(boolean)", sawTyped);
    }

    /** {@code @Negate("name")} on a boolean field produces a second zero-arg + typed pair. */
    public void testNegateInversePair() {
        PsiClass builder = builderFor("Door",
            """
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Negate;
            @ClassBuilder
            public class Door {
                @Negate("closed") boolean open;
            }
            """);
        assertEquals("primary pair", 2, builder.findMethodsByName("isOpen", false).length);
        assertEquals("inverse pair", 2, builder.findMethodsByName("isClosed", false).length);
    }

    /** {@code Optional<T>} fields get nullable-raw + wrapped setters. */
    public void testOptionalDualSetter() {
        PsiClass builder = builderFor("Box",
            """
            import dev.sbs.annotation.ClassBuilder;
            import java.util.Optional;
            @ClassBuilder
            public class Box { Optional<String> label; }
            """);
        PsiMethod[] withLabel = builder.findMethodsByName("withLabel", false);
        assertEquals("nullable-raw + wrapped", 2, withLabel.length);
    }

    /** {@code Optional<String>} with {@code @Formattable} gets a third overload. */
    public void testOptionalFormattableOverload() {
        PsiClass builder = builderFor("Card",
            """
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Formattable;
            import java.util.Optional;
            @ClassBuilder
            public class Card {
                @Formattable Optional<String> description;
            }
            """);
        // raw(String), wrapped(Optional<String>), formattable(String, Object...)
        assertEquals(3, builder.findMethodsByName("withDescription", false).length);
    }

    /** String {@code @Formattable} adds a {@code (String, Object...)} overload. */
    public void testStringFormattableOverload() {
        PsiClass builder = builderFor("Greeting",
            """
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Formattable;
            @ClassBuilder
            public class Greeting {
                @Formattable String message;
            }
            """);
        PsiMethod[] withMessage = builder.findMethodsByName("withMessage", false);
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
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Tags { String[] labels; }
            """);
        PsiMethod[] withLabels = builder.findMethodsByName("withLabels", false);
        assertEquals(1, withLabels.length);
        assertTrue("single-param varargs",
            withLabels[0].getParameterList().getParameter(0).isVarArgs());
    }

    /**
     * Bare {@code @Collector} on a list: only varargs + iterable (no singular
     * add / clear since those are opt-in via attributes).
     */
    public void testCollectorList_bulkOnlyByDefault() {
        PsiClass builder = builderFor("Cart",
            """
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Collector;
            import java.util.List;
            @ClassBuilder
            public class Cart {
                @Collector List<String> items;
            }
            """);
        PsiMethod[] withItems = builder.findMethodsByName("withItems", false);
        assertEquals("varargs-replace + iterable-replace", 2, withItems.length);
        assertEquals("no singular add without @Collector(singular=true)",
            0, builder.findMethodsByName("withItem", false).length);
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
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Collector;
            import java.util.List;
            @ClassBuilder
            public class Cart {
                @Collector(singular = true, clearable = true) List<String> items;
            }
            """);
        assertEquals("varargs + iterable", 2, builder.findMethodsByName("withItems", false).length);
        assertEquals("add single", 1, builder.findMethodsByName("withItem", false).length);
        assertEquals("clear", 1, builder.findMethodsByName("clearItems", false).length);
    }

    /**
     * {@code @Collector} on a map with all opt-ins: replace + put + clear +
     * putIfAbsent.
     */
    public void testCollectorMap_fullOptIn() {
        PsiClass builder = builderFor("Bag",
            """
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Collector;
            import java.util.Map;
            @ClassBuilder
            public class Bag {
                @Collector(singular = true, clearable = true, compute = true) Map<String, Integer> counts;
            }
            """);
        assertEquals("replace", 1, builder.findMethodsByName("withCounts", false).length);
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
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Collector;
            import java.util.List;
            @ClassBuilder
            public class Shop {
                @Collector(singular = true, singularMethodName = "flavor") List<String> flavors;
            }
            """);
        // Default "with" prefix + explicit singular "flavor" -> withFlavor.
        assertEquals(1, builder.findMethodsByName("withFlavor", false).length);
        // The varargs-replace on the plural field name is still withFlavors;
        // neither that nor withFlavor collapses to a mistakenly doubled name.
        assertEquals(0, builder.findMethodsByName("withFlavorss", false).length);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Shortcut: configure a file, return the synthesised nested Builder PsiClass. */
    private PsiClass builderFor(String className, String source) {
        PsiFile file = myFixture.configureByText(className + ".java", source);
        PsiClass target = ((com.intellij.psi.PsiJavaFile) file).getClasses()[0];
        PsiClass[] inner = target.getInnerClasses();
        assertEquals("expected exactly one synthesised Builder", 1, inner.length);
        return inner[0];
    }

}
