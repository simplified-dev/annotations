package dev.simplified.utility.editor;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
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
 * whose members javac sees as {@code static} however the author wrote them.
 *
 * <p>The processor adds the modifier by mutating the javac AST, which leaves
 * nothing in the PSI to read. {@link UtilityClassAugmentProvider} contributes it
 * back, so a class-qualified call is as clean in the editor as it is in the
 * build; without it the call reads as an instance member referenced from a
 * static context, on source that compiles.
 *
 * <p>The control cases matter as much as the positive ones: a member the author
 * wrote {@code static} must be unaffected, and the reference has to keep
 * resolving, since only the modifier ever diverged.
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

    public void testMemberIsStaticToThePsi() {
        configureCaller();
        PsiMethod resolved = singleCallIn().resolveMethod();
        assertNotNull(resolved);
        assertTrue("the provider contributes static to a MAKE_STATIC member",
            resolved.hasModifierProperty(PsiModifier.STATIC));
    }

    public void testStaticQualifiedCallIsClean() {
        configureCaller();
        List<String> errors = errorsIn();
        assertTrue("the editor must agree with javac, got: " + errors, errors.isEmpty());
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

    /** The class the fixture declares, by name, for reading members directly. */
    private PsiClass configureClass(String name, String source) {
        PsiFile file = myFixture.configureByText(name + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    public void testFieldIsStaticToThePsi() {
        PsiClass target = configureClass("Registry",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(members = UtilityClass.Members.MAKE_STATIC)
            public class Registry {
                private String cache = "";
            }
            """);
        PsiField cache = target.findFieldByName("cache", false);
        assertNotNull(cache);
        assertTrue("a field is a member the policy rewrites too",
            cache.hasModifierProperty(PsiModifier.STATIC));
    }

    public void testRequireStaticContributesNothing() {
        PsiClass target = configureClass("Strict",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass
            public class Strict {
                public String read() { return ""; }
            }
            """);
        PsiMethod read = target.findMethodsByName("read", false)[0];
        assertFalse("the default mode reports an instance member rather than rewriting it",
            read.hasModifierProperty(PsiModifier.STATIC));
    }

    public void testConstructorIsNeverStatic() {
        PsiClass target = configureClass("Holder",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(members = UtilityClass.Members.MAKE_STATIC)
            public class Holder {
                Holder() { }
            }
            """);
        PsiMethod[] constructors = target.getConstructors();
        assertEquals(1, constructors.length);
        assertFalse("a static constructor does not exist",
            constructors[0].hasModifierProperty(PsiModifier.STATIC));
    }

    public void testNestedTypeNeedsTheOptIn() {
        PsiClass target = configureClass("Outer",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(members = UtilityClass.Members.MAKE_STATIC)
            public class Outer {
                class Inner { }
            }
            """);
        PsiClass inner = target.findInnerClassByName("Inner", false);
        assertNotNull(inner);
        assertFalse("nestedTypes is a separate opt-in, and the only one that can change "
            + "a nested type's meaning", inner.hasModifierProperty(PsiModifier.STATIC));
    }

    public void testNestedTypeIsStaticUnderTheOptIn() {
        PsiClass target = configureClass("Opted",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(members = UtilityClass.Members.MAKE_STATIC, nestedTypes = true)
            public class Opted {
                class Inner { }
            }
            """);
        PsiClass inner = target.findInnerClassByName("Inner", false);
        assertNotNull(inner);
        assertTrue(inner.hasModifierProperty(PsiModifier.STATIC));
    }

    /**
     * A local class is declared in a method body, which javac's round scan never
     * descends into, so the annotation is a no-op there on both sides.
     */
    public void testLocalClassIsUntouched() {
        PsiClass target = configureClass("WithLocal",
            """
            import dev.simplified.annotations.UtilityClass;
            @UtilityClass(members = UtilityClass.Members.MAKE_STATIC, nestedTypes = true)
            public class WithLocal {
                public void run() {
                    class Local { }
                }
            }
            """);
        Collection<PsiClass> locals = PsiTreeUtil.findChildrenOfType(target, PsiClass.class);
        assertEquals("the fixture declares exactly one local class", 1, locals.size());
        assertFalse("a local class is not a member of the target",
            locals.iterator().next().hasModifierProperty(PsiModifier.STATIC));
    }

}
