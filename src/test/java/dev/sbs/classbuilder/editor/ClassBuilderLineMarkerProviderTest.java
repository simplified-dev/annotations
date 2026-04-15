package dev.sbs.classbuilder.editor;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.openapi.util.IconLoader;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.Icon;
import java.util.List;
import java.util.Optional;

/**
 * Verifies that a gutter icon is attached to {@code @ClassBuilder}
 * annotations and not to unrelated annotations, and that the marker is
 * wired to the expected icon resource on disk.
 */
public class ClassBuilderLineMarkerProviderTest extends BasePlatformTestCase {

    /**
     * Path tracked by {@code ClassBuilderLineMarkerProvider}. Duplicated as
     * a constant here on purpose: the test fails loudly if the production
     * code points at a different file, instead of silently following the
     * change. Update both sites together when intentionally swapping the
     * icon.
     */
    private static final String EXPECTED_ICON_PATH = "/icons/classbuilder_generated.svg";

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

    /**
     * The marker's icon must resolve to the file at {@link #EXPECTED_ICON_PATH}.
     * {@link IconLoader#getIcon} caches by path, so loading the expected icon
     * here yields the same instance the provider holds; identity equality
     * confirms the provider is wired to the right resource.
     */
    public void testGutterIconResolvesToExpectedPath() {
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
        Optional<GutterMark> classBuilderMark = gutters.stream()
            .filter(g -> {
                String t = g.getTooltipText();
                return t != null && t.contains("@ClassBuilder");
            })
            .findFirst();
        assertTrue("expected a @ClassBuilder gutter mark", classBuilderMark.isPresent());

        Icon actual = classBuilderMark.get().getIcon();
        Icon expected = IconLoader.getIcon(EXPECTED_ICON_PATH, ClassBuilderLineMarkerProvider.class);
        assertSame("gutter marker must reference " + EXPECTED_ICON_PATH
                + "; provider points at a different resource", expected, actual);
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
