package dev.simplified.accessor.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Exercises {@link AccessorInspection}: every processor rejection has to be
 * underlined while typing, and every rejection the processor keeps quiet about
 * has to stay silent here too.
 */
public class AccessorInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new AccessorInspection());
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Getter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Getter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Setter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Setter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
    }

    /** Any severity, so a weak warning is as visible to the assertions as an error. */
    private boolean hasProblemContaining(String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo info : highlights) {
            String description = info.getDescription();
            if (description != null && description.contains(needle)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // @Setter on a final field
    // ------------------------------------------------------------------

    public void testSetterOnFinalField_flagged() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Setter;
            public class Widget {
                @Setter private final String id = "x";
            }
            """);
        assertTrue(hasProblemContaining("@Setter cannot be applied to final field 'id'"));
    }

    public void testSetterOnFinalField_silentFromTypeLevel() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Setter;
            @Setter
            public class Widget {
                private final String id = "x";
                private String label;
            }
            """);
        assertFalse("fanning out over a class is not a claim about the final field",
            hasProblemContaining("@Setter cannot be applied to final field"));
    }

    public void testSetterOnFinalField_silentWhenTheFieldOptsOut() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Setter;
            public class Widget {
                @Setter(AccessLevel.NONE) private final String id = "x";
            }
            """);
        assertFalse(hasProblemContaining("@Setter cannot be applied to final field"));
    }

    public void testSetterOnMutableField_clean() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Setter;
            public class Widget {
                @Setter private String label;
            }
            """);
        assertFalse(hasProblemContaining("@Setter"));
    }

    // ------------------------------------------------------------------
    // @Setter beside @Lazy
    // ------------------------------------------------------------------

    public void testSetterWithLazy_flagged() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.Setter;
            public class Widget {
                @Lazy @Setter private String label = compute();
                private static String compute() { return "x"; }
            }
            """);
        assertTrue(hasProblemContaining("@Setter cannot be combined with @Lazy on 'label'"));
    }

    /** The processor errors here whatever the annotation's origin, and so does this. */
    public void testSetterWithLazy_flaggedFromTypeLevelToo() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.Setter;
            @Setter
            public class Widget {
                @Lazy private String label = compute();
                private static String compute() { return "x"; }
            }
            """);
        assertTrue(hasProblemContaining("@Setter cannot be combined with @Lazy on 'label'"));
    }

    public void testSetterWithLazy_silentWhenExcluded() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.Setter;
            @Setter(exclude = "label")
            public class Widget {
                @Lazy private String label = compute();
                private static String compute() { return "x"; }
            }
            """);
        assertFalse("an excluded field is not written to at all",
            hasProblemContaining("@Setter cannot be combined with @Lazy"));
    }

    // ------------------------------------------------------------------
    // @Getter beside @Lazy
    // ------------------------------------------------------------------

    public void testGetterOnLazyField_reportedAsRedundant() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Getter;
            import dev.simplified.annotations.Lazy;
            public class Widget {
                @Lazy @Getter private String label = compute();
                private static String compute() { return "x"; }
            }
            """);
        assertTrue(hasProblemContaining("@Getter on 'label' is redundant"));
    }

    public void testGetterOnLazyField_silentFromTypeLevel() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Getter;
            import dev.simplified.annotations.Lazy;
            @Getter
            public class Widget {
                @Lazy private String label = compute();
                private String name;
                private static String compute() { return "x"; }
            }
            """);
        assertFalse("the class-level request never named the lazy field",
            hasProblemContaining("is redundant"));
    }

    // ------------------------------------------------------------------
    // Target kind
    // ------------------------------------------------------------------

    public void testRecordTarget_flagged() {
        myFixture.configureByText("Point.java",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public record Point(int x) { }
            """);
        assertTrue(hasProblemContaining(
            "@Getter / @Setter are only supported on classes and enums - Point is a record"));
    }

    public void testInterfaceTarget_flagged() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public interface Shape { }
            """);
        assertTrue(hasProblemContaining(
            "@Getter / @Setter are only supported on classes and enums - Shape is an interface"));
    }

    public void testFieldLevelAnnotationInsideInterface_flagged() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.Getter;
            public interface Shape {
                @Getter String NAME = "shape";
            }
            """);
        assertTrue(hasProblemContaining("only supported on classes and enums"));
    }

    public void testClassTarget_clean() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        assertFalse(hasProblemContaining("only supported on classes and enums"));
    }

    public void testEnumTarget_clean() {
        myFixture.configureByText("Suit.java",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public enum Suit {
                HEARTS("red");
                private final String colour;
                Suit(String colour) { this.colour = colour; }
            }
            """);
        assertFalse(hasProblemContaining("only supported on classes and enums"));
    }

    // ------------------------------------------------------------------
    // name patterns
    // ------------------------------------------------------------------

    public void testNameWithoutPlaceholder_flagged() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter(name = "value") private String label;
            }
            """);
        assertTrue(hasProblemContaining("must contain the '{}' placeholder"));
    }

    /** Worse on the type, where one pattern names every field's accessor. */
    public void testTypeLevelNameWithoutPlaceholder_flagged() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Getter;
            @Getter(name = "value")
            public class Widget {
                private String label;
                private String name;
            }
            """);
        assertTrue(hasProblemContaining("must contain the '{}' placeholder"));
    }

    public void testNameSetToTheSuppressionSentinel_flagged() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Setter;
            public class Widget {
                @Setter(name = "-") private String label;
            }
            """);
        assertTrue(hasProblemContaining("write AccessLevel.NONE to generate nothing"));
    }

    public void testNameWithIllegalCharacter_flagged() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter(name = "read-{}") private String label;
            }
            """);
        assertTrue(hasProblemContaining("expands to an invalid Java identifier"));
    }

    public void testValidName_clean() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter(name = "read{}") private String label;
            }
            """);
        assertFalse(hasProblemContaining("Naming pattern"));
    }

    public void testUnwrittenName_clean() {
        myFixture.configureByText("Widget.java",
            """
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter private String label;
            }
            """);
        assertFalse(hasProblemContaining("Naming pattern"));
    }

}
