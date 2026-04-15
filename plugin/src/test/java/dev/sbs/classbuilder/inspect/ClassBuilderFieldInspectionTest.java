package dev.sbs.classbuilder.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class ClassBuilderFieldInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections((Class<? extends LocalInspectionTool>) ClassBuilderFieldInspection.class);
        addAnnotationSources();
    }

    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/sbs/annotation/ClassBuilder.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder { }
            """);
        myFixture.addFileToProject("dev/sbs/annotation/Formattable.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Formattable { }
            """);
        myFixture.addFileToProject("dev/sbs/annotation/Negate.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Negate { String value(); }
            """);
        myFixture.addFileToProject("dev/sbs/annotation/Collector.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
            }
            """);
        myFixture.addFileToProject("dev/sbs/annotation/BuildFlag.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
            public @interface BuildFlag {
                boolean nonNull() default false;
                String pattern() default "";
                int limit() default -1;
            }
            """);
    }

    private boolean hasErrorContaining(String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo h : highlights) {
            if (h.getSeverity() != HighlightSeverity.ERROR && h.getSeverity() != HighlightSeverity.WARNING) continue;
            String desc = h.getDescription();
            if (desc != null && desc.contains(needle)) return true;
        }
        return false;
    }

    public void testNegateOnNonBoolean_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.Negate;
            public class Foo {
                @Negate("other") int count;
            }
            """);
        assertTrue(hasErrorContaining("@Negate requires a boolean field"));
    }

    public void testNegateOnBoolean_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.Negate;
            public class Foo {
                @Negate("enabled") boolean disabled;
            }
            """);
        assertFalse(hasErrorContaining("@Negate"));
    }

    public void testCollectorOnNonCollection_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.Collector;
            public class Foo {
                @Collector int count;
            }
            """);
        assertTrue(hasErrorContaining("@Collector requires"));
    }

    public void testBuildFlagLimitOnIntField_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.BuildFlag;
            public class Foo {
                @BuildFlag(limit = 10) int count;
            }
            """);
        assertTrue(hasErrorContaining("@BuildFlag(limit"));
    }

    public void testBuildFlagLimit_notApplicableToAllTypes() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.BuildFlag;
            public class Foo {
                @BuildFlag(limit = 10) boolean flag;
            }
            """);
        assertTrue(hasErrorContaining("@BuildFlag(limit"));
    }
}
