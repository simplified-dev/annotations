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
                boolean retainInit() default true;
                AccessLevel builderConstructorAccess() default AccessLevel.PACKAGE;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
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
     * a class nested in the interface body, so its shape is not a question
     * under a bare annotation - even one missing the type parameters the merge
     * would require on a class. An inspection that did not ask whether the
     * owner is an interface type target would judge that class and report an
     * error the build never raises.
     */
    public void testAClassNestedInAnInterface_isNotJudged() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
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
     * editor left the child's builder unrooted and reported nothing. The
     * ancestor's declaration is itself refused as a root shape, so the file
     * carries both errors, as the build does - the cause on the root's builder
     * and its consequence on the link.
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
        List<String> errors = errors();
        assertEquals("the cause and its consequence: " + errors, 2, errors.size());
        assertTrue("names the supertype: " + errors, errors.contains(
            "@ClassBuilder generates no builder on 'Leaf' - its annotated supertype 'Rooted' "
                + "declares its own nested builder"));
        assertTrue("and refuses the root's own shape: " + errors, errors.contains(
            "@ClassBuilder cannot merge into 'Builder' - the builder of Rooted carries an abstract "
                + "self() and build(), so the class holding them has to be abstract too"));
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
     * A link declaring its own builder is asked about its ancestor too, and
     * ahead of its own shape: that declaration's extends clause has to name the
     * ancestor's builder as a generated one does. The target's own declaration
     * used to end the question with a note on both halves, when the chain did
     * not merge.
     */
    public void testWhereBothTargetAndAncestorDeclareABuilder_isReportedAsAnAncestorError() {
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
        assertTrue("the ancestor error, as the build reports it: " + errors(),
            theOnlyError().contains("@ClassBuilder generates no builder on 'Leaf' - its annotated "
                + "supertype 'Rooted' declares its own nested builder"));
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
     * A link extending a generic root raw is refused on its annotation, in the
     * sentence the processor reports. The editor said nothing and contributed a
     * builder whose generated extends clause javac fails on.
     */
    public void testALinkExtendingAGenericRootRaw_isReportedOnItsAnnotation() {
        myFixture.configureByText("Circle.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            abstract class Box<V> {
                private V value;
            }
            @ClassBuilder
            public class Circle extends Box {
                private int radius;
            }
            """);
        assertEquals(List.of("@ClassBuilder generates no builder on 'Circle' - its annotated supertype 'Box' is "
            + "generic, so the extends clause has to give its type arguments"), errors());
        List<String> anchors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (info.getSeverity() == HighlightSeverity.ERROR && description != null
                && description.startsWith("@ClassBuilder generates no builder on 'Circle'")) {
                anchors.add(info.getText());
            }
        }
        assertEquals("reported on the link's annotation", List.of("@ClassBuilder"), anchors);
    }

    /** A link giving a generic root its type arguments is left alone. */
    public void testALinkExtendingAGenericRootWithItsTypeArguments_isNotReported() {
        myFixture.configureByText("Circle.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            abstract class Box<V> {
                private V value;
            }
            @ClassBuilder
            public class Circle extends Box<String> {
                private int radius;
            }
            """);
        assertEquals("a parameterised chain is left alone: " + errors(), 0, errors().size());
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
     * A field differing from its slot in a type argument alone cannot take what
     * the generated setter assigns. Both halves compared erasures and passed it,
     * javac then failing on the generated setter with nothing on the author's
     * field; the sentence is the one the apt twin asserts.
     */
    public void testASlotDifferingInATypeArgument_isReportedOnTheField() {
        myFixture.configureByText("Tagged.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.List;
            @ClassBuilder
            public class Tagged {
                private List<String> tags;
                public static class Builder {
                    private List<Integer> tags;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'tags' declared as "
                + "List<Integer>, and the slot it stands for is java.util.List<java.lang.String> - the "
                + "generated setter has nothing to assign it to"));
    }

    /**
     * An initialised slot whose initializer reads nothing of the instance is
     * held as declared, and the processor judges its field as it judges any
     * other. The editor left every initialised slot unjudged, so the field was
     * green over source javac refuses on it.
     */
    public void testAMistypedSlotWithALiteralInitializer_isReported() {
        myFixture.configureByText("Titled.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Titled {
                private String name = "untitled";
                public static class Builder {
                    private int name;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'name' declared as int, "
                + "and the slot it stands for is java.lang.String - the generated setter has nothing to "
                + "assign it to"));
    }

    /**
     * An initializer reading the instance holds its slot as a supplier, so the
     * slot's declared type is the spelling the merge cannot assign - the
     * sentence the apt twin asserts, reason included.
     */
    public void testAMistypedSlotWhoseInitializerReadsTheInstance_namesTheSupplier() {
        myFixture.configureByText("Labelled.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Labelled {
                private String name;
                private String label = name + "!";
                public static class Builder {
                    private String label;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'label' declared as "
                + "String, and the slot it stands for is java.util.function.Supplier<java.lang.String> - "
                + "the generated setter has nothing to assign it to. A slot whose retained initializer "
                + "reads instance state is held in the builder as a supplier of its declared type"));
    }

    /**
     * A {@code @Collector} slot whose default reads the instance is held in a
     * plain {@code java.util} scratch container, so a field of the slot's
     * declared type is refused - the list's and the map's sentences the apt twin
     * asserts. Such a slot was left unjudged.
     */
    public void testAMistypedCollectedSlotWhoseInitializerReadsTheInstance_namesTheScratchContainer() {
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface Collector { }
            """);
        myFixture.configureByText("Tagged.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.ArrayList;
            import java.util.LinkedHashMap;
            import java.util.List;
            @ClassBuilder
            public class Tagged {
                private String name;
                @Collector private ArrayList<String> tags = new ArrayList<>(List.of(String.valueOf(name)));
                @Collector private LinkedHashMap<String, Integer> counts = seed(name);
                private static LinkedHashMap<String, Integer> seed(String name) {
                    return new LinkedHashMap<>();
                }
                public static class Builder {
                    private ArrayList<String> tags;
                    private LinkedHashMap<String, Integer> counts;
                }
            }
            """);
        assertEquals("the shared wording",
            List.of("@ClassBuilder merged into 'Builder' finds 'tags' declared as ArrayList<String>, and the "
                    + "slot it stands for is java.util.List<java.lang.String> - the generated setter has "
                    + "nothing to assign it to",
                "@ClassBuilder merged into 'Builder' finds 'counts' declared as LinkedHashMap<String, "
                    + "Integer>, and the slot it stands for is java.util.Map<java.lang.String, "
                    + "java.lang.Integer> - the generated setter has nothing to assign it to"),
            errors());
    }

    /**
     * An initializer calling a getter {@code @Getter} generates reads the
     * instance, so its slot is a supplier and a field of the declared type is
     * refused, in the sentence the apt twin asserts. Neither half counted a
     * generated getter as an instance member.
     */
    public void testAMistypedSlotWhoseInitializerCallsAGeneratedGetter_namesTheSupplier() {
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Getter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Getter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
            }
            """);
        myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Getter;
            @ClassBuilder
            @Getter
            public class Named {
                private String name;
                private String label = getName() + "!";
                public static class Builder {
                    private String label;
                }
            }
            """);
        assertEquals("the shared wording",
            "@ClassBuilder merged into 'Builder' finds 'label' declared as String, and the slot it stands "
                + "for is java.util.function.Supplier<java.lang.String> - the generated setter has nothing to "
                + "assign it to. A slot whose retained initializer reads instance state is held in the "
                + "builder as a supplier of its declared type",
            theOnlyError());
    }

    /**
     * A setter {@code @Setter} generates and a getter {@code @Lazy} generates,
     * each named through the scheme its annotation writes, are instance methods
     * an initializer can call, so both slots are suppliers - the sentences the
     * apt twin asserts.
     */
    public void testMistypedSlotsWhoseInitializersCallAGeneratedSetterOrLazyGetter_nameTheSupplier() {
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Setter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.FIELD})
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
            @Retention(RetentionPolicy.CLASS)
            @Target(ElementType.FIELD)
            public @interface Lazy {
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
            }
            """);
        myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.Setter;
            @ClassBuilder
            public class Named {
                @Setter private String name;
                @Lazy(name = "load{}") private String heavy = compute();
                private Runnable reset = () -> setName("x");
                private String label = loadHeavy() + "!";
                private static String compute() { return "h"; }
                public static class Builder {
                    private Runnable reset;
                    private String label;
                }
            }
            """);
        assertEquals("the shared wording",
            List.of("@ClassBuilder merged into 'Builder' finds 'reset' declared as Runnable, and the slot it "
                    + "stands for is java.util.function.Supplier<java.lang.Runnable> - the generated setter has "
                    + "nothing to assign it to. A slot whose retained initializer reads instance state is held "
                    + "in the builder as a supplier of its declared type",
                "@ClassBuilder merged into 'Builder' finds 'label' declared as String, and the slot it stands "
                    + "for is java.util.function.Supplier<java.lang.String> - the generated setter has nothing "
                    + "to assign it to. A slot whose retained initializer reads instance state is held in the "
                    + "builder as a supplier of its declared type"),
            errors());
    }

    /**
     * With {@code retainInit = false} no initializer is kept, so a slot is held
     * as declared whatever its initializer reads, and a field of the declared
     * type is the right one.
     */
    public void testASlotWhoseInitializerIsNotRetained_isHeldAsDeclared() {
        myFixture.configureByText("Labelled.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(retainInit = false)
            public class Labelled {
                private String name;
                private String label = name + "!";
                public static class Builder {
                    private String label;
                }
            }
            """);
        assertTrue("held as declared: " + errors(), errors().isEmpty());
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

    /**
     * An instance initializer that assigns nothing of the seed leaves each
     * constructor as responsible for it, and javac refuses the one that does
     * not assign it. A builder with any instance initializer was left unjudged.
     */
    public void testASeedLeftUnassignedBesideAnInstanceInitializer_isReported() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { }
                public static class Builder {
                    private int count;
                    { count = 1; }
                    public Builder(String origin) { }
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' appends the seed 'origin' as "
                + "a final field, and this constructor leaves it unassigned"));
    }

    /** An instance initializer assigning the seed assigns it for every constructor, as javac finds. */
    public void testASeedAnInstanceInitializerAssigns_isNotReported() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { }
                public static class Builder {
                    { origin = "desk"; }
                    public Builder(String ignored) { }
                }
            }
            """);
        assertTrue("javac accepts this: " + errors(), errors().isEmpty());
    }

    /**
     * A seed an instance initializer assigns is assigned again by a constructor
     * that writes it too, and javac refuses that constructor with {@code
     * variable origin might already have been assigned}. The platform's check
     * never saw the appended field and the seed rule looked only for a missing
     * assignment, so the editor was green over it.
     *
     * <p>javac reports it on the assignment, a line below the constructor's own
     * here; the report sat on the constructor's name.
     */
    public void testASeedAnInstanceInitializerAndAConstructorBothAssign_isReportedOnTheAssignment() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                private final String item;
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { this.item = origin + ":" + item; }
                public String getItem() { return item; }
                public static class Builder {
                    { origin = "desk"; }
                    public Builder(String origin) {
                        String trimmed = origin.trim();
                        this.origin = trimmed;
                    }
                    public Builder(int ignored) { }
                }
            }
            """);
        assertEquals("the shared wording",
            "@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, and this "
                + "constructor assigns it after an instance initializer already has",
            theOnlyError());
        assertEquals("reported where javac reports it", List.of("this.origin@12"), seedAnchors());
    }

    /**
     * A seed one constructor may assign twice is refused by javac on the second
     * assignment, the merge appending it {@code final}. The seed rule asked
     * only whether an instance initializer assigned it first, and the
     * platform's check never sees the appended field, so the editor was green.
     */
    public void testASeedAConstructorMayAssignTwice_isReportedOnTheSecondAssignment() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                private final String item;
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { this.item = origin + ":" + item; }
                public String getItem() { return item; }
                public static class Builder {
                    public Builder(String origin) {
                        this.origin = origin;
                        if (origin.isEmpty()) this.origin = "desk";
                    }
                }
            }
            """);
        assertEquals("the shared wording",
            "@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, and this "
                + "constructor may assign it more than once",
            theOnlyError());
        assertEquals("reported where javac reports it", List.of("this.origin@11"), seedAnchors());
    }

    /**
     * A seed assigned inside a loop may be assigned on every pass, and javac
     * refuses that assignment. The editor was green over it.
     */
    public void testASeedAConstructorAssignsInALoop_isReportedOnThatAssignment() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                private final String item;
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { this.item = origin + ":" + item; }
                public String getItem() { return item; }
                public static class Builder {
                    public Builder(String origin) {
                        do { this.origin = origin; } while (origin.isEmpty());
                    }
                }
            }
            """);
        assertEquals("the shared wording",
            "@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, and this "
                + "constructor may assign it more than once",
            theOnlyError());
        assertEquals("reported where javac reports it", List.of("this.origin@10"), seedAnchors());
    }

    /**
     * A constructor delegating through {@code this(..)} has the seed assigned by
     * the one it calls, so javac refuses it assigning the seed again. Every
     * delegating constructor was left unread.
     */
    public void testASeedADelegatingConstructorAssignsAgain_isReportedOnThatAssignment() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                private final String item;
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { this.item = origin + ":" + item; }
                public String getItem() { return item; }
                public static class Builder {
                    public Builder(String origin) { this.origin = origin; }
                    public Builder(int copies) { this("desk"); this.origin = String.valueOf(copies); }
                }
            }
            """);
        assertEquals("the shared wording",
            "@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, and this "
                + "constructor assigns it after the constructor it delegates to already has",
            theOnlyError());
        assertEquals("reported where javac reports it", List.of("this.origin@10"), seedAnchors());
    }

    /** A seed each constructor assigns exactly once, one of them by delegating, draws nothing. */
    public void testASeedEachConstructorAssignsOnce_isNotReported() {
        addBuilderSeedAnnotation();
        myFixture.configureByText("Slip.java",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Slip {
                private final String item;
                @ClassBuilder
                Slip(@BuilderSeed String origin, String item) { this.item = origin + ":" + item; }
                public String getItem() { return item; }
                public static class Builder {
                    public Builder(String origin) {
                        if (origin.isEmpty()) this.origin = "desk";
                        else this.origin = origin;
                    }
                    public Builder(int copies) { this(String.valueOf(copies)); }
                }
            }
            """);
        assertTrue("javac accepts this: " + errors(), errors().isEmpty());
    }

    /**
     * A constructor {@code @AllArgsConstructor} appends onto the declared builder
     * takes the fields the builder has before the merge, so it leaves an
     * appended seed unassigned, and javac refuses it on the builder's line. The
     * editor read the author's constructors alone and said the builder declares
     * none; it now names the appended one, on the builder's name.
     */
    public void testASeedAnAppendedArgsConstructorLeavesUnassigned_isReportedOnTheBuildersName() {
        addBuilderSeedAnnotation();
        addArgsConstructorAnnotation("AllArgsConstructor");
        myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, String item) { this.item = origin + ":" + item; }
                @AllArgsConstructor
                public static final class Builder {
                    private final String code;
                }
            }
            """);
        assertEquals("the condition javac reports",
            "@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, and the "
                + "constructor Builder(String) that @AllArgsConstructor appends leaves it unassigned",
            theOnlyError());
        assertEquals("reported on the builder, where javac reports it", List.of("Builder@9"), seedAnchors());
    }

    /**
     * Beside an author constructor that assigns the seed, the appended one still
     * leaves it unassigned and javac still refuses it. The editor read the
     * author's constructor alone and was green.
     */
    public void testASeedAnAppendedArgsConstructorLeavesUnassignedBesideAnAssigningOne_isReported() {
        addBuilderSeedAnnotation();
        addArgsConstructorAnnotation("AllArgsConstructor");
        myFixture.configureByText("Order.java",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Order {
                private final String item;
                @ClassBuilder
                Order(@BuilderSeed String origin, String item) { this.item = origin + ":" + item; }
                @AllArgsConstructor
                public static final class Builder {
                    private final int code;
                    public Builder(String origin) { this.origin = origin; this.code = 1; }
                }
            }
            """);
        assertEquals("the condition javac reports",
            "@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, and the "
                + "constructor Builder(int) that @AllArgsConstructor appends leaves it unassigned",
            theOnlyError());
        assertEquals("reported on the builder, where javac reports it", List.of("Builder@9"), seedAnchors());
    }

    // ------------------------------------------------------------------
    // Chain roles
    //
    // A root, a link or a chained abstract merges into its declared builder, so
    // its shape is judged. Each case asserts the sentence its apt twin asserts;
    // the inspection used to skip every chain role.
    // ------------------------------------------------------------------

    /** A root's builder carries the abstract pair, so a concrete one cannot hold the merge. */
    public void testARootWhoseDeclaredBuilderIsNotSelfTyped_isReported() {
        myFixture.configureByText("Rooted.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Rooted {
                private String label;
                public static class Builder { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - the builder of Rooted "
                + "carries an abstract self() and build(), so the class holding them has to be "
                + "abstract too"));
    }

    /** The sentence shows the bounds the pair needs beside the ones it has. */
    public void testAnUnboundedTrailingPair_isReported() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                private String name;
                public abstract static class Builder<T, B> { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - its trailing pair has "
                + "to be bounded as <T extends Shape, B extends Builder<T, B>> for the generated setters "
                + "to return the caller's own builder type, and this one declares <T, B>"));
    }

    /** A root that is not generic is told about its self-typed pair, not the target's parameters. */
    public void testARootWhoseBuilderDeclaresNoPair_namesTheSelfTypedPair() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                private String name;
                public abstract static class Builder { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - a static nested "
                + "builder for an abstract target in a builder chain has to declare the self-typed "
                + "pair <T, B>, and this one declares none"));
    }

    /** A link's builder inherits the ancestor's setters through its extends clause. */
    public void testALinkWhoseDeclaredBuilderOmitsTheExtendsClause_isReported() {
        myFixture.configureByText("Link.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Link extends Base {
                private String extra;
                public static class Builder { }
            }
            @ClassBuilder
            abstract class Base { private String label; }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - the builder of a "
                + "chained target has to extend Base.Builder, and this one extends nothing"));
    }

    /**
     * Another type's builder of the same simple name is not the ancestor's; read
     * by erased simple name it passed, and the build failed on the generated
     * {@code super(b)}.
     */
    public void testALinkExtendingAnotherTypesBuilder_isReported() {
        myFixture.configureByText("Link.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Link extends Base {
                private String extra;
                public static class Builder extends Other.Builder<Link, Builder> { }
            }
            @ClassBuilder
            abstract class Base { private String label; }
            @ClassBuilder
            abstract class Other {
                private String note;
                public abstract static class Builder<T extends Other, B extends Builder<T, B>> { }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - the builder of a "
                + "chained target has to extend Base.Builder, and this one extends Other.Builder"));
    }

    /** The build method a link inherits is its role's, so the author's has to return the link. */
    public void testALinkDeclaringBuildReturningSomethingElse_isReported() {
        myFixture.configureByText("Link.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Link extends Base {
                private String extra;
                public static class Builder extends Base.Builder<Link, Builder> {
                    public Object build() { return null; }
                }
            }
            @ClassBuilder
            abstract class Base { private String label; }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - its build method "
                + "returns Object where this role builds Link, so it cannot stand in for the "
                + "generated one"));
    }

    /**
     * A root's build method returning the root is overridden by every link's,
     * so it stands in for the generated one; the processor merges it and the
     * program runs. Both halves refused it for not naming the self type.
     */
    public void testARootWhoseBuildReturnsTheRoot_isNotReported() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                private String name;
                public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {
                    public abstract Shape build();
                }
            }
            """);
        assertTrue("the hand-written equivalent compiles: " + errors(), errors().isEmpty());
    }

    /**
     * The extends clause's arguments were read by neither half, so a pair
     * written the wrong way round passed and failed inside the generated
     * members.
     */
    public void testALinkPassingItsPairReversed_isReported() {
        myFixture.configureByText("Link.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Link extends Base {
                private String extra;
                public static class Builder extends Base.Builder<Builder, Link> { }
            }
            @ClassBuilder
            abstract class Base { private String label; }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - the builder of a "
                + "chained target has to pass Base.Builder the arguments <Link, Builder>, and this "
                + "one passes <Builder, Link>"));
    }

    /** A slot field on a root's declared builder is judged as on any other. */
    public void testAMistypedSlotOnARootsDeclaredBuilder_isReported() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                private int size;
                public abstract static class Builder<T extends Shape, B extends Builder<T, B>> {
                    private String size;
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'size' declared as "
                + "String, and the slot it stands for is int - the generated setter has nothing to "
                + "assign it to"));
    }

    /** A usable shape on every chain role draws nothing. */
    public void testUsableChainShapes_areNotReported() {
        myFixture.configureByText("Leaf.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Leaf extends Mid {
                private int size;
                public static class Builder extends Mid.Builder<Leaf, Builder> { }
            }
            @ClassBuilder
            abstract class Mid extends Base {
                private String kind;
                public abstract static class Builder<T extends Mid, B extends Builder<T, B>>
                        extends Base.Builder<T, B> { }
            }
            @ClassBuilder
            abstract class Base {
                private String label;
                public abstract static class Builder<R extends Base, S extends Builder<R, S>> { }
            }
            """);
        assertEquals("every role's usable shape: " + errors(), 0, errors().size());
    }

    /**
     * The apt suite's sentence, on the attribute: the author's constructor keeps
     * its own access, so the attribute beside it changes nothing. It used to be
     * accepted there in silence on both halves.
     */
    public void testBuilderConstructorAccessBesideTheAuthorsConstructor_isWarnedOnTheAttribute() {
        myFixture.configureByText("Owned.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
            public class Owned {
                private String name;
                public static class Builder {
                    public Builder() { }
                }
            }
            """);
        List<HighlightInfo> warnings = accessWarnings();
        assertEquals("one warning: " + warnings, 1, warnings.size());
        assertEquals("@ClassBuilder(builderConstructorAccess) has no effect - the declared 'Builder' "
                + "declares its own constructor, which keeps the access it is written with. Write "
                + "the access on that constructor, or drop the attribute",
            warnings.get(0).getDescription());
        assertEquals("on the written value", "AccessLevel.PRIVATE",
            myFixture.getEditor().getDocument().getText()
                .substring(warnings.get(0).getStartOffset(), warnings.get(0).getEndOffset()));
    }

    /** Where the builder declares none, the attribute reaches javac's default, so nothing is said. */
    public void testBuilderConstructorAccessOnABuilderWithNoConstructor_isNotWarned() {
        myFixture.configureByText("Sealed.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
            public class Sealed {
                private String name;
                public static class Builder { }
            }
            """);
        assertEquals("the retype takes it: " + accessWarnings(), 0, accessWarnings().size());
    }

    /** The apt suite's sentence where {@code builderConstructorAccess} reaches no constructor. */
    private static String inertOn(String targetName) {
        return "@ClassBuilder(builderConstructorAccess) has no effect on '" + targetName + "' - it applies "
            + "only to the builder of a class or record outside a SuperBuilder chain, or of a constructor or "
            + "factory target, never to a chain's builder or an interface's. Drop the attribute";
    }

    /** The one {@code builderConstructorAccess} warning, as its description and its highlighted text. */
    private String theOnlyAccessWarning() {
        List<HighlightInfo> warnings = accessWarnings();
        assertEquals("one warning: " + warnings, 1, warnings.size());
        HighlightInfo warning = warnings.get(0);
        return warning.getDescription() + " @ " + myFixture.getEditor().getDocument().getText()
            .substring(warning.getStartOffset(), warning.getEndOffset());
    }

    /**
     * A chain role's builder keeps javac's default constructor, so the
     * attribute written on an abstract root is warned on the written value, in
     * the apt suite's sentence. Both halves were silent.
     */
    public void testBuilderConstructorAccessOnAnAbstractRoot_isWarnedOnTheAttribute() {
        myFixture.addFileToProject("Circle.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Circle extends Shape {
                private int radius;
            }
            """);
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
            public abstract class Shape {
                private String name;
            }
            """);
        assertEquals(inertOn("Shape") + " @ AccessLevel.PRIVATE", theOnlyAccessWarning());
    }

    /** A concrete link's builder is a chain role's too. */
    public void testBuilderConstructorAccessOnAConcreteLink_isWarnedOnTheAttribute() {
        myFixture.addFileToProject("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Shape {
                private String name;
            }
            """);
        myFixture.configureByText("Circle.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PUBLIC)
            public class Circle extends Shape {
                private int radius;
            }
            """);
        assertEquals(inertOn("Circle") + " @ AccessLevel.PUBLIC", theOnlyAccessWarning());
    }

    /** An interface target's sibling builder keeps its implicit constructor. */
    public void testBuilderConstructorAccessOnAnInterface_isWarnedOnTheAttribute() {
        myFixture.configureByText("Face.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
            public interface Face {
                String name();
            }
            """);
        assertEquals(inertOn("Face") + " @ AccessLevel.PRIVATE", theOnlyAccessWarning());
    }

    /**
     * The default written out requests nothing on a chain role or an
     * interface, and a class standing alone takes the attribute, so none of
     * the three is warned.
     */
    public void testBuilderConstructorAccessAtItsDefaultOrWhereItApplies_isNotWarned() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PACKAGE)
            public abstract class Shape {
                private String name;
            }
            """);
        assertEquals("the default on a root: " + accessWarnings(), 0, accessWarnings().size());
        myFixture.configureByText("Face.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PACKAGE)
            public interface Face {
                String name();
            }
            """);
        assertEquals("the default on an interface: " + accessWarnings(), 0, accessWarnings().size());
        myFixture.configureByText("Alone.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
            public class Alone {
                private String name;
            }
            """);
        assertEquals("where it applies: " + accessWarnings(), 0, accessWarnings().size());
    }

    // ------------------------------------------------------------------
    // Reviewed reproductions: each shape was green here while javac failed on
    // a generated line, or red here over source javac compiles.
    // ------------------------------------------------------------------

    /**
     * A {@code final} field under a slot's name is refused, in the sentence the
     * apt twin asserts. The editor contributed the setter assigning it and said
     * nothing, while javac refused the assignment on the class line.
     */
    public void testAFinalSlotField_isReported() {
        myFixture.configureByText("Bag.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder
            public class Bag {
                List<String> items;
                public static class Builder {
                    private final List<String> items = new ArrayList<>();
                    public Builder item(String item) { items.add(item); return this; }
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'items' declared final, "
                + "and the generated setter assigns it"));
    }

    /** A looser bound on the re-declared parameter fails the generated build(). */
    public void testABuilderBoundingATypeParameterLooser_isReported() {
        myFixture.configureByText("Box.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Box<T extends Number> {
                T value;
                public static class Builder<T> {
                    public Builder<T> twice(T v) { this.value = v; return this; }
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - a static nested builder "
                + "has to bound the target's type parameters as <T extends Number>, and this one "
                + "declares <T>"));
    }

    /** A narrower bound on the re-declared parameter fails the generated builder(). */
    public void testABuilderBoundingATypeParameterNarrower_isReported() {
        myFixture.configureByText("Box.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Box<T> {
                T value;
                public static class Builder<T extends Number> {
                    public Builder<T> twice(T v) { this.value = v; return this; }
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - a static nested builder "
                + "has to bound the target's type parameters as <T>, and this one declares "
                + "<T extends Number>"));
    }

    /**
     * A C-style array field is read with its brackets, as javac folds them into
     * the declared type. The editor read the type element alone, {@code String},
     * and reported a field javac accepts.
     */
    public void testACStyleArrayFieldOverAnArraySlot_isNotReported() {
        myFixture.configureByText("Tags.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Tags {
                String[] tags;
                public static class Builder {
                    private String tags[];
                    public Builder only(String t) { this.tags = new String[] { t }; return this; }
                }
            }
            """);
        assertEquals("javac accepts it: " + errors(), 0, errors().size());
    }

    /** The other direction: javac refuses {@code String label[]} over a {@code String} slot. */
    public void testACStyleArrayFieldOverAPlainSlot_isReported() {
        myFixture.configureByText("Tags.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Tags {
                String label;
                public static class Builder {
                    private String label[];
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'label' declared as "
                + "String[], and the slot it stands for is java.lang.String - the generated setter has "
                + "nothing to assign it to"));
    }

    /**
     * A nested record, enum or interface is refused in the processor's
     * sentence. The editor read the implicit {@code static} of each and merged
     * into a record or an enum, and gave an interface the abstract sentence.
     */
    public void testANestedRecordEnumOrInterface_isReportedAsNotAClass() {
        String[][] shapes = {
            {"record Builder(int unused) { }", "a record"},
            {"static enum Builder { ; }", "an enum"},
            {"interface Builder { }", "an interface"},
        };
        for (int i = 0; i < shapes.length; i++) {
            String[] shape = shapes[i];
            myFixture.configureByText("Note" + i + ".java",
                """
                import dev.simplified.annotations.ClassBuilder;
                @ClassBuilder
                public class Note%d {
                    String text;
                    %s
                }
                """.formatted(i, shape[0]));
            assertTrue(shape[0] + " in the shared wording: " + errors(),
                theOnlyError().contains("@ClassBuilder cannot merge into 'Builder' - it is declared as "
                    + shape[1] + ", and only a class can hold the builder's fields and the constructor "
                    + "builder() calls"));
        }
    }

    /**
     * A boxed field over a primitive slot is refused in the sentence the apt
     * twin asserts: left unset it reaches the primitive constructor parameter as
     * {@code null}. The editor accepted it, as the processor did.
     */
    public void testABoxedFieldOverAPrimitiveSlot_isReported() {
        myFixture.configureByText("Counter.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Counter {
                int count;
                public static class Builder {
                    private Integer count;
                    public Builder bump() { this.count = count == null ? 1 : count + 1; return this; }
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'count' declared as Integer, "
                + "and the slot it stands for is int - an unset Integer field reaches the primitive "
                + "constructor parameter as null"));
    }

    /** A primitive field over a boxed slot takes everything the setters assign, and javac accepts it. */
    public void testAPrimitiveFieldOverABoxedSlot_isNotReported() {
        myFixture.configureByText("Counter.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Counter {
                Integer count;
                public static class Builder {
                    private int count;
                    public Builder bump() { this.count++; return this; }
                }
            }
            """);
        assertEquals("javac accepts it: " + errors(), 0, errors().size());
    }

    /**
     * A {@code final} field whose slot's every generated setter the author
     * spells is assigned by nothing the merge appends, and javac accepts it. The
     * editor refused it in the processor's sentence, as the processor did.
     */
    public void testAFinalSlotEverySetterOfWhichTheAuthorSpells_isNotReported() {
        myFixture.configureByText("Server.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Server {
                int port;
                public static class Builder {
                    private final int port;
                    public Builder() { this(80); }
                    private Builder(int port) { this.port = port; }
                    public Builder port(int port) { return new Builder(port); }
                }
            }
            """);
        assertEquals("javac accepts it: " + errors(), 0, errors().size());
    }

    /** A {@code final} field one of whose slot's setters is left generated is still refused. */
    public void testAFinalSlotOneOfWhoseSettersIsLeftGenerated_isReported() {
        myFixture.configureByText("Switch.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Switch {
                boolean enabled;
                public static class Builder {
                    private final boolean enabled;
                    public Builder() { this(false); }
                    private Builder(boolean enabled) { this.enabled = enabled; }
                    public Builder enabled(boolean enabled) { return new Builder(enabled); }
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds 'enabled' declared final, "
                + "and the generated setter assigns it"));
    }

    /**
     * An author method covering a generated setter while taking another
     * parameterisation of the same generic type is refused in the sentence the
     * apt twin asserts. The editor offered {@code from(T)} and said nothing,
     * while javac failed on the generated copy passing the slot's own type.
     */
    public void testAnAuthorMethodCoveringASetterWithOtherTypeArguments_isReported() {
        myFixture.configureByText("Bag.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder
            public class Bag {
                List<String> items;
                public static class Builder {
                    public Builder items(List<Integer> codes) {
                        this.items = new ArrayList<>();
                        for (Integer code : codes) this.items.add("#" + code);
                        return this;
                    }
                }
            }
            """);
        assertTrue("the shared wording: " + errors(),
            theOnlyError().contains("@ClassBuilder merged into 'Builder' finds items(List<Integer>) standing "
                + "in for the generated items(List<String>), and 'from' and 'mutate' pass it the slot's "
                + "List<String>, which its List<Integer> parameter cannot take"));
    }

    /** On a constructor target no copy entry point passes the slot to it, and javac accepts it. */
    public void testOnAConstructorTarget_anAuthorMethodCoveringASetterWithOtherTypeArguments_isNotReported() {
        myFixture.configureByText("Bag.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.ArrayList;
            import java.util.List;
            public class Bag {
                final List<String> items;
                @ClassBuilder
                Bag(List<String> items) { this.items = items; }
                public static class Builder {
                    public Builder items(List<Integer> codes) {
                        this.items = new ArrayList<>();
                        for (Integer code : codes) this.items.add("#" + code);
                        return this;
                    }
                }
            }
            """);
        assertEquals("javac accepts it: " + errors(), 0, errors().size());
    }

    /**
     * {@code from(T)} and {@code mutate()} call only the setter taking the
     * slot's own type, so an author method covering the singular add with
     * another parameterisation is never passed the slot, and javac accepts it.
     * The rule judged every covered setter and reported it.
     */
    public void testAnAuthorMethodCoveringASetterTheCopyNeverCalls_isNotReported() {
        addCollectorAnnotation();
        myFixture.configureByText("Bag.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder
            public class Bag {
                @Collector(singular = true) List<List<String>> items;
                public static class Builder {
                    public Builder addItem(List<Integer> codes) {
                        List<String> item = new ArrayList<>();
                        for (Integer code : codes) item.add("#" + code);
                        this.items.add(item);
                        return this;
                    }
                }
            }
            """);
        assertEquals("javac accepts it: " + errors(), 0, errors().size());
    }

    /**
     * A {@code final} collected field whose generated setters all append into
     * the container is assigned by nothing the merge appends, so javac accepts
     * it. Every generated setter counted as assigning the field, and the editor
     * reported it.
     */
    public void testAFinalCollectedSlotWhoseSettersAllAppend_isNotReported() {
        addCollectorAnnotation();
        myFixture.configureByText("Crate.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder
            public class Crate {
                @Collector(append = true) List<String> tags;
                public static class Builder {
                    private final List<String> tags = new ArrayList<>();
                }
            }
            """);
        assertEquals("javac accepts it: " + errors(), 0, errors().size());
    }

    /**
     * Where the author spells both replacing bulk setters, the singular add and
     * the clear the merge appends only mutate the container, so javac accepts
     * the {@code final} field.
     */
    public void testAFinalCollectedSlotWhoseReplacingSettersTheAuthorSpells_isNotReported() {
        addCollectorAnnotation();
        myFixture.configureByText("Crate.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder
            public class Crate {
                @Collector(singular = true, clearable = true) List<String> tags;
                public static class Builder {
                    private final List<String> tags = new ArrayList<>();
                    public Builder tags(String... tags) {
                        this.tags.clear();
                        for (String tag : tags) this.tags.add(tag);
                        return this;
                    }
                    public Builder tags(Iterable<String> tags) {
                        this.tags.clear();
                        tags.forEach(this.tags::add);
                        return this;
                    }
                }
            }
            """);
        assertEquals("javac accepts it: " + errors(), 0, errors().size());
    }

    // ------------------------------------------------------------------
    // Reviewed reproductions: the constructor and factory path
    // ------------------------------------------------------------------

    /**
     * A static factory inside an interface merges into the class the interface
     * body declares, so an abstract one is refused as on any other target. The
     * inspection returned on every interface owner, reading the factory's site as
     * an interface type target, and was silent over javac's error.
     */
    public void testAnAbstractBuilderUnderAnInterfacesStaticFactory_isReported() {
        myFixture.addFileToProject("Circle.java", "public record Circle(double radius) { }");
        myFixture.configureByText("Shapes.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public interface Shapes {
                @ClassBuilder
                static Circle circle(double radius) { return new Circle(radius); }
                abstract class Builder { }
            }
            """);
        assertEquals("@ClassBuilder cannot merge into 'Builder' - it is what builder() instantiates, so it "
            + "cannot be abstract", theOnlyError());
    }

    /**
     * A varargs parameter's slot is held as the array it is, which is what javac
     * reads off the parameter. The editor rendered the slot's ellipsis type,
     * erased it to nothing, and reported the author's {@code String[]} field.
     */
    public void testAVarargsParameterSlotOverAnArrayField_isNotReported() {
        myFixture.configureByText("Tags.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Tags {
                private final String[] values;
                @ClassBuilder
                Tags(String... values) { this.values = values; }
                public static final class Builder {
                    private String[] values = new String[0];
                    public Builder none() { this.values = new String[0]; return this; }
                }
            }
            """);
        assertEquals("javac accepts it: " + errors(), 0, errors().size());
    }

    /**
     * javac refuses an instance-method target outright and merges nothing, so
     * none of the merge's diagnostics is printed for it. The inspection took any
     * annotated method as the target and judged the enclosing type's builder
     * against its parameters.
     */
    public void testAnInstanceMethodTarget_isNotJudged() {
        myFixture.configureByText("Job.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            public final class Job {
                private final String name;
                Job(String name) { this.name = name; }
                @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
                public Job copy(String name) { return new Job(name); }
                public static final class Builder {
                    private int name;
                    public Builder(String unused) { }
                }
            }
            """);
        assertEquals("javac prints no merge error for a refused member: " + errors(), 0, errors().size());
        assertEquals("nor the no-effect warning", 0, accessWarnings().size());
    }

    /**
     * Where the type carries the annotation too, javac refuses the member's and
     * merges the type's slots. The inspection judged the author's field against
     * the refused member's parameter instead, an error over a field javac
     * accepts.
     */
    public void testAMemberTargetBesideAnAnnotatedType_isNotJudged() {
        myFixture.configureByText("Job.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public final class Job {
                private final String name;
                Job(String name) { this.name = name; }
                @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
                public static Job copy(int name) { return new Job(String.valueOf(name)); }
                public static final class Builder {
                    private String name;
                }
            }
            """);
        assertEquals("javac merges the type's slots, which the field holds: " + errors(), 0, errors().size());
        assertEquals("nor the no-effect warning", 0, accessWarnings().size());
    }

    /** The warnings this inspection raises about {@code builderConstructorAccess}. */
    private List<HighlightInfo> accessWarnings() {
        List<HighlightInfo> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (info.getSeverity() == HighlightSeverity.WARNING && description != null
                && description.startsWith("@ClassBuilder(builderConstructorAccess)")) {
                out.add(info);
            }
        }
        return out;
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

    private void addCollectorAnnotation() {
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
                boolean append() default false;
                boolean removable() default false;
            }
            """);
    }

    /** Adds a constructor annotation of the given simple name. */
    private void addArgsConstructorAnnotation(String name) {
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

    /** Where each seed diagnostic sits, as its highlighted text and its one-based line. */
    private List<String> seedAnchors() {
        List<String> anchors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (info.getSeverity() == HighlightSeverity.ERROR && description != null
                && description.contains("appends the seed")) {
                int line = myFixture.getEditor().getDocument().getLineNumber(info.getStartOffset());
                anchors.add(info.getText() + "@" + (line + 1));
            }
        }
        return anchors;
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

    // ------------------------------------------------------------------
    // An inherited member a generated setter cannot override
    // ------------------------------------------------------------------

    /**
     * An {@code Item} whose declared builder extends {@code Fluent<Builder>},
     * which declares {@code fluentMethod} beside a {@code tag} field.
     */
    private void configureItemOver(String fluentMethod) {
        myFixture.addFileToProject("Fluent.java",
            """
            public abstract class Fluent<B> {
                protected String tag;
                %s
                protected abstract B self();
            }
            """.formatted(fluentMethod));
        myFixture.configureByText("Item.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Item {
                String tag;
                public static class Builder extends Fluent<Builder> {
                    @Override
                    protected Builder self() { return this; }
                }
            }
            """);
    }

    /** Where each inherited-method diagnostic sits, as its highlighted text and its one-based line. */
    private List<String> inheritedAnchors() {
        List<String> anchors = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (info.getSeverity() == HighlightSeverity.ERROR && description != null
                && description.contains(" inherited from ")) {
                int line = myFixture.getEditor().getDocument().getLineNumber(info.getStartOffset());
                anchors.add(info.getText() + "@" + (line + 1));
            }
        }
        return anchors;
    }

    /**
     * An inherited {@code final} method under a generated setter's name and
     * erased parameter types is reported on the declared builder's name, in
     * the processor's sentence. javac refuses the appended setter and the
     * editor was green in every file.
     */
    public void testAnInheritedFinalMethodOfASetterSignature_isReportedOnTheBuildersName() {
        configureItemOver("public final B tag(String t) { this.tag = t; return self(); }");
        assertEquals("@ClassBuilder merged into 'Builder' finds tag(String) inherited from Fluent declared final, "
            + "so the generated setter of that signature cannot override it", theOnlyError());
        assertEquals(List.of("Builder@5"), inheritedAnchors());
    }

    /** An inherited method returning {@code void} under a setter's signature is reported the same way. */
    public void testAnInheritedVoidMethodOfASetterSignature_isReportedOnTheBuildersName() {
        configureItemOver("public void tag(String t) { this.tag = t; }");
        assertEquals("@ClassBuilder merged into 'Builder' finds tag(String) inherited from Fluent returning void, "
            + "which the generated setter returning Builder cannot override", theOnlyError());
        assertEquals(List.of("Builder@5"), inheritedAnchors());
    }

    /** An inherited method returning the self type the builder binds to itself is overridden legally. */
    public void testAnInheritedSelfTypedMethodOfASetterSignature_isNotReported() {
        configureItemOver("public B tag(String t) { this.tag = t; return self(); }");
        assertEquals(List.of(), errors());
    }

    /** A final inherited method of the setter's name taking another type is an overload. */
    public void testAnInheritedFinalMethodOfAnotherParameterType_isNotReported() {
        configureItemOver("public final B tag(int t) { this.tag = \"#\" + t; return self(); }");
        assertEquals(List.of(), errors());
    }

    /** {@code Object}'s final {@code wait(long)} blocks the setter of a {@code long} slot named {@code wait}. */
    public void testALongSlotNamedWait_isReportedForObjectsFinalWait() {
        myFixture.configureByText("Waiter.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Waiter {
                long wait;
                public static class Builder { }
            }
            """);
        assertEquals("@ClassBuilder merged into 'Builder' finds wait(long) inherited from Object declared final, "
            + "so the generated setter of that signature cannot override it", theOnlyError());
    }

    /**
     * An inherited {@code static} method under a setter's signature is reported
     * on the declared builder's name. The editor's reader skipped static
     * methods, so it was green while javac refused the appended setter with
     * {@code overridden method is static}.
     */
    public void testAnInheritedStaticMethodOfASetterSignature_isReportedOnTheBuildersName() {
        configureItemOver("public static Fluent<?> tag(String t) { return null; }");
        assertEquals("@ClassBuilder merged into 'Builder' finds tag(String) inherited from Fluent declared static, "
            + "so the generated setter of that signature cannot override it", theOnlyError());
        assertEquals(List.of("Builder@5"), inheritedAnchors());
    }

    /** A static inherited method of the setter's name taking another type is an overload. */
    public void testAnInheritedStaticMethodOfAnotherParameterType_isNotReported() {
        configureItemOver("public static Fluent<?> tag(int t) { return null; }");
        assertEquals(List.of(), errors());
    }

    /** A class inherits no static method of an interface it implements. */
    public void testAStaticInterfaceMethodOfASetterSignature_isNotReported() {
        myFixture.addFileToProject("Fluent.java",
            """
            public abstract class Fluent<B> {
                protected String tag;
                protected abstract B self();
            }
            """);
        myFixture.addFileToProject("Tagging.java",
            """
            public interface Tagging {
                static Object tag(String t) { return null; }
            }
            """);
        myFixture.configureByText("Item.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Item {
                String tag;
                public static class Builder extends Fluent<Builder> implements Tagging {
                    @Override
                    protected Builder self() { return this; }
                }
            }
            """);
        assertEquals(List.of(), errors());
    }

    /**
     * A generic {@code Box} whose declared builder, declaring
     * {@code typeParameter}, extends {@code superclass}, declared by
     * {@code baseSource}, with a {@code value} slot of the type variable.
     */
    private void configureBoxOver(String typeParameter, String superclass, String baseSource) {
        myFixture.addFileToProject("Base.java", baseSource);
        myFixture.configureByText("Box.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Box<%1$s> {
                T value;
                public static class Builder<%1$s> extends %2$s { }
            }
            """.formatted(typeParameter, superclass));
    }

    /**
     * A generated {@code value(T)} erases to {@code value(Object)}, the
     * signature of an inherited {@code final value(Object)}. The setter was
     * keyed by the variable's name, so the editor was green while javac
     * reported a name clash on the target's line.
     */
    public void testATypeVariableSetterUnderAnInheritedFinalObjectMethod_isReportedOnTheBuildersName() {
        configureBoxOver("T", "Base", "public class Base { public final Base value(Object v) { return this; } }");
        assertEquals("@ClassBuilder merged into 'Builder' finds value(T) inherited from Base declared final, "
            + "so the generated setter of that signature cannot override it", theOnlyError());
        assertEquals(List.of("Builder@5"), inheritedAnchors());
    }

    /** A generic supertype's {@code final value(X)} taken with {@code T} is the method {@code value(T)} overrides. */
    public void testATypeVariableSetterUnderAGenericSupertypesFinalMethod_isReportedOnTheBuildersName() {
        configureBoxOver("T", "Base<T>",
            "public class Base<X> { public final Base<X> value(X v) { return this; } }");
        assertEquals("@ClassBuilder merged into 'Builder' finds value(T) inherited from Base declared final, "
            + "so the generated setter of that signature cannot override it", theOnlyError());
    }

    /** A bounded variable erases to its bound, so {@code value(T extends Number)} meets a final {@code value(Number)}. */
    public void testABoundedTypeVariableSetterUnderAnInheritedFinalMethodOfItsBound_isReportedOnTheBuildersName() {
        configureBoxOver("T extends Number", "Base",
            "public class Base { public final Base value(Number v) { return this; } }");
        assertEquals("@ClassBuilder merged into 'Builder' finds value(T) inherited from Base declared final, "
            + "so the generated setter of that signature cannot override it", theOnlyError());
    }

    /** An unbounded variable erases to {@code Object}, so a final {@code value(String)} is an overload beside it. */
    public void testATypeVariableSetterBesideAnInheritedFinalMethodOfAnotherErasure_isNotReported() {
        configureBoxOver("T", "Base", "public class Base { public final Base value(String v) { return this; } }");
        assertEquals(List.of(), errors());
    }

    /**
     * A generated {@code value(T)} beside an inherited, non-final
     * {@code value(Object)} shares its erasure without overriding it, and is
     * reported on the declared builder's name in the processor's sentence. The
     * editor was green while javac reported a name clash on the target's line.
     */
    public void testATypeVariableSetterBesideAnInheritedMethodOfItsErasure_isReportedOnTheBuildersName() {
        configureBoxOver("T", "Base", "public class Base { public Base value(Object v) { return this; } }");
        assertEquals("@ClassBuilder merged into 'Builder' finds value(Object) inherited from Base, which has the "
            + "same erasure as the generated setter value(T) but is not overridden by it", theOnlyError());
        assertEquals(List.of("Builder@5"), inheritedAnchors());
    }

    /** A bounded {@code value(T extends Number)} beside an inherited, non-final {@code value(Number)} is the same clash. */
    public void testABoundedTypeVariableSetterBesideAnInheritedMethodOfItsBound_isReportedOnTheBuildersName() {
        configureBoxOver("T extends Number", "Base",
            "public class Base { public Base value(Number v) { return this; } }");
        assertEquals("@ClassBuilder merged into 'Builder' finds value(Number) inherited from Base, which has the "
            + "same erasure as the generated setter value(T) but is not overridden by it", theOnlyError());
    }

    /** {@code Base<X>}'s {@code value(X)} taken with {@code T} is the method {@code value(T)} overrides. */
    public void testATypeVariableSetterOverridingAGenericSupertypesMethod_isNotReported() {
        configureBoxOver("T", "Base<T>", "public class Base<X> { public Base<X> value(X v) { return this; } }");
        assertEquals(List.of(), errors());
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
