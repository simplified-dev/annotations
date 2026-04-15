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
            public @interface Formattable { boolean nullable() default false; }
            """);
        myFixture.addFileToProject("dev/sbs/annotation/Singular.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Singular { String value() default ""; }
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

    /** {@code @Singular} on a list produces varargs-replace / iterable-replace / add / clear. */
    public void testSingularListSetters() {
        PsiClass builder = builderFor("Cart",
            """
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Singular;
            import java.util.List;
            @ClassBuilder
            public class Cart {
                @Singular List<String> items;
            }
            """);
        PsiMethod[] withItems = builder.findMethodsByName("withItems", false);
        assertEquals("varargs-replace + iterable-replace", 2, withItems.length);
        // With the default "with" prefix the singular add becomes withItem,
        // matching what BuilderEmitter / FieldMutators emit.
        assertEquals("add single",
            1, builder.findMethodsByName("withItem", false).length);
        assertEquals("clear",
            1, builder.findMethodsByName("clearItems", false).length);
    }

    /** {@code @Singular} on a map produces replace / put / clear. */
    public void testSingularMapSetters() {
        PsiClass builder = builderFor("Bag",
            """
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Singular;
            import java.util.Map;
            @ClassBuilder
            public class Bag {
                @Singular Map<String, Integer> counts;
            }
            """);
        assertEquals("replace", 1, builder.findMethodsByName("withCounts", false).length);
        assertEquals("put",
            1, builder.findMethodsByName("putCount", false).length);
        assertEquals("clear",
            1, builder.findMethodsByName("clearCounts", false).length);
    }

    /** {@code @Singular("flavor")} lets the caller override the derived singular name. */
    public void testSingularCustomName() {
        PsiClass builder = builderFor("Shop",
            """
            import dev.sbs.annotation.ClassBuilder;
            import dev.sbs.annotation.Singular;
            import java.util.List;
            @ClassBuilder
            public class Shop {
                @Singular("flavor") List<String> flavors;
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
