package dev.simplified.shared.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Behaviour of {@link GeneratedMemberHighlightFilter} over a real highlighting
 * pass: the two compiler-parity errors are gone from the fields a generated
 * constructor assigns, and every other report on the same source survives.
 *
 * <p>The reports are provoked rather than constructed, and that is the point of
 * the fixture. Which element of a declaration the platform anchors a report on
 * is the platform's choice, not the filter's - a resolver that looks somewhere
 * else answers {@code null}, the filter falls through to the platform default,
 * and the error stays on screen with every unit-level assertion still green. A
 * report built from an element of the test's own choosing cannot see that at
 * all, so the one case built by hand is ranged over the elements a companion
 * case measures the platform's own reports inside, on the same source.
 *
 * <p>Nothing is registered here. The fixture loads the plugin descriptor, so the
 * extension under test is the one the plugin ships; contributing a second
 * instance would test the instance rather than the registration.
 *
 * <p>Every silenced case is paired with a control carrying no annotation of
 * ours, on which the same report has to be present.
 */
public class GeneratedMemberHighlightFilterTest extends LightJavaCodeInsightFixtureTestCase {

    /** Matched as a substring, since the platform words the subject either way. */
    private static final String NOT_INITIALIZED = "might not have been initialized";

    private static final String FINAL_ASSIGNMENT = "Cannot assign a value to final variable";

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        GeneratedMemberTestSources.install(myFixture);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    /** The daemon runs what the plugin descriptor registers. */
    public void testTheFilterIsContributedToTheDaemon() {
        assertTrue("the daemon has no GeneratedMemberHighlightFilter to run",
            HighlightInfoFilter.EXTENSION_POINT_NAME.getExtensionList().stream()
                .anyMatch(GeneratedMemberHighlightFilter.class::isInstance));
    }

    // ------------------------------------------------------------------
    // Field 'x' might not have been initialized
    // ------------------------------------------------------------------

