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
 * Edit-time behaviour of {@link EqualsAndHashCodeInspection}: every refusal it
 * shares with the processor, the three checks it makes on its own, and the
 * negatives each of those three would be harmful to over-fire on.
 */
public class EqualsAndHashCodeInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(new EqualsAndHashCodeInspection());
        WholeObjectTestSources.install(myFixture);
        WholeObjectTestSources.installPersistence(myFixture);
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

    private void assertStubsResolve(Map<String, List<String>> stubs) {
        for (Map.Entry<String, List<String>> stub : stubs.entrySet()) {
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
    // The fixture project itself
    // ------------------------------------------------------------------

    /**
     * The fixture never sees the real library sources, so an annotation that
     * stops resolving is indistinguishable from an inspection that stops
     * running - roughly a third of the assertions in this class are that
     * nothing was reported, and each of those passes either way.
     */
    public void testEveryAnnotationStubResolves() {
        assertStubsResolve(WholeObjectTestSources.INSTALLED);
        assertStubsResolve(WholeObjectTestSources.PERSISTENCE);
    }

    // ------------------------------------------------------------------
    // Target kinds
    // ------------------------------------------------------------------

    public void testAnnotationTypeIsRejected() {
        configure("app/Marker.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public @interface Marker { }
            """);
        assertTrue(reports("on the annotation type Marker", HighlightSeverity.ERROR));
    }

    public void testInterfaceIsRejected() {
        configure("app/Named.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public interface Named {
                String name();
            }
            """);
        assertTrue(reports("on the interface Named", HighlightSeverity.ERROR));
    }

    /** The builder emits a concrete class for the interface, and that is where the pair lands. */
    public void testInterfaceCarryingClassBuilderIsSilent() {
        configure("app/Named.java",
            """
            package app;
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.EqualsAndHashCode;
            @ClassBuilder
            @EqualsAndHashCode
            public interface Named {
                String name();
            }
            """);
        assertSilent("an implementation exists for the annotation to reach");
    }

    public void testEnumIsRejected() {
        configure("app/Mode.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public enum Mode { A, B }
            """);
        assertTrue(reports("on the enum Mode", HighlightSeverity.ERROR));
    }

    public void testRecordIsAccepted() {
        configure("app/Point.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public record Point(int x, int y) { }
            """);
        assertSilent("a record is the shape that needs this most");
    }

    // ------------------------------------------------------------------
    // A pair the author already wrote
    // ------------------------------------------------------------------

    public void testDeclaredEqualsIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @Override
                public boolean equals(Object o) { return this == o; }
            }
            """);
        assertTrue(reports("which already declares equals -", HighlightSeverity.ERROR));
    }

    public void testDeclaredHashCodeIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @Override
                public int hashCode() { return 0; }
            }
            """);
        assertTrue(reports("which already declares hashCode", HighlightSeverity.ERROR));
    }

    public void testDeclaredPairNamesBoth() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @Override
                public boolean equals(Object o) { return this == o; }
                @Override
                public int hashCode() { return 0; }
            }
            """);
        assertTrue(reports("which already declares equals and hashCode", HighlightSeverity.ERROR));
    }

    // ------------------------------------------------------------------
    // Supertypes
    // ------------------------------------------------------------------

    public void testFinalEqualsInASupertypeIsRejected() {
        myFixture.addFileToProject("app/Sealed.java",
            """
            package app;
            public class Sealed {
                @Override
                public final boolean equals(Object o) { return this == o; }
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Child extends Sealed {
                private String label;
            }
            """);
        assertTrue(reports("app.Sealed declares it final", HighlightSeverity.ERROR));
    }

    /** Both members are checked, so a supertype sealing only the hash is caught too. */
    public void testFinalHashCodeInASupertypeIsRejected() {
        myFixture.addFileToProject("app/Sealed.java",
            """
            package app;
            public class Sealed {
                @Override
                public final int hashCode() { return 0; }
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Child extends Sealed {
                private String label;
            }
            """);
        assertTrue(reports("cannot generate hashCode on Child", HighlightSeverity.ERROR));
    }

    public void testIdentityDisagreeingWithAnAnnotatedSuperIsRejected() {
        myFixture.addFileToProject("app/Base.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF)
            public class Base {
                private String id;
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.EXACT_CLASS)
            public class Child extends Base {
                private String label;
            }
            """);
        assertTrue(reports("disagrees with Base, which asks for INSTANCE_OF",
            HighlightSeverity.ERROR));
    }

    public void testIdentityAgreeingWithAnAnnotatedSuperIsClean() {
        myFixture.addFileToProject("app/Base.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF)
            public class Base {
                private String id;
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF)
            public class Child extends Base {
                private String label;
            }
            """);
        assertSilent("one hierarchy, one relation");
    }

    // ------------------------------------------------------------------
    // callSuper
    // ------------------------------------------------------------------

    public void testCallSuperYesOnARootTypeIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(callSuper = CallSuper.YES)
            public class Token {
                private String value;
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
                public boolean equals(Object o) { return this == o; }
                @Override
                public int hashCode() { return 0; }
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(callSuper = CallSuper.YES)
            public class Child extends Base {
                private String label;
            }
            """);
        assertSilent("there is an implementation to fold in");
    }

    public void testCallSuperNoOnARootTypeIsClean() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(callSuper = CallSuper.NO)
            public class Token {
                private String value;
            }
            """);
        assertSilent("declining to call is always expressible");
    }

    // ------------------------------------------------------------------
    // of / exclude
    // ------------------------------------------------------------------

    public void testOfAndExcludeTogetherIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(of = "value", exclude = "issued")
            public class Token {
                private String value;
                private long issued;
            }
            """);
        assertTrue(reports("sets both 'of' and 'exclude'", HighlightSeverity.ERROR));
    }

    public void testOfNamingNothingIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(of = {"value", "ghost"})
            public class Token {
                private String value;
            }
            """);
        assertTrue(reports("(of) names 'ghost'", HighlightSeverity.ERROR));
        assertFalse("the name that matches is left alone", reports("names 'value'",
            HighlightSeverity.ERROR));
    }

    public void testExcludeNamingNothingIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(exclude = "issuedAt")
            public class Token {
                private String value;
                private long issued;
            }
            """);
        assertTrue(reports("(exclude) names 'issuedAt'", HighlightSeverity.ERROR));
    }

    /**
     * {@code transient} state is outside the value, so naming it is a name the
     * selection never reaches - the asymmetry with {@code @ToString}, which
     * keeps it.
     */
    public void testOfNamingATransientFieldIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(of = "cache")
            public class Token {
                private String value;
                private transient String cache;
            }
            """);
        assertTrue(reports("(of) names 'cache'", HighlightSeverity.ERROR));
    }

    public void testOfNamingAMemberIsClean() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(of = "value")
            public class Token {
                private String value;
                private long issued;
            }
            """);
        assertSilent("a name that matches says nothing");
    }

    // ------------------------------------------------------------------
    // cacheHashCode
    // ------------------------------------------------------------------

    public void testCacheHashCodeOnARecordIsRejected() {
        configure("app/Point.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(cacheHashCode = true)
            public record Point(int x, int y) { }
            """);
        assertTrue(reports("a record body cannot declare the instance field the memo needs",
            HighlightSeverity.ERROR));
    }

    public void testCacheHashCodeWithAMutableMemberIsWarned() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(cacheHashCode = true)
            public class Token {
                private final String value = "";
                private long issued;
            }
            """);
        assertTrue(reports("with the mutable member 'issued'", HighlightSeverity.WARNING));
        assertFalse("a final member is fixed at construction",
            reports("with the mutable member 'value'", HighlightSeverity.WARNING));
    }

    public void testCacheHashCodeOverFinalMembersIsClean() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(cacheHashCode = true)
            public final class Token {
                private final String value = "";
                private final long issued = 0L;
            }
            """);
        assertSilent("every compared member is fixed at construction");
    }

    public void testCacheHashCodeWithCallSuperIsWarned() {
        myFixture.addFileToProject("app/Base.java",
            """
            package app;
            public class Base {
                @Override
                public boolean equals(Object o) { return this == o; }
                @Override
                public int hashCode() { return 0; }
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(cacheHashCode = true, callSuper = CallSuper.YES)
            public class Child extends Base {
                private final String label = "";
            }
            """);
        assertTrue(reports("(cacheHashCode) with callSuper", HighlightSeverity.WARNING));
    }

    /** An excluded member cannot make the memo stale, so its mutability says nothing. */
    public void testCacheHashCodeIgnoresAnExcludedMutableMember() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(cacheHashCode = true, exclude = "issued")
            public class Token {
                private final String value = "";
                private long issued;
            }
            """);
        assertFalse(reports("with the mutable member 'issued'", HighlightSeverity.WARNING));
    }

    // ------------------------------------------------------------------
    // Member types - the container-of-array check and its negatives
    // ------------------------------------------------------------------

    public void testArrayBehindATypeArgumentIsWarned() {
        configure("app/Chunked.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import java.util.List;
            import java.util.Map;
            @EqualsAndHashCode
            public class Chunked {
                private List<byte[]> chunks;
                private Map<String, int[]> grids;
            }
            """);
        assertTrue("an element behind a type argument compares by identity",
            reports("'chunks' is a List<byte[]>", HighlightSeverity.WARNING));
        assertTrue("and so does a map value",
            reports("'grids' is a Map<String, int[]>", HighlightSeverity.WARNING));
    }

    /**
     * The check must stay off a plain array of any depth. The flat and deep
     * split compares those by content, and warning about them would train the
     * reader past the one shape that is genuinely unreachable.
     */
    public void testPlainArraysOfEveryDepthAreSilent() {
        myFixture.addFileToProject("app/Vector3f.java",
            """
            package app;
            public class Vector3f {
                public float x;
            }
            """);
        configure("app/Mesh.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Mesh {
                private byte[] data;
                private int[][] grid;
                private Vector3f[] points;
            }
            """);
        assertSilent("byte[], int[][] and Vector3f[] are all compared by content");
    }

    public void testBareCollectionIsWarned() {
        configure("app/Bag.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import java.util.Collection;
            @EqualsAndHashCode
            public class Bag {
                private Collection<String> items;
            }
            """);
        assertTrue(reports("which specifies no equals contract at all", HighlightSeverity.WARNING));
    }

    public void testListIsNotFlaggedAsABareCollection() {
        configure("app/Bag.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import java.util.List;
            @EqualsAndHashCode
            public class Bag {
                private List<String> items;
            }
            """);
        assertSilent("List specifies the contract Collection does not");
    }

    public void testMemberTypeDeclaringNoEqualsIsWeaklyWarned() {
        myFixture.addFileToProject("app/Handle.java",
            """
            package app;
            public class Handle {
                public int fd;
            }
            """);
        configure("app/Session.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Session {
                private Handle handle;
            }
            """);
        assertTrue(reports("which declares no equals of its own", HighlightSeverity.WEAK_WARNING));
    }

    public void testMemberTypeDeclaringEqualsIsSilent() {
        myFixture.addFileToProject("app/Handle.java",
            """
            package app;
            public class Handle {
                public int fd;
                @Override
                public boolean equals(Object o) { return o instanceof Handle; }
            }
            """);
        configure("app/Session.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Session {
                private Handle handle;
            }
            """);
        assertSilent("the member type supplies its own relation");
    }

    public void testExcludeFixOnAContainerOfArraysClearsTheReport() {
        configure("app/Chunked.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import java.util.List;
            @EqualsAndHashCode
            public class Chunked {
                private String name;
                private List<byte[]> chunks;
            }
            """);
        caretAt("chunks;");
        myFixture.launchAction(
            myFixture.findSingleIntention("Exclude 'chunks' with @EqualsExclude"));
        String text = myFixture.getFile().getText();
        assertTrue("the marker is written onto the field", text.contains("@EqualsExclude"));
        assertTrue("and its import with it",
            text.contains("import dev.simplified.annotations.EqualsExclude;"));
        assertSilent("and the member leaves the comparison");
    }

    // ------------------------------------------------------------------
    // The marker pair
    // ------------------------------------------------------------------

    public void testBothMarkersOnOneFieldIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsExclude;
            import dev.simplified.annotations.EqualsInclude;
            @EqualsAndHashCode
            public class Token {
                @EqualsExclude @EqualsInclude private String value;
            }
            """);
        assertTrue(reports("'value' carries both the include and exclude markers",
            HighlightSeverity.ERROR));
    }

    /** A record's markers live on its components, which {@code visitField} never reaches. */
    public void testBothMarkersOnOneRecordComponentIsRejected() {
        configure("app/Point.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsExclude;
            import dev.simplified.annotations.EqualsInclude;
            @EqualsAndHashCode
            public record Point(@EqualsExclude @EqualsInclude int x, int y) { }
            """);
        assertTrue(reports("'x' carries both the include and exclude markers",
            HighlightSeverity.ERROR));
    }

    public void testExcludeOnARecordComponentIsAccepted() {
        configure("app/Point.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsExclude;
            @EqualsAndHashCode
            public record Point(int x, @EqualsExclude int cachedHash) { }
            """);
        assertSilent("a component is excluded the same way a field is");
    }

    public void testMarkerOnAnUnannotatedTypeIsInert() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsExclude;
            public class Token {
                @EqualsExclude private String value;
            }
            """);
        assertTrue(reports("@EqualsExclude is never read", HighlightSeverity.WARNING));
    }

    /**
     * The method path reaches the same report, and a method is where the
     * include marker is most likely to be left stranded by a removed
     * annotation.
     */
    public void testIncludeOnAMethodOfAnUnannotatedTypeIsInert() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsInclude;
            public class Token {
                private String value;
                @EqualsInclude
                public int length() { return this.value.length(); }
            }
            """);
        assertTrue(reports("@EqualsInclude is never read", HighlightSeverity.WARNING));
    }

    public void testIncludeOnALazyFieldIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsInclude;
            import dev.simplified.annotations.Lazy;
            @EqualsAndHashCode
            public class Token {
                @Lazy @EqualsInclude private String value;
            }
            """);
        assertTrue(reports("'value' is @Lazy", HighlightSeverity.ERROR));
    }

    public void testIncludeOnAStaticMethodIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsInclude;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @EqualsInclude
                public static int shared() { return 0; }
            }
            """);
        assertTrue(reports("'shared' is static", HighlightSeverity.ERROR));
    }

    public void testIncludeOnAParameterisedMethodIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsInclude;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @EqualsInclude
                public int lengthOf(String other) { return other.length(); }
            }
            """);
        assertTrue(reports("'lengthOf' takes parameters", HighlightSeverity.ERROR));
    }

    public void testIncludeOnAVoidMethodIsRejected() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsInclude;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @EqualsInclude
                public void touch() { }
            }
            """);
        assertTrue(reports("'touch' returns void", HighlightSeverity.ERROR));
    }

    public void testIncludeOnAZeroArgMethodIsClean() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsInclude;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @EqualsInclude
                public int length() { return this.value.length(); }
            }
            """);
        assertSilent("a zero-arg value-returning method is the shape the marker adds");
    }

    /**
     * The exclude marker on a method is inert rather than wrong - a method is
     * never selected unless the include marker adds it, so the processor ignores
     * it too.
     */
    public void testExcludeOnAMethodIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsExclude;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @EqualsExclude
                public int length() { return this.value.length(); }
            }
            """);
        assertSilent("nothing was going to read the method anyway");
    }

    // ------------------------------------------------------------------
    // @BuilderIgnore
    // ------------------------------------------------------------------

    public void testBuilderIgnoredFieldIsPrompted() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @BuilderIgnore private long issued;
            }
            """);
        assertTrue(reports("@BuilderIgnore does not take 'issued' out of @EqualsAndHashCode",
            HighlightSeverity.WEAK_WARNING));
    }

    public void testBuilderIgnoredFieldCarryingTheExcludeMarkerIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsExclude;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @BuilderIgnore @EqualsExclude private long issued;
            }
            """);
        assertSilent("the author has already said which set the field is in");
    }

    public void testBuilderIgnoreOnAnUnannotatedTypeIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            public class Token {
                private String value;
                @BuilderIgnore private long issued;
            }
            """);
        assertSilent("there is no equality set for the two to disagree about");
    }

    public void testExcludeFixOnABuilderIgnoredFieldClearsTheReport() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @BuilderIgnore private long issued;
            }
            """);
        caretAt("@BuilderIgnore private");
        myFixture.launchAction(
            myFixture.findSingleIntention("Exclude 'issued' with @EqualsExclude"));
        String text = myFixture.getFile().getText();
        assertTrue("the marker is written onto the field", text.contains("@EqualsExclude"));
        assertTrue("and its import with it",
            text.contains("import dev.simplified.annotations.EqualsExclude;"));
        assertSilent("and the prompt is answered");
    }

    // ------------------------------------------------------------------
    // The identity recommendation and its negatives
    // ------------------------------------------------------------------

    public void testEntityGetsTheIdentityRecommendation() {
        configure("app/Account.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import jakarta.persistence.Entity;
            @Entity
            @EqualsAndHashCode
            public class Account {
                private String owner;
            }
            """);
        assertTrue(reports("Account carries @Entity", HighlightSeverity.WEAK_WARNING));
    }

    public void testLazyAssociationGetsTheIdentityRecommendation() {
        configure("app/Order.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import jakarta.persistence.OneToMany;
            import java.util.List;
            @EqualsAndHashCode
            public class Order {
                private String id;
                @OneToMany private List<String> lines;
            }
            """);
        assertTrue(reports("holds the lazily-fetched association 'lines'",
            HighlightSeverity.WEAK_WARNING));
    }

    public void testExplicitlyLazyManyToOneGetsTheIdentityRecommendation() {
        configure("app/Order.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import jakarta.persistence.FetchType;
            import jakarta.persistence.ManyToOne;
            @EqualsAndHashCode
            public class Order {
                private String id;
                @ManyToOne(fetch = FetchType.LAZY) private String customer;
            }
            """);
        assertTrue(reports("holds the lazily-fetched association 'customer'",
            HighlightSeverity.WEAK_WARNING));
    }

    /** An association loaded with the row leaves no proxy behind to be stranded. */
    public void testEagerAssociationIsSilent() {
        configure("app/Order.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import jakarta.persistence.ManyToOne;
            @EqualsAndHashCode
            public class Order {
                private String id;
                @ManyToOne private String customer;
            }
            """);
        assertSilent("the default fetch on a to-one association is eager");
    }

    /**
     * The recommendation asks an author to give up the one relation that is
     * never silently wrong, so it must fire only where a subclass is made at
     * runtime rather than written in source.
     */
    public void testOrdinaryValueClassGetsNoRecommendation() {
        configure("app/Money.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Money {
                private long amount;
                private String currency;
            }
            """);
        assertSilent("nothing subclasses a plain value class at runtime");
    }

    public void testFinalEntityGetsNoRecommendation() {
        configure("app/Snapshot.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import jakarta.persistence.Entity;
            @Entity
            @EqualsAndHashCode
            public final class Snapshot {
                private String id;
            }
            """);
        assertSilent("a type nothing can subclass has no proxy to be stranded by");
    }

    public void testSetIdentityFixRewritesTheAttributeAndClearsTheReport() {
        configure("app/Account.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import jakarta.persistence.Entity;
            @Entity
            @EqualsAndHashCode(exclude = "version")
            public class Account {
                private String owner;
                private long version;
            }
            """);
        caretAt("EqualsAndHashCode(exclude");
        myFixture.launchAction(
            myFixture.findSingleIntention("Compare with instanceof and a canEqual hook"));
        String text = myFixture.getFile().getText();
        assertTrue("the relation is rewritten", text.contains("INSTANCE_OF_CANEQUAL"));
        assertTrue("and every other attribute travels with it",
            text.contains("exclude = \"version\""));
        assertSilent("and the recommendation is answered");
    }

    // ------------------------------------------------------------------
    // The degraded hook
    // ------------------------------------------------------------------

    public void testCanEqualOnAFinalRootTypeIsWeaklyWarned() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public final class Token {
                private String value;
            }
            """);
        assertTrue(reports("no subclass can override the hook", HighlightSeverity.WEAK_WARNING));
    }

    public void testCanEqualOnARecordIsWeaklyWarned() {
        configure("app/Point.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public record Point(int x, int y) { }
            """);
        assertTrue(reports("no subclass can override the hook", HighlightSeverity.WEAK_WARNING));
    }

    public void testCanEqualOnASubclassableTypeIsClean() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public class Token {
                private String value;
            }
            """);
        assertSilent("the hook has somewhere to be overridden");
    }

    // ------------------------------------------------------------------
    // A subclass overriding equals without the hook
    // ------------------------------------------------------------------

    private void addHookedShape() {
        myFixture.addFileToProject("app/Shape.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public class Shape {
                private int sides;
            }
            """);
    }

    public void testSubclassOverridingEqualsWithoutTheHookIsWarned() {
        addHookedShape();
        configure("app/Circle.java",
            """
            package app;
            public class Circle extends Shape {
                private int radius;
                @Override
                public boolean equals(Object o) { return o instanceof Circle; }
            }
            """);
        assertTrue(reports("Circle overrides equals but not canEqual", HighlightSeverity.WARNING));
    }

    public void testSubclassDeclaringTheHookIsClean() {
        addHookedShape();
        configure("app/Circle.java",
            """
            package app;
            public class Circle extends Shape {
                private int radius;
                @Override
                public boolean equals(Object o) { return o instanceof Circle; }
                protected boolean canEqual(Object other) { return other instanceof Circle; }
            }
            """);
        assertSilent("the cooperation protocol is honoured");
    }

    /** {@code EXACT_CLASS} needs no cooperation, so an override of one member says nothing. */
    public void testSubclassOfAnExactClassParentIsClean() {
        myFixture.addFileToProject("app/Shape.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Shape {
                private int sides;
            }
            """);
        configure("app/Circle.java",
            """
            package app;
            public class Circle extends Shape {
                private int radius;
                @Override
                public boolean equals(Object o) { return o instanceof Circle; }
            }
            """);
        assertSilent("no hook is generated for the relation to depend on");
    }

    public void testUnrelatedClassOverridingEqualsIsClean() {
        configure("app/Circle.java",
            """
            package app;
            public class Circle {
                private int radius;
                @Override
                public boolean equals(Object o) { return o instanceof Circle; }
            }
            """);
        assertSilent("there is no annotated ancestor above it");
    }

    public void testAddCanEqualFixWritesTheHookAndClearsTheReport() {
        addHookedShape();
        configure("app/Circle.java",
            """
            package app;
            public class Circle extends Shape {
                private int radius;
                @Override
                public boolean equals(Object o) { return o instanceof Circle; }
            }
            """);
        caretAt("equals(Object o)");
        myFixture.launchAction(myFixture.findSingleIntention("Override 'canEqual' in 'Circle'"));
        String text = myFixture.getFile().getText();
        assertTrue("the hook is written",
            text.contains("protected boolean canEqual(Object other)"));
        assertTrue("and accepts exactly the subclass",
            text.contains("other instanceof Circle"));
        assertSilent("and the asymmetry is closed");
    }

    /** A generic target is spelled as a wildcarded test, since a raw one warns. */
    public void testAddCanEqualFixWildcardsAGenericTarget() {
        myFixture.addFileToProject("app/Holder.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public class Holder<T> {
                private T value;
            }
            """);
        configure("app/Boxed.java",
            """
            package app;
            public class Boxed<T> extends Holder<T> {
                private int depth;
                @Override
                public boolean equals(Object o) { return o instanceof Boxed; }
            }
            """);
        caretAt("equals(Object o)");
        myFixture.launchAction(myFixture.findSingleIntention("Override 'canEqual' in 'Boxed'"));
        assertTrue(myFixture.getFile().getText().contains("other instanceof Boxed<?>"));
    }

    // ------------------------------------------------------------------
    // A correct, fully configured use
    // ------------------------------------------------------------------

    public void testFullyConfiguredUseIsSilent() {
        configure("app/Palette.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.EqualsExclude;
            import dev.simplified.annotations.EqualsInclude;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL,
                               callSuper = CallSuper.NO,
                               exclude = "loadedAt")
            public class Palette {
                private String name;
                private byte[] swatches;
                @EqualsExclude private long checksum;
                private long loadedAt;

                @EqualsInclude
                public int size() { return this.swatches.length; }
            }
            """);
        assertSilent("a correct use says nothing at all");
    }

    // ------------------------------------------------------------------
    // callSuper that AUTO cannot resolve
    // ------------------------------------------------------------------

    private void addBase(String members) {
        myFixture.addFileToProject("app/Base.java",
            """
            package app;
            public class Base {
            %s
            }
            """.formatted(members));
    }

    private void configureChild() {
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Child extends Base {
                private String label;
            }
            """);
    }

    /**
     * A superclass overriding one half of the pair is already inconsistent, so
     * {@code AUTO} has no answer to give. The resolved boolean cannot carry the
     * difference - the same {@code false} means "no super call" and "no answer"
     * - and without the report the file is green here and red on the next
     * build, which inverts what this half is for.
     */
    public void testCallSuperCannotBeResolvedAgainstHalfAPair() {
        addBase("""
                @Override
                public boolean equals(Object o) { return this == o; }
            """);
        configureChild();
        assertTrue(reports("declares some of the pair and not the rest",
            HighlightSeverity.ERROR));
    }

    /** The hash alone reaches the same stand-off from the other side. */
    public void testCallSuperCannotBeResolvedAgainstAnInheritedHashAlone() {
        addBase("""
                @Override
                public int hashCode() { return 0; }
            """);
        configureChild();
        assertTrue(reports("declares some of the pair and not the rest",
            HighlightSeverity.ERROR));
    }

    public void testCallSuperResolvesWhenTheSuperclassDeclaresBoth() {
        addBase("""
                @Override
                public boolean equals(Object o) { return this == o; }
                @Override
                public int hashCode() { return 0; }
            """);
        configureChild();
        assertSilent("there is a whole implementation to fold in");
    }

    public void testCallSuperResolvesWhenTheSuperclassDeclaresNeither() {
        addBase("    protected int tag;");
        configureChild();
        assertSilent("nothing above supplies a relation, which resolves to NO");
    }

    /** Writing the attribute is the remedy the message asks for, so it has to silence it. */
    public void testAWrittenCallSuperSettlesTheStandOff() {
        addBase("""
                @Override
                public boolean equals(Object o) { return this == o; }
            """);
        for (String written : new String[]{"YES", "NO"}) {
            configure("app/Child" + written + ".java",
                """
                package app;
                import dev.simplified.annotations.CallSuper;
                import dev.simplified.annotations.EqualsAndHashCode;
                @EqualsAndHashCode(callSuper = CallSuper.%s)
                public class Child%s extends Base {
                    private String label;
                }
                """.formatted(written, written));
            assertSilent("callSuper = " + written + " leaves nothing to resolve");
        }
    }

    // ------------------------------------------------------------------
    // A typed equals overload is not the override
    // ------------------------------------------------------------------

    /**
     * A convenience {@code equals(Vec)} overrides nothing and supplies no
     * relation, so reading it as a written {@code equals} turns a legal value
     * type into a rejected one - a red editor over a build that succeeds.
     */
    public void testATypedEqualsOverloadIsNotADeclaredMember() {
        configure("app/Vec.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Vec {
                private float x;

                public boolean equals(Vec other) { return other != null && other.x == this.x; }
            }
            """);
        assertSilent("the overload leaves the pair unwritten");
    }

    /** The same match settles what a supertype seals, where the symptom is a refusal to generate. */
    public void testAFinalTypedOverloadInASupertypeDoesNotSealTheRealMember() {
        myFixture.addFileToProject("app/Base.java",
            """
            package app;
            public class Base {
                public final boolean equals(Base other) { return other == this; }
                @Override
                public boolean equals(Object o) { return this == o; }
                @Override
                public int hashCode() { return 0; }
            }
            """);
        configure("app/Child.java",
            """
            package app;
            import dev.simplified.annotations.CallSuper;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(callSuper = CallSuper.NO)
            public class Child extends Base {
                private int q;
            }
            """);
        assertFalse("the overridable equals(Object) is the one an override has to compile past",
            reports("declares it final", HighlightSeverity.ERROR));
    }

    // ------------------------------------------------------------------
    // The shape of an author-written hook
    // ------------------------------------------------------------------

    private void configureHook(String hook) {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public class Token {
                private String value;

            %s
            }
            """.formatted(hook));
    }

    /**
     * The hook is reused on its name and argument count alone, which puts the
     * rest of the shape on the author - and a return type the generated
     * {@code equals} cannot negate fails inside a member nobody wrote.
     */
    public void testAHookReturningSomethingElseIsRejected() {
        configureHook("    protected int canEqual(Object other) { return 0; }");
        assertTrue(reports("'canEqual' returns int", HighlightSeverity.ERROR));
    }

    public void testAHookThatCannotBePassedTheTargetIsRejected() {
        configureHook("    protected boolean canEqual(String other) { return false; }");
        assertTrue(reports("which no Token can be passed as", HighlightSeverity.ERROR));
    }

    /** Assignable but narrower: it compiles, and a subclass overriding the protocol misses it. */
    public void testAHookNarrowerThanObjectIsWarned() {
        configureHook("    protected boolean canEqual(Token other) { return other != null; }");
        assertTrue(reports("takes Token rather than Object", HighlightSeverity.WARNING));
    }

    public void testAHookNoSubclassCanReachIsWarned() {
        configureHook("    private boolean canEqual(Object other) { return other != null; }");
        assertTrue(reports("'canEqual' is private, so no subclass can override it",
            HighlightSeverity.WARNING));
    }

    public void testAStaticHookIsWarned() {
        configureHook("    static boolean canEqual(Object other) { return other != null; }");
        assertTrue(reports("'canEqual' is static, so no subclass can override it",
            HighlightSeverity.WARNING));
    }

    public void testTheDocumentedHookShapeIsSilent() {
        configureHook("    protected boolean canEqual(Object other) { return other instanceof Token; }");
        assertSilent("this is the declaration the protocol is written in");
    }

    /** Nothing reads the hook under the other two relations, so its shape says nothing. */
    public void testAHookIsNotCheckedUnderTheDefaultRelation() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private String value;

                protected int canEqual(Object other) { return 0; }
            }
            """);
        assertSilent("no generated member calls it");
    }

    /**
     * A written hook is reused rather than replaced, and the reuse is settled
     * before the degradation is - so the call is emitted on a final root type
     * too, and reporting that it is not would describe the wrong member.
     */
    public void testAWrittenHookOnAFinalRootTypeIsNotReportedAsDegraded() {
        configure("app/Sealed.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public final class Sealed {
                private String value;

                protected boolean canEqual(Object other) { return other instanceof Sealed; }
            }
            """);
        assertSilent("the author's own hook is what the relation calls");
    }

    // ------------------------------------------------------------------
    // @BuilderIgnore on a member the relation never reads
    // ------------------------------------------------------------------

    /** {@code transient} state is outside the value already, so there is nothing to reconcile. */
    public void testBuilderIgnoreOnATransientFieldIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @BuilderIgnore private transient String cache;
            }
            """);
        assertSilent("this policy drops transient state before any marker is read");
    }

    public void testBuilderIgnoreOnALazyFieldIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.Lazy;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @Lazy @BuilderIgnore private String derived;
            }
            """);
        assertSilent("the relation cannot read the wrapper's slot either way");
    }

    public void testBuilderIgnoreOnAStaticFieldIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private String value;
                @BuilderIgnore private static String SHARED = "";
            }
            """);
        assertSilent("a static holds no per-instance value to compare");
    }

    public void testBuilderIgnoreOnAnExcludedFieldIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(exclude = "issued")
            public class Token {
                private String value;
                @BuilderIgnore private long issued;
            }
            """);
        assertSilent("the attribute has already taken it out");
    }

    public void testBuilderIgnoreOutsideOfIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(of = "value")
            public class Token {
                private String value;
                @BuilderIgnore private long issued;
            }
            """);
        assertSilent("only the named member is compared");
    }

    // ------------------------------------------------------------------
    // A fix that would break the build is withheld
    // ------------------------------------------------------------------

    /**
     * The narrowing list and the marker are two statements about one member,
     * and the list is matched against the selection the marker removes it from
     * - so writing the marker trades the prompt for the hard error that reports
     * a name the selection can no longer reach.
     */
    public void testTheExcludeFixIsWithheldWhereTheAttributeAlreadyNamesTheMember() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(of = {"value", "issued"})
            public class Token {
                private String value;
                @BuilderIgnore private long issued;
            }
            """);
        assertTrue("the prompt still stands",
            reports("@BuilderIgnore does not take 'issued' out of", HighlightSeverity.WEAK_WARNING));
        caretAt("@BuilderIgnore private");
        assertNull("writing the marker would break the build",
            myFixture.getAvailableIntention("Exclude 'issued' with @EqualsExclude"));
    }

    public void testTheContainerOfArrayFixIsWithheldForTheSameReason() {
        configure("app/Chunked.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import java.util.List;
            @EqualsAndHashCode(of = "chunks")
            public class Chunked {
                private String name;
                private List<byte[]> chunks;
            }
            """);
        assertTrue("the limitation still stands",
            reports("'chunks' is a List<byte[]>", HighlightSeverity.WARNING));
        caretAt("chunks;");
        assertNull("the member is spoken for by the attribute",
            myFixture.getAvailableIntention("Exclude 'chunks' with @EqualsExclude"));
    }

    // ------------------------------------------------------------------
    // A hierarchy that does not terminate
    // ------------------------------------------------------------------

    /**
     * A cyclic {@code extends} is one rename away in any file, and the platform
     * resolves one without complaint. Every check here walks the supertypes on
     * the daemon thread, so an unguarded loop is a frozen IDE rather than a
     * wrong answer - a highlighting pass that finishes is the assertion.
     */
    public void testASelfExtendingTargetDoesNotHang() {
        configure("app/Loop.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Loop extends Loop {
                private String value;
            }
            """);
        assertNotNull(myFixture.doHighlighting());
        assertFalse("the cycle itself is the platform's to report",
            complaints().stream().anyMatch(text -> text.contains("@EqualsAndHashCode")));
    }

    public void testMutuallyExtendingTargetsDoNotHang() {
        myFixture.addFileToProject("app/Down.java",
            """
            package app;
            public class Down extends Up { }
            """);
        configure("app/Up.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Up extends Down {
                private String value;
            }
            """);
        assertNotNull(myFixture.doHighlighting());
        assertFalse("the cycle itself is the platform's to report",
            complaints().stream().anyMatch(text -> text.contains("@EqualsAndHashCode")));
    }

}
