package dev.simplified.shared.psi;

import com.intellij.find.findUsages.JavaFindUsagesHandlerFactory;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.usageView.UsageInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Exercises {@link GeneratedMemberFindUsagesHandlerFactory}: a call to a member
 * the plugin synthesised has to count as a usage of the field it was minted
 * from, since that field is the only declaration a reader can put a caret on.
 */
public class GeneratedMemberFindUsagesHandlerFactoryTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        // A real JDK so the builder path classifies String and collection
        // fields against a populated java.util hierarchy.
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

    /** The source text of every reference reported as a usage of {@code target}. */
    private List<String> usageTexts(PsiElement target) {
        List<String> out = new ArrayList<>();
        for (UsageInfo usage : myFixture.findUsages(target)) {
            PsiElement element = usage.getElement();
            out.add(element == null ? "<null>" : element.getText());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public void testGeneratedGetterCallIsAUsageOfTheField() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                int width(Widget widget) { return widget.getLabel().length(); }
            }
            """);

        assertEquals("the only reference to the property is the call to the synthesised getter",
            List.of("widget.getLabel"), usageTexts(widget.findFieldByName("label", false)));
    }

    public void testGeneratedSetterCallIsAUsageOfTheField() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Setter;
            @Setter
            public class Widget {
                private String label;
            }
            """);
        myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                void rename(Widget widget) { widget.setLabel("x"); }
            }
            """);

        assertEquals(List.of("widget.setLabel"),
            usageTexts(widget.findFieldByName("label", false)));
    }

    public void testFluentAccessorIsFoundUnderItsOwnName() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            import dev.simplified.annotations.NamingStyle;
            @Getter(style = NamingStyle.FLUENT)
            public class Widget {
                private String label;
            }
            """);
        myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                int width(Widget widget) { return widget.label().length(); }
            }
            """);

        assertEquals("the name comes from the naming scheme, not from a bean-shaped guess",
            List.of("widget.label"), usageTexts(widget.findFieldByName("label", false)));
    }

    /**
     * The reads the author wrote against the field are the Java handler's
     * answer, and folding the synthesised accessor in must not cost them.
     */
    public void testWrittenFieldReadsSurviveAlongsideTheAccessorCall() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
                int width() { return label.length(); }
            }
            """);
        myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                int width(Widget widget) { return widget.getLabel().length(); }
            }
            """);

        List<String> usages = usageTexts(widget.findFieldByName("label", false));
        assertEquals("both the written read and the generated call, got " + usages, 2, usages.size());
        assertTrue(usages.contains("label"));
        assertTrue(usages.contains("widget.getLabel"));
    }

    // ------------------------------------------------------------------
    // Builder setters, which live on the nested class
    // ------------------------------------------------------------------

    public void testBuilderSetterCallIsAUsageOfTheField() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget {
                String label;
            }
            """);
        myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                Widget make() { return Widget.builder().label("x").build(); }
            }
            """);

        List<String> usages = usageTexts(widget.findFieldByName("label", false));
        assertEquals("the setter sits on the nested Builder, so the walk has to reach it, got "
            + usages, 1, usages.size());
        assertTrue(usages.stream().anyMatch(text -> text.endsWith(".label")));
    }

    public void testBuilderSetterCallIsAUsageOfTheRecordComponent() {
        PsiClass point = configure("Point",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public record Point(String label) { }
            """);
        myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                Point make() { return Point.builder().label("x").build(); }
            }
            """);

        PsiRecordComponent label = point.getRecordComponents()[0];
        List<String> usages = usageTexts(label);
        assertTrue("a component backs the same setters a field does, got " + usages,
            usages.stream().anyMatch(text -> text.endsWith(".label")));
    }

    // ------------------------------------------------------------------
    // Declining
    // ------------------------------------------------------------------

    public void testFieldBackingNothingIsLeftToTheJavaHandler() {
        PsiClass plain = configure("Plain",
            """
            public class Plain {
                public String label;
            }
            """);
        myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                int width(Plain plain) { return plain.label.length(); }
            }
            """);

        PsiField label = plain.findFieldByName("label", false);
        assertFalse("nothing was synthesised here, so the field is not ours to claim",
            new GeneratedMemberFindUsagesHandlerFactory().canFindUsages(label));
        assertEquals("and the ordinary search is unaffected",
            List.of("plain.label"), usageTexts(label));
    }

    public void testAnnotatedFieldIsClaimed() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        assertTrue(new GeneratedMemberFindUsagesHandlerFactory()
            .canFindUsages(widget.findFieldByName("label", false)));
    }

    // ------------------------------------------------------------------
    // Calls made through a supertype
    // ------------------------------------------------------------------

    /**
     * A call written against the interface resolves to the interface's own
     * method, so reaching it means searching that declaration - which the
     * platform puts behind an option, and so does this.
     */
    public void testSupertypeCallFollowsTheBaseAccessorOption() {
        myFixture.addFileToProject("Named.java",
            """
            public interface Named {
                String getLabel();
            }
            """);
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget implements Named {
                private String label;
            }
            """);
        myFixture.addFileToProject("Caller.java",
            """
            public class Caller {
                String direct(Widget widget) { return widget.getLabel(); }
                String viaInterface(Named named) { return named.getLabel(); }
            }
            """);

        PsiField label = widget.findFieldByName("label", false);
        assertEquals("off by default, the interface's own call sites stay its own",
            List.of("widget.getLabel"), usageTexts(label));

        JavaFindUsagesHandlerFactory java = JavaFindUsagesHandlerFactory.getInstance(getProject());
        boolean previous = java.getFindVariableOptions().isSearchForBaseAccessors;
        java.getFindVariableOptions().isSearchForBaseAccessors = true;
        try {
            List<String> usages = usageTexts(label);
            assertTrue("the call on the declaring type is kept, got " + usages,
                usages.contains("widget.getLabel"));
            assertTrue("and the one through the interface is added, got " + usages,
                usages.contains("named.getLabel"));
        } finally {
            java.getFindVariableOptions().isSearchForBaseAccessors = previous;
        }
    }

}
