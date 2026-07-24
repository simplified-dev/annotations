package dev.simplified.classbuilder.inspect;

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
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Formattable.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Formattable { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Negate.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Negate { String value(); }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuildFlag.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface BuildFlag {
                boolean nonNull() default false;
                String pattern() default "";
                int limit() default -1;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/ObtainVia.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface ObtainVia {
                String method() default "";
                String field() default "";
                boolean isStatic() default false;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderDefault.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderDefault {
                boolean value() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderIgnore.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderIgnore { }
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
            import dev.simplified.annotations.Negate;
            public class Foo {
                @Negate("other") int count;
            }
            """);
        assertTrue(hasErrorContaining("@Negate requires a boolean field"));
    }

    public void testNegateOnBoolean_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Negate;
            public class Foo {
                @Negate("enabled") boolean disabled;
            }
            """);
        assertFalse(hasErrorContaining("@Negate"));
    }

    public void testCollectorOnNonCollection_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Collector;
            public class Foo {
                @Collector int count;
            }
            """);
        assertTrue(hasErrorContaining("@Collector requires"));
    }

    public void testBuildRuleFlagLimitOnIntField_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(limit = 10) int count;
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag(limit = ...) only applies to CharSequence, Collection, Map, array, or Optional<String>/Optional<Number> fields"));
    }

    public void testBuildRuleFlagLimit_notApplicableToAllTypes() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(limit = 10) boolean flag;
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag(limit = ...) only applies to CharSequence, Collection, Map, array, or Optional<String>/Optional<Number> fields"));
    }

    public void testBuildRuleFlagPattern_warnedOnIntField() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(pattern = "[a-z]+") int count;
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag(pattern = ...) only applies to CharSequence or Optional<String> fields"));
    }

    // ------------------------------------------------------------------
    // Accessors - an interface target declares its constraints there
    // ------------------------------------------------------------------

    public void testBuildFlagOnInterfaceAccessor_notFlagged() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public interface Shape {
                @BuildFlag(nonNull = true) String name();
            }
            """);
        assertFalse(hasErrorContaining("@BuildFlag"));
    }

    public void testBuildFlagLimitOnNonLimitableAccessor_warned() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public interface Shape {
                @BuildFlag(limit = 10) int sides();
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag(limit = ...) only applies to CharSequence, Collection, Map, array, "
                + "or Optional<String>/Optional<Number> fields"));
    }

    /** Widening the target to METHOD also widened the ways it can do nothing. */
    public void testBuildFlagOnConcreteMethod_warnedAsNoEffect() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(nonNull = true) String name() { return ""; }
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag is only read on an abstract zero-arg accessor of an interface target"));
    }

    public void testBuildFlagOnAccessorWithParameters_warnedAsNoEffect() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public interface Shape {
                @BuildFlag(nonNull = true) String name(int index);
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag is only read on an abstract zero-arg accessor of an interface target"));
    }
}
