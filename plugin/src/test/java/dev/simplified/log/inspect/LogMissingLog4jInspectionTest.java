package dev.simplified.log.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Exercises {@link LogInspection}'s classpath check, which needs the one
 * project shape the sibling test cannot provide: log4j2 absent entirely.
 * {@code LogInspectionTest} adds the logger stubs to every fixture it builds,
 * so a project that never receives them has to be its own test case.
 *
 * <p>The check mirrors the processor's. An unresolved logger type reddens every
 * call on the generated field either way; what this adds is the reason.
 */
public class LogMissingLog4jInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections((Class<? extends LocalInspectionTool>) LogInspection.class);
        // Deliberately the annotation only - no org.apache.logging.log4j stubs.
        myFixture.addFileToProject("dev/simplified/annotations/Log.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface Log {
                String topic() default "";
                String name() default "";
                boolean emitGenerated() default true;
            }
            """);
    }

    public void testMissingLog4j_flaggedWithTheArtifactName() {
        myFixture.configureByText("Service.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public class Service { }
            """);

        assertTrue("a module without log4j2 should be told which dependency is missing",
            hasHighlightContaining(HighlightSeverity.ERROR, "org.apache.logging.log4j:log4j-api"));
    }

    /**
     * An illegal target is reported as such rather than as a missing
     * dependency. The target check runs first because the annotation is wrong
     * there whatever the classpath holds.
     */
    public void testIllegalTargetOutranksTheClasspathCheck() {
        myFixture.configureByText("Contract.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public interface Contract { }
            """);

        assertTrue("the target kind should still be the reported problem",
            hasHighlightContaining(HighlightSeverity.ERROR, "only supported on classes and enums"));
        assertFalse("a rejected target should not also draw the classpath error",
            hasHighlightContaining(HighlightSeverity.ERROR, "log4j-api"));
    }

    private boolean hasHighlightContaining(HighlightSeverity severity, String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo h : highlights) {
            if (h.getSeverity() != severity) continue;
            String desc = h.getDescription();
            if (desc != null && desc.contains(needle)) return true;
        }
        return false;
    }
}
