package dev.simplified.silentthrows.editor;

import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * Exercises {@link SilentThrowsExceptionHandler} directly rather than through
 * highlighting, so the answers are asserted independently of the extension being
 * registered.
 */
public class SilentThrowsExceptionHandlerTest extends LightJavaCodeInsightFixtureTestCase {

    private final SilentThrowsExceptionHandler handler = new SilentThrowsExceptionHandler();

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        // The default descriptor's mock JDK carries no java.lang.Exception, so a
        // fixture throwable has an unresolved supertype and no narrowing is
        // assignable to anything.
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
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
        myFixture.addFileToProject("Job.java", "public interface Job { String run(); }");
        myFixture.addFileToProject("Wire.java",
            """
            public class Wire {
                public static void send() throws Boom {}
            }
            """);
    }

    /** The call the caret sits in, in the file just configured. */
    private PsiMethodCallExpression callAtCaret() {
        PsiElement at = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(at, PsiMethodCallExpression.class);
        assertNotNull("no call expression at the caret", call);
        return call;
    }

    private PsiClassType exceptionType(String name) {
        PsiClassType type = PsiType.getTypeByName(name, getProject(),
            GlobalSearchScope.allScope(getProject()));
        assertNotNull("unresolved fixture exception " + name, type.resolve());
        return type;
    }

    private boolean handled(String name) {
        PsiMethodCallExpression call = callAtCaret();
        PsiElement top = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
        return handler.isHandled(call, exceptionType(name), top);
    }

    /**
     * The same question asked at a method reference. The element the platform
     * passes is the reference's own name element, which is where its unhandled
     * thrown types are reported.
     */
    private boolean handledAtMethodReference(String name) {
        PsiElement at = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        PsiMethodReferenceExpression reference =
            PsiTreeUtil.getParentOfType(at, PsiMethodReferenceExpression.class);
        assertNotNull("no method reference at the caret", reference);
        PsiElement place = reference.getReferenceNameElement();
        assertNotNull(place);
        PsiElement top = PsiTreeUtil.getParentOfType(reference, PsiMethod.class);
        return handler.isHandled(place, exceptionType(name), top);
    }

    public void testBareAnnotationCoversTheBody() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows
                void go() { Wire.se<caret>nd(); }
            }
            """);
        assertTrue(handled("Boom"));
    }

    public void testUnannotatedMethodIsNotCovered() {
        myFixture.configureByText("Foo.java",
            """
            public class Foo {
                void go() { Wire.se<caret>nd(); }
            }
            """);
        assertFalse(handled("Boom"));
    }

    /**
     * The boundary the whole feature turns on. A lambda body is its own
     * exception-analysis context and the wrap does not reach into it, so
     * answering true here would take the editor green on code javac rejects.
     */
    public void testLambdaBodyIsNotCovered() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows
                Job go() {
                    return () -> { Wire.se<caret>nd(); return "x"; };
                }
            }
            """);
        assertFalse(handled("Boom"));
    }

    /**
     * A method reference is a third exception-analysis context the wrap does not
     * reach. Its thrown types are checked against the function type it is
     * assigned to, which no enclosing {@code try} changes, so javac rejects this
     * and the editor has to agree.
     */
    public void testMethodReferenceIsNotCovered() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows
                void go() {
                    Runnable r = Wire::se<caret>nd;
                    r.run();
                }
            }
            """);
        assertFalse(handledAtMethodReference("Boom"));
    }

    public void testAnonymousClassBodyIsNotCovered() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows
                Runnable go() {
                    return new Runnable() {
                        public void run() { Wire.se<caret>nd(); }
                    };
                }
            }
            """);
        assertFalse(handled("Boom"));
    }

    public void testNarrowedValueCoversAssignableType() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows(Exception.class)
                void go() { Wire.se<caret>nd(); }
            }
            """);
        assertTrue(handled("Boom"));
    }

    public void testNarrowedValueDoesNotCoverUnrelatedType() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows(Bang.class)
                void go() { Wire.se<caret>nd(); }
            }
            """);
        assertFalse(handled("Boom"));
    }

    /**
     * An explicitly empty {@code value} names no type to catch, so the processor
     * falls back to {@code Throwable} and the build succeeds. Answering false
     * here would paint an unhandled exception on code that compiles clean.
     */
    public void testExplicitlyEmptyValueCoversEverything() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows({})
                void go() { Wire.se<caret>nd(); }
            }
            """);
        assertTrue(handled("Boom"));
    }

    public void testConstructorIsCovered() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                @SilentThrows
                Foo() { Wire.se<caret>nd(); }
            }
            """);
        assertTrue(handled("Boom"));
    }

    public void testFieldInitializerIsNotCovered() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.SilentThrows;
            public class Foo {
                int value = fail<caret>ing();
                @SilentThrows
                static int failing() { return 0; }
            }
            """);
        assertFalse(handled("Boom"));
    }

}
