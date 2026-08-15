package dev.simplified.classbuilder.editor;

import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's half of {@code mergeDeclaredBuilder}.
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
                boolean mergeDeclaredBuilder() default false;
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
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE }
            """);
    }

    public void testMerge_offersTheGeneratedSettersOnTheDeclaredBuilder() {
        List<String> names = declaredBuilderMethodsOf("Settings",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(mergeDeclaredBuilder = true)
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
            @ClassBuilder(mergeDeclaredBuilder = true)
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
            @ClassBuilder(mergeDeclaredBuilder = true)
            public class Boxed {
                private int size;
                public static class Builder {
                    public Boxed build() { return null; }
                }
            }
            """);
        assertEquals("exactly one build(): " + names, 1, count(names, "build"));
    }

    /**
     * Without the opt-in the declaration suppresses the whole pass, so the
     * editor has to leave the declared builder exactly as written - offering a
     * setter here would be completion for a method no build emits.
     */
    public void testWithoutTheOptIn_theDeclaredBuilderIsLeftAlone() {
        List<String> names = declaredBuilderMethodsOf("Untouched",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Untouched {
                private String name;
                public static class Builder {
                    public Builder apply(Runnable r) { return this; }
                }
            }
            """);
        assertTrue("the author's member: " + names, names.contains("apply"));
        assertFalse("and nothing generated: " + names, names.contains("name"));
        assertFalse("not even a build(): " + names, names.contains("build"));
    }

    /** A nested class that is not the builder is not a merge target. */
    public void testAnUnrelatedNestedClass_isLeftAlone() {
        PsiFile file = myFixture.configureByText("Holder.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(mergeDeclaredBuilder = true)
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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
