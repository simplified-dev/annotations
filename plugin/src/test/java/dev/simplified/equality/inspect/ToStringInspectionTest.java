package dev.simplified.equality.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Edit-time behaviour of {@link ToStringInspection}, including the two places it
 * deliberately parts company with its equality sibling - a written
 * {@code toString} is kept rather than refused, and a {@code transient} member
 * is still state a dump wants.
 */
public class ToStringInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(new ToStringInspection());
        WholeObjectTestSources.install(myFixture);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

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
        return myFixture.doHighlighting().stream()
            .anyMatch(info -> severity.equals(info.getSeverity())
                && info.getDescription() != null && info.getDescription().contains(fragment));
    }

    /**
     * Every complaint the daemon makes about the configured file, at the three
     * severities an inspection can raise.
     */
    private List<String> complaints() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getDescription() == null) continue;
            if (!HighlightSeverity.ERROR.equals(info.getSeverity())
                && !HighlightSeverity.WARNING.equals(info.getSeverity())
                && !HighlightSeverity.WEAK_WARNING.equals(info.getSeverity())) continue;
            out.add(info.getDescription());
        }
        return out;
    }

    private void assertSilent(String message) {
        assertEquals(message, List.of(), complaints());
    }

    /** A stub type, resolved through its owner where the name is a nested one. */
    private PsiClass resolve(String fqn) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(getProject());
        PsiClass direct = facade.findClass(fqn, scope);
        if (direct != null) return direct;
        int dot = fqn.lastIndexOf('.');
        PsiClass owner = dot < 0 ? null : facade.findClass(fqn.substring(0, dot), scope);
        return owner == null ? null : owner.findInnerClassByName(fqn.substring(dot + 1), false);
    }

    // ------------------------------------------------------------------
    // The fixture project itself
    // ------------------------------------------------------------------

    /**
     * The fixture never sees the real library sources, so an annotation that
     * stops resolving is indistinguishable from an inspection that stops
     * running - and most of the assertions in this class are that nothing was
     * reported, which passes either way.
     */
    public void testEveryAnnotationStubResolves() {
        for (Map.Entry<String, List<String>> stub : WholeObjectTestSources.INSTALLED.entrySet()) {
            PsiClass declared = resolve(stub.getKey());
            assertNotNull(stub.getKey() + " does not resolve in the fixture project, so the "
                + "inspection declines to read it and every assertion that nothing is reported "
                + "passes without the check ever running", declared);
            for (String attribute : stub.getValue()) {
                assertEquals(stub.getKey() + " declares no '" + attribute + "', which the "
                    + "inspection reads - an attribute missing from a stub is silence rather "
                    + "than a failure", 1, declared.findMethodsByName(attribute, false).length);
            }
        }
    }

    // ------------------------------------------------------------------
    // Target kinds
    // ------------------------------------------------------------------

    public void testAnnotationTypeIsRejected() {
        configure("app/Marker.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public @interface Marker { }
            """);
        assertTrue(reports("on the annotation type Marker", HighlightSeverity.ERROR));
    }

    public void testInterfaceIsRejected() {
        configure("app/Named.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public interface Named {
                String name();
            }
            """);
        assertTrue(reports("on the interface Named", HighlightSeverity.ERROR));
    }

    public void testInterfaceCarryingClassBuilderIsSilent() {
        configure("app/Named.java",
            """
            package app;
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.ToString;
            @ClassBuilder
            @ToString
            public interface Named {
                String name();
            }
            """);
        assertSilent("the generated implementation already renders its own accessors");
    }

    public void testEnumIsRejected() {
        configure("app/Mode.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public enum Mode { A, B }
            """);
        assertTrue(reports("on the enum Mode", HighlightSeverity.ERROR));
    }

    public void testRecordIsAccepted() {
        configure("app/Point.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public record Point(int x, int y) { }
            """);
        assertSilent("a record's own rendering is exactly the shape this replaces");
    }

    // ------------------------------------------------------------------
    // A toString the author already wrote
    // ------------------------------------------------------------------

    /**
     * The deliberate asymmetry with the equality pair. A written
     * {@code toString} is a diagnostic rather than a contract, so the annotation
     * becoming a no-op is worth a prompt and not a refusal.
     */
    public void testDeclaredToStringIsAPromptRatherThanAnError() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Ping {
                private String motd;
                @Override
                public String toString() { return this.motd; }
            }
            """);
        assertTrue("the reader is told the annotation generates nothing",
            reports("which already declares toString", HighlightSeverity.WEAK_WARNING));
        assertFalse("and the build is not broken over it",
            reports("which already declares toString", HighlightSeverity.ERROR));
    }

    // ------------------------------------------------------------------
    // Supertypes
    // ------------------------------------------------------------------

    public void testFinalToStringInASupertypeIsRejected() {
        myFixture.addFileToProject("app/Sealed.java",
            """
            package app;
            public class Sealed {
                @Override
                public final String toString() { return ""; }
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Child extends Sealed {
                private String label;
            }
            """);
        assertTrue(reports("app.Sealed declares it final", HighlightSeverity.ERROR));
    }

    public void testNonFinalToStringInASupertypeIsClean() {
        myFixture.addFileToProject("app/Base.java",
            """
            package app;
            public class Base {
                @Override
                public String toString() { return ""; }
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Child extends Base {
                private String label;
            }
            """);
        assertSilent("an override is what the annotation is for");
    }

    // ------------------------------------------------------------------
    // callSuper
    // ------------------------------------------------------------------

    public void testCallSuperYesOnARootTypeIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.ToString;
            @ToString(callSuper = CallSuper.YES)
            public class Ping {
                private String motd;
            }
            """);
        assertTrue(reports("whose superclass supplies no implementation to call",
            HighlightSeverity.ERROR));
    }

    public void testCallSuperYesWithASuperclassIsClean() {
        myFixture.addFileToProject("app/Base.java",
            """
            package app;
            public class Base {
                @Override
                public String toString() { return ""; }
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.ToString;
            @ToString(callSuper = CallSuper.YES)
            public class Child extends Base {
                private String label;
            }
            """);
        assertSilent("there is an implementation to prefix the dump with");
    }

    public void testCallSuperNoOnARootTypeIsClean() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.ToString;
            @ToString(callSuper = CallSuper.NO)
            public class Ping {
                private String motd;
            }
            """);
        assertSilent("declining to call is always expressible");
    }

    // ------------------------------------------------------------------
    // of / exclude
    // ------------------------------------------------------------------

    public void testOfAndExcludeTogetherIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString(of = "motd", exclude = "players")
            public class Ping {
                private String motd;
                private int players;
            }
            """);
        assertTrue(reports("sets both 'of' and 'exclude'", HighlightSeverity.ERROR));
    }

    public void testOfNamingNothingIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString(of = {"motd", "ghost"})
            public class Ping {
                private String motd;
            }
            """);
        assertTrue(reports("(of) names 'ghost'", HighlightSeverity.ERROR));
    }

    public void testExcludeNamingNothingIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString(exclude = "favicons")
            public class Ping {
                private String motd;
                private String favicon;
            }
            """);
        assertTrue(reports("(exclude) names 'favicons'", HighlightSeverity.ERROR));
    }

    /**
     * The second deliberate asymmetry: a {@code transient} member is outside the
     * value but still state a debugger wants, so naming it here is a name the
     * selection reaches.
     */
    public void testOfNamingATransientFieldIsAccepted() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString(of = "cache")
            public class Ping {
                private String motd;
                private transient String cache;
            }
            """);
        assertSilent("@ToString keeps what @EqualsAndHashCode drops");
    }

    public void testOfNamingAnIncludedMethodIsAccepted() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringInclude;
            @ToString(of = "latency")
            public class Ping {
                private String motd;
                @ToStringInclude
                public long latency() { return 0L; }
            }
            """);
        assertSilent("the include marker puts the method into the selection");
    }

    /**
     * The same method without the marker: reachable, but only once it carries
     * one, and the bare report never said so.
     */
    public void testOfNamingAnUnmarkedMethodNamesTheMarker() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString(of = "latency")
            public class Ping {
                private String motd;
                public long latency() { return 0L; }
            }
            """);
        assertTrue(reports("names 'latency', which is a method rather than a field - mark it "
            + "@ToStringInclude to make it a member", HighlightSeverity.ERROR));
    }

    /** A shape the marker could not rescue keeps the report that names no remedy. */
    public void testOfNamingAnUnusableMethodKeepsThePlainReport() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString(of = "scaled")
            public class Ping {
                private int players;
                public int scaled(int by) { return this.players * by; }
            }
            """);
        assertTrue(reports("names 'scaled', which is not a member this selection reaches",
            HighlightSeverity.ERROR));
    }

    // ------------------------------------------------------------------
    // The marker pair
    // ------------------------------------------------------------------

    public void testBothMarkersOnOneFieldIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringExclude;
            import dev.simplified.annotations.ToStringInclude;
            @ToString
            public class Ping {
                @ToStringExclude @ToStringInclude private String motd;
            }
            """);
        assertTrue(reports("'motd' carries both the include and exclude markers",
            HighlightSeverity.ERROR));
    }

    /** A record's markers live on its components, which {@code visitField} never reaches. */
    public void testBothMarkersOnOneRecordComponentIsRejected() {
        configure("app/Point.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringExclude;
            import dev.simplified.annotations.ToStringInclude;
            @ToString
            public record Point(@ToStringExclude @ToStringInclude int x, int y) { }
            """);
        assertTrue(reports("'x' carries both the include and exclude markers",
            HighlightSeverity.ERROR));
    }

    public void testExcludeOnARecordComponentIsAccepted() {
        configure("app/Point.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringExclude;
            @ToString
            public record Point(int x, @ToStringExclude int noisy) { }
            """);
        assertSilent("a component is excluded the same way a field is");
    }

    public void testMarkerOnAnUnannotatedTypeIsInert() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToStringExclude;
            public class Ping {
                @ToStringExclude private String motd;
            }
            """);
        assertTrue(reports("@ToStringExclude is never read", HighlightSeverity.WARNING));
    }

    public void testIncludeOnALazyFieldIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringInclude;
            @ToString
            public class Ping {
                @Lazy @ToStringInclude private String motd;
            }
            """);
        assertTrue(reports("'motd' is @Lazy", HighlightSeverity.ERROR));
    }

    public void testIncludeOnAStaticMethodIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringInclude;
            @ToString
            public class Ping {
                private String motd;
                @ToStringInclude
                public static int shared() { return 0; }
            }
            """);
        assertTrue(reports("'shared' is static", HighlightSeverity.ERROR));
    }

    public void testIncludeOnAParameterisedMethodIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringInclude;
            @ToString
            public class Ping {
                private String motd;
                @ToStringInclude
                public int lengthOf(String other) { return other.length(); }
            }
            """);
        assertTrue(reports("'lengthOf' takes parameters", HighlightSeverity.ERROR));
    }

    public void testIncludeOnAVoidMethodIsRejected() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringInclude;
            @ToString
            public class Ping {
                private String motd;
                @ToStringInclude
                public void touch() { }
            }
            """);
        assertTrue(reports("'touch' returns void", HighlightSeverity.ERROR));
    }

    public void testIncludeCarryingNameAndRankIsClean() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringInclude;
            @ToString
            public class Ping {
                private String motd;
                @ToStringInclude(name = "latency", rank = 1)
                public long ping() { return 0L; }
            }
            """);
        assertSilent("renaming and reordering a printed term is what the attributes are for");
    }

    // ------------------------------------------------------------------
    // @BuilderIgnore
    // ------------------------------------------------------------------

    public void testBuilderIgnoredFieldIsPrompted() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Ping {
                private String motd;
                @BuilderIgnore private String favicon;
            }
            """);
        assertTrue(reports("@BuilderIgnore does not take 'favicon' out of @ToString",
            HighlightSeverity.WEAK_WARNING));
    }

    public void testBuilderIgnoredFieldCarryingTheExcludeMarkerIsSilent() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringExclude;
            @ToString
            public class Ping {
                private String motd;
                @BuilderIgnore @ToStringExclude private String favicon;
            }
            """);
        assertSilent("the author has already said which set the field is in");
    }

    public void testBuilderIgnoreOnAnUnannotatedTypeIsSilent() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            public class Ping {
                private String motd;
                @BuilderIgnore private String favicon;
            }
            """);
        assertSilent("there is no printed set for the two to disagree about");
    }

    public void testExcludeFixOnABuilderIgnoredFieldClearsTheReport() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Ping {
                private String motd;
                @BuilderIgnore private String favicon;
            }
            """);
        caretAt("@BuilderIgnore private");
        myFixture.launchAction(
            myFixture.findSingleIntention("Exclude 'favicon' with @ToStringExclude"));
        String text = myFixture.getFile().getText();
        assertTrue("the marker is written onto the field", text.contains("@ToStringExclude"));
        assertTrue("and its import with it",
            text.contains("import dev.simplified.annotations.ToStringExclude;"));
        assertSilent("and the prompt is answered");
    }

    // ------------------------------------------------------------------
    // A correct, fully configured use
    // ------------------------------------------------------------------

    public void testFullyConfiguredUseIsSilent() {
        configure("app/ServerPing.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.ToString;
            import dev.simplified.annotations.ToStringExclude;
            import dev.simplified.annotations.ToStringInclude;
            @ToString(style = ToString.Style.LOMBOK,
                      callSuper = CallSuper.NO,
                      includeFieldNames = false,
                      exclude = "favicon")
            public class ServerPing {
                private String motd;
                private int players;
                private transient String cache;
                @ToStringExclude private String secret;
                private String favicon;

                @ToStringInclude(name = "latency", rank = 1)
                public long ping() { return 0L; }
            }
            """);
        assertSilent("a correct use says nothing at all");
    }

    // ------------------------------------------------------------------
    // @BuilderIgnore against the resolved selection
    // ------------------------------------------------------------------

    /**
     * The third place the two annotations part company, and the one that has to
     * be read off the resolved selection rather than the marker's presence:
     * this policy keeps {@code transient} state, so the prompt is true here
     * where its equality counterpart is a false positive.
     */
    public void testBuilderIgnoreOnATransientFieldStillSpeaks() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Ping {
                private String motd;
                @BuilderIgnore private transient String cache;
            }
            """);
        assertTrue(reports("@BuilderIgnore does not take 'cache' out of @ToString",
            HighlightSeverity.WEAK_WARNING));
    }

    public void testBuilderIgnoreOnALazyFieldIsSilent() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Ping {
                private String motd;
                @Lazy @BuilderIgnore private String derived;
            }
            """);
        assertSilent("the dump cannot read the wrapper's slot either way");
    }

    public void testBuilderIgnoreOnAStaticFieldIsSilent() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Ping {
                private String motd;
                @BuilderIgnore private static String SHARED = "";
            }
            """);
        assertSilent("a static holds no per-instance value to print");
    }

    public void testBuilderIgnoreOnAnExcludedFieldIsSilent() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ToString;
            @ToString(exclude = "favicon")
            public class Ping {
                private String motd;
                @BuilderIgnore private String favicon;
            }
            """);
        assertSilent("the attribute has already taken it out");
    }

    /**
     * The narrowing list and the marker are two statements about one member,
     * and the list is matched against the selection the marker removes it from
     * - so writing the marker trades the prompt for the hard error that reports
     * a name the selection can no longer reach.
     */
    public void testTheExcludeFixIsWithheldWhereTheAttributeAlreadyNamesTheMember() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ToString;
            @ToString(of = {"motd", "favicon"})
            public class Ping {
                private String motd;
                @BuilderIgnore private String favicon;
            }
            """);
        assertTrue("the prompt still stands",
            reports("@BuilderIgnore does not take 'favicon' out of", HighlightSeverity.WEAK_WARNING));
        caretAt("@BuilderIgnore private");
        assertNull("writing the marker would break the build",
            myFixture.getAvailableIntention("Exclude 'favicon' with @ToStringExclude"));
    }

    // ------------------------------------------------------------------
    // A toString overload is not the member
    // ------------------------------------------------------------------

    /** Only the zero-arg member overrides anything, so an overload leaves the annotation live. */
    public void testAToStringOverloadIsNotADeclaredMember() {
        configure("app/Ping.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Ping {
                private String motd;

                public String toString(int radix) { return this.motd; }
            }
            """);
        assertFalse(reports("which already declares toString", HighlightSeverity.WEAK_WARNING));
    }

    // ------------------------------------------------------------------
    // A hierarchy that does not terminate
    // ------------------------------------------------------------------

    /**
     * A cyclic {@code extends} is one rename away in any file, and the platform
     * resolves one without complaint. The supertype walk runs on the daemon
     * thread, so an unguarded loop is a frozen IDE rather than a wrong answer -
     * a highlighting pass that finishes is the assertion.
     */
    public void testASelfExtendingTargetDoesNotHang() {
        configure("app/Loop.java",
            """
            package app;
            import dev.simplified.annotations.ToString;
            @ToString
            public class Loop extends Loop {
                private String value;
            }
            """);
        assertNotNull(myFixture.doHighlighting());
        assertFalse("the cycle itself is the platform's to report",
            complaints().stream().anyMatch(text -> text.contains("@ToString")));
    }

}
