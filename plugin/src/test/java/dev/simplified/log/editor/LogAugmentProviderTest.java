package dev.simplified.log.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.simplified.shared.psi.GeneratedMemberMarker;

import java.util.Arrays;

/**
 * Exercises {@link LogAugmentProvider}: a {@code @Log} class or enum should
 * surface one {@code private static final Logger} field to the PSI layer, under
 * the name the annotation resolves, and nothing at all on a target the
 * annotation cannot generate into.
 *
 * <p>log4j2 is not on this repository's classpath, so the two types the
 * generated field references are supplied as stub sources inside the fixture
 * project.
 */
public class LogAugmentProviderTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
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

    public void testLogOnClass_synthesizesLoggerField() {
        PsiClass foo = firstClass("Foo.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public class Foo { }
            """);

        PsiField log = foo.findFieldByName("log", false);
        assertNotNull("log synthesised", log);
        assertTrue(log.hasModifierProperty(PsiModifier.PRIVATE));
        assertTrue(log.hasModifierProperty(PsiModifier.STATIC));
        assertTrue(log.hasModifierProperty(PsiModifier.FINAL));
        assertEquals("org.apache.logging.log4j.Logger", log.getType().getCanonicalText());
        assertTrue("log carries the generated marker", GeneratedMemberMarker.isGenerated(log));
    }

    public void testLogOnEnum_synthesizesLoggerField() {
        PsiClass color = firstClass("Color.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public enum Color { RED, GREEN }
            """);

        PsiField log = color.findFieldByName("log", false);
        assertNotNull("log synthesised on an enum", log);
        assertTrue(log.hasModifierProperty(PsiModifier.PRIVATE));
        assertTrue(log.hasModifierProperty(PsiModifier.STATIC));
        assertTrue(log.hasModifierProperty(PsiModifier.FINAL));
        assertEquals("org.apache.logging.log4j.Logger", log.getType().getCanonicalText());
        assertTrue("log carries the generated marker", GeneratedMemberMarker.isGenerated(log));
    }

    public void testNamePatternExpandsToSimpleName() {
        PsiClass foo = firstClass("Foo.java",
            """
            import dev.simplified.annotations.Log;
            @Log(name = "{}Log")
            public class Foo { }
            """);

        PsiField fooLog = foo.findFieldByName("FooLog", false);
        assertNotNull("FooLog synthesised", fooLog);
        assertTrue(GeneratedMemberMarker.isGenerated(fooLog));
        assertEquals("org.apache.logging.log4j.Logger", fooLog.getType().getCanonicalText());
        assertNoSynthesizedField(foo, "log");
    }

    public void testDeclaredFieldSuppressesSynthesis() {
        PsiClass foo = firstClass("Foo.java",
            """
            import dev.simplified.annotations.Log;
            import org.apache.logging.log4j.LogManager;
            import org.apache.logging.log4j.Logger;
            @Log
            public class Foo {
                private static final Logger log = LogManager.getLogger("mine");
            }
            """);

        PsiField log = foo.findFieldByName("log", false);
        assertNotNull("the author's own log survives", log);
        assertFalse("the field found is the author's, not a synthesized one",
            GeneratedMemberMarker.isGenerated(log));

        long named = Arrays.stream(foo.getFields())
            .filter(f -> "log".equals(f.getName()))
            .count();
        assertEquals("no second field named log", 1, named);
    }

    public void testInterfaceTarget_noSynthesis() {
        PsiClass foo = firstClass("Foo.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public interface Foo { }
            """);
        assertNoSynthesizedField(foo, "log");
    }

    public void testRecordTarget_noSynthesis() {
        PsiClass point = firstClass("Point.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public record Point(int x, int y) { }
            """);
        assertNoSynthesizedField(point, "log");
    }

    public void testAnnotationTypeTarget_noSynthesis() {
        PsiClass marker = firstClass("Marker.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public @interface Marker { }
            """);
        assertNoSynthesizedField(marker, "log");
    }

    public void testClassWithoutLog_noSynthesis() {
        PsiClass foo = firstClass("Foo.java",
            """
            public class Foo { }
            """);
        assertNoSynthesizedField(foo, "log");
    }

    /**
     * The stub log4j2 sources are added in {@code setUp}, so they are on every
     * fixture project this class builds and a per-test project without them
     * cannot be arranged without standing up a second fixture. The equivalent
     * guarantee is asserted instead: the synthesised field keeps the log4j2
     * fully-qualified type text, which is what the provider contributes whether
     * or not the type resolves, so an absent log4j2 can never degrade into a
     * silently green {@code java.lang.Object} field.
     */
    public void testLoggerTypeIsNeverSilentlyDowngraded() {
        PsiClass foo = firstClass("Foo.java",
            """
            import dev.simplified.annotations.Log;
            @Log
            public class Foo { }
            """);

        PsiField log = foo.findFieldByName("log", false);
        assertNotNull("log synthesised", log);
        String typeText = log.getType().getCanonicalText();
        assertEquals("org.apache.logging.log4j.Logger", typeText);
        assertFalse("no fallback to Object", "java.lang.Object".equals(typeText));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiClass firstClass(String fileName, String text) {
        PsiFile file = myFixture.configureByText(fileName, text);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    private static void assertNoSynthesizedField(PsiClass owner, String name) {
        PsiField field = owner.findFieldByName(name, false);
        if (field != null && GeneratedMemberMarker.isGenerated(field))
            fail("did not expect a synthesized field '" + name + "' on " + owner.getName());
    }
}
