package dev.simplified.shared.psi;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * Exercises {@link GeneratedMemberRenameProcessor} and its two companions: a
 * generated member is spelled from the slot behind it, so renaming the slot has
 * to carry every call site of that member with it.
 */
public class GeneratedMemberRenameTest extends LightJavaCodeInsightFixtureTestCase {

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

    private void rename(PsiClass owner, String field, String newName) {
        myFixture.renameElement(owner.findFieldByName(field, false), newName);
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public void testRenamingAFieldRenamesItsGeneratedGetterCalls() {
        PsiClass widget = configure("Widget",
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

        rename(widget, "label", "caption");

        assertTrue("the call site has to follow the field, got " + caller.getText(),
            caller.getText().contains("widget.getCaption()"));
    }

    /** The name comes from the scheme that spelled the old one, not from a prefix. */
    public void testRenamingAFieldRenamesItsFluentAccessorCalls() {
        PsiClass widget = configure("Widget",
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

        rename(widget, "label", "caption");

        assertTrue("got " + caller.getText(), caller.getText().contains("widget.caption(\"x\")"));
    }

    /**
     * {@code @Lazy} mints its own getter and names it through the same scheme,
     * so a rename has to reach it the same way.
     */
    public void testRenamingALazyFieldRenamesItsGetterCalls() {
        PsiClass holder = configure("Holder",
            """
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.NamingStyle;
            public class Holder {
                @Lazy(style = NamingStyle.FLUENT)
                private String label = "x";
            }
            """);
        PsiFile caller = myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                int width(Holder holder) { return holder.label().length(); }
            }
            """);

        rename(holder, "label", "caption");

        assertTrue("got " + caller.getText(), caller.getText().contains("holder.caption()"));
    }

    // ------------------------------------------------------------------
    // Builder setters
    // ------------------------------------------------------------------

    public void testRenamingAFieldRenamesItsBuilderSetterCalls() {
        PsiClass widget = configure("Widget",
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

        rename(widget, "label", "caption");

        assertTrue("got " + caller.getText(), caller.getText().contains(".caption(\"x\")"));
    }

    /**
     * The singular name a {@code @Collector} falls back to is derived from the
     * slot, so it has to follow the slot too - which is the whole reason the
     * new names come from the dispatch rather than from a prefix swap.
     */
    public void testRenamingACollectorFieldFollowsThroughToItsSingularNames() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            @ClassBuilder
            public class Widget {
                @Collector(singular = true, clearable = true) List<String> items;
            }
            """);
        PsiFile caller = myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                Widget make() {
                    return Widget.builder().items("a").addItem("b").clearItems().build();
                }
            }
            """);

        rename(widget, "items", "tags");

        String text = caller.getText();
        assertTrue("bulk setter, got " + text, text.contains(".tags(\"a\")"));
        assertTrue("single-element add, got " + text, text.contains(".addTag(\"b\")"));
        assertTrue("clear, got " + text, text.contains(".clearTags()"));
    }

    /**
     * A name an annotation pins does not follow the slot, and the pairing has
     * to leave it alone rather than rewrite it to something the build will not
     * generate.
     */
    public void testANegatedFlagKeepsThePinnedName() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Negate;
            @ClassBuilder
            public class Widget {
                @Negate("closed") boolean open;
            }
            """);
        PsiFile caller = myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                Widget make() { return Widget.builder().open().closed().build(); }
            }
            """);

        rename(widget, "open", "expanded");

        String text = caller.getText();
        assertTrue("the slot's own flag follows it, got " + text, text.contains(".expanded()"));
        assertTrue("the pinned one does not, got " + text, text.contains(".closed()"));
    }

    // ------------------------------------------------------------------
    // Renaming from the other end
    // ------------------------------------------------------------------

    public void testRenamingTheGeneratedAccessorRedirectsToTheField() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        PsiMethod getter = widget.findMethodsByName("getLabel", false)[0];

        RenamePsiElementProcessor processor = RenamePsiElementProcessor.forElement(getter);
        assertTrue("got " + processor, processor instanceof GeneratedMemberRenameSubstitutor);
        assertEquals("the field is the declaration a rename can move",
            widget.findFieldByName("label", false),
            processor.substituteElementToRename(getter, null));
    }

    public void testRenamingAGeneratedAccessorRewritesTheFieldAndItsCalls() {
        PsiClass widget = configure("Widget",
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
        PsiMethod getter = widget.findMethodsByName("getLabel", false)[0];

        myFixture.renameElement(
            RenamePsiElementProcessor.forElement(getter).substituteElementToRename(getter, null),
            "caption");

        assertNotNull("the field carries the new name",
            widget.findFieldByName("caption", false));
        assertTrue("got " + caller.getText(), caller.getText().contains("widget.getCaption()"));
    }

    // ------------------------------------------------------------------
    // Refusing
    // ------------------------------------------------------------------

    public void testAMemberStandingForNoSlotIsRefused() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget {
                String label;
            }
            """);
        PsiMethod bootstrap = widget.findMethodsByName("builder", false)[0];

        assertTrue("builder() is spelled from the annotation, not from a field",
            new GeneratedMemberRenameVetoHandler().isAvailableOnDataContext(contextOf(bootstrap)));
    }

    public void testAGeneratedAccessorIsNotRefused() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        PsiMethod getter = widget.findMethodsByName("getLabel", false)[0];

        assertFalse("it stands for a field, so it is redirected rather than refused",
            new GeneratedMemberRenameVetoHandler().isAvailableOnDataContext(contextOf(getter)));
    }

    public void testAWrittenMemberIsNotRefused() {
        PsiClass plain = configure("Plain",
            """
            public class Plain {
                private String label;
                public String getLabel() { return label; }
            }
            """);
        PsiMethod getter = plain.findMethodsByName("getLabel", false)[0];

        assertFalse(new GeneratedMemberRenameVetoHandler().isAvailableOnDataContext(contextOf(getter)));
    }

    private static DataContext contextOf(PsiMethod method) {
        return SimpleDataContext.builder().add(CommonDataKeys.PSI_ELEMENT, method).build();
    }

    // ------------------------------------------------------------------
    // Declining
    // ------------------------------------------------------------------

    public void testAFieldBackingNothingIsLeftToTheJavaProcessor() {
        PsiClass plain = configure("Plain",
            """
            public class Plain {
                public String label;
            }
            """);
        PsiField label = plain.findFieldByName("label", false);

        assertFalse("nothing was synthesised here, so the field is not ours to claim",
            new GeneratedMemberRenameProcessor().canProcessElement(label));
    }

}
