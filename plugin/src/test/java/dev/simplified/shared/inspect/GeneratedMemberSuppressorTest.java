package dev.simplified.shared.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LanguageInspectionSuppressors;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.codeInspection.nullable.NotNullFieldNotInitializedInspection;
import com.intellij.codeInspection.nullable.NullableStuffInspection;
import com.intellij.codeInspection.varScopeCanBeNarrowed.FieldCanBeLocalInspection;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.siyeh.ig.bugs.MismatchedArrayReadWriteInspection;
import com.siyeh.ig.bugs.MismatchedCollectionQueryUpdateInspection;
import com.siyeh.ig.bugs.MismatchedStringBuilderQueryUpdateInspection;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

/**
 * Behaviour of {@link GeneratedMemberSuppressor}: the inspections that read a
 * field as uninitialized, unread, needlessly a field or holding a container
 * nothing fills stop firing on the fields a generated member initializes and
 * reads, and keep firing everywhere else.
 *
 * <p>The warnings are provoked by the platform's own tools over a real
 * highlighting pass, because which element of a declaration a tool reports on is
 * the tool's choice and not this suppressor's. A suppressor that resolves the
 * wrong element answers {@code false}, the warning is drawn, and a test that
 * hands the suppressor a field of its own choosing sees none of it. The cases
 * that call the suppressor directly cover the anchors and tool ids no bundled
 * tool can be made to draw on demand.
 *
 * <p>Nothing is registered here. The fixture loads the plugin descriptor, so the
 * extension under test is the one the plugin ships.
 *
 * <p>{@code FieldCanBeLocal} is the report with the worst consequence. It is the
 * only one in the set that ships a quick fix, and its fix deletes the field the
 * generated accessor reads, so a test that it stops firing is a test that the
 * fix stops being offered. It is not the report that pins the walk, though: it
 * is drawn on the field's own name, the one element no walk has to leave. The
 * not-null cases are the pair that pins it, since their report is drawn on the
 * nullability annotation two steps below the declaration and stops being
 * suppressed the moment the walk stops short of it.
 */
public class GeneratedMemberSuppressorTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String FIELD_CAN_BE_LOCAL = "FieldCanBeLocal";
    private static final String NULLABLE_PROBLEMS = "NullableProblems";
    private static final String NOT_NULL_NOT_INITIALIZED = "NotNullFieldNotInitialized";
    private static final String UNUSED = "unused";

    /** The wording of the report {@code NotNullFieldNotInitialized} draws. */
    private static final String MUST_BE_INITIALIZED = "must be initialized";

    /** The wording of the report {@code FieldCanBeLocal} draws. */
    private static final String CAN_BE_LOCAL = "converted to a local variable";

    /** The wording of the collection report drawn on a container only read from. */
    private static final String NEVER_POPULATED = "never populated";

    /** The wording of the collection report drawn on a container only written to. */
    private static final String NEVER_QUERIED = "never queried";

    /** The wording of the report {@code MismatchedArrayReadWrite} draws. */
    private static final String NEVER_WRITTEN = "never written to";

    /** The wording of the report {@code MismatchedStringBuilderQueryUpdate} draws. */
    private static final String NEVER_UPDATED = "never updated";

    private final GeneratedMemberSuppressor suppressor = new GeneratedMemberSuppressor();

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(
            new NullableStuffInspection(),
            new NotNullFieldNotInitializedInspection(),
            new FieldCanBeLocalInspection(),
            new MismatchedCollectionQueryUpdateInspection(),
            new MismatchedArrayReadWriteInspection(),
            new MismatchedStringBuilderQueryUpdateInspection());
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

    /** The Java language consults what the plugin descriptor registers. */
    public void testTheSuppressorIsContributedToJava() {
        assertTrue("Java has no GeneratedMemberSuppressor to consult",
            LanguageInspectionSuppressors.INSTANCE.allForLanguage(JavaLanguage.INSTANCE).stream()
                .anyMatch(GeneratedMemberSuppressor.class::isInstance));
    }

    // ------------------------------------------------------------------
    // Not-null fields must be initialized
    // ------------------------------------------------------------------

    /**
     * Pins where the not-null report lands: on the annotation it is about, which
     * is two steps below the declaration it belongs to and is not the field's
     * name.
     */
    public void testTheNotNullReportIsAnchoredOnTheAnnotation() {
        PsiFile file = configure(
            """
            import org.jetbrains.annotations.NotNull;
            public final class Target {
                private final @NotNull String a;
            }
            """);
        HighlightInfo report = firstReport(MUST_BE_INITIALIZED);
        assertNotNull("the not-null report was never drawn", report);
        PsiElement anchor = file.findElementAt(report.getStartOffset());
        assertNotNull("the report is anchored on nothing", anchor);
        PsiField declared = field(file, "a");
        assertNotSame("the report is anchored on the field's name",
            declared.getNameIdentifier(), anchor);
        assertNotNull("the report is anchored outside the annotation",
            PsiTreeUtil.getParentOfType(anchor, PsiAnnotation.class, false));
        assertSame("the report belongs to the declaration", declared,
            PsiTreeUtil.getParentOfType(anchor, PsiField.class, false));
    }

    public void testUnannotatedNotNullFinalFieldIsReportedUninitialized() {
        configure(
            """
            import org.jetbrains.annotations.NotNull;
            public final class Target {
                private final @NotNull String a;
                private final int b;
                private final String c;
            }
            """);
        assertTrue(reports(MUST_BE_INITIALIZED));
    }

    /** The generated constructor initializes it, so the report is answered. */
    public void testRequiredArgsConstructorSilencesTheNotNullReport() {
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
        assertFalse(reports(MUST_BE_INITIALIZED));
    }

    // ------------------------------------------------------------------
    // Field can be converted to a local variable
    // ------------------------------------------------------------------

    public void testUnannotatedFieldIsReportedAsConvertibleToALocal() {
        configure(
            """
            public class Target {
                private int count;
                int run() {
                    count = 1;
                    return count;
                }
            }
            """);
        assertTrue(reports(CAN_BE_LOCAL));
    }

    /** The generated accessor is a second reader, so the field has to stay one. */
    public void testGetterSilencesFieldCanBeLocal() {
        configure(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private int count;
                int run() {
                    count = 1;
                    return count;
                }
            }
            """);
        assertFalse(reports(CAN_BE_LOCAL));
    }

    /** A field-level {@code AccessLevel.NONE} opts out, so the report is right again. */
    public void testGetterNoneKeepsFieldCanBeLocal() {
        configure(
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Getter;
            public class Target {
                @Getter(AccessLevel.NONE)
                private int count;
                int run() {
                    count = 1;
                    return count;
                }
            }
            """);
        assertTrue("AccessLevel.NONE generates no accessor, so nothing reads the field",
            reports(CAN_BE_LOCAL));
    }

    /** A type-level {@code exclude} is the other way one field opts out. */
    public void testExcludedFieldKeepsFieldCanBeLocal() {
        configure(
            """
            import dev.simplified.annotations.Getter;
            @Getter(exclude = "count")
            public class Target {
                private int count;
                private int kept;
                int run() {
                    count = 1;
                    return count;
                }
            }
            """);
        assertTrue(reports(CAN_BE_LOCAL));
    }

    // ------------------------------------------------------------------
    // Contents of a container nothing in source fills or empties
    // ------------------------------------------------------------------

    public void testUnannotatedCollectionFieldIsReportedNeverPopulated() {
        configure(
            """
            import java.util.ArrayList;
            import java.util.List;
            public class Target {
                private final List<String> lines = new ArrayList<>();
                String first() {
                    return lines.get(0);
                }
            }
            """);
        assertTrue(reports(NEVER_POPULATED));
    }

    /**
     * The pair the whole family turns on: one annotation is the only difference
     * from the case above, and the accessor that fills nothing is what hands the
     * list to a caller who can.
     */
    public void testGetterSilencesTheNeverPopulatedReport() {
        configure(
            """
            import dev.simplified.annotations.Getter;
            import java.util.ArrayList;
            import java.util.List;
            @Getter
            public class Target {
                private final List<String> lines = new ArrayList<>();
                String first() {
                    return lines.get(0);
                }
            }
            """);
        assertFalse(reports(NEVER_POPULATED));
    }

    /**
     * The builder fills it, and asking the tool about the field's type is what
     * makes the platform resolve a reference this provider is reached through -
     * so a green result here is also a report the provider serviced without
     * re-entering that resolve.
     */
    public void testClassBuilderSilencesTheNeverPopulatedReport() {
        configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder
            public class Target {
                private final List<String> lines = new ArrayList<>();
                String first() {
                    return lines.get(0);
                }
            }
            """);
        assertFalse(reports(NEVER_POPULATED));
    }

    /** A blank final the generated constructor takes a value for is filled too. */
    public void testRequiredArgsConstructorSilencesTheNeverPopulatedReport() {
        configure(
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            import java.util.List;
            @RequiredArgsConstructor
            public class Target {
                private final List<String> lines;
                String first() {
                    return lines.get(0);
                }
            }
            """);
        assertFalse(reports(NEVER_POPULATED));
    }

    public void testUnannotatedCollectionFieldIsReportedNeverQueried() {
        configure(
            """
            import java.util.ArrayList;
            import java.util.List;
            public class Target {
                private final List<String> lines = new ArrayList<>();
                void add(String value) {
                    lines.add(value);
                }
            }
            """);
        assertTrue(reports(NEVER_QUERIED));
    }

    /**
     * The other direction of the same tool, and the case no constructor answers:
     * the accessor is the reader, and reference search cannot see it.
     */
    public void testGetterSilencesTheNeverQueriedReport() {
        configure(
            """
            import dev.simplified.annotations.Getter;
            import java.util.ArrayList;
            import java.util.List;
            @Getter
            public class Target {
                private final List<String> lines = new ArrayList<>();
                void add(String value) {
                    lines.add(value);
                }
            }
            """);
        assertFalse(reports(NEVER_QUERIED));
    }

    public void testUnannotatedArrayFieldIsReportedNeverWritten() {
        configure(
            """
            public class Target {
                private final String[] rows = new String[4];
                String at(int index) {
                    return rows[index];
                }
            }
            """);
        assertTrue(reports(NEVER_WRITTEN));
    }

    public void testRequiredArgsConstructorSilencesTheArrayReport() {
        configure(
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            @RequiredArgsConstructor
            public class Target {
                private final String[] rows;
                String at(int index) {
                    return rows[index];
                }
            }
            """);
        assertFalse(reports(NEVER_WRITTEN));
    }

    public void testUnannotatedStringBuilderFieldIsReportedNeverUpdated() {
        configure(
            """
            public class Target {
                private final StringBuilder text = new StringBuilder();
                String rendered() {
                    return text.toString();
                }
            }
            """);
        assertTrue(reports(NEVER_UPDATED));
    }

    /**
     * The one in the family a constructor parameter cannot reach: the tool needs
     * an initializer to know the builder is the class's own, so a slot filled
     * from a parameter is a shape it never reports on. The accessor is what
     * makes the report wrong here, since it hands the builder to a caller that
     * can append to it.
     */
    public void testGetterSilencesTheStringBuilderReport() {
        configure(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private final StringBuilder text = new StringBuilder();
                String rendered() {
                    return text.toString();
                }
            }
            """);
        assertFalse(reports(NEVER_UPDATED));
    }

    // ------------------------------------------------------------------
    // The anchors a tool hands in
    // ------------------------------------------------------------------

    /** The annotation a nullability report is about belongs to its field. */
    public void testTheNullabilityAnnotationResolvesToItsField() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            import org.jetbrains.annotations.NotNull;
            @RequiredArgsConstructor
            public class Target {
                private final @NotNull String x;
            }
            """);
        PsiAnnotation notNull = PsiTreeUtil.findChildOfType(field(file, "x"), PsiAnnotation.class);
        assertNotNull("fixture has no annotation on the field", notNull);
        assertTrue(suppressor.isSuppressedFor(notNull, NOT_NULL_NOT_INITIALIZED));
    }

    /** A tool that hands in the declaration itself is answered the same way. */
    public void testGeneratedConstructorSuppressesNullableProblems() {
        PsiField x = field(configure(
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            import org.jetbrains.annotations.NotNull;
            @RequiredArgsConstructor
            public class Target {
                private final @NotNull String x;
            }
            """), "x");
        assertTrue(suppressor.isSuppressedFor(x, NULLABLE_PROBLEMS));
    }

    public void testUnannotatedClassKeepsNullableProblems() {
        PsiField x = field(configure(
            """
            import org.jetbrains.annotations.NotNull;
            public class Target {
                private final @NotNull String x;
            }
            """), "x");
        assertFalse(suppressor.isSuppressedFor(x, NULLABLE_PROBLEMS));
    }

    // ------------------------------------------------------------------
    // What the walk must not reach
    // ------------------------------------------------------------------

    /**
     * A report about an expression the author wrote in an initializer is about
     * that expression. The field it initializes is the one a generated
     * constructor assigns, and answering for it would silence a warning nothing
     * generated has anything to say about.
     */
    public void testAnExpressionInsideAFieldInitializerIsNotSuppressed() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.ClassBuilder;
            import org.jetbrains.annotations.NotNull;
            @ClassBuilder
            public class Target {
                private final @NotNull String a = compute();
                static String compute() {
                    return null;
                }
            }
            """);
        PsiField a = field(file, "a");
        PsiExpression initializer = a.getInitializer();
        assertNotNull("fixture has no initializer", initializer);
        assertTrue("the declaration is the suppressor's",
            suppressor.isSuppressedFor(a, NULLABLE_PROBLEMS));
        assertFalse("an expression the author wrote is not",
            suppressor.isSuppressedFor(initializer, NULLABLE_PROBLEMS));
    }

    /** A body is below every field declaration and belongs to none of them. */
    public void testAnElementInsideAMethodBodyIsNotSuppressed() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private String x;
                void run() {
                    String local = x;
                }
            }
            """);
        PsiLocalVariable local = PsiTreeUtil.findChildOfType(file, PsiLocalVariable.class);
        assertNotNull("fixture has no local variable", local);
        assertFalse(suppressor.isSuppressedFor(local, UNUSED));
    }

    /**
     * A {@code static} field is outside every selection, so an annotated class
     * does not blanket-suppress it.
     */
    public void testStaticFieldKeepsItsReports() {
        PsiField x = field(configure(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private static String x;
                private String kept;
            }
            """), "x");
        assertFalse(suppressor.isSuppressedFor(x, FIELD_CAN_BE_LOCAL));
    }

    /** Nothing here reports on a method, so a method never resolves to a field. */
    public void testNonFieldElementIsNotSuppressed() {
        PsiFile file = configure(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private String x;
                public void run() { }
            }
            """);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        assertFalse(suppressor.isSuppressedFor(target.getMethods()[0], FIELD_CAN_BE_LOCAL));
    }

    // ------------------------------------------------------------------
    // Scope
    // ------------------------------------------------------------------

    public void testSetterSuppressesFieldCanBeLocal() {
        PsiField x = field(configure(
            """
            import dev.simplified.annotations.Setter;
            @Setter
            public class Target {
                private String x;
            }
            """), "x");
        assertTrue(suppressor.isSuppressedFor(x, FIELD_CAN_BE_LOCAL));
    }

    /** A generated accessor is a reader, so the field is not unused. */
    public void testGetterSuppressesUnused() {
        PsiField x = field(configure(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private String x;
            }
            """), "x");
        assertTrue(suppressor.isSuppressedFor(x, UNUSED));
    }

    /** A tool outside both sets is never this suppressor's to answer. */
    public void testUnrelatedToolIsNeverSuppressed() {
        PsiField x = field(configure(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private String x;
            }
            """), "x");
        assertFalse(suppressor.isSuppressedFor(x, "SillyAssignment"));
    }

    public void testNoSuppressActionsAreOffered() {
        assertSame(SuppressQuickFix.EMPTY_ARRAY,
            suppressor.getSuppressActions(null, FIELD_CAN_BE_LOCAL));
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
     * Whether the highlighting pass draws a report carrying the message.
     *
     * @param needle the substring to look for
     * @return whether a report survived suppression
     */
    private boolean reports(String needle) {
        return firstReport(needle) != null;
    }

    /**
     * The first report carrying the message.
     *
     * @param needle the substring to look for
     * @return the report, or {@code null} when none survived suppression
     */
    private HighlightInfo firstReport(String needle) {
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (description != null && description.contains(needle)) return info;
        }
        return null;
    }

}
