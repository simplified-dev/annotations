package dev.sbs.classbuilder.editor;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Verifies that a gutter icon is attached to {@code @ClassBuilder}
 * annotations and not to unrelated annotations.
 */
public class ClassBuilderLineMarkerProviderTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("dev/sbs/annotation/ClassBuilder.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder { }
            """);
    }

    public void testGutterOnClassBuilderAnnotation() {
        myFixture.configureByText("Widget.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Widget {
                String name;
            }
            """);
        myFixture.doHighlighting();
        List<GutterMark> gutters = myFixture.findAllGutters();
        assertTrue("expected at least one gutter; found " + gutters.size(),
            gutters.stream().anyMatch(g -> {
                String t = g.getTooltipText();
                return t != null && t.contains("@ClassBuilder");
            }));
    }

    public void testNoGutterOnUnrelatedAnnotation() {
        myFixture.configureByText("Plain.java",
            """
            @Deprecated
            public class Plain {
                String name;
            }
            """);
        myFixture.doHighlighting();
        List<GutterMark> gutters = myFixture.findAllGutters();
        assertTrue("no ClassBuilder gutter should appear",
            gutters.stream().noneMatch(g -> {
                String t = g.getTooltipText();
                return t != null && t.contains("@ClassBuilder");
            }));
    }

}
