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
 * The editor's half of the shapes the merge refuses.
 *
 * <p>Each case asserts the literal its processor twin asserts, which is the
 * cross-check the whole arrangement is for: the two halves render one decision,
 * so a substring pinned on one side is pinned of the other. A case that only
 * checked <em>that</em> something was reported would pass over two sentences
 * that had drifted apart.
 */
public class DeclaredBuilderShapeInspectionTest extends BasePlatformTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return LightJavaCodeInsightFixtureTestCase.JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(DeclaredBuilderShapeInspection.class);
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder {
            }
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

    /** The literal the apt suite pins at the same condition. */
    public void testANonStaticBuilder_isReported() {
        myFixture.configureByText("Inner.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Inner {
                private String name;
                public class Builder { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("an inner class captures the enclosing instance"));
    }

    public void testABuilderMissingTheTypeParameters_isReported() {
        myFixture.configureByText("Raw.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Raw<T> {
                private T item;
                public static class Builder { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("re-declare the target's type parameters"));
    }

    /** A builder the entry points instantiate cannot be abstract. */
    public void testAnAbstractBuilderOnAConcreteRole_isReported() {
        myFixture.configureByText("Sealed.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Sealed {
                private String name;
                public abstract static class Builder { }
            }
            """);
        assertTrue("names the entry point that instantiates it: " + errors(),
            theOnlyError().contains("it is what builder() instantiates, so it cannot be abstract"));
    }

    /**
     * Standing alone, nothing generated calls {@code build()} - the author's is
     * kept and reported as kept - so a build method returning something else is
     * theirs to write, and the build accepts it. Reporting it here would be red
     * over source that compiles.
     */
    public void testAStandaloneMistypedBuildMethod_isNotReported() {
        myFixture.configureByText("Wrong.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Wrong {
                private String name;
                public static class Builder {
                    public Object build() { return null; }
                }
            }
            """);
        assertEquals("the build accepts it, so nothing is said: " + errors(), 0, errors().size());
    }

    /** The shape the feature exists for draws nothing. */
    public void testAUsableBuilder_isNotReported() {
        myFixture.configureByText("Fine.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Fine {
                private String name;
                public static class Builder {
                    public Builder apply(Runnable task) { return this; }
                    public Fine build() { return null; }
                }
            }
            """);
        assertEquals("a usable shape draws nothing: " + errors(), 0, errors().size());
    }

    /**
     * A bare annotation over a non-static builder is reported, and on the
     * builder's name identifier - the element the author acts on. This shape
     * went unreported while the merge had to be asked for, the build then
     * leaving the declaration alone.
     */
    public void testANonStaticBuilderUnderABareAnnotation_isReportedOnItsName() {
        myFixture.configureByText("Untouched.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Untouched {
                private String name;
                public class Builder { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("an inner class captures the enclosing instance"));
        List<String> anchors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (info.getSeverity() == HighlightSeverity.ERROR && description != null
                && description.startsWith("@ClassBuilder cannot merge into")) {
                anchors.add(info.getText());
            }
        }
        assertEquals("reported on the builder's name identifier", List.of("Builder"), anchors);
    }

    /**
     * An interface's builder is a sibling file and the processor never looks at
     * a class nested in the interface body, so its shape is not a question -
     * even one missing the type parameters the merge would require on a class,
     * and even where the annotation still writes the attribute that once asked
     * for the merge. The inspection used to read that attribute and judge the
     * interface's nested class, reporting an error the build never raises.
     */
    public void testAnInterfacesNestedBuilder_isNotJudgedUnderAStaleAttribute() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(mergeDeclaredBuilder = true)
            public interface Shape<T> {
                T value();
                class Builder { }
            }
            """);
        assertEquals("nothing merges into an interface's nested class: " + errors(),
            0, errors().size());
    }

    /**
     * The other direction the same divergence was recorded from: the processor
     * wrote an extends clause naming a builder that could not take it, and the
     * editor left the child's builder unrooted and reported nothing.
     */
    public void testALinkWhoseAnnotatedSuperDeclaresItsOwnBuilder_isReported() {
        myFixture.configureByText("Leaf.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            abstract class Rooted {
                private String label;
                public static class Builder { }
            }
            @ClassBuilder
            public class Leaf extends Rooted {
                private String extra;
            }
            """);
        assertTrue("names the supertype: " + errors(),
            theOnlyError().contains(
                "@ClassBuilder generates no builder on 'Leaf' - its annotated supertype 'Rooted' "
                    + "declares its own nested builder"));
    }

    /**
     * A constructor target is never in a chain - the processor's third path
     * never looks for an annotated super - so its enclosing class's supertype
     * says nothing about whether a builder is generated.
     */
    public void testAConstructorTargetUnderADeclaringSuper_isNotReported() {
        myFixture.configureByText("Child.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            class Parent { public static class Builder { } }
            public class Child extends Parent {
                private final String a;
                @ClassBuilder
                public Child(String a) { this.a = a; }
            }
            """);
        assertEquals("the executable path has no chain to block it: " + errors(),
            0, errors().size());
    }

    /**
     * The processor asks about the target's own declaration first and returns on
     * it with a note, so the ancestor question is never reached. Reporting it
     * anyway was red over source javac accepts.
     */
    public void testWhereBothTargetAndAncestorDeclareABuilder_isNotReportedAsAnAncestorError() {
        myFixture.configureByText("Leaf.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            class Rooted { public static class Builder { } }
            @ClassBuilder
            public class Leaf extends Rooted {
                private String b;
                public static class Builder { }
            }
            """);
        assertEquals("the build prints a note, not an error: " + errors(), 0, errors().size());
    }

    /** An ancestor whose builder is generated takes the clause, so nothing is said. */
    public void testALinkWhoseAnnotatedSuperGeneratesItsBuilder_isNotReported() {
        myFixture.configureByText("Child.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            abstract class Parent {
                private String label;
            }
            @ClassBuilder
            public class Child extends Parent {
                private String extra;
            }
            """);
        assertEquals("an ordinary chain is left alone: " + errors(), 0, errors().size());
    }

    /**
     * The processor refuses a declared field of a slot's name whose type the
     * generated setter cannot assign, and the editor said nothing - the merged
     * setters were contributed beside the field and the class was green up to
     * the build. The sentence is the one the apt twin asserts, and it is
     * reported on the field's type.
     */
    public void testAMistypedSlot_isReportedOnTheFieldsType() {
        myFixture.configureByText("Mistyped.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Mistyped {
                private int size;
                public static class Builder {
                    private String size;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'size' declared as "
                + "String, and the slot it stands for is int - the generated setter has nothing to "
                + "assign it to"));
        List<String> anchors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (info.getSeverity() == HighlightSeverity.ERROR && description != null
                && description.startsWith("@ClassBuilder merged into")) {
                anchors.add(info.getText());
            }
        }
        assertEquals("reported on the declared field's type", List.of("String"), anchors);
    }

    /**
     * The slot's arguments print as the processor prints them, which is what
     * lets both suites assert one sentence over a parameterised slot.
     */
    public void testAMistypedGenericSlot_isReportedInTheProcessorsSentence() {
        myFixture.configureByText("Tally.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.List;
            import java.util.Map;
            @ClassBuilder
            public class Tally {
                private Map<String, Integer> counts;
                public static class Builder {
                    private List<String> counts;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'counts' declared as "
                + "List<String>, and the slot it stands for is java.util.Map<java.lang.String, "
                + "java.lang.Integer> - the generated setter has nothing to assign it to"));
    }

    /**
     * A lazy slot is held as a supplier of its declared type, so its natural
     * spelling is the one the merge cannot assign - the same sentence the apt
     * twin asserts, storage type and reason included.
     */
    public void testALazySlotDeclaredWithItsNaturalType_isReported() {
        addLazyAnnotation();
        myFixture.configureByText("Natural.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            @ClassBuilder
            public class Natural {
                @Lazy private String note = compute();
                private static String compute() { return "computed"; }
                public static class Builder {
                    private String note;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'note' declared as "
                + "String, and the slot it stands for is java.util.function.Supplier<java.lang.String> - "
                + "the generated setter has nothing to assign it to. A @Lazy field is held in the "
                + "builder as a supplier of its declared type"));
    }

    /** A primitive lazy slot names the boxed supplier, as the processor does. */
    public void testALazyPrimitiveSlotDeclaredWithItsNaturalType_namesTheBoxedSupplier() {
        addLazyAnnotation();
        myFixture.configureByText("Counted.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            @ClassBuilder
            public class Counted {
                @Lazy private int count = compute();
                private static int compute() { return 7; }
                public static class Builder {
                    private int count;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("finds 'count' declared as int, and the slot it stands for is "
                + "java.util.function.Supplier<java.lang.Integer> - the generated setter"));
    }

    /**
     * The supplier spelling is the one the generated setters assign, so it draws
     * nothing. A check comparing against the declared type would report exactly
     * this shape.
     */
    public void testALazySlotDeclaredAsASupplier_isNotReported() {
        addLazyAnnotation();
        myFixture.configureByText("Held.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            import java.util.function.Supplier;
            @ClassBuilder
            public class Held {
                @Lazy private String note = compute();
                private static String compute() { return "computed"; }
                public static class Builder {
                    private Supplier<String> note;
                }
            }
            """);
        assertEquals("the supplier spelling is the storage type: " + errors(), 0, errors().size());
    }

    // ------------------------------------------------------------------
    // Executable targets
    // ------------------------------------------------------------------

    /**
     * A constructor target merges into its enclosing type's declared builder, so
     * the processor judges that builder's shape and refuses a non-static one.
     * The inspection used to skip every executable target.
     */
    public void testANonStaticBuilderOnAConstructorTarget_isReportedOnItsName() {
        myFixture.configureByText("Hook.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Hook {
                private final String key;
                @ClassBuilder
                Hook(String key) { this.key = key; }
                public class Builder { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - an inner class "
                + "captures the enclosing instance, so builder() has nothing to create it from"));
        List<String> anchors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (info.getSeverity() == HighlightSeverity.ERROR && description != null
                && description.startsWith("@ClassBuilder cannot merge into")) {
                anchors.add(info.getText());
            }
        }
        assertEquals("reported on the builder's name identifier", List.of("Builder"), anchors);
    }

    /**
     * A static factory's builder re-declares the factory's parameters, so one
     * re-declaring the enclosing type's is refused in the processor's sentence,
     * which names the factory's list.
     */
    public void testAStaticFactoryBuilderRedeclaringTheEnclosingTypesParameters_isReported() {
        myFixture.configureByText("Crate.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Crate<V> {
                private final V value;
                private Crate(V value) { this.value = value; }
                @ClassBuilder
                public static <T> Crate<T> of(T value) { return new Crate<>(value); }
                public static class Builder<V> { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("re-declare the target's type parameters <T>, and this one "
                + "declares <V>"));
    }

    /** And the factory's own list draws nothing. */
    public void testAStaticFactoryBuilderRedeclaringTheFactorysParameters_isNotReported() {
        myFixture.configureByText("Box.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Box<V> {
                private final V value;
                private Box(V value) { this.value = value; }
                @ClassBuilder
                public static <T> Box<T> of(T value) { return new Box<>(value); }
                public static class Builder<T> { }
            }
            """);
        assertEquals("the processor merges into this shape: " + errors(), 0, errors().size());
    }

    /**
     * A declared field sharing a parameter slot's name is judged against the
     * parameter's type. An enclosing-type field of the same name, initialised or
     * not, is no part of the slot, so it leaves the slot judged.
     */
    public void testAMistypedParameterSlot_isReportedBesideAnInitialisedFieldOfItsName() {
        myFixture.configureByText("Gauge.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Gauge {
                private int size = 3;
                @ClassBuilder
                Gauge(int size) { this.size = size; }
                public static class Builder {
                    private String size;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'size' declared as "
                + "String, and the slot it stands for is int - the generated setter has nothing to "
                + "assign it to"));
    }

    /**
     * A seed is appended to the declared builder as a {@code final} field and
     * only the author's constructors can assign it, so javac refuses one that
     * does not. The platform's own check never saw the appended field, so the
     * editor was green over it. A constructor assigning the seed, and one
     * delegating to such a constructor, draw nothing.
     */
    public void testASeedTheBuildersConstructorLeavesUnassigned_isReportedOnThatConstructor() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { }
                public static class Builder {
                    public Builder(String origin) { this.origin = origin; }
                    public Builder(int copies) { this(String.valueOf(copies)); }
                    public Builder(long ignored) { }
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' appends the seed 'origin' as "
                + "a final field, and this constructor leaves it unassigned"));
        List<String> anchors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (info.getSeverity() == HighlightSeverity.ERROR && description != null
                && description.contains("appends the seed")) {
                int line = myFixture.getEditor().getDocument().getLineNumber(info.getStartOffset());
                anchors.add(info.getText() + "@" + (line + 1));
            }
        }
        assertEquals("reported on the constructor that leaves it unassigned",
            List.of("Builder@9"), anchors);
    }

    /** A builder declaring no constructor keeps javac's default, which assigns nothing. */
    public void testASeedWithNoBuilderConstructorToAssignIt_isReportedOnTheBuildersName() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Ticket.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Ticket {
                @ClassBuilder
                Ticket(@BuilderSeed String origin, String item) { }
                public static class Builder { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' appends the seed 'origin' as "
                + "a final field, and 'Builder' declares no constructor to assign it"));
    }

    private void addBuilderSeedAnnotation() {
        myFixture.addFileToProject("dev/simplified/annotations/BuilderSeed.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.PARAMETER)
            public @interface BuilderSeed {
            }
            """);
    }

    private void addLazyAnnotation() {
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.FIELD)
            public @interface Lazy {
            }
            """);
    }

    private String theOnlyError() {
        List<String> errors = errors();
        assertEquals("expected exactly one highlight, got: " + errors, 1, errors.size());
        // get(0) rather than getFirst(): the plugin compiles against the Java 17
        // API, and the sequenced-collection accessors arrived in 21.
        return errors.get(0);
    }

    private List<String> errors() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() != HighlightSeverity.ERROR) continue;
            String description = info.getDescription();
            if (description != null && (description.startsWith("@ClassBuilder cannot merge into")
                || description.startsWith("@ClassBuilder generates no builder on")
                || description.startsWith("@ClassBuilder merged into"))) {
                out.add(description);
            }
        }
        return out;
    }

}