    /**
     * Pins where a declaration report lands: inside the field, and never on its
     * name. The type reference carries it on an annotated reference type, the
     * modifier list on everything else.
     */
    public void testAnUninitializedReportIsAnchoredOffTheFieldName() {
        PsiFile file = configure(
            """
            import org.jetbrains.annotations.NotNull;
            public final class Target {
                private final @NotNull String a;
                private final int b;
                private final String c;
            }
            """);
        int anchored = 0;
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (!describes(info, NOT_INITIALIZED)) continue;
            PsiElement anchor = file.findElementAt(info.getStartOffset());
            assertNotNull("the report is anchored on nothing", anchor);
            PsiField declared = PsiTreeUtil.getParentOfType(anchor, PsiField.class, false);
            assertNotNull("the report is anchored outside the declaration", declared);
            assertNotSame("the report is anchored on the field's name",
                declared.getNameIdentifier(), anchor);
            anchored++;
        }
        assertEquals("one report per field", 3, anchored);
    }

    /** The three declaration shapes, unannotated, all reported. */
    public void testUnannotatedFinalFieldsAreAllReportedUninitialized() {
        configure(
            """
            import org.jetbrains.annotations.NotNull;
            public final class Target {
                private final @NotNull String a;
                private final int b;
                private final String c;
            }
            """);
        assertTrue("annotated reference type", reportsUninitialized("a"));
        assertTrue("primitive", reportsUninitialized("b"));
        assertTrue("plain reference type", reportsUninitialized("c"));
    }

    /** The same three shapes under the constructor that assigns them. */
    public void testRequiredArgsConstructorClearsEveryUninitializedReport() {
        configure(
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Getter;
            import dev.simplified.annotations.RequiredArgsConstructor;
            import org.jetbrains.annotations.NotNull;
            @Getter
            @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
            public final class Target {
                private final @NotNull String a;
                private final int b;
                private final String c;
            }
            """);
        assertFalse("annotated reference type", reportsUninitialized("a"));
        assertFalse("primitive", reportsUninitialized("b"));
        assertFalse("plain reference type", reportsUninitialized("c"));
    }

    /** {@code @ClassBuilder} answers through the constructor it infers. */
    public void testClassBuilderClearsEveryUninitializedReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public final class Target {
                private final int b;
                private final String c;
            }
            """);
        assertFalse("primitive", reportsUninitialized("b"));
        assertFalse("plain reference type", reportsUninitialized("c"));
    }

    /** {@code force} fills the finals no parameter covers. */
    public void testForcedNoArgsConstructorClearsTheUninitializedReport() {
        configure(
            """
            import dev.simplified.annotations.NoArgsConstructor;
            @NoArgsConstructor(force = true)
            public final class Target {
                private final String a;
            }
            """);
        assertFalse(reportsUninitialized("a"));
    }

    /**
     * The error javac still gives. {@code @NoArgsConstructor} without
     * {@code force} assigns nothing, so the field really is unassigned and the
     * report is the one the author needs.
     */
    public void testUnforcedNoArgsConstructorKeepsTheUninitializedReport() {
        configure(
            """
            import dev.simplified.annotations.NoArgsConstructor;
            @NoArgsConstructor
            public final class Target {
                private final String a;
            }
            """);
        assertTrue(reportsUninitialized("a"));
    }

    /** A field the builder is told to skip keeps the error it has earned. */
    public void testExcludedFieldKeepsItsUninitializedReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(exclude = "b")
            public final class Target {
                private final String a;
                private final String b;
            }
            """);
        assertFalse("the builder still assigns this one", reportsUninitialized("a"));
        assertTrue("exclude drops the field from the constructor", reportsUninitialized("b"));
    }

    /**
     * A different error on the same declaration is not this filter's to drop.
     *
     * <p>The unresolved type is reported inside the declaration of a
     * {@code final} field with no initializer, which is exactly the field the
     * generated constructor assigns - so the field test passes and the message
     * test is the only thing keeping the error on screen.
     */
    public void testUnrelatedErrorOnAnAssignedFieldSurvives() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            @RequiredArgsConstructor
            public final class Target {
                private final Nonsense a;
            }
            """);
        assertTrue("the unresolved type is still an error",
            reportsErrorWithin(field(file, "a"), "Nonsense"));
    }

    /**
     * One declaration of two fields is judged per field. The second declarator
     * owns neither the modifiers nor the type the two share, so its report is
     * ranged from its own name and the verdict for it is its own.
     */
    public void testEachFieldOfOneDeclarationIsJudgedOnItsOwn() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(exclude = "b")
            public final class Target {
                private final int a, b;
            }
            """);
        assertFalse("the builder still assigns the first", reportsUninitialized("a"));
        assertTrue("exclude drops the second from the constructor", reportsUninitialized("b"));
    }

    /** Both reports, on a class the filter has no say over. */
    public void testAnUnannotatedInitializerReadsAnUnassignedFinal() {
        PsiFile file = configure(
            """
            public final class Target {
                private final int a;
                private final int b = a + 1;
            }
            """);
        assertTrue("the declaration of a", reportsErrorWithin(field(file, "a"), NOT_INITIALIZED));
        assertTrue("the read of a inside b's initializer",
            reportsErrorWithin(initializerOf(file, "b"), NOT_INITIALIZED));
    }

    /**
     * A read of a blank final from another field's initializer belongs to the
     * expression the author wrote, not to the field being declared, so it stands
     * even though a generated constructor assigns that field.
     *
     * <p>The two reports are ranged over the declaration and the initializer
     * that {@link #testAnUnannotatedInitializerReadsAnUnassignedFinal} measures
     * the platform's own reports inside, on the same source, so the anchors are
     * the platform's and the verdicts are the filter's.
     */
    public void testAnErrorInsideAFieldInitializerSurvives() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public final class Target {
                private final int a;
                private final int b = a + 1;
            }
            """);
        GeneratedMemberHighlightFilter filter = new GeneratedMemberHighlightFilter();
        assertFalse("the report on the declaration is the filter's",
            filter.accept(uninitializedAt(field(file, "a"), "a"), file));
        assertTrue("the report inside the initializer is not",
            filter.accept(uninitializedAt(initializerOf(file, "b"), "a"), file));
    }

    // ------------------------------------------------------------------
    // Cannot assign a value to final variable
    // ------------------------------------------------------------------

    /** Both write shapes, on a class nothing lifts an initializer off. */
    public void testUnannotatedWritesToAnInitializedFinalAreReported() {
        configure(
            """
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                }
                void reset(int v) {
                    a = v;
                }
            }
            """);
        assertEquals("the qualified write and the unqualified one",
            2, errorCount(FINAL_ASSIGNMENT));
    }

    /**
     * The blank-final lift: {@code retainInit} keeps the initializer as a builder
     * default and strips it from the field, so each constructor's write is the
     * field's first. javac accepts every one of these shapes - through
     * {@code this}, by the bare name, and parenthesised.
     */
    public void testWritesToALiftedBlankFinalAreCleared() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                }
                public Target(long v) {
                    a = (int) v;
                }
                public Target(short v) {
                    (this.a) = v;
                }
            }
            """);
        assertEquals(0, errorCount(FINAL_ASSIGNMENT));
    }

    /**
     * Turning retention off changes what the builder defaults to, not where the
     * initializer ends up. The generated constructor assigns every selected
     * field either way, so the field is lifted to a blank final either way and
     * the write is still its first.
     */
    public void testWritesToAnUnretainedBlankFinalAreCleared() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(retainInit = false)
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                }
            }
            """);
        assertEquals(0, errorCount(FINAL_ASSIGNMENT));
    }

    /**
     * A blank final is assigned only by its own class's constructors, so javac
     * rejects both method writes with {@code cannot assign a value to final
     * variable a} on their own lines. The report was dropped because the field
     * is lifted, whatever the write sat in, which left the editor green over
     * source javac rejects.
     */
    public void testWritesToALiftedBlankFinalInAMethod_keepTheReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                }
                void reset(int v) {
                    a = v;
                }
                void again(int v) {
                    this.a = v;
                }
            }
            """);
        assertEquals("javac rejects the two method writes", List.of(9, 12), finalAssignmentLines());
    }

    /**
     * A lifted field is assigned by every constructor, so an instance
     * initializer's write is one more than javac accepts - it reports
     * {@code variable a might already have been assigned} on the constructor.
     * The editor keeps its own report on the initializer's write.
     */
    public void testAWriteToALiftedBlankFinalInAnInstanceInitializer_keepsTheReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                {
                    a = 5;
                }
                public Target(int a) {
                    this.a = a;
                }
            }
            """);
        assertEquals("javac rejects the initializer's write", List.of(6), finalAssignmentLines());
    }

    /**
     * A compound assignment and an increment read the field as well as write
     * it, so javac rejects each even in a constructor - here with
     * {@code variable a might already have been assigned} on the line of each.
     */
    public void testACompoundWriteToALiftedBlankFinalInAConstructor_keepsTheReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                    this.a += 1;
                }
                public Target() {
                    this.a = 0;
                    this.a++;
                }
            }
            """);
        assertEquals("javac rejects the compound write and the increment",
            List.of(7, 11), finalAssignmentLines());
    }

    /**
     * Inside a constructor, a write from a lambda or a local class, or through
     * any qualifier but a bare {@code this}, is not the constructor assigning
     * its own field, and javac rejects each.
     */
    public void testAConstructorWriteNotToItsOwnField_keepsTheReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private static Target last;
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                    Runnable r = () -> { this.a = 3; };
                    class Local { void f() { Target.this.a = 1; } }
                    last.a = 4;
                }
            }
            """);
        assertEquals("javac rejects the lambda's, the local class's and the other instance's writes",
            List.of(8, 9, 10), finalAssignmentLines());
    }

    /**
     * {@code @BuilderIgnore} drops the field from the builder's selection, so its
     * initializer stays where the author wrote it and the write is a second
     * assignment.
     */
    public void testWriteToAnIgnoredFinalKeepsTheReport() {
        configure(
            """
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                @BuilderIgnore
                private final int a = 128;
                private final int kept = 1;
                public Target(int a) {
                    this.a = a;
                }
            }
            """);
        assertTrue(reportsFinalAssignment("a"));
    }

    /**
     * A written constructor that assigns the field nowhere keeps its initializer
     * in javac, so the write in the other constructor is a second assignment -
     * {@code cannot assign a value to final variable a} on that line. The report
     * was dropped as a write to a lifted blank final, which left the editor
     * green over source javac rejects.
     */
    public void testAWriteBesideAConstructorLeavingTheFinal_keepsTheReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                }
                public Target() {
                }
            }
            """);
        assertTrue("javac rejects the write as a second assignment", reportsFinalAssignment("a"));
    }

    /**
     * Configures a {@code Target} with a {@code final int a = 128} and one
     * constructor whose body is {@code body}, starting on the fixture's sixth
     * line.
     */
    private void configureFinalAssignedBy(String body) {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                Target(int a) {
                    %s
                }
            }
            """.formatted(body));
    }

    /**
     * A constructor assigning the field on one branch only leaves it assigned
     * nowhere on the other, so javac keeps the initializer and refuses the
     * branch's write as a second assignment on that line. The field was taken
     * for a lifted final and the report dropped, green over source javac
     * rejects.
     */
    public void testAWriteToAFinalOnABranchOnly_keepsTheReport() {
        configureFinalAssignedBy("if (a > 0) this.a = a;");
        assertEquals("javac rejects the branch's write", List.of(6), finalAssignmentLines());
    }

    /** A loop's write is no assignment the lift counts, and javac rejects it. */
    public void testAWriteToAFinalInALoop_keepsTheReport() {
        configureFinalAssignedBy("for (int i = 0; i < a; i++) this.a = i;");
        assertEquals("javac rejects the loop's write", List.of(6), finalAssignmentLines());
    }

    /** A write inside a {@code try} is not counted either. */
    public void testAWriteToAFinalInATry_keepsTheReport() {
        configureFinalAssignedBy("try { this.a = Integer.parseInt(\"\" + a); } catch (RuntimeException e) { }");
        assertEquals("javac rejects the try's write", List.of(6), finalAssignmentLines());
    }

    /**
     * An {@code if} and an {@code else} that both assign the field assign it,
     * so javac lifts the field and accepts both writes. Only a statement of the
     * body itself counted, so both halves refused both writes.
     */
    public void testWritesToAFinalOnBothBranches_areCleared() {
        configureFinalAssignedBy("if (a > 0) this.a = a;\n        else this.a = -a;");
        assertEquals("javac accepts both writes", List.of(), finalAssignmentLines());
    }

    /**
     * A {@code switch} with a {@code default} whose every arm assigns the field
     * and leaves the switch assigns it, in arrow form and in colon form with
     * each arm ending in {@code break}; a block assigning it does too.
     */
    public void testWritesToAFinalInEveryArmOfASwitchWithADefault_areCleared() {
        configureFinalAssignedBy("switch (a) {\n        case 1 -> this.a = 10;\n        default -> { this.a = a; }\n        }");
        assertEquals("arrow arms", List.of(), finalAssignmentLines());
        configureFinalAssignedBy(
            "switch (a) {\n        case 1: case 2: this.a = 10; break;\n        default: this.a = a; break;\n        }");
        assertEquals("colon arms", List.of(), finalAssignmentLines());
        configureFinalAssignedBy("{ int doubled = a * 2; this.a = doubled; }");
        assertEquals("a block", List.of(), finalAssignmentLines());
    }

    /**
     * A {@code switch} with no {@code default}, a last colon arm falling out
     * without a {@code break}, and an arm that may break ahead of its write are
     * outside the rule: javac keeps the initializer and refuses every write.
     */
    public void testWritesToAFinalInASwitchOutsideTheRule_keepTheReport() {
        configureFinalAssignedBy("switch (a) {\n        case 1 -> this.a = 1;\n        case 2 -> this.a = 2;\n        }");
        assertEquals("no default", List.of(7, 8), finalAssignmentLines());
        configureFinalAssignedBy("switch (a) {\n        case 1: this.a = 1; break;\n        default: this.a = 2;\n        }");
        assertEquals("the last arm falls out", List.of(7, 8), finalAssignmentLines());
        configureFinalAssignedBy(
            "switch (a) {\n        case 1: if (a > 5) break; this.a = 1; break;\n        default: this.a = 2; break;\n        }");
        assertEquals("a break ahead of the write", List.of(7, 8), finalAssignmentLines());
    }

    /**
     * Two plain writes to a lifted field in one constructor: javac refuses the
     * second with {@code variable a might already have been assigned}, on its
     * line. Every plain constructor write was cleared, so the editor was green.
     */
    public void testASecondTopLevelWrite_keepsTheReport() {
        configureFinalAssignedBy("this.a = a;\n        this.a = 2;");
        assertEquals("javac rejects the second write", List.of(7), finalAssignmentLines());
    }

    /** A branch's write after a top-level one is refused on the branch's line. */
    public void testABranchsWriteAfterATopLevelOne_keepsTheReport() {
        configureFinalAssignedBy("this.a = a;\n        if (a > 0) this.a = 2;");
        assertEquals("javac rejects the branch's write", List.of(7), finalAssignmentLines());
    }

    /**
     * A branch's write ahead of a top-level one is the field's first on its
     * path, so javac accepts it and refuses the top-level write.
     */
    public void testATopLevelWriteAfterABranchsOne_keepsTheReport() {
        configureFinalAssignedBy("if (a > 0) this.a = 2;\n        this.a = a;");
        assertEquals("javac rejects the top-level write", List.of(7), finalAssignmentLines());
    }

    /**
     * A loop's write is refused whichever side of a top-level write it sits,
     * and javac reports nothing on a write after a {@code for} loop.
     */
    public void testALoopsWriteBesideATopLevelOne_keepsTheLoopsReport() {
        configureFinalAssignedBy("for (int i = 0; i < 2; i++) this.a = i;\n        this.a = a;");
        assertEquals("javac rejects the loop's write alone", List.of(6), finalAssignmentLines());
        configureFinalAssignedBy("this.a = a;\n        for (int i = 0; i < 2; i++) this.a = i;");
        assertEquals("javac rejects the loop's write", List.of(7), finalAssignmentLines());
    }

    /** Both branches assign the field, so a top-level write after them is refused. */
    public void testATopLevelWriteAfterBothBranches_keepsTheReport() {
        configureFinalAssignedBy("if (a > 0) this.a = 1;\n        else this.a = 2;\n        this.a = 3;");
        assertEquals("javac rejects the third write", List.of(8), finalAssignmentLines());
        configureFinalAssignedBy(
            "switch (a) {\n        case 1 -> this.a = 1;\n        default -> this.a = 2;\n        }\n        this.a = 3;");
        assertEquals("after a switch every arm of which assigns it", List.of(10), finalAssignmentLines());
    }

    /**
     * A branch that assigns the field and returns leaves the rest of the body
     * with the field unassigned, so javac accepts both writes.
     */
    public void testAWriteAfterABranchThatReturns_isCleared() {
        configureFinalAssignedBy("if (a > 0) { this.a = 1; return; }\n        this.a = a;");
        assertEquals("javac accepts both writes", List.of(), finalAssignmentLines());
    }

    /** After {@code this(..)} the field is assigned, and javac refuses a write. */
    public void testAWriteAfterThis_keepsTheReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                Target(int a) {
                    this.a = a;
                }
                Target() {
                    this(1);
                    this.a = 2;
                }
            }
            """);
        assertEquals("javac rejects the write after this(..)", List.of(10), finalAssignmentLines());
    }

    /**
     * Under a {@code factoryMethod} with no written constructor nothing
     * generated assigns the field, so javac leaves its initializer on it. The
     * editor read the field as lifted; an author constructor assigning it
     * still answers for it.
     */
    public void testAFinalUnderAFactoryMethodWithNoConstructor_isNotLifted() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(factoryMethod = "make")
            class Named {
                private final String label = "declared";
                static Named make(String label) { return new Named(); }
            }
            """);
        assertFalse("no generated constructor assigns it",
            GeneratedFieldAccess.liftedBlankFinal(field(file, "label")));
        PsiFile assigned = configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(factoryMethod = "make")
            class Named {
                private final String label = "declared";
                Named(String label) { this.label = label; }
                static Named make(String label) { return new Named(label); }
            }
            """);
        assertTrue("the author constructor answers for it",
            GeneratedFieldAccess.liftedBlankFinal(field(assigned, "label")));
    }

    /**
     * A Lombok constructor annotation beside a written constructor adds a
     * constructor that assigns no initialized {@code final}, so javac keeps the
     * initializer and refuses the written constructor's write. The editor read
     * the written constructors alone, lifted the field and cleared the write.
     */
    public void testALombokConstructorBesideAWrittenOne_keepsTheReport() {
        for (String name : List.of("NoArgsConstructor", "RequiredArgsConstructor", "AllArgsConstructor"))
            myFixture.addClass("package lombok; public @interface " + name + " { }");
        for (String name : List.of("NoArgsConstructor", "RequiredArgsConstructor", "AllArgsConstructor")) {
            configure(
                """
                import dev.simplified.annotations.ClassBuilder;
                @ClassBuilder
                @lombok.%s
                public class Target {
                    private final int a = 128;
                    Target(int a) {
                        this.a = a;
                    }
                }
                """.formatted(name));
            assertEquals("javac rejects the write beside @" + name, List.of(7), finalAssignmentLines());
        }
    }

    /** {@code @Data} implies no constructor beside a written one, so the field is lifted as before. */
    public void testLombokDataBesideAWrittenConstructor_isCleared() {
        myFixture.addClass("package lombok; public @interface Data { }");
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            import lombok.Data;
            @ClassBuilder
            @Data
            public class Target {
                private final int a = 128;
                Target(int a) {
                    this.a = a;
                }
            }
            """);
        assertEquals(List.of(), finalAssignmentLines());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiFile configure(String source) {
        return myFixture.configureByText("Target.java", source);
    }

    /**
     * The named field of the fixture's only class.
     *
     * @param file the configured file
     * @param name the field's name
     * @return the declaration
     */
    private static PsiField field(PsiFile file, String name) {
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        PsiField found = target.findFieldByName(name, false);
        assertNotNull("fixture has no field named " + name, found);
        return found;
    }

    /**
     * The initializer expression of a named field.
     *
     * @param file the configured file
     * @param name the field's name
     * @return the expression the author wrote
     */
    private static PsiElement initializerOf(PsiFile file, String name) {
        PsiElement initializer = field(file, name).getInitializer();
        assertNotNull("field " + name + " has no initializer", initializer);
        return initializer;
    }

    /**
     * Whether the field is reported as possibly unassigned anywhere in the file.
     *
     * @param name the field's name
     * @return whether such an error survived the filter
     */
    private boolean reportsUninitialized(String name) {
        return reportsError("'" + name + "' " + NOT_INITIALIZED);
    }

    /**
     * Whether a write to the field is reported as an assignment to a final.
     *
     * @param name the field's name
     * @return whether such an error survived the filter
     */
    private boolean reportsFinalAssignment(String name) {
        return reportsError(FINAL_ASSIGNMENT + " '" + name + "'");
    }

    /**
     * The one-based lines of every surviving assignment-to-final report, in
     * source order.
     *
     * @return the lines
     */
    private List<Integer> finalAssignmentLines() {
        List<Integer> lines = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting())
            if (describes(info, FINAL_ASSIGNMENT))
                lines.add(myFixture.getEditor().getDocument().getLineNumber(info.getStartOffset()) + 1);
        Collections.sort(lines);
        return lines;
    }

    /**
     * Whether any surviving error carries the message.
     *
     * @param needle the substring to look for
     * @return whether an error describes it
     */
    private boolean reportsError(String needle) {
        return errorCount(needle) > 0;
    }

    /**
     * How many surviving errors carry the message.
     *
     * @param needle the substring to look for
     * @return the count
     */
    private int errorCount(String needle) {
        int count = 0;
        for (HighlightInfo info : myFixture.doHighlighting())
            if (describes(info, needle)) count++;
        return count;
    }

    /**
     * Whether an error carrying the message is anchored inside the element.
     *
     * @param element the source the report has to belong to
     * @param needle the substring to look for
     * @return whether such an error survived the filter
     */
    private boolean reportsErrorWithin(PsiElement element, String needle) {
        TextRange range = element.getTextRange();
        for (HighlightInfo info : myFixture.doHighlighting())
            if (describes(info, needle) && range.containsOffset(info.getStartOffset())) return true;
        return false;
    }

    /**
     * A definite-assignment error ranged over an element.
     *
     * @param anchor the element the platform anchors such a report on
     * @param name the field the message names
     * @return the report
     */
    private static HighlightInfo uninitializedAt(PsiElement anchor, String name) {
        // createUnconditionally rather than create: the latter runs every
        // registered filter itself and answers null when one rejects, which
        // leaves the verdict under test indistinguishable from the others.
        return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
            .range(anchor)
            .descriptionAndTooltip("Field '" + name + "' " + NOT_INITIALIZED)
            .createUnconditionally();
    }

    /**
     * Whether a report is an error carrying the message.
     *
     * @param info the report
     * @param needle the substring to look for
     * @return whether it matches
     */
    private static boolean describes(HighlightInfo info, String needle) {
        if (info.getSeverity() != HighlightSeverity.ERROR) return false;
        String description = info.getDescription();
        return description != null && description.contains(needle);
    }

}
