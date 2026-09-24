package dev.simplified.classbuilder.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's half of a setter, on a builder the generator writes whole, that
 * meets a {@code java.lang.Object} method it cannot override.
 *
 * <p>javac refused each of these on the target's line and the editor was green.
 * Each case asserts the literal its processor twin in {@code ObjectMethodSetterTest}
 * asserts, on the slot the processor reports on.
 */
public class ObjectMethodSetterInspectionTest extends BasePlatformTestCase {

    /** The per-slot naming that spells a boolean's zero-argument flag setter as the slot's own name. */
    private static final String FLAG_AS_NAME = "@SetterNames(flag = \"{}\")";

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return LightJavaCodeInsightFixtureTestCase.JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections((Class<? extends LocalInspectionTool>) ClassBuilderFieldInspection.class);
        addAnnotationSources();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder {
                BuilderNames builder() default @BuilderNames;
                SetterNames setters() default @SetterNames;
                AccessLevel access() default AccessLevel.PUBLIC;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/SetterNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface SetterNames {
                String INHERIT = "";
                String NONE = "-";
                String set() default INHERIT;
                String flag() default INHERIT;
                String add() default INHERIT;
                String put() default INHERIT;
                String compute() default INHERIT;
                String clear() default INHERIT;
                String remove() default INHERIT;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({})
            public @interface BuilderNames {
                String INHERIT = "";
                String NONE = "-";
                String type() default INHERIT;
                String builder() default INHERIT;
                String build() default INHERIT;
                String from() default INHERIT;
                String toBuilder() default INHERIT;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
                boolean append() default false;
                boolean removable() default false;
                String key() default "";
            }
            """);
    }

    /** The sentence for a setter {@code Object} declares {@code final}. */
    private static String declaredFinal(String signature) {
        return "@ClassBuilder generating 'Builder' finds " + signature + " inherited from java.lang.Object "
            + "declared final, so the generated setter of that signature cannot override it";
    }

    /** The sentence for a setter whose {@code Object} method returns a type the builder is not. */
    private static String returning(String signature, String returnType) {
        return "@ClassBuilder generating 'Builder' finds " + signature + " inherited from java.lang.Object "
            + "returning " + returnType + ", which the generated setter returning Builder cannot override";
    }

    /**
     * Each ERROR highlight of this family in the open file, as
     * {@code [highlighted text]@line description}.
     */
    private List<String> objectMethodErrors() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo h : myFixture.doHighlighting()) {
            if (h.getSeverity() != HighlightSeverity.ERROR || h.getDescription() == null) continue;
            if (!h.getDescription().startsWith("@ClassBuilder generating")) continue;
            int line = myFixture.getEditor().getDocument().getLineNumber(h.getStartOffset()) + 1;
            out.add("[" + h.getText() + "]@" + line + " " + h.getDescription());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Standalone
    // ------------------------------------------------------------------

    /** The typed setter of a {@code long wait} meets {@code Object}'s final {@code wait(long)}. */
    public void testALongSlotNamedWait_isReportedOnTheField() {
        myFixture.configureByText("Waiter.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Waiter {
                long wait;
            }
            """);
        assertEquals(List.of("[wait]@4 " + declaredFinal("wait(long)")), objectMethodErrors());
    }

    /** A boolean's flag setter named {@code notify} meets the final {@code notify()}. */
    public void testABooleanSlotNamedNotify_isReportedForItsFlagSetter() {
        myFixture.configureByText("Bell.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            @ClassBuilder
            public class Bell {
                %s boolean notify;
            }
            """.formatted(FLAG_AS_NAME));
        assertEquals(List.of("[notify]@5 " + declaredFinal("notify()")), objectMethodErrors());
    }

    /** A boolean's flag setter named {@code hashCode} meets {@code hashCode()}, whose {@code int} it is not. */
    public void testABooleanSlotNamedHashCode_isReportedForItsReturnType() {
        myFixture.configureByText("Hashed.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            @ClassBuilder
            public class Hashed {
                %s boolean hashCode;
            }
            """.formatted(FLAG_AS_NAME));
        assertEquals(List.of("[hashCode]@5 " + returning("hashCode()", "int")), objectMethodErrors());
    }

    /** A collector's single-element add named {@code equals} meets {@code equals(Object)}. */
    public void testACollectorAddNamedEquals_isReportedForItsReturnType() {
        myFixture.configureByText("Bag.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import dev.simplified.annotations.SetterNames;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder
            public class Bag {
                @Collector(singular = true, singularMethodName = "equals") @SetterNames(add = "{}")
                List<Object> items = new ArrayList<>();
            }
            """);
        assertEquals(List.of("[items]@9 " + returning("equals(Object)", "boolean")), objectMethodErrors());
    }

    /** {@code clone()} returns {@code Object}, which the builder is, and is overridden legally. */
    public void testABooleanSlotNamedClone_isNotReported() {
        myFixture.configureByText("Cloned.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            @ClassBuilder
            public class Cloned {
                %s boolean clone;
            }
            """.formatted(FLAG_AS_NAME));
        assertEquals(List.of(), objectMethodErrors());
    }

    /**
     * A declared builder is merged into, and its supertypes - {@code Object}
     * among them - are read by the shape inspection, so this one is silent.
     */
    public void testADeclaredBuilder_isLeftToTheShapeInspection() {
        myFixture.configureByText("Waiter.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Waiter {
                long wait;
                public static class Builder { }
            }
            """);
        assertEquals(List.of(), objectMethodErrors());
    }

    // ------------------------------------------------------------------
    // Constructor target and chain roles
    // ------------------------------------------------------------------

    /** On a constructor target the slot is the parameter, and the report sits on its name. */
    public void testAConstructorParameterNamedWait_isReportedOnTheParameter() {
        myFixture.configureByText("Waiter.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Waiter {
                private final long wait;
                @ClassBuilder
                Waiter(long wait) { this.wait = wait; }
            }
            """);
        assertEquals(List.of("[wait]@5 " + declaredFinal("wait(long)")), objectMethodErrors());
    }

    /** An abstract root's self-typed setter meets {@code Object}'s final {@code wait(long)} too. */
    public void testAnAbstractRootsLongSlotNamedWait_isReportedOnTheField() {
        myFixture.configureByText("Timed.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Timed {
                long wait;
            }
            """);
        assertEquals(List.of("[wait]@4 " + declaredFinal("wait(long)")), objectMethodErrors());
    }

    /** An abstract root annotated with a {@code String name} slot, for a link or chained abstract below it. */
    private void addShape() {
        myFixture.addFileToProject("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                String name;
            }
            """);
    }

    /** A concrete link's flag setter named {@code notify} is reported on the link's field. */
    public void testAConcreteLinksFlagSetterNamedNotify_isReportedOnTheField() {
        addShape();
        myFixture.configureByText("Circle.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            @ClassBuilder
            public class Circle extends Shape {
                %s boolean notify;
            }
            """.formatted(FLAG_AS_NAME));
        assertEquals(List.of("[notify]@5 " + declaredFinal("notify()")), objectMethodErrors());
    }

    /** A chained abstract's self-typed flag setter named {@code notifyAll} is reported on its field. */
    public void testAChainedAbstractsFlagSetterNamedNotifyAll_isReportedOnTheField() {
        addShape();
        myFixture.configureByText("Round.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            @ClassBuilder
            public abstract class Round extends Shape {
                %s boolean notifyAll;
            }
            """.formatted(FLAG_AS_NAME));
        assertEquals(List.of("[notifyAll]@5 " + declaredFinal("notifyAll()")), objectMethodErrors());
    }

}
