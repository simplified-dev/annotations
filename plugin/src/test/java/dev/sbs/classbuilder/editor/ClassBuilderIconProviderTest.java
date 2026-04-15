package dev.sbs.classbuilder.editor;

import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.Icon;

/**
 * Verifies that {@link ClassBuilderIconProvider} decorates every
 * augment-synthesised element (Builder class + its methods + the
 * target-level bootstrap methods) with the project icon, and leaves
 * unrelated elements untouched.
 */
public class ClassBuilderIconProviderTest extends BasePlatformTestCase {

    private static final String ICON_PATH = "/icons/classbuilder_generated.svg";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
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

    public void testGeneratedMembersCarryIcon() {
        PsiFile file = myFixture.configureByText("Widget.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Widget {
                String name;
                public Widget(String name) { this.name = name; }
                public String getName() { return name; }
            }
            """);
        PsiClass widget = ((PsiJavaFile) file).getClasses()[0];
        ClassBuilderIconProvider provider = new ClassBuilderIconProvider();
        Icon expected = IconLoader.getIcon(ICON_PATH, ClassBuilderIconProvider.class);

        // Target-level bootstrap methods are augment-synthesised.
        PsiMethod builderMethod = widget.findMethodsByName("builder", false)[0];
        assertSame("bootstrap builder() must wear the generated icon",
            expected, provider.getIcon(builderMethod, 0));

        // Nested Builder class and every method on it.
        PsiClass[] inner = widget.getInnerClasses();
        assertEquals("expected one synthesised Builder", 1, inner.length);
        PsiClass builder = inner[0];
        assertSame("synthesised Builder class must wear the generated icon",
            expected, provider.getIcon(builder, 0));

        for (PsiMethod m : builder.getMethods()) {
            assertSame("every Builder method should wear the generated icon; " + m.getName() + " did not",
                expected, provider.getIcon(m, 0));
        }
    }

    public void testUserCodeDoesNotGetIcon() {
        PsiFile file = myFixture.configureByText("Widget.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Widget {
                String name;
                public Widget(String name) { this.name = name; }
                public String getName() { return name; }
            }
            """);
        PsiClass widget = ((PsiJavaFile) file).getClasses()[0];
        ClassBuilderIconProvider provider = new ClassBuilderIconProvider();

        // The target class itself and its user-written constructor / getter
        // are not augment-synthesised; the provider must fall through.
        assertNull("user target class must not get the generated icon",
            provider.getIcon(widget, 0));
        assertNull("user-written getter must not get the generated icon",
            provider.getIcon(widget.findMethodsByName("getName", false)[0], 0));
    }

    public void testUnrelatedFileIgnored() {
        PsiFile file = myFixture.configureByText("Plain.java",
            """
            public class Plain {
                public void doThing() {}
            }
            """);
        PsiClass plain = ((PsiJavaFile) file).getClasses()[0];
        ClassBuilderIconProvider provider = new ClassBuilderIconProvider();

        assertNull(provider.getIcon(plain, 0));
        for (PsiMethod m : plain.getMethods()) {
            assertNull(provider.getIcon(m, 0));
        }
    }

}
