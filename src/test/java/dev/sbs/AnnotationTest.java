package dev.sbs;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.sbs.resourcepath.ResourcePathInspection;

public class AnnotationTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(ResourcePathInspection.class);

        // Add @ResourcePath annotation source so the test project's PSI can resolve it
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

        // Add a resource file that the valid-path tests reference
        myFixture.addFileToProject("META-INF/plugin.xml", "<idea-plugin/>");
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
        myFixture.checkHighlighting(false, false, false);
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
        myFixture.checkHighlighting(false, false, false);
    }

    /** A path that does not exist should be flagged as an error. */
    public void testMissingPath_hasError() {
        myFixture.configureByText("Test.java",
            """
            import dev.sbs.annotation.ResourcePath;
            class Test {
                @ResourcePath
                private final String path = <error descr="Missing Resource File: META-INF/nonexistent.xml">"META-INF/nonexistent.xml"</error>;
            }
            """);
        myFixture.checkHighlighting(false, false, false);
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
        myFixture.checkHighlighting(false, false, false);
    }

}