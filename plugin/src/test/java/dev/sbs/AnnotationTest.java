package dev.sbs;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.sbs.resourcepath.ResourcePathInspection;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inspection-level tests for {@link ResourcePathInspection}.
 *
 * <p>Originally these tests called {@code myFixture.checkHighlighting(...)}
 * which validates the FULL highlight set against embedded {@code <error>}
 * markers - meaning any unrelated highlight (such as the test framework's
 * "Cannot resolve symbol 'String'" noise from a missing mock JDK in the
 * platform 232 test framework descriptors) failed the assertion. The
 * tests are now scoped to the inspection under test by filtering errors
 * to the {@code Resource} family, which is what they always intended.
 */
public class AnnotationTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(ResourcePathInspection.class);

        // Add @ResourcePath annotation source so the test project's PSI can resolve it.
        myFixture.addFileToProject("dev/sbs/annotation/ResourcePath.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            public @interface ResourcePath {
                String base() default "";
            }
            """);

        // Add a resource file that the valid-path tests reference.
        myFixture.addFileToProject("META-INF/plugin.xml", "<idea-plugin/>");
    }

    private List<HighlightInfo> resourcePathErrors() {
        return myFixture.doHighlighting(HighlightSeverity.ERROR).stream()
            .filter(i -> i.getDescription() != null
                && (i.getDescription().contains("Resource")
                    || i.getDescription().contains("resource")))
            .toList();
    }

    /** A valid full path should produce no inspection error. */
    public void testValidFullPath_noError() {
        myFixture.configureByText("Test.java",
            """
            import dev.sbs.annotation.ResourcePath;
            class Test {
                @ResourcePath
                private final String path = "META-INF/plugin.xml";
            }
            """);
        List<HighlightInfo> errors = resourcePathErrors();
        assertTrue("expected no resource-path errors; got " + errors, errors.isEmpty());
    }

    /** A valid base + relative path should produce no inspection error. */
    public void testValidBaseAndRelativePath_noError() {
        myFixture.configureByText("Test.java",
            """
            import dev.sbs.annotation.ResourcePath;
            class Test {
                @ResourcePath(base = "META-INF")
                private final String path = "plugin.xml";
            }
            """);
        List<HighlightInfo> errors = resourcePathErrors();
        assertTrue("expected no resource-path errors; got " + errors, errors.isEmpty());
    }

    /** A path that does not exist should be flagged as an error. */
    public void testMissingPath_hasError() {
        myFixture.configureByText("Test.java",
            """
            import dev.sbs.annotation.ResourcePath;
            class Test {
                @ResourcePath
                private final String path = "META-INF/nonexistent.xml";
            }
            """);
        List<HighlightInfo> errors = resourcePathErrors();
        assertEquals("expected exactly one missing-resource error; got " + errors,
            1, errors.size());
        assertTrue("error should mention the missing path; got " + errors.get(0).getDescription(),
            errors.get(0).getDescription().contains("nonexistent.xml"));
    }

    /** A valid path on a method parameter should produce no inspection error. */
    public void testValidPathOnParameter_noError() {
        myFixture.configureByText("Test.java",
            """
            import dev.sbs.annotation.ResourcePath;
            class Test {
                void load(@ResourcePath String path) {}
                void use() { load("META-INF/plugin.xml"); }
            }
            """);
        List<HighlightInfo> errors = resourcePathErrors();
        assertTrue("expected no resource-path errors; got " + errors, errors.isEmpty());
    }

}
