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

    private static final String SEED_SKIPPED = "@ClassBuilder merged into 'Builder' but no single constructor "
        + "it declares takes the seed 'builder' passes as its own type, its box or primitive, a wider primitive "
        + "or Object, so 'builder' was not added - declare a constructor taking (origin) or write it";

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
            + "throws clause naming an exception not known to be unchecked, so 'builder', 'from' and "
            + "'mutate' were not added - declare one throwing only unchecked exceptions or write them",
            theOnlyWarning());
    }

    /**
     * A throws clause naming only known unchecked exceptions leaves the entry
     * points a constructor to call, so the processor emits them and there is no
     * note. The editor warned over any throws clause, as the processor did.
     */
    public void testADeclaredBuilderWhoseNoArgConstructorThrowsOnlyUncheckedExceptions_isNotWarned() {
        myFixture.configureByText("Conn.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Conn {
                private String host;
                public static class Builder {
                    Builder() throws IllegalStateException, java.util.NoSuchElementException { }
                }
            }
            """);
        assertEquals("the entry points are emitted: " + weakWarningTexts(), 0, weakWarnings().size());
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
        assertEquals(SEED_SKIPPED, theOnlyWarning());
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

    /**
     * A static factory inside an interface is an executable target, which merges
     * into the class the interface body declares - so a builder there whose
     * constructors all take parameters leaves {@code builder()} nothing to call,
     * and javac says so in this note. The editor stayed silent, reading the
     * interface around the factory as an interface type target.
     */
    public void testOnAStaticFactoryInAnInterfaceWhoseBuilderTakesParameters_isWarned() {
        myFixture.addFileToProject("Circle.java", "public record Circle(double radius) { }");
        myFixture.configureByText("Shapes.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public interface Shapes {
                @ClassBuilder
                static Circle circle(double radius) { return new Circle(radius); }
                class Builder { Builder(int n) { } }
            }
            """);
        assertEquals("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder' was not added - declare a no-argument constructor or write it",
            theOnlyWarning());
    }

    /**
     * A constructor of the seed count taking another type is not one
     * {@code builder(seed)} can call. javac emitted the entry point against it
     * and failed on the class line; it now skips it with this note.
     */
    public void testOnASeededConstructorWhoseBuilderTakesAnotherTypeAtTheSeedsArity_isWarned() {
        myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String origin;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                public static final class Builder {
                    Builder(int code) { this.origin = "code-" + code; }
                }
            }
            """);
        assertEquals(SEED_SKIPPED, theOnlyWarning());
    }

    /**
     * A primitive seed reaches a constructor taking its box, as javac's own call
     * does, so the entry point is emitted and there is no skip to report. Only
     * equal types were counted, and the warning fired over a call that compiles.
     */
    public void testOnAPrimitiveSeedWhoseBuilderTakesItsBox_isNotWarned() {
        myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final int origin;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed int origin, String item) { this.origin = origin; this.item = item; }
                public static final class Builder {
                    Builder(Integer origin) { this.origin = origin; }
                }
            }
            """);
        assertEquals("javac emits builder(int): " + weakWarningTexts(), 0, weakWarnings().size());
    }

    /**
     * Two constructors reached by widening, neither more specific than the
     * other, leave no single one javac would call, so the entry point is skipped
     * with the note.
     */
    public void testOnSeedsTwoBuilderConstructorsTakeAmbiguously_isWarned() {
        myFixture.configureByText("Grid.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Grid {
                private final int x;
                private final int y;
                private final String label;
                @ClassBuilder
                Grid(@BuilderSeed int x, @BuilderSeed int y, String label) { this.x = x; this.y = y; this.label = label; }
                public static final class Builder {
                    Builder(long x, int y) { this.x = (int) x; this.y = y; }
                    Builder(int x, long y) { this.x = x; this.y = (int) y; }
                }
            }
            """);
        assertEquals("@ClassBuilder merged into 'Builder' but no single constructor it declares takes the 2 "
                + "seeds 'builder' passes as their own types, their boxes or primitives, wider primitives or "
                + "Object, so 'builder' was not added - declare a constructor taking (x, y) or write it",
            theOnlyWarning());
    }

    /**
     * The constructor {@code @AllArgsConstructor} appends onto the declared
     * builder is in the builder javac counts the entry points against, and it
     * takes parameters, so the three are skipped. The editor counted only the
     * author's constructors and stayed silent over a builder javac skips them on.
     */
    public void testADeclaredBuilderWhoseOnlyConstructorAnAllArgsAnnotationAppends_isWarned() {
        addConstructorAnnotations();
        myFixture.configureByText("Held.java",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Held {
                private String name;
                @AllArgsConstructor
                public static class Builder {
                    private String tag;
                }
            }
            """);
        assertEquals(ALL_THREE_SKIPPED, theOnlyWarning());
    }

    /** The same for {@code @RequiredArgsConstructor} over a final field. */
    public void testADeclaredBuilderWhoseOnlyConstructorARequiredArgsAnnotationAppends_isWarned() {
        addConstructorAnnotations();
        myFixture.configureByText("Held.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.RequiredArgsConstructor;
            @ClassBuilder
            public class Held {
                private String name;
                @RequiredArgsConstructor
                public static class Builder {
                    private final String tag;
                }
            }
            """);
        assertEquals(ALL_THREE_SKIPPED, theOnlyWarning());
    }

    /**
     * The no-argument constructor {@code @NoArgsConstructor} appends beside the
     * author's parameterised one serves the entry points, so javac emits them
     * and there is nothing to report.
     */
    public void testANoArgsAnnotationBesideAParameterConstructor_isNotWarned() {
        addConstructorAnnotations();
        myFixture.configureByText("Held.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.NoArgsConstructor;
            @ClassBuilder
            public class Held {
                private String name;
                @NoArgsConstructor
                public static class Builder {
                    public Builder(String tag) { }
                }
            }
            """);
        assertEquals("javac emits the entry points: " + weakWarningTexts(), 0, weakWarnings().size());
    }

    /** At {@code AccessLevel.NONE} the annotation appends nothing, so the author's constructor is all there is. */
    public void testANoArgsAnnotationAtAccessNoneBesideAParameterConstructor_isWarned() {
        addConstructorAnnotations();
        myFixture.configureByText("Held.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.NoArgsConstructor;
            @ClassBuilder
            public class Held {
                private String name;
                @NoArgsConstructor(access = AccessLevel.NONE)
                public static class Builder {
                    public Builder(String tag) { }
                }
            }
            """);
        assertEquals(ALL_THREE_SKIPPED, theOnlyWarning());
    }

    /**
     * On a constructor target, the constructor {@code @AllArgsConstructor}
     * appends over the author's seed field takes the seed, so javac emits
     * {@code builder(origin)} and there is nothing to report.
     */
    public void testOnAConstructorTargetWhoseAllArgsBuilderTakesTheSeed_isNotWarned() {
        addConstructorAnnotations();
        myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String origin;
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, String item) { this.origin = origin; this.item = item; }
                @AllArgsConstructor
                public static final class Builder {
                    private final String origin;
                }
            }
            """);
        assertEquals("javac emits builder(String): " + weakWarningTexts(), 0, weakWarnings().size());
    }

    /**
     * The processor refuses an instance method outright and merges nothing, so
     * there is no skipped entry point to account for.
     */
    public void testOnAnInstanceMethodTheProcessorRefuses_isNotWarned() {
        myFixture.configureByText("Job.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Job {
                private final String name;
                Job(String name) { this.name = name; }
                @ClassBuilder
                public Job copy(String name) { return new Job(name); }
                public static final class Builder {
                    public Builder(String unused) { }
                }
            }
            """);
        assertEquals("javac prints no merge note for a refused member: " + weakWarningTexts(),
            0, weakWarnings().size());
    }

    /** Adds the access enum and the three constructor annotations that append a constructor. */
    private void addConstructorAnnotations() {
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        for (String name : List.of("AllArgsConstructor", "RequiredArgsConstructor", "NoArgsConstructor")) {
            myFixture.addFileToProject("dev/simplified/annotations/" + name + ".java",
                """
                package dev.simplified.annotations;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.TYPE)
                public @interface %s {
                    AccessLevel access() default AccessLevel.PUBLIC;
                    boolean force() default false;
                }
                """.formatted(name));
        }
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
