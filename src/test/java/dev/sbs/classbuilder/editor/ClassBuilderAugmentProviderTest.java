package dev.sbs.classbuilder.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Exercises {@link ClassBuilderAugmentProvider}: a {@code @ClassBuilder}
 * class should surface synthetic {@code builder()}, {@code from(T)}, and
 * {@code mutate()} methods to the PSI layer.
 */
public class ClassBuilderAugmentProviderTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationSource();
    }

    /** Adds a minimal {@code @ClassBuilder} stub to the test fixture's source path. */
    private void addAnnotationSource() {
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
            }
            """);
    }

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
        // Abstract targets can't be instantiated, so builder()/from() are
        // not generated on them per the APT pipeline; the augment provider
        // matches that policy.
        assertEquals(0, shape.findMethodsByName("builder", false).length);
        assertEquals(0, shape.findMethodsByName("from", false).length);
        assertEquals(0, shape.findMethodsByName("mutate", false).length);
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

}
