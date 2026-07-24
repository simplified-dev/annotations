package dev.simplified.utility.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Edit-time behaviour of {@link UtilityClassInspection}, including the
 * "add static" fix that is the whole point of the default reporting policy.
 */
public class UtilityClassInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String MEMBER_ERROR = "requires every member to be static";

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(new UtilityClassInspection());
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
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/UtilityClass.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface UtilityClass {
                boolean makeFinal() default true;
                Members members() default Members.REQUIRE_STATIC;
                boolean nestedTypes() default false;
                AccessLevel constructorAccess() default AccessLevel.PRIVATE;
                String message() default "";
                boolean emitContracts() default true;
                boolean emitGenerated() default true;
                enum Members { REQUIRE_STATIC, MAKE_STATIC }
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder { }
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

    // ------------------------------------------------------------------
    // Instance members under REQUIRE_STATIC
    // ------------------------------------------------------------------

    public void testInstanceMethodIsFlagged() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                public String reverse(String value) { return value; }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "method 'reverse' is not"));
    }

    public void testInstanceFieldIsFlagged() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                private String cache = "";
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "field 'cache' is not"));
    }

    public void testStaticMembersAreClean() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                private static final String EMPTY = "";
                public static String reverse(String value) { return value; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, MEMBER_ERROR));
    }

    /**
     * {@code MAKE_STATIC} asks for the rewrite, so an instance member is legal
     * there and reporting it would contradict the processor.
     */
    public void testMakeStaticSilencesTheMemberCheck() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(members = UtilityClass.Members.MAKE_STATIC)
            public class StringUtil {
                private String cache = "";
                public String reverse(String value) { return value; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, MEMBER_ERROR));
    }

    public void testNestedTypeIsNotReportedAsAMember() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                public class Mode { }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, MEMBER_ERROR));
    }

    public void testAnnotatedClassWithNoMembersIsClean() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil { }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "@UtilityClass"));
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING, "@UtilityClass"));
    }

    public void testUnannotatedClassIsClean() {
        myFixture.configureByText("StringUtil.java",
            """
            public class StringUtil {
                public String reverse(String value) { return value; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, MEMBER_ERROR));
    }

    // ------------------------------------------------------------------
    // Quick fix
    // ------------------------------------------------------------------

    public void testAddStaticFixOnAMethod() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                public String rev<caret>erse(String value) { return value; }
            }
            """);
        IntentionAction fix = myFixture.findSingleIntention("Add 'static' to 'reverse'");
        myFixture.launchAction(fix);
        myFixture.checkResult(
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                public static String reverse(String value) { return value; }
            }
            """);
    }

    public void testAddStaticFixOnAField() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                private String ca<caret>che = "";
            }
            """);
        IntentionAction fix = myFixture.findSingleIntention("Add 'static' to 'cache'");
        myFixture.launchAction(fix);
        myFixture.checkResult(
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                private static String cache = "";
            }
            """);
    }

    public void testFixedMemberStopsBeingReported() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                public String rev<caret>erse(String value) { return value; }
            }
            """);
        myFixture.launchAction(myFixture.findSingleIntention("Add 'static' to 'reverse'"));
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, MEMBER_ERROR));
    }

    // ------------------------------------------------------------------
    // constructorAccess
    // ------------------------------------------------------------------

    public void testConstructorAccessNoneIsFlagged() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(constructorAccess = AccessLevel.NONE)
            public class StringUtil { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "constructorAccess = NONE) is not expressible"));
    }

    public void testConstructorAccessPackageIsClean() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(constructorAccess = AccessLevel.PACKAGE)
            public class StringUtil { }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "constructorAccess"));
    }

    // ------------------------------------------------------------------
    // Author-declared constructor
    // ------------------------------------------------------------------

    public void testDeclaredConstructorIsWarned() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                private StringUtil() { }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.WARNING,
            "will not synthesise a throwing constructor"));
    }

    public void testNoDeclaredConstructorIsClean() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class StringUtil {
                public static String reverse(String value) { return value; }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.WARNING,
            "will not synthesise a throwing constructor"));
    }

    // ------------------------------------------------------------------
    // Target kinds
    // ------------------------------------------------------------------

    public void testRecordIsFlagged() {
        myFixture.configureByText("Point.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public record Point(int x, int y) { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "is a record"));
    }

    public void testEnumIsFlagged() {
        myFixture.configureByText("Mode.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public enum Mode { A, B }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "is an enum"));
    }

    public void testInterfaceIsFlagged() {
        myFixture.configureByText("Reader.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public interface Reader { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "is an interface"));
    }

    public void testAnnotationTypeIsFlagged() {
        myFixture.configureByText("Marker.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public @interface Marker { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "is an annotation type"));
    }

    public void testAbstractClassIsFlagged() {
        myFixture.configureByText("Base.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public abstract class Base { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "cannot be applied to an abstract class"));
    }

    public void testInnerClassIsFlagged() {
        myFixture.configureByText("Outer.java",
            """
            import dev.simplified.annotations.UtilityClass;
            public class Outer {
                @UtilityClass
                public class Inner { }
            }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR, "is an inner class"));
    }

    public void testStaticNestedClassIsClean() {
        myFixture.configureByText("Outer.java",
            """
            import dev.simplified.annotations.UtilityClass;
            public class Outer {
                @UtilityClass
                public static class Inner {
                    public static String read() { return ""; }
                }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "@UtilityClass"));
    }

    /** A rejected target is reported once - the member check never runs on it. */
    public void testRejectedTargetSuppressesTheMemberCheck() {
        myFixture.configureByText("Mode.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public enum Mode {
                A;
                private final int code = 0;
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, MEMBER_ERROR));
    }

    // ------------------------------------------------------------------
    // @ClassBuilder clash
    // ------------------------------------------------------------------

    public void testClassBuilderOnTheSameTypeIsFlagged() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.UtilityClass;
            @ClassBuilder
            @UtilityClass
            public class StringUtil { }
            """);
        assertTrue(hasHighlightContaining(HighlightSeverity.ERROR,
            "@ClassBuilder contradicts @UtilityClass"));
    }

    /**
     * A local class is not an element of any annotation-processing round, so
     * the annotation does nothing there and neither does the inspection - an
     * error on source the build accepts is the worse failure.
     */
    public void testLocalClassInsideAnInnerClassIsSilent() {
        myFixture.configureByText("Outer.java",
            """
            import dev.simplified.annotations.UtilityClass;
            public class Outer {
                public class Inner {
                    void m() {
                        @UtilityClass class Local { }
                    }
                }
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "static all the way out"));
    }

    /**
     * The mutator returns on the {@code NONE} error and never reaches its own
     * declared-constructor warning, so a second complaint here would be about a
     * constructor the processor never considered.
     */
    public void testConstructorAccessNoneSuppressesTheDeclaredConstructorWarning() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(constructorAccess = AccessLevel.NONE)
            public class StringUtil {
                private StringUtil() { }
            }
            """);
        assertTrue("the unexpressible level is still reported",
            hasHighlightContaining(HighlightSeverity.ERROR, "is not expressible"));
        assertFalse("and is the only complaint about the constructor",
            hasHighlightContaining(HighlightSeverity.WARNING,
                "will not synthesise a throwing constructor"));
    }

    public void testClassBuilderAloneIsClean() {
        myFixture.configureByText("StringUtil.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class StringUtil {
                private String name;
            }
            """);
        assertFalse(hasHighlightContaining(HighlightSeverity.ERROR, "contradicts"));
    }

}
