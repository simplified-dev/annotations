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
 * The editor's explanation for a builder that is not generated.
 *
 * <p>Three members and a whole nested class vanish from completion when the
 * target declares a nested type of the builder's name, and until this fires the
 * only account of it anywhere is a compiler note. What the cases pin is which
 * positions suppress: a class or record target merges into the declaration and
 * draws nothing, while a chain role and a constructor or factory target do not
 * merge, and the message names which of the two the target is.
 */
public class DeclaredBuilderSuppressesGenerationInspectionTest extends BasePlatformTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return LightJavaCodeInsightFixtureTestCase.JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(DeclaredBuilderSuppressesGenerationInspection.class);
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

    /**
     * A standalone target merges into its declared builder, so nothing is
     * suppressed and nothing is said. This position used to warn and advise an
     * attribute that asked for the merge.
     */
    public void testAStandaloneDeclaredBuilder_isNotWarned() {
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
        assertEquals("the merge runs, so nothing is suppressed: " + weakWarnings(),
            0, weakWarnings().size());
    }

    /**
     * An interface's builder is a sibling file, so a class nested in the
     * interface body suppresses nothing and the processor emits every entry
     * point regardless. This position used to warn over source that builds.
     */
    public void testAnInterfaceDeclaringANestedBuilder_isNotWarned() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public interface Shape {
                String name();
                class Builder { }
            }
            """);
        assertEquals("nothing is suppressed on an interface: " + weakWarnings(),
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
        assertEquals("nothing is declared, so nothing is suppressed: " + weakWarnings(),
            0, weakWarnings().size());
    }

    /** An unrelated nested class is not the builder and suppresses nothing. */
    public void testAnUnrelatedNestedClass_isNotWarned() {
        myFixture.configureByText("Holder.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Holder {
                private String name;
                public static class Helper { }
            }
            """);
        assertEquals("only the builder's own name suppresses: " + weakWarnings(),
            0, weakWarnings().size());
    }

    /**
     * A link does not merge into its declared builder, and the message says so
     * without naming an attribute - the one it used to name no longer exists.
     */
    public void testOnAChain_isWarnedThatAChainDoesNotMerge() {
        myFixture.configureByText("Link.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            abstract class Base { private String label; }
            @ClassBuilder
            public class Link extends Base {
                private String extra;
                public static class Builder {
                    public Builder apply(Runnable task) { return this; }
                }
            }
            """);
        String warning = theOnlyWarning();
        assertTrue("still says nothing is generated: " + warning,
            warning.contains(
                "No builder is generated because 'Link' declares a nested type named 'Builder'"));
        assertTrue("and that a chain does not merge: " + warning,
            warning.contains(
                "A SuperBuilder chain does not merge into a declared builder, so the declaration "
                    + "suppresses generation"));
        assertFalse("names no attribute: " + warning, warning.contains("mergeDeclaredBuilder"));
    }

    /** An abstract root merges no more than a link does. */
    public void testOnAnAbstractRoot_isWarnedAsAChain() {
        myFixture.configureByText("Rooted.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public abstract class Rooted {
                private String label;
                public static class Builder {
                    public Builder apply(Runnable task) { return this; }
                }
            }
            """);
        assertTrue("an abstract root is a chain role: " + weakWarnings(),
            theOnlyWarning().contains("A SuperBuilder chain does not merge into a declared builder"));
    }

    /** A constructor target does not merge into a declared builder, and the message says so. */
    public void testOnAConstructorTarget_isWarnedThatItDoesNotMerge() {
        myFixture.configureByText("Action.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public class Action {
                private final String key;
                @ClassBuilder
                Action(String key) { this.key = key; }
                public static class Builder {
                    public Builder apply(Runnable task) { return this; }
                }
            }
            """);
        assertTrue("no merge runs on that path: " + weakWarnings(),
            theOnlyWarning().contains(
                "A constructor or factory target does not merge into a declared builder, so the "
                    + "declaration suppresses generation"));
    }

    /**
     * The one warning the file draws, asserting on the way that it drew exactly
     * one - a second highlight means the visitor fired on a nested annotation.
     *
     * @return the warning's text
     */
    private String theOnlyWarning() {
        List<String> warnings = weakWarnings();
        assertEquals("expected exactly one highlight, got: " + warnings, 1, warnings.size());
        // get(0) rather than getFirst(): the plugin compiles against the Java 17
        // API, which is the oldest IDE it supports, and the sequenced-collection
        // accessors arrived in 21.
        return warnings.get(0);
    }

    private List<String> weakWarnings() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() != HighlightSeverity.WEAK_WARNING) continue;
            String description = info.getDescription();
            if (description != null && description.startsWith("No builder is generated")) {
                out.add(description);
            }
        }
        return out;
    }

}
