package dev.simplified.log.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/**
 * Exercises {@link LogInspection}: every check restates a diagnostic the
 * processor emits, so the assertions match on the message text the inspection
 * registers rather than on markup in the fixture source.
 *
 * <p>log4j2 is not on this repository's classpath, so the two types the
 * generated field references are supplied as stub sources inside the fixture
 * project.
 */
public class LogInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections((Class<? extends LocalInspectionTool>) LogInspection.class);
        addAnnotationSources();
    }

    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/simplified/annotations/Log.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface Log {
                String topic() default "";
                String name() default "";
                boolean emitGenerated() default true;
            }
            """);
        myFixture.addFileToProject("org/apache/logging/log4j/Logger.java",
            """
            package org.apache.logging.log4j;
            public interface Logger {
                void info(String message);
                String getName();
            }
            """);
        myFixture.addFileToProject("org/apache/logging/log4j/LogManager.java",
            """
            package org.apache.logging.log4j;
            public final class LogManager {
                public static Logger getLogger(Class<?> type) { return null; }
                public static Logger getLogger(String name) { return null; }
            }
            """);
    }

    private boolean hasHighlightContaining(HighlightSeverity severity, String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo h : highlights) {
            if (h.getSeverity() != severity) continue;
            String desc = h.getDescription();
            if (desc != null && desc.contains(needle)) return true;
        }
        return false;
    }

    public void testLogOnInterface_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public interface Foo { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@Log is only supported on classes and enums - 'Foo' is an interface"));
    }

    public void testLogOnRecord_flagged() {
        myFixture.configureByText("Point.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public record Point(int x, int y) { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@Log is only supported on classes and enums - 'Point' is a record"));
    }

    public void testLogOnAnnotationType_flagged() {
        myFixture.configureByText("Marker.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public @interface Marker { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@Log is only supported on classes and enums - 'Marker' is an annotation type"));
    }

    public void testSuppressedName_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Log;
            @Log(name = "-")
            public class Foo { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@Log(name) must not be suppressed"));
    }

    public void testMalformedNamePattern_flagged() {
        // A space cannot occupy any position in a Java identifier, which is the
        // defect NamePattern.patternError reports without the placeholder being
        // required - the field is generated once per target.
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Log;
            @Log(name = "my log")
            public class Foo { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@Log(name) 'my log' expands to an invalid Java identifier"));
    }

    public void testDeclaredFieldOfResolvedName_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Log;
            import org.apache.logging.log4j.LogManager;
            import org.apache.logging.log4j.Logger;
            @Log
            public class Foo {
                private static final Logger log = LogManager.getLogger("mine");
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WARNING,
            "@Log generates nothing - 'Foo' already declares a field named 'log'"));
    }

    public void testLogOnClass_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public class Foo { }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "@Log"));
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "@Log"));
    }

    public void testLogOnEnum_clean() {
        myFixture.configureByText("Color.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public enum Color { RED, GREEN }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "@Log"));
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "@Log"));
    }
}
