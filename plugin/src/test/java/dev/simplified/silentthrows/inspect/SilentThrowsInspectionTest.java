package dev.simplified.silentthrows.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Exercises {@link SilentThrowsInspection}: every shape where the annotation
 * cannot do its job has to be underlined while typing, and every shape where it
 * can has to stay silent.
 */
public class SilentThrowsInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new SilentThrowsInspection());
        myFixture.addFileToProject("dev/simplified/annotations/SilentThrows.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
            public @interface SilentThrows {
                Class<? extends Throwable>[] value() default Throwable.class;
            }
            """);
        myFixture.addFileToProject("Boom.java", "public class Boom extends Exception {}");
        myFixture.addFileToProject("Bang.java", "public class Bang extends Exception {}");
        myFixture.addFileToProject("Wire.java",
            """
            public class Wire {
                public static void send() throws Boom {}
            }
            """);
    }

    private boolean hasHighlightContaining(HighlightSeverity severity, String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo info : highlights) {
            if (info.getSeverity() != severity) continue;
            String description = info.getDescription();
            if (description != null && description.contains(needle)) return true;
        }
        return false;
    }

    public void testAbstractMethod_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public abstract class Foo {
                @SilentThrows
                abstract void go();
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "has nothing to wrap"));
    }

    public void testInterfaceMethod_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public interface Foo {
                @SilentThrows
                void go();
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "has nothing to wrap"));
    }

    public void testBareOnThrowingBody_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows
                void go() { Wire.send(); }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WEAK_WARNING, "@SilentThrows"));
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "cannot throw"));
    }

    public void testNoCheckedException_weaklyWarned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows
                int go() { return 1; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WEAK_WARNING,
            "throws no checked exception"));
    }

    public void testUnreachableNarrowing_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows(Bang.class)
                void go() { Wire.send(); }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WARNING, "cannot throw Bang"));
    }

    public void testReachableNarrowing_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows(Boom.class)
                void go() { Wire.send(); }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "cannot throw"));
    }

    /** A supertype clause is reachable whenever a subtype is thrown. */
    public void testSupertypeNarrowing_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows(Exception.class)
                void go() { Wire.send(); }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "cannot throw"));
    }

    /** Unchecked clauses are never unreachable, whatever the body throws. */
    public void testUncheckedNarrowing_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows(RuntimeException.class)
                void go() { Wire.send(); }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "cannot throw"));
    }

    public void testAlsoInThrowsClause_weaklyWarned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows(Boom.class)
                void go() throws Boom { Wire.send(); }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WEAK_WARNING,
            "also declared in the throws clause"));
    }

    public void testUnrelatedThrowsClause_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows(Boom.class)
                void go() throws Bang { Wire.send(); }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WEAK_WARNING,
            "also declared in the throws clause"));
    }

    public void testConstructor_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows
                Foo() { Wire.send(); }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "@SilentThrows"));
        assertFalse(hasHighlightContaining(HighlightSeverity.WEAK_WARNING, "@SilentThrows"));
    }

    public void testUnannotatedMethod_clean() {
        myFixture.configureByText("Foo.java",
            """
            public class Foo {
                int go() { return 1; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WEAK_WARNING, "@SilentThrows"));
    }

}
