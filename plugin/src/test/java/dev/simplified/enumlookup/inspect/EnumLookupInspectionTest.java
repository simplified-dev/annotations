package dev.simplified.enumlookup.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class EnumLookupInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections((Class<? extends LocalInspectionTool>) EnumLookupInspection.class);
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

    public void testEnumLookupOnClass_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            @EnumLookup
            public class Foo {}
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@EnumLookup is only supported on enum types"));
    }

    public void testEnumLookupOnEnum_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            @EnumLookup
            public enum Foo { A, B }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR,
            "@EnumLookup is only supported on enum types"));
    }

    public void testIgnoreCaseOnNonString_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1);
                @KeyField(ignoreCase = true) private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WARNING,
            "@KeyField(ignoreCase = true) has no effect on a field that is not a String"));
    }

    public void testIgnoreCaseOnString_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A("en-US");
                @KeyField(ignoreCase = true) private final String tag;
                Foo(String tag) { this.tag = tag; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING,
            "@KeyField(ignoreCase = true) has no effect"));
    }

    public void testHandRolledCachedValues_flagged() {
        // The processor refuses the enum for this, so the editor has to say it
        // too - otherwise the build fails on a line the editor called clean.
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            @EnumLookup
            public enum Foo {
                A, B;
                private static final Foo[] CACHED_VALUES = values();
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@EnumLookup generates a field named 'CACHED_VALUES'"));
    }

    public void testHandRolledKeyCache_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1);
                @KeyField private final int code;
                private static final int[] CACHED_KEYS_code = new int[1];
                Foo(int code) { this.code = code; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@EnumLookup generates a field named 'CACHED_KEYS_code'"));
    }

    public void testUnrelatedStaticField_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            @EnumLookup
            public enum Foo {
                A, B;
                private static final String LABEL = "x";
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR,
            "@EnumLookup generates a field named"));
    }

    public void testKeyFieldOnStaticField_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                ;
                @KeyField private static final int code = 0;
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@KeyField is not allowed on static fields"));
    }

    public void testKeyFieldWithoutEnumLookup_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.KeyField;
            public enum Foo {
                A(1);
                @KeyField private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WARNING,
            "@KeyField has no effect"));
    }

    public void testStrictNullKeysOnPrimitive_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1);
                @KeyField(strictNullKeys = true) private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WARNING,
            "@KeyField(strictNullKeys = true) has no effect on a primitive"));
    }

    public void testStrictNullKeysOnReference_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A("x");
                @KeyField(strictNullKeys = true) private final String slug;
                Foo(String slug) { this.slug = slug; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING,
            "strictNullKeys"));
    }

    public void testInvalidMethodName_lowercase_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1);
                @KeyField(methodName = "byCode") private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "methodName"));
    }

    public void testInvalidMethodName_notIdentifier_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1);
                @KeyField(methodName = "Code 1") private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "methodName"));
    }

    public void testValidMethodName_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1);
                @KeyField(methodName = "Id") private final int code;
                Foo(int code) { this.code = code; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "methodName"));
    }

    public void testSignatureCollision_flagged() {
        // Two int @KeyFields with the same effective suffix would produce
        // identical of<Suffix>(int) signatures.
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Foo {
                A(1, 2);
                @KeyField(methodName = "Code") private final int x;
                @KeyField(methodName = "Code") private final int y;
                Foo(int x, int y) { this.x = x; this.y = y; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "colliding signature"));
    }
}
