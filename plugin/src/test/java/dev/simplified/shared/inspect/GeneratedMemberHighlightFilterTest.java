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
     * default and strips it from the field, so each write is the field's first.
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
                void reset(int v) {
                    a = v;
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
                void reset(int v) {
                    a = v;
                }
            }
            """);
        assertEquals(0, errorCount(FINAL_ASSIGNMENT));
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
