package dev.simplified.classbuilder.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
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
 * The editor's account of entry points skipped beside a declared builder.
 *
 * <p>Every entry point instantiates the builder, and a declared builder's
 * constructors are the author's, so one declaring no constructor of the arity
 * the entry points pass leaves them with nothing to call. The processor skips
 * them with a note and the editor withholds them from completion; before this
 * fired, the note was the only account of why {@code builder()} was missing.
 * The cases pin where it fires, that it fires nowhere else, and that its text
 * is the processor's note.
 */
public class DeclaredBuilderSkipsEntryPointsInspectionTest extends BasePlatformTestCase {

    private static final String ALL_THREE_SKIPPED = "@ClassBuilder merged into 'Builder' but every "
        + "constructor it declares takes parameters, so 'builder', 'from' and 'mutate' were not added - "
        + "declare a no-argument constructor or write them";

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return LightJavaCodeInsightFixtureTestCase.JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(DeclaredBuilderSkipsEntryPointsInspection.class);
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder {
                BuilderNames builder() default @BuilderNames;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                boolean validate() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
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
        myFixture.addFileToProject("dev/simplified/annotations/BuilderSeed.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.PARAMETER)
            public @interface BuilderSeed { }
            """);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    /**
     * A declared builder whose constructors all take parameters leaves the three
     * entry points nothing to call, so the processor skips them with a note -
     * and the warning is that note, word for word.
     */
    public void testADeclaredBuilderWhoseConstructorsAllTakeParameters_isWarned() {
        myFixture.configureByText("Seeded.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Seeded {
                private String name;
                public static class Builder {
                    private final String origin;
                    public Builder(String origin) { this.origin = origin; }
                }
            }
            """);
        assertEquals(ALL_THREE_SKIPPED, theOnlyWarning());
    }

