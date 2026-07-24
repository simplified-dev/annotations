package dev.simplified.cleanup.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Edit-time behaviour of {@link CleanupInspection}, covering each declaration
 * shape the generated try-with-resources cannot be built from.
 */
public class CleanupInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(new CleanupInspection());
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

    private boolean hasHighlightContaining(HighlightSeverity severity, String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo info : highlights) {
            if (info.getSeverity() != severity) continue;
            String description = info.getDescription();
            if (description != null && description.contains(needle)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // The shapes that are accepted
    // ------------------------------------------------------------------

    public void testClosedResourceWithATailIsClean() {
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
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "@Cleanup"));
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "@Cleanup"));
    }

    /** The last statement of a block is the empty-tail case, and it is legal. */
    public void testDeclarationAsTheLastStatementIsClean() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup Res res = new Res();
                }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "@Cleanup"));
    }

    public void testUnannotatedDeclarationIsClean() {
        myFixture.configureByText("Demo.java",
            """
            public class Demo {
                void run() {
                    String name;
                    name = "x";
                }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "@Cleanup"));
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "@Cleanup"));
    }

    // ------------------------------------------------------------------
    // No initializer
    // ------------------------------------------------------------------

    public void testDeclarationWithoutAnInitializerIsFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup Res res;
                }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "needs an initializer"));
    }

    /**
     * The missing initializer is the only complaint - a declaration with nothing
     * in it has no assignment to check for finality either.
     */
    public void testMissingInitializerSuppressesTheOtherChecks() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup String name;
                    name = "x";
                }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "needs an initializer"));
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "requires an AutoCloseable"));
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "effectively final"));
    }

    // ------------------------------------------------------------------
    // Not AutoCloseable
    // ------------------------------------------------------------------

    public void testNonCloseableTypeIsFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup String name = "x";
                    name.length();
                }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "requires an AutoCloseable"));
    }

    public void testPrimitiveTypeIsFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup int count = 1;
                    System.out.println(count);
                }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "requires an AutoCloseable"));
    }

    /** An unresolved type is mid-edit, not a misuse of the annotation. */
    public void testUnresolvedTypeIsNotFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup Missing thing = null;
                    System.out.println(thing);
                }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "requires an AutoCloseable"));
    }

    // ------------------------------------------------------------------
    // Loop variables
    // ------------------------------------------------------------------

    public void testForInitializerIsFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    for (@Cleanup Res res = new Res(); res.open(); ) {
                        res.use();
                    }
                }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "'for' initializer"));
    }

    public void testForeachVariableIsFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run(Res[] all) {
                    for (@Cleanup Res res : all) {
                        res.use();
                    }
                }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "'for'-each variable"));
    }

    // ------------------------------------------------------------------
    // Effectively final
    // ------------------------------------------------------------------

    public void testReassignedVariableIsFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    @Cleanup Res res = new Res();
                    res = new Res();
                    res.use();
                }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WARNING, "effectively final"));
    }

    /** A field of the same name is a different variable and must not count. */
    public void testAssignmentToASameNamedFieldIsNotFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                private Res other;
                void run() {
                    @Cleanup Res res = new Res();
                    this.other = new Res();
                    res.use();
                }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "effectively final"));
    }

    // ------------------------------------------------------------------
    // Already a resource
    // ------------------------------------------------------------------

    public void testResourceVariableIsFlagged() {
        myFixture.configureByText("Demo.java",
            """
            import demo.Res;
            import dev.simplified.annotations.Cleanup;
            public class Demo {
                void run() {
                    try (@Cleanup Res res = new Res()) {
                        res.use();
                    }
                }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WARNING, "already closed by that try"));
    }

}
