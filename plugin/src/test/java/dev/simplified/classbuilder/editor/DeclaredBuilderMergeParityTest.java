package dev.simplified.classbuilder.editor;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
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

    /**
     * The processor returns ahead of every entry point where the target declares
     * a nested type of the builder's name and the opt-in is not written, so an
     * editor that offers one is completing a member the build answers
     * {@code cannot find symbol} on. Reachable on a plain standalone target with
     * no chain and no opt-in anywhere in it.
     */
    public void testDeclaredBuilder_offersNoEntryPoints() {
        assertParity(BuilderParityFixture.load("standalone-declared-builder-opt-out"));
    }

    /**
     * The chain branch returns ahead of the declared-builder check, so the
     * opt-in reaches nothing on a link: no member is appended to the author's
     * builder and no entry point lands on the target. The attribute being
     * written is what makes this worth pinning separately - the editor reads it
     * and the processor never does.
     */
    public void testADeclaredChainBuilderWithTheOptIn_isLeftAlone() {
        assertParity(BuilderParityFixture.load("chain-link-declared-builder-opt-in"));
    }

    /** The same abort one role up, where no opt-in is written at all. */
    public void testADeclaredChainBuilderWithoutTheOptIn_isLeftAlone() {
        assertParity(BuilderParityFixture.load("chain-root-declared-builder-opt-out"));
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
            @ClassBuilder(mergeDeclaredBuilder = true)
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
            @ClassBuilder(mergeDeclaredBuilder = true)
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
            @ClassBuilder(mergeDeclaredBuilder = true)
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
            @ClassBuilder(mergeDeclaredBuilder = true)
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
            @ClassBuilder(mergeDeclaredBuilder = true)
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void assertNoErrors() {
        List<String> errors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.ERROR) errors.add(info.getDescription());
        }
        assertTrue("expected no editor errors, got: " + errors, errors.isEmpty());
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
