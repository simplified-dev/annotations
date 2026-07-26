package dev.simplified.shared.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/**
 * Behaviour of {@link GeneratedMemberHighlightFilter}: the two compiler-parity
 * errors have to disappear on a field a generated constructor assigns, and
 * survive everywhere else.
 *
 * <p>The reports are constructed rather than provoked. What is under test is the
 * filter's decision, and building the {@code HighlightInfo} pins the exact
 * message and offset the platform produces without also depending on the
 * highlighter running an augment provider inside a light fixture.
 */
public class GeneratedMemberHighlightFilterTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String NOT_INITIALIZED = "Field 'x' might not have been initialized";
    private static final String FINAL_ASSIGNMENT = "Cannot assign a value to final variable 'x'";

    private final GeneratedMemberHighlightFilter filter = new GeneratedMemberHighlightFilter();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        GeneratedMemberTestSources.install(myFixture);
    }

    // ------------------------------------------------------------------
    // Field 'x' might not have been initialized
    // ------------------------------------------------------------------

    /** The shape 64 files of the first real migration reported on. */
    public void testRequiredArgsConstructorFinalFieldIsNotUninitialized() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            @RequiredArgsConstructor
            public class Target {
                private final String x;
            }
            """);
        assertFalse("a @RequiredArgsConstructor final field is assigned by the generated constructor",
            accepts(file, NOT_INITIALIZED, field(file, "x")));
    }

    /** {@code @ClassBuilder} answers through the constructor it infers. */
    public void testClassBuilderFinalFieldIsNotUninitialized() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final String x;
            }
            """);
        assertFalse(accepts(file, NOT_INITIALIZED, field(file, "x")));
    }

    /** {@code force} fills the finals no parameter covers. */
    public void testForcedNoArgsConstructorFinalFieldIsNotUninitialized() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.NoArgsConstructor;
            @NoArgsConstructor(force = true)
            public class Target {
                private final String x;
            }
            """);
        assertFalse(accepts(file, NOT_INITIALIZED, field(file, "x")));
    }

    /**
     * The error javac still gives. {@code @NoArgsConstructor} without
     * {@code force} assigns nothing, so the field really is unassigned and the
     * report is the one the author needs.
     */
    public void testUnforcedNoArgsConstructorKeepsTheError() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.NoArgsConstructor;
            @NoArgsConstructor
            public class Target {
                private final String x;
            }
            """);
        assertTrue("no constructor annotation assigns this field, so the error stands",
            accepts(file, NOT_INITIALIZED, field(file, "x")));
    }

    /** An unannotated class is none of the filter's business. */
    public void testUnannotatedClassKeepsTheError() {
        PsiFile file = configure(
            """
            public class Target {
                private final String x;
            }
            """);
        assertTrue(accepts(file, NOT_INITIALIZED, field(file, "x")));
    }

    /** A field the builder is told to skip keeps the error it has earned. */
    public void testExcludedFieldKeepsTheError() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(exclude = "x")
            public class Target {
                private final String x;
                private final String kept;
            }
            """);
        assertTrue("exclude drops the field from the constructor, so it is genuinely unassigned",
            accepts(file, NOT_INITIALIZED, field(file, "x")));
    }

    /** A different error on the same field is not this filter's to drop. */
    public void testUnrelatedErrorOnAnAssignedFieldSurvives() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            @RequiredArgsConstructor
            public class Target {
                private final String x;
            }
            """);
        assertTrue(accepts(file, "Cannot resolve symbol 'Nonsense'", field(file, "x")));
    }

    // ------------------------------------------------------------------
    // Cannot assign a value to final variable
    // ------------------------------------------------------------------

    /**
     * The blank-final lift: {@code retainInit} strips the initializer, so the
     * constructor's assignment is the only one in the class javac emits.
     */
    public void testLiftedBlankFinalAssignmentIsNotADoubleAssignment() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int x = 128;
                public Target(int x) {
                    this.x = x;
                }
            }
            """);
        assertFalse("retainInit lifts the initializer off the field",
            accepts(file, FINAL_ASSIGNMENT, assignmentTarget(file)));
    }

    /** Without {@code @ClassBuilder} nothing lifts the initializer. */
    public void testUnannotatedFinalAssignmentKeepsTheError() {
        PsiFile file = configure(
            """
            public class Target {
                private final int x = 128;
                public Target(int x) {
                    this.x = x;
                }
            }
            """);
        assertTrue(accepts(file, FINAL_ASSIGNMENT, assignmentTarget(file)));
    }

    /**
     * {@code @BuilderIgnore} drops the field from the builder's selection, so
     * its initializer stays where the author wrote it.
     */
    public void testIgnoredFinalAssignmentKeepsTheError() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                @BuilderIgnore
                private final int x = 128;
                private final int kept = 1;
                public Target(int x) {
                    this.x = x;
                }
            }
            """);
        assertTrue(accepts(file, FINAL_ASSIGNMENT, assignmentTarget(file)));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiFile configure(String source) {
        return myFixture.configureByText("Target.java", source);
    }

    private static PsiField field(PsiFile file, String name) {
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        PsiField found = target.findFieldByName(name, false);
        assertNotNull("fixture has no field named " + name, found);
        return found;
    }

    /** The left-hand reference of the class's single assignment. */
    private static PsiElement assignmentTarget(PsiFile file) {
        PsiAssignmentExpression assignment =
            PsiTreeUtil.findChildOfType(file, PsiAssignmentExpression.class);
        assertNotNull("fixture has no assignment", assignment);
        return assignment.getLExpression();
    }

    /**
     * Whether the filter lets a report through.
     *
     * @param file the file being highlighted
     * @param description the platform's message
     * @param anchor the element the report lands on
     * @return {@code true} when the report survives, which is the platform default
     */
    private boolean accepts(PsiFile file, String description, PsiElement anchor) {
        // A declaration's report lands on its name identifier, which is where
        // the platform anchors it and where the filter looks for it.
        PsiElement range = anchor instanceof PsiField declared && declared.getNameIdentifier() != null
            ? declared.getNameIdentifier()
            : anchor;
        // createUnconditionally rather than create: the latter runs every
        // registered HighlightInfoFilter itself and answers null when one
        // rejects, which would leave this filter's own verdict untested.
        HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
            .range(range)
            .descriptionAndTooltip(description)
            .createUnconditionally();
        return filter.accept(info, file);
    }

}
