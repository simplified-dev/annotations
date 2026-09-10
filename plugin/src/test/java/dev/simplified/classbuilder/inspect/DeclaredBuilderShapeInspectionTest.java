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
                boolean mergeDeclaredBuilder() default false;
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
            @ClassBuilder(mergeDeclaredBuilder = true)
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
            @ClassBuilder(mergeDeclaredBuilder = true)
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
            @ClassBuilder(mergeDeclaredBuilder = true)
            public class Sealed {
                private String name;
                public abstract static class Builder { }
            }
            """);
        assertTrue("names the entry point that instantiates it: " + errors(),
            theOnlyError().contains("it is what builder() instantiates, so it cannot be abstract"));
    }

    /** A declared build method stands in for the generated one, so it has to match it. */
    public void testAMistypedBuildMethod_isReported() {
        myFixture.configureByText("Wrong.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(mergeDeclaredBuilder = true)
            public class Wrong {
                private String name;
                public static class Builder {
                    public Object build() { return null; }
                }
            }
            """);
        assertTrue("names both types: " + errors(),
            theOnlyError().contains("its build method returns Object where this role builds Wrong"));
    }

    /** The shape the feature exists for draws nothing. */
    public void testAUsableBuilder_isNotReported() {
        myFixture.configureByText("Fine.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(mergeDeclaredBuilder = true)
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

    /** Without the opt-in nothing is merged, so the shape is not a question. */
    public void testWithoutTheOptIn_isNotReported() {
        myFixture.configureByText("Untouched.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Untouched {
                private String name;
                public class Builder { }
            }
            """);
        assertEquals("no merge, no shape requirement: " + errors(), 0, errors().size());
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
                || description.startsWith("@ClassBuilder generates no builder on"))) {
                out.add(description);
            }
        }
        return out;
    }

}
