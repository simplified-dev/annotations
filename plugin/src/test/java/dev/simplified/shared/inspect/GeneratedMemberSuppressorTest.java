package dev.simplified.shared.inspect;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/**
 * Behaviour of {@link GeneratedMemberSuppressor}: a field a generated member
 * initializes or reads stops drawing the inspections that say otherwise, and
 * every other field keeps them.
 *
 * <p>{@code FieldCanBeLocal} carries the weight here. It is the only report in
 * the set that ships a quick fix, and its fix deletes the field the generated
 * accessor reads - so a test that it stops firing is a test that the fix stops
 * being offered.
 */
public class GeneratedMemberSuppressorTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String FIELD_CAN_BE_LOCAL = "FieldCanBeLocal";
    private static final String NULLABLE_PROBLEMS = "NullableProblems";
    private static final String UNUSED = "unused";

    private final GeneratedMemberSuppressor suppressor = new GeneratedMemberSuppressor();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        GeneratedMemberTestSources.install(myFixture);
    }

    // ------------------------------------------------------------------
    // Not-null fields must be initialized
    // ------------------------------------------------------------------

    public void testGeneratedConstructorSuppressesNullableProblems() {
        PsiField x = field(
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            import org.jetbrains.annotations.NotNull;
            @RequiredArgsConstructor
            public class Target {
                private final @NotNull String x;
            }
            """, "x");
        assertTrue(suppressor.isSuppressedFor(x, NULLABLE_PROBLEMS));
    }

    public void testUnannotatedClassKeepsNullableProblems() {
        PsiField x = field(
            """
            import org.jetbrains.annotations.NotNull;
            public class Target {
                private final @NotNull String x;
            }
            """, "x");
        assertFalse(suppressor.isSuppressedFor(x, NULLABLE_PROBLEMS));
    }

    // ------------------------------------------------------------------
    // Field can be converted to a local variable
    // ------------------------------------------------------------------

    /** The report whose quick fix deletes the field the generated getter reads. */
    public void testGetterSuppressesFieldCanBeLocal() {
        PsiField x = field(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private String x;
            }
            """, "x");
        assertTrue(suppressor.isSuppressedFor(x, FIELD_CAN_BE_LOCAL));
    }

    /** A field-level {@code AccessLevel.NONE} opts out, so the report is right again. */
    public void testGetterNoneKeepsFieldCanBeLocal() {
        PsiField x = field(
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Getter;
            public class Target {
                @Getter(AccessLevel.NONE)
                private String x;
            }
            """, "x");
        assertFalse("AccessLevel.NONE generates no accessor, so nothing reads the field",
            suppressor.isSuppressedFor(x, FIELD_CAN_BE_LOCAL));
    }

    /** A type-level {@code exclude} is the other way one field opts out. */
    public void testExcludedFieldKeepsFieldCanBeLocal() {
        PsiField x = field(
            """
            import dev.simplified.annotations.Getter;
            @Getter(exclude = "x")
            public class Target {
                private String x;
                private String kept;
            }
            """, "x");
        assertFalse(suppressor.isSuppressedFor(x, FIELD_CAN_BE_LOCAL));
    }

    public void testSetterSuppressesFieldCanBeLocal() {
        PsiField x = field(
            """
            import dev.simplified.annotations.Setter;
            @Setter
            public class Target {
                private String x;
            }
            """, "x");
        assertTrue(suppressor.isSuppressedFor(x, FIELD_CAN_BE_LOCAL));
    }

    public void testUnannotatedClassKeepsFieldCanBeLocal() {
        PsiField x = field(
            """
            public class Target {
                private String x;
            }
            """, "x");
        assertFalse(suppressor.isSuppressedFor(x, FIELD_CAN_BE_LOCAL));
    }

    // ------------------------------------------------------------------
    // Scope
    // ------------------------------------------------------------------

    /** A generated accessor is a reader, so the field is not unused. */
    public void testGetterSuppressesUnused() {
        PsiField x = field(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private String x;
            }
            """, "x");
        assertTrue(suppressor.isSuppressedFor(x, UNUSED));
    }

    /** A tool outside both sets is never this suppressor's to answer. */
    public void testUnrelatedToolIsNeverSuppressed() {
        PsiField x = field(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private String x;
            }
            """, "x");
        assertFalse(suppressor.isSuppressedFor(x, "SillyAssignment"));
    }

    /**
     * A {@code static} field is outside every selection, so an annotated class
     * does not blanket-suppress it.
     */
    public void testStaticFieldKeepsItsReports() {
        PsiField x = field(
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Target {
                private static String x;
                private String kept;
            }
            """, "x");
        assertFalse(suppressor.isSuppressedFor(x, FIELD_CAN_BE_LOCAL));
    }

    /** Nothing here reports on a method, so a method never resolves to a field. */
    public void testNonFieldElementIsNotSuppressed() {
        PsiFile file = myFixture.configureByText("Target.java",
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
    // Helpers
    // ------------------------------------------------------------------

    private PsiField field(String source, String name) {
        PsiFile file = myFixture.configureByText("Target.java", source);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        PsiField found = target.findFieldByName(name, false);
        assertNotNull("fixture has no field named " + name, found);
        return found;
    }

}
