package dev.sbs.classbuilder.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * The IntelliJ test fixture this platform uses does not include mock
 * {@code java.lang.String}, so test fixtures use primitives throughout
 * to sidestep {@code Cannot resolve 'String'} noise in highlighting diffs.
 */
public class ClassBuilderBootstrapInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections((Class<? extends LocalInspectionTool>) ClassBuilderBootstrapInspection.class);
        addAnnotationSource();
    }

    private void addAnnotationSource() {
        myFixture.addFileToProject("dev/sbs/annotation/ClassBuilder.java",
            """
            package dev.sbs.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder {
                String builderName() default "Builder";
                String builderMethodName() default "builder";
                String fromMethodName() default "from";
                String toBuilderMethodName() default "mutate";
                boolean generateBuilder() default true;
                boolean generateFrom() default true;
                boolean generateMutate() default true;
            }
            """);
    }

    private boolean hasBootstrapError(List<HighlightInfo> highlights) {
        for (HighlightInfo h : highlights) {
            if (h.getSeverity() != HighlightSeverity.ERROR) continue;
            String desc = h.getDescription();
            if (desc != null && desc.contains("bootstrap")) return true;
        }
        return false;
    }

    public void testMissingAllBootstrapMethods_flags() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Foo {
                private final int count;
                public Foo(int count) { this.count = count; }
            }
            """);
        assertTrue("Expected bootstrap missing error", hasBootstrapError(myFixture.doHighlighting()));
    }

    public void testAllBootstrapMethodsPresent_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Foo {
                private final int count;
                public Foo(int count) { this.count = count; }
                public static FooBuilder builder() { return null; }
                public static FooBuilder from(Foo instance) { return null; }
                public FooBuilder mutate() { return null; }
            }
            class FooBuilder {}
            """);
        assertFalse("Should be clean", hasBootstrapError(myFixture.doHighlighting()));
    }

    public void testCustomMethodNames_honored() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder(builderMethodName = "newBuilder", fromMethodName = "of", toBuilderMethodName = "toBuilder")
            public class Foo {
                private final int count;
                public Foo(int count) { this.count = count; }
                public static FooBuilder newBuilder() { return null; }
                public static FooBuilder of(Foo instance) { return null; }
                public FooBuilder toBuilder() { return null; }
            }
            class FooBuilder {}
            """);
        assertFalse("Custom names should be recognised",
            hasBootstrapError(myFixture.doHighlighting()));
    }

    public void testMissingCustomName_flags() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder(builderMethodName = "newBuilder")
            public class Foo {
                private final int count;
                public Foo(int count) { this.count = count; }
                public static FooBuilder builder() { return null; }
                public static FooBuilder from(Foo instance) { return null; }
                public FooBuilder mutate() { return null; }
            }
            class FooBuilder {}
            """);
        assertTrue("builder() alone doesn't satisfy custom newBuilder()",
            hasBootstrapError(myFixture.doHighlighting()));
    }

    public void testSuppressedGeneration_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder(generateFrom = false, generateMutate = false)
            public class Foo {
                private final int count;
                public Foo(int count) { this.count = count; }
                public static FooBuilder builder() { return null; }
            }
            class FooBuilder {}
            """);
        assertFalse("Suppressed bootstrap methods should not be required",
            hasBootstrapError(myFixture.doHighlighting()));
    }

    public void testInterfaceTarget_skipped() {
        myFixture.configureByText("Bar.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public interface Bar {}
            """);
        assertFalse("Interface targets deferred to a later phase",
            hasBootstrapError(myFixture.doHighlighting()));
    }

    public void testQuickFix_available() {
        myFixture.configureByText("Foo.java",
            """
            import dev.sbs.annotation.ClassBuilder;
            @ClassBuilder
            public class Foo {
                private final int count;
                public Foo(int count) { this.count = count; }
            }
            """);
        myFixture.doHighlighting();
        boolean hasFix = myFixture.getAllQuickFixes().stream()
            .anyMatch(f -> f.getText().toLowerCase().contains("bootstrap")
                || f.getFamilyName().toLowerCase().contains("bootstrap"));
        assertTrue("Expected an Add-bootstrap quick-fix to be offered", hasFix);
    }

}
