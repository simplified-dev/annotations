package dev.simplified.lazy.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Edit-time behaviour of {@link LazyFieldInspection} over where a
 * {@code @Lazy} field's supplier body comes from.
 *
 * <p>The accepted shapes matter more here than the rejected one. This
 * inspection reports an error, so a shape the processor accepts and the
 * inspection does not is a red mark over source that compiles - the failure
 * mode this plugin exists to prevent, in the direction hardest to ignore.
 */
public class LazyFieldInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String NOTHING_TO_DEFER = "has nothing to defer";

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(new LazyFieldInspection());
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder { }
            """);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    private boolean reportsNothingToDefer() {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo info : highlights) {
            if (info.getSeverity() != HighlightSeverity.ERROR) continue;
            String description = info.getDescription();
            if (description != null && description.contains(NOTHING_TO_DEFER)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Where a supplier body can come from
    // ------------------------------------------------------------------

    public void testFieldInitializerIsAccepted() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Demo {
                @Lazy private final String value = compute();
                private static String compute() { return "x"; }
            }
            """);
        assertFalse(reportsNothingToDefer());
    }

    public void testConstructorAssignmentIsAccepted() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Demo {
                private final String raw;
                @Lazy private final String headers;
                public Demo(String raw) {
                    this.raw = raw;
                    this.headers = parse(this.raw);
                }
                private static String parse(String s) { return s; }
            }
            """);
        assertFalse("a constructor-assigned field is what the processor accepts",
            reportsNothingToDefer());
    }

    /** The unqualified spelling is the same assignment. */
    public void testConstructorAssignmentWithoutThisIsAccepted() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Demo {
                @Lazy private final String label;
                public Demo(String seed) {
                    label = seed.trim();
                }
            }
            """);
        assertFalse(reportsNothingToDefer());
    }

    /** Assigned in only one arm of a branch is still assigned. */
    public void testAssignmentInsideABranchIsAccepted() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Demo {
                @Lazy private final String label;
                public Demo(boolean flag) {
                    if (flag) {
                        this.label = "yes";
                    } else {
                        this.label = "no";
                    }
                }
            }
            """);
        assertFalse(reportsNothingToDefer());
    }

    public void testClassBuilderSuppliesTheValue() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            @ClassBuilder
            public class Demo {
                @Lazy String value;
            }
            """);
        assertFalse(reportsNothingToDefer());
    }

    // ------------------------------------------------------------------
    // The one shape with no source at all
    // ------------------------------------------------------------------

    public void testNeitherInitializerNorAssignmentIsRejected() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Demo {
                @Lazy private String value;
                public Demo() { }
            }
            """);
        assertTrue("nothing supplies the value, so there is nothing to defer",
            reportsNothingToDefer());
    }

    /** An assignment to a different field is not this field's supplier. */
    public void testAnotherFieldsAssignmentIsNotThisFieldsSupplier() {
        myFixture.configureByText("Demo.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Demo {
                private String other;
                @Lazy private String value;
                public Demo(String other) { this.other = other; }
            }
            """);
        assertTrue(reportsNothingToDefer());
    }

}