    /**
     * A no-argument constructor declaring a throws clause serves no entry
     * point, so the processor skips all three with a note naming why. The
     * editor offered them and said nothing, while javac reported the unhandled
     * exception on the class line.
     */
    public void testADeclaredBuilderWhoseNoArgConstructorThrows_isWarned() {
        myFixture.configureByText("Conn.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Conn {
                private String host;
                public static class Builder {
                    Builder() throws java.io.IOException { }
                }
            }
            """);
        assertEquals("@ClassBuilder merged into 'Builder' but its no-argument constructor declares a "
            + "throws clause, so 'builder', 'from' and 'mutate' were not added - declare one that throws "
            + "nothing or write them", theOnlyWarning());
    }

    /** A no-argument constructor beside a parameterised one serves the entry points. */
    public void testADeclaredBuilderWithANoArgumentConstructor_isNotWarned() {
        myFixture.configureByText("Both.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Both {
                private String name;
                public static class Builder {
                    public Builder() { }
                    public Builder(String name) { this.name = name; }
                }
            }
            """);
        assertEquals("a no-argument constructor serves them: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    /** A declared builder declaring no constructor keeps javac's default, which serves them. */
    public void testADeclaredBuilderDeclaringNoConstructor_isNotWarned() {
        myFixture.configureByText("Untouched.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Untouched {
                private String name;
                public static class Builder {
                    public Builder apply(Runnable task) { return this; }
                }
            }
            """);
        assertEquals("the default constructor serves them: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    public void testWithNoDeclaredBuilder_isNotWarned() {
        myFixture.configureByText("Plain.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Plain {
                private String name;
            }
            """);
        assertEquals("a generated builder always has its constructor: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    /** An unrelated nested class is not the builder, whatever its constructors take. */
    public void testAnUnrelatedNestedClass_isNotWarned() {
        myFixture.configureByText("Holder.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Holder {
                private String name;
                public static class Helper {
                    public Helper(String name) { }
                }
            }
            """);
        assertEquals("only the builder's own name counts: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    /**
     * An entry point named {@code NONE} is not emitted, so it is not skipped
     * either, and the warning names the ones that are. Spelled as the literal
     * the constant holds, which is what the editor's name reader recognises.
     */
    public void testWithFromNamedNone_namesOnlyTheOtherTwo() {
        myFixture.configureByText("Partial.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(from = "-"))
            public class Partial {
                private String name;
                public static class Builder {
                    public Builder(String name) { }
                }
            }
            """);
        assertEquals("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder' and 'mutate' were not added - declare a no-argument "
                + "constructor or write them",
            theOnlyWarning());
    }

    /** With every entry point named {@code NONE} nothing is skipped, and nothing is said. */
    public void testWithEveryEntryPointNamedNone_isNotWarned() {
        myFixture.configureByText("Closed.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(builder = "-", from = "-", toBuilder = "-"))
            public class Closed {
                private String name;
                public static class Builder {
                    public Builder(String name) { }
                }
            }
            """);
        assertEquals("nothing is emitted, so nothing is skipped: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    /**
     * A seeded constructor's {@code builder(seed)} passes the seed to the
     * builder's constructor, so a declared builder taking none leaves it
     * nothing to call, and the warning names the constructor it needed.
     */
    public void testOnASeededConstructorWhoseBuilderTakesNoSeed_isWarned() {
        myFixture.configureByText("Ticket.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Ticket {
                private final String origin;
                private final String item;
                @ClassBuilder
                Ticket(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public static class Builder {
                    public Builder() { this.origin = "desk"; }
                }
            }
            """);
        assertEquals("@ClassBuilder merged into 'Builder' but none of its constructors takes the seed "
                + "'builder' passes, so 'builder' was not added - declare a constructor taking "
                + "(origin) or write it",
            theOnlyWarning());
    }

    /** A declared constructor taking exactly the seed serves the seeded entry point. */
    public void testOnASeededConstructorWhoseBuilderTakesTheSeed_isNotWarned() {
        myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String origin;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public static class Builder {
                    public Builder(String origin) { this.origin = origin; }
                }
            }
            """);
        assertEquals("one constructor takes the seed: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    /**
     * An unseeded constructor target's one entry point passes nothing, so a
     * declared builder whose constructors all take parameters skips it - and
     * only it, {@code from} and {@code mutate} never being emitted there.
     */
    public void testOnAnUnseededConstructorWhoseBuilderTakesParameters_isWarned() {
        myFixture.configureByText("Action.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public class Action {
                private final String key;
                @ClassBuilder
                Action(String key) { this.key = key; }
                public static class Builder {
                    public Builder(String key) { this.key = key; }
                }
            }
            """);
        assertEquals("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder' was not added - declare a no-argument constructor or write it",
            theOnlyWarning());
    }

    /**
     * A factory's entry point is emitted whatever the class around it is, so an
     * abstract enclosing type is no reason to stay silent.
     */
    public void testOnAFactoryInsideAnAbstractClass_isWarned() {
        myFixture.configureByText("Shapes.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public abstract class Shapes {
                @ClassBuilder
                public static String label(String text) { return text; }
                public static class Builder {
                    public Builder(String text) { }
                }
            }
            """);
        assertEquals("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder' was not added - declare a no-argument constructor or write it",
            theOnlyWarning());
    }

    /** A concrete link's entry points instantiate its declared builder as well. */
    public void testOnAConcreteLinkWhoseBuilderTakesParameters_isWarned() {
        myFixture.configureByText("Link.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Link extends Base {
                private String extra;
                public static class Builder extends Base.Builder<Link, Builder> {
                    public Builder(String extra) { this.extra = extra; }
                }
            }
            @ClassBuilder
            abstract class Base { private String label; }
            """);
        assertEquals(ALL_THREE_SKIPPED, theOnlyWarning());
    }

    /** An abstract root has no entry points of its own to skip. */
    public void testOnAnAbstractRoot_isNotWarned() {
        myFixture.configureByText("Rooted.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Rooted {
                private String label;
                public abstract static class Builder<T extends Rooted, B extends Builder<T, B>> {
                    protected Builder(String label) { }
                }
            }
            """);
        assertEquals("a root's entry points belong to its concrete links: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    /**
     * A shape the merge refuses is an error, and the processor returns ahead of
     * the entry points without a note, so there is no skip to report beside it.
     */
    public void testARefusedShape_isNotWarned() {
        myFixture.configureByText("Inner.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Inner {
                private String name;
                public class Builder {
                    public Builder(String name) { }
                }
            }
            """);
        assertEquals("the refusal is the shape inspection's error: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    /**
     * An interface's entry points call its sibling builder, never a class nested
     * in the interface body, whatever that class's constructors take.
     */
    public void testAnInterfaceDeclaringANestedBuilder_isNotWarned() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public interface Shape {
                String name();
                class Builder {
                    Builder(String name) { }
                }
            }
            """);
        assertEquals("an interface never instantiates a nested class: " + weakWarningTexts(),
            0, weakWarnings().size());
    }


    private List<HighlightInfo> weakWarnings() {
        List<HighlightInfo> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.WEAK_WARNING) out.add(info);
        }
        return out;
    }

    private List<String> weakWarningTexts() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : weakWarnings()) out.add(info.getDescription());
        return out;
    }

    /**
     * Asserts exactly one weak warning, on the annotation, and answers its text.
     *
     * @return the warning's description
     */
    private String theOnlyWarning() {
        List<HighlightInfo> warnings = weakWarnings();
        assertEquals("exactly one weak warning: " + weakWarningTexts(), 1, warnings.size());
        HighlightInfo only = warnings.get(0);
        assertTrue("reported on the annotation: " + only.getText(),
            only.getText().startsWith("@ClassBuilder"));
        return only.getDescription();
    }

}
