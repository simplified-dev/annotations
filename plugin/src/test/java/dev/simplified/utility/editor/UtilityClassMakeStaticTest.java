package dev.simplified.utility.editor;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Editor behaviour for a {@code @UtilityClass(members = MAKE_STATIC)} target,
 * whose members javac sees as {@code static} and the IDE does not.
 *
 * <p>The processor adds {@code static} to every instance member of such a
 * target by mutating the javac AST, so a class-qualified call compiles. The
 * plugin contributes nothing for {@code @UtilityClass}, so the PSI still holds
 * the modifiers the author wrote and the editor reports the call as a static
 * reference to an instance member.
 *
 * <p>These tests pin that divergence as it stands today rather than the
 * behaviour that is wanted. Closing it needs a {@code PsiAugmentProvider} that
 * overrides {@code transformModifiers} to add {@code static} to any member of a
 * {@code MAKE_STATIC} target - the one platform hook that alters an element the
 * platform already built. When that lands, {@link #testMemberIsNotStaticToThePsi}
 * and {@link #testStaticQualifiedCallIsFlaggedAsAStaticContextError} flip, and
 * the assertions below say so inline.
 */
public class UtilityClassMakeStaticTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
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
        myFixture.addFileToProject("NbtFactory.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(members = UtilityClass.Members.MAKE_STATIC)
            public class NbtFactory {
                public String fromFile(String path) { return path; }
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

    /** Error-severity highlights in the configured file, as descriptions. */
    private List<String> errorsIn() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() != HighlightSeverity.ERROR) continue;
            out.add(String.valueOf(info.getDescription()));
        }
        return out;
    }

    private void configureCaller() {
        myFixture.configureByText("Caller.java",
            """
            public class Caller {
                public static String read() {
                    return NbtFactory.fromFile("level.dat");
                }
            }
            """);
    }

    private PsiMethodCallExpression singleCallIn() {
        Collection<PsiMethodCallExpression> calls =
            PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiMethodCallExpression.class);
        assertEquals("the fixture declares exactly one call", 1, calls.size());
        return calls.iterator().next();
    }

    // ------------------------------------------------------------------
    // Current behaviour
    // ------------------------------------------------------------------

    public void testCallStillResolvesToTheDeclaredMethod() {
        configureCaller();
        PsiMethod resolved = singleCallIn().resolveMethod();
        assertNotNull("the reference resolves - only the static scope is wrong", resolved);
        assertEquals("fromFile", resolved.getName());
    }

    public void testMemberIsNotStaticToThePsi() {
        configureCaller();
        PsiMethod resolved = singleCallIn().resolveMethod();
        assertNotNull(resolved);
        // Current behaviour. javac sees this member as static because
        // UtilityClassMutator sets the flag on the tree and the symbol; the PSI
        // sees the source modifiers only. A transformModifiers-based augment
        // provider for @UtilityClass would make this assertion read assertTrue.
        assertFalse("no plugin hook contributes static to a MAKE_STATIC member",
            resolved.hasModifierProperty(PsiModifier.STATIC));
    }

    public void testStaticQualifiedCallIsFlaggedAsAStaticContextError() {
        configureCaller();
        List<String> errors = errorsIn();
        // Current behaviour. The build is clean, the editor is not - the call
        // reads as an instance member referenced from a static context. This
        // assertion becomes assertTrue(errors.isEmpty()) once the modifier is
        // contributed to the PSI.
        assertFalse("expected the editor to disagree with javac, got no errors", errors.isEmpty());
        boolean staticComplaint = errors.stream().anyMatch(e -> e.contains("static"));
        assertTrue("expected a static-context error, got: " + errors, staticComplaint);
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------

    public void testWrittenStaticMemberIsClean() {
        myFixture.addFileToProject("NbtReader.java",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(members = UtilityClass.Members.MAKE_STATIC)
            public class NbtReader {
                public static String fromFile(String path) { return path; }
            }
            """);
        myFixture.configureByText("CleanCaller.java",
            """
            public class CleanCaller {
                public static String read() {
                    return NbtReader.fromFile("level.dat");
                }
            }
            """);
        List<String> errors = errorsIn();
        assertTrue("a member the author wrote static needs no contribution: " + errors,
            errors.isEmpty());
    }

}
