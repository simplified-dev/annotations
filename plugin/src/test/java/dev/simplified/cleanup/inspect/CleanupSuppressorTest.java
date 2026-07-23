package dev.simplified.cleanup.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LanguageInspectionSuppressors;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Behaviour of {@link CleanupSuppressor}, including the end-to-end case: a
 * resource-leak warning that fires on an unannotated declaration and is gone
 * once {@code @Cleanup} is written on it.
 *
 * <p>The warning is produced by a stand-in tool declaring the same suppression
 * id the platform's resource inspections declare, rather than by those
 * inspections themselves. What is under test is the suppression path, and a
 * stand-in exercises it for the exact id while staying independent of a bundled
 * tool's own reporting heuristics.
 */
public class CleanupSuppressorTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String LEAK = "resource is never closed";

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        // plugin.xml registration is not read by the fixture, so the extension
        // is contributed for the life of the test instead.
        LanguageInspectionSuppressors.INSTANCE.addExplicitExtension(
            JavaLanguage.INSTANCE, new CleanupSuppressor(), getTestRootDisposable());
        myFixture.enableInspections(new FakeResourceInspection());
        CleanupTestSources.install(myFixture);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    // ------------------------------------------------------------------
    // End to end
    // ------------------------------------------------------------------

    public void testResourceWarningFiresWithoutCleanup() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            public class Demo {
                void run() {
                    Res res = new Res();
                    res.use();
                }
            }
            """);
        assertTrue(hasWarning(LEAK));
    }

    public void testCleanupSilencesTheResourceWarning() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup Res res = new Res();
                    res.use();
                }
            }
            """);
        assertFalse(hasWarning(LEAK));
    }

    // ------------------------------------------------------------------
    // The id test and the declaration test, both of which have to pass
    // ------------------------------------------------------------------

    public void testEveryResourceToolIdIsSuppressed() {
        PsiExpression initializer = initializerOf(
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup Res res = new Res();
                    res.use();
                }
            }
            """);
        CleanupSuppressor suppressor = new CleanupSuppressor();
        for (String toolId : CleanupConstants.RESOURCE_TOOL_IDS)
            assertTrue(toolId, suppressor.isSuppressedFor(initializer, toolId));
    }

    /**
     * The generic {@code resource} id is shared with the comment form, so the
     * declaration is what decides - an unannotated one keeps its warning.
     */
    public void testAnUnannotatedDeclarationIsNotSuppressed() {
        PsiExpression initializer = initializerOf(
            """
            import demo.Res;
            public class Demo {
                void run() {
                    Res res = new Res();
                    res.use();
                }
            }
            """);
        assertFalse(new CleanupSuppressor().isSuppressedFor(initializer, "resource"));
    }

    public void testAnUnrelatedToolIdIsNotSuppressed() {
        PsiExpression initializer = initializerOf(
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup Res res = new Res();
                    res.use();
                }
            }
            """);
        assertFalse(new CleanupSuppressor().isSuppressedFor(initializer, "UnusedAssignment"));
    }

    /**
     * A resource id reported on an element that belongs to no declaration is
     * left alone. This is the answer the id test cannot give on its own, and it
     * is what makes testing the cheap id first safe.
     */
    public void testAResourceIdOutsideAnyDeclarationIsNotSuppressed() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            public class Demo {
                void run() {
                    new Res().use();
                }
            }
            """);
        PsiNewExpression expression =
            PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiNewExpression.class);
        assertNotNull(expression);
        assertFalse(new CleanupSuppressor().isSuppressedFor(expression, "resource"));
    }

    public void testNoSuppressActionsAreOffered() {
        assertEquals(0, new CleanupSuppressor().getSuppressActions(null, "resource").length);
        assertSame(SuppressQuickFix.EMPTY_ARRAY,
            new CleanupSuppressor().getSuppressActions(null, "resource"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiExpression initializerOf(String source) {
        myFixture.configureByText("Demo.java", source);
        PsiLocalVariable variable =
            PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiLocalVariable.class);
        assertNotNull(variable);
        PsiExpression initializer = variable.getInitializer();
        assertNotNull(initializer);
        return initializer;
    }

    private boolean hasWarning(String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo info : highlights) {
            if (info.getSeverity() != HighlightSeverity.WARNING) continue;
            String description = info.getDescription();
            if (description != null && description.contains(needle)) return true;
        }
        return false;
    }

    /**
     * Stand-in for the platform's resource-leak tools. Only the suppression id
     * matters - it is what reaches
     * {@link CleanupSuppressor#isSuppressedFor} - so the tool reports on every
     * instantiation and nothing more.
     */
    private static final class FakeResourceInspection extends LocalInspectionTool {

        @Override
        public @NotNull String getID() {
            return "resource";
        }

        @Override
        public @NotNull String getShortName() {
            return "FakeResource";
        }

        @Override
        public @NotNull String getDisplayName() {
            return "Fake resource leak";
        }

        @Override
        public @NotNull String getGroupDisplayName() {
            return "Simplified Annotations";
        }

        @Override
        public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                       boolean isOnTheFly) {
            return new JavaElementVisitor() {
                @Override
                public void visitNewExpression(@NotNull PsiNewExpression expression) {
                    holder.registerProblem(expression, LEAK);
                }
            };
        }

    }

}
