package dev.simplified.classbuilder.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Exercises {@link GetterVisibilityInspection}: both triggers, both fix
 * families, and every near-miss shape that has to stay silent.
 */
public class GetterVisibilityInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new GetterVisibilityInspection());
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Getter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Getter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
            }
            """);
    }

    private void configure(String path, String source) {
        PsiFile file = myFixture.addFileToProject(path, source);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
    }

    /**
     * Puts the caret on the first occurrence of a marker, since a fix is only
     * offered where its problem was registered.
     */
    private void caretAt(String marker) {
        int offset = myFixture.getFile().getText().indexOf(marker);
        assertTrue("marker not found: " + marker, offset >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
    }

    private boolean reports(String fragment, HighlightSeverity severity) {
        List<HighlightInfo> infos = myFixture.doHighlighting();
        return infos.stream().anyMatch(info -> severity.equals(info.getSeverity())
            && info.getDescription() != null && info.getDescription().contains(fragment));
    }

    private boolean reportsAnything() {
        List<HighlightInfo> infos = myFixture.doHighlighting();
        return infos.stream().anyMatch(info -> info.getDescription() != null
            && (info.getDescription().contains("is generated public")
                || info.getDescription().contains("return their field and nothing else")));
    }

    // Trigger A - a generated accessor wider than its readers.

    public void testUnreadAccessorOffersBothNoneAndPrivate() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        assertTrue("an unread public accessor is reported",
            reports("nothing outside Widget reads it", HighlightSeverity.WEAK_WARNING));
        caretAt("label;");
        assertNotNull("deleting the accessor is one intent",
            myFixture.getAvailableIntention("Drop the accessor with @Getter(AccessLevel.NONE)"));
        assertNotNull("keeping it private is the other",
            myFixture.getAvailableIntention("Narrow the accessor to private"));
    }

    public void testNarrowingFixLeavesTheTypeLevelAnnotationAlone() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        caretAt("label;");
        IntentionAction fix = myFixture.findSingleIntention("Narrow the accessor to private");
        myFixture.launchAction(fix);
        String text = myFixture.getFile().getText();
        assertTrue("the field carries the override", text.contains("@Getter(AccessLevel.PRIVATE)"));
        assertTrue("the type-level annotation is untouched",
            text.contains("@Getter\npublic class Widget"));
    }

    public void testAccessorWrittenOnTheFieldIsRewrittenInPlace() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter(AccessLevel.PUBLIC) private String label;
            }
            """);
        caretAt("label;");
        myFixture.launchAction(myFixture.findSingleIntention("Narrow the accessor to private"));
        String text = myFixture.getFile().getText();
        assertTrue(text.contains("@Getter(AccessLevel.PRIVATE)"));
        assertFalse("no second annotation is added", text.contains("@Getter(AccessLevel.PUBLIC)"));
    }

    public void testPackageOnlyReaderNarrowsToPackage() {
        myFixture.addFileToProject("app/Neighbour.java",
            """
            package app;
            public class Neighbour {
                String read(Widget widget) { return widget.getLabel(); }
            }
            """);
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        assertTrue(reports("only Widget's own package reads it", HighlightSeverity.WEAK_WARNING));
        caretAt("label;");
        assertNotNull(myFixture.getAvailableIntention("Narrow the accessor to package"));
    }

    public void testSubclassOnlyReaderNarrowsToProtected() {
        myFixture.addFileToProject("sub/Child.java",
            """
            package sub;
            import app.Widget;
            public class Child extends Widget {
                String read() { return getLabel(); }
            }
            """);
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        assertTrue(reports("only subclasses of Widget read it", HighlightSeverity.WEAK_WARNING));
        caretAt("label;");
        assertNotNull(myFixture.getAvailableIntention("Narrow the accessor to protected"));
    }

    public void testUnrelatedOutsideReaderIsSilent() {
        myFixture.addFileToProject("other/Consumer.java",
            """
            package other;
            import app.Widget;
            public class Consumer {
                String read(Widget widget) { return widget.getLabel(); }
            }
            """);
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        assertFalse("a reader that no narrowing would keep must stay silent", reportsAnything());
    }

    public void testNonPublicAccessorIsSilent() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Getter;
            @Getter(AccessLevel.PROTECTED)
            public class Widget {
                private String label;
            }
            """);
        assertFalse("nothing to narrow below the width already written", reportsAnything());
    }

    public void testFieldLevelNoneIsSilent() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                @Getter(AccessLevel.NONE) private String label;
            }
            """);
        assertFalse("the field already opted out", reportsAnything());
    }

    public void testExcludedFieldIsSilent() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter(exclude = "label")
            public class Widget {
                private String label;
            }
            """);
        assertFalse("no accessor is generated for an excluded field", reportsAnything());
    }

    public void testHandWrittenAccessorSuppressesTriggerA() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
                public String getLabel() { return this.label.trim(); }
            }
            """);
        assertFalse("the author's own method's visibility is theirs", reportsAnything());
    }

    // Trigger B - hand-written accessors a @Getter subsumes.

    public void testTwoTrivialAccessorsArePromoted() {
        configure("app/Widget.java",
            """
            package app;
            public class Widget {
                private String label;
                private int size;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
            }
            """);
        assertTrue(reports("return their field and nothing else", HighlightSeverity.WARNING));

        caretAt("Widget {");
        myFixture.launchAction(
            myFixture.findSingleIntention("Replace the trivial accessors with @Getter"));
        String text = myFixture.getFile().getText();
        assertTrue("the type carries the annotation", text.contains("@Getter"));
        assertTrue("and carries it on the class",
            text.indexOf("@Getter") < text.indexOf("class Widget"));
        assertFalse("the subsumed accessor is gone", text.contains("getLabel"));
        assertFalse(text.contains("getSize"));
    }

    public void testFluentAccessorsPromoteWithFluentStyle() {
        configure("app/Widget.java",
            """
            package app;
            public class Widget {
                private String label;
                private int size;
                public String label() { return this.label; }
                public int size() { return size; }
            }
            """);
        caretAt("Widget {");
        myFixture.launchAction(
            myFixture.findSingleIntention("Replace the trivial accessors with @Getter"));
        assertTrue(myFixture.getFile().getText().contains("@Getter(style = NamingStyle.FLUENT)"));
    }

    public void testNonMatchingMethodsSurviveTheFix() {
        configure("app/Widget.java",
            """
            package app;
            public class Widget {
                private String label;
                private int size;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
                public String describe() { return this.label + size; }
            }
            """);
        caretAt("Widget {");
        myFixture.launchAction(
            myFixture.findSingleIntention("Replace the trivial accessors with @Getter"));
        assertTrue("a method that does work is left alone",
            myFixture.getFile().getText().contains("describe()"));
    }

    public void testSingleTrivialAccessorIsSilent() {
        configure("app/Widget.java",
            """
            package app;
            public class Widget {
                private String label;
                public String getLabel() { return this.label; }
            }
            """);
        assertFalse("one accessor is not a pattern", reportsAnything());
    }

    public void testClassAlreadyCarryingGetterIsSilent() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
                private int size;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
            }
            """);
        assertFalse("nothing to propose on a class that already asked",
            reports("return their field and nothing else", HighlightSeverity.WARNING));
    }

    // Trigger B near misses - each pairs one real accessor with one shape that
    // must not match, so a false match would be the second one needed to fire.

    private void assertNearMiss(String description, String second) {
        configure("app/Widget.java",
            """
            package app;
            import java.util.ArrayList;
            import java.util.List;
            public class Widget {
                private String label;
                private int size;
                private List<String> items = new ArrayList<>();
                private Object other;
                public String getLabel() { return this.label; }
            """ + second + """
            }
            """);
        assertFalse(description,
            reports("return their field and nothing else", HighlightSeverity.WARNING));
    }

    public void testNullCheckingAccessorIsNotMatched() {
        assertNearMiss("a null check is work",
            "    public Object getOther() { if (other == null) return \"\"; return other; }\n");
    }

    public void testDefensiveCopyIsNotMatched() {
        assertNearMiss("a defensive copy is work",
            "    public List<String> getItems() { return new ArrayList<>(this.items); }\n");
    }

    public void testCastIsNotMatched() {
        assertNearMiss("a cast is work",
            "    public String getOther() { return (String) this.other; }\n");
    }

    public void testTernaryIsNotMatched() {
        assertNearMiss("a ternary is work",
            "    public Object getOther() { return other == null ? \"\" : other; }\n");
    }

    public void testDifferentFieldIsNotMatched() {
        assertNearMiss("the name must match the field it returns",
            "    public Object getOther() { return this.label; }\n");
    }

    public void testWideningReturnIsNotMatched() {
        assertNearMiss("a widening return is a conversion",
            "    public long getSize() { return this.size; }\n");
    }

    public void testBoxingReturnIsNotMatched() {
        assertNearMiss("a boxing return is a conversion",
            "    public Integer getSize() { return this.size; }\n");
    }

    public void testAnnotatedAccessorIsNotMatched() {
        assertNearMiss("an annotation on the accessor is a contract elsewhere",
            "    @Deprecated public int getSize() { return this.size; }\n");
    }

    public void testStaticAccessorIsNotMatched() {
        configure("app/Widget.java",
            """
            package app;
            public class Widget {
                private String label;
                private static int shared;
                public String getLabel() { return this.label; }
                public static int getShared() { return shared; }
            }
            """);
        assertFalse("a static accessor is not the shape @Getter fans out over",
            reports("return their field and nothing else", HighlightSeverity.WARNING));
    }

    public void testInheritedFieldIsNotMatched() {
        myFixture.addFileToProject("app/Base.java",
            """
            package app;
            public class Base {
                protected int size;
            }
            """);
        configure("app/Widget.java",
            """
            package app;
            public class Widget extends Base {
                private String label;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
            }
            """);
        assertFalse("an inherited field is the parent's to annotate",
            reports("return their field and nothing else", HighlightSeverity.WARNING));
    }

    public void testSupertypeDeclaredAccessorIsNotMatched() {
        myFixture.addFileToProject("app/Labelled.java",
            """
            package app;
            public interface Labelled {
                String getLabel();
            }
            """);
        configure("app/Widget.java",
            """
            package app;
            public class Widget implements Labelled {
                private String label;
                private int size;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
            }
            """);
        assertFalse("@Getter cannot satisfy an inherited declaration",
            reports("return their field and nothing else", HighlightSeverity.WARNING));
    }

    public void testSynchronizedAccessorIsNotMatched() {
        assertNearMiss("the monitor is work a generated accessor never carries",
            "    public synchronized int getSize() { return this.size; }\n");
    }

    /**
     * A field-level annotation replaces the promoted type-level one outright,
     * so a narrowed level would generate a private accessor where a public
     * method was deleted.
     */
    public void testFieldLevelNarrowedGetterIsNotMatched() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter(AccessLevel.PRIVATE) private String label;
                private int size;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
            }
            """);
        assertFalse("a narrowed field-level level would not regenerate the deleted method",
            reports("return their field and nothing else", HighlightSeverity.WARNING));
    }

    /** Same shape, where the field-level annotation renames the accessor. */
    public void testFieldLevelRenamedGetterIsNotMatched() {
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter(name = "fetch{}") private String label;
                private int size;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
            }
            """);
        assertFalse("the field-level name would mint fetchLabel(), not the deleted getLabel()",
            reports("return their field and nothing else", HighlightSeverity.WARNING));
    }

    /**
     * A cross-package subclass reaching the accessor through the superclass type
     * loses it the moment it is protected, so nothing may be proposed.
     */
    public void testCrossPackageQualifiedSubclassReadIsSilent() {
        myFixture.addFileToProject("sub/Child.java",
            """
            package sub;
            import app.Widget;
            public class Child extends Widget {
                String read(Widget other) { return other.getLabel(); }
            }
            """);
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
            }
            """);
        assertFalse("protected would not survive the qualifier", reportsAnything());
    }

    /** An enum's constants are fields of the enum type, and generate nothing. */
    public void testEnumConstantIsNotReported() {
        configure("app/Color.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            @Getter
            public enum Color {
                RED, GREEN;
                private final int rgb = 0;
            }
            """);
        assertFalse("no accessor is generated for a constant", reports("getRED", HighlightSeverity.WEAK_WARNING));
        assertFalse(reports("getGREEN", HighlightSeverity.WEAK_WARNING));
    }

    /**
     * The generated set has to be the deleted set - a field nobody published
     * must not gain a public accessor as a side effect.
     */
    public void testPromotionExcludesFieldsWithoutAnAccessor() {
        configure("app/Widget.java",
            """
            package app;
            public class Widget {
                private String label;
                private int size;
                private String password;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
            }
            """);
        caretAt("Widget {");
        myFixture.launchAction(
            myFixture.findSingleIntention("Replace the trivial accessors with @Getter"));
        assertTrue("the unpublished field is excluded",
            myFixture.getFile().getText().contains("exclude = \"password\""));
    }

    /**
     * Narrowing must not rename. A field-level override replaces the type-level
     * annotation outright, so its naming has to travel with the access level.
     */
    public void testNarrowingCarriesTheTypeLevelStyle() {
        myFixture.addFileToProject("app/Neighbour.java",
            """
            package app;
            public class Neighbour {
                String read(Widget widget) { return widget.label(); }
            }
            """);
        configure("app/Widget.java",
            """
            package app;
            import dev.simplified.annotations.Getter;
            import dev.simplified.annotations.NamingStyle;
            @Getter(style = NamingStyle.FLUENT)
            public class Widget {
                private String label;
            }
            """);
        assertTrue(reports("only Widget's own package reads it", HighlightSeverity.WEAK_WARNING));
        caretAt("label;");
        myFixture.launchAction(myFixture.findSingleIntention("Narrow the accessor to package"));
        String text = myFixture.getFile().getText();
        assertTrue("the override carries the style across",
            text.contains("@Getter(AccessLevel.PACKAGE, style = NamingStyle.FLUENT)"));
    }

    public void testMethodReferenceAbortsTheFix() {
        myFixture.addFileToProject("app/Uses.java",
            """
            package app;
            import java.util.function.Function;
            public class Uses {
                Function<Widget, String> read = Widget::getLabel;
            }
            """);
        configure("app/Widget.java",
            """
            package app;
            public class Widget {
                private String label;
                private int size;
                public String getLabel() { return this.label; }
                public int getSize() { return size; }
            }
            """);
        String before = myFixture.getFile().getText();
        caretAt("Widget {");
        myFixture.launchAction(
            myFixture.findSingleIntention("Replace the trivial accessors with @Getter"));
        assertEquals("the fix aborts rather than breaking the method reference",
            before, myFixture.getFile().getText());
    }

}
