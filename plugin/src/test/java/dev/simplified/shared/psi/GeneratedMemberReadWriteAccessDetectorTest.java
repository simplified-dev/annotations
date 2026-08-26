package dev.simplified.shared.psi;

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * Exercises {@link GeneratedMemberReadWriteAccessDetector}: a call that assigns
 * a slot has to read as a write of it, whatever the naming scheme spelled the
 * method.
 */
public class GeneratedMemberReadWriteAccessDetectorTest extends LightJavaCodeInsightFixtureTestCase {

    private final ReadWriteAccessDetector detector = new GeneratedMemberReadWriteAccessDetector();

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        GeneratedMemberStubs.install(myFixture);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiClass configure(String name, String source) {
        PsiFile file = myFixture.configureByText(name + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    /** The reference expression of the call to {@code name} in {@code file}. */
    private static PsiReferenceExpression callTo(PsiFile file, String name) {
        for (PsiMethodCallExpression call
            : PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class)) {
            if (name.equals(call.getMethodExpression().getReferenceName())) {
                return call.getMethodExpression();
            }
        }
        throw new AssertionError("no call to " + name + " in " + file.getName());
    }

    /** The reference expression naming {@code field} in {@code file}. */
    private static PsiReferenceExpression readOf(PsiFile file, String field) {
        for (PsiReferenceExpression reference
            : PsiTreeUtil.findChildrenOfType(file, PsiReferenceExpression.class)) {
            if (field.equals(reference.getReferenceName())
                && reference.resolve() instanceof PsiField) {
                return reference;
            }
        }
        throw new AssertionError("no reference to " + field + " in " + file.getName());
    }

    // ------------------------------------------------------------------
    // Generated setters
    // ------------------------------------------------------------------

    public void testBeanSetterCallIsAWrite() {
        configure("Widget",
            """
            import dev.simplified.annotations.Setter;
            @Setter
            public class Widget {
                private String label;
            }
            """);
        PsiFile caller = myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                void rename(Widget widget) { widget.setLabel("x"); }
            }
            """);

        assertEquals(ReadWriteAccessDetector.Access.Write,
            detector.getExpressionAccess(callTo(caller, "setLabel")));
    }

    /**
     * The shape the inherited analysis cannot recognise: it identifies a setter
     * by a {@code set} prefix, and this one has none.
     */
    public void testFluentSetterCallIsAWrite() {
        configure("Widget",
            """
            import dev.simplified.annotations.NamingStyle;
            import dev.simplified.annotations.Setter;
            @Setter(style = NamingStyle.FLUENT)
            public class Widget {
                private String label;
            }
            """);
        PsiFile caller = myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                void rename(Widget widget) { widget.label("x"); }
            }
            """);

        assertEquals(ReadWriteAccessDetector.Access.Write,
            detector.getExpressionAccess(callTo(caller, "label")));
    }

    public void testBuilderSetterCallIsAWrite() {
        configure("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget {
                String label;
            }
            """);
        PsiFile caller = myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                Widget make() { return Widget.builder().label("x").build(); }
            }
            """);

        assertEquals("a whole builder chain otherwise reads as reads of the fields it assigns",
            ReadWriteAccessDetector.Access.Write,
            detector.getExpressionAccess(callTo(caller, "label")));
    }

    public void testGeneratedGetterCallIsARead() {
        configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        PsiFile caller = myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                int width(Widget widget) { return widget.getLabel().length(); }
            }
            """);

        assertEquals(ReadWriteAccessDetector.Access.Read,
            detector.getExpressionAccess(callTo(caller, "getLabel")));
    }

    // ------------------------------------------------------------------
    // Everything the inherited analysis already answers
    // ------------------------------------------------------------------

    public void testWrittenFieldAccessKeepsTheInheritedAnswer() {
        configure("Plain",
            """
            public class Plain {
                public String label;
            }
            """);
        PsiFile write = myFixture.addFileToProject("Write.java",
            """
            public class Write {
                void set(Plain plain) { plain.label = "x"; }
            }
            """);
        PsiFile read = myFixture.addFileToProject("Read.java",
            """
            public class Read {
                int len(Plain plain) { return plain.label.length(); }
            }
            """);

        assertEquals(ReadWriteAccessDetector.Access.Write,
            detector.getExpressionAccess(readOf(write, "label")));
        assertEquals(ReadWriteAccessDetector.Access.Read,
            detector.getExpressionAccess(readOf(read, "label")));
    }

    public void testHandWrittenSetterKeepsTheInheritedAnswer() {
        configure("Plain",
            """
            public class Plain {
                private String label;
                public void setLabel(String label) { this.label = label; }
            }
            """);
        PsiFile caller = myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                void rename(Plain plain) { plain.setLabel("x"); }
            }
            """);

        assertEquals(ReadWriteAccessDetector.Access.Write,
            detector.getExpressionAccess(callTo(caller, "setLabel")));
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    public void testThisDetectorAnswersForAField() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Setter;
            @Setter
            public class Widget {
                private String label;
            }
            """);

        ReadWriteAccessDetector found =
            ReadWriteAccessDetector.findDetector(widget.findFieldByName("label", false));
        assertTrue("the detector that answers first is the one the platform uses, got " + found,
            found instanceof GeneratedMemberReadWriteAccessDetector);
    }

}
