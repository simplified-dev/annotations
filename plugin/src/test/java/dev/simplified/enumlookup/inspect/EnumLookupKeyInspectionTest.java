package dev.simplified.enumlookup.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class EnumLookupKeyInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections((Class<? extends LocalInspectionTool>) EnumLookupKeyInspection.class);
        addAnnotationSources();
    }

    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/simplified/annotations/EnumLookup.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface EnumLookup { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/KeyField.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface KeyField {
                String methodName() default "";
                boolean strictKeys() default false;
                boolean strictNullKeys() default false;
            }
            """);
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

    private int countHighlightsContaining(HighlightSeverity severity, String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        int count = 0;
        for (HighlightInfo h : highlights) {
            if (h.getSeverity() != severity) continue;
            String desc = h.getDescription();
            if (desc != null && desc.contains(needle)) count++;
        }
        return count;
    }

    public void testDuplicateIntKeys_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1), B(1);
                @KeyField(strictKeys = true) private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "Duplicate @KeyField 'code' value 1"));
    }

    public void testDuplicateStringKeys_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A("x"), B("x");
                @KeyField(strictKeys = true) private final String slug;
                Foo(String slug) { this.slug = slug; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "Duplicate @KeyField 'slug' value \"x\""));
    }

    public void testUniqueKeys_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1), B(2);
                @KeyField(strictKeys = true) private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "Duplicate"));
    }

    public void testStrictKeysDisabled_dupesNotFlagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1), B(1);
                @KeyField private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "Duplicate"));
    }

    public void testNullKey_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A("x"), B(null);
                @KeyField(strictNullKeys = true) private final String slug;
                Foo(String slug) { this.slug = slug; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "Null value not allowed for @KeyField 'slug'"));
    }

    public void testStrictNullKeysDisabled_nullsNotFlagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A("x"), B(null);
                @KeyField private final String slug;
                Foo(String slug) { this.slug = slug; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "Null value"));
    }

    public void testStaticFinalConstantReference_resolves() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(Constants.VAL), B(Constants.VAL);
                @KeyField(strictKeys = true) private final int code;
                Foo(int code) { this.code = code; }
                static class Constants { static final int VAL = 7; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "Duplicate @KeyField 'code' value 7"));
    }

    public void testNonResolvableExpression_silent() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A((int) System.currentTimeMillis()), B((int) System.currentTimeMillis());
                @KeyField(strictKeys = true) private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "Duplicate"));
    }

    public void testReorderedConstructorAssignments_stillResolves() {
        // Constructor assigns parameters in non-default order; the inspection
        // must still track param-to-field mapping correctly.
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A("x", 1), B("y", 1);
                @KeyField(strictKeys = true) private final int code;
                @KeyField(methodName = "Slug") private final String slug;
                Foo(String slug, int code) { this.slug = slug; this.code = code; }
            }
            """);
        // Despite the reordering, code values 1 and 1 are duplicates and slug
        // values "x" and "y" are not.
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "Duplicate @KeyField 'code'"));
    }
}
