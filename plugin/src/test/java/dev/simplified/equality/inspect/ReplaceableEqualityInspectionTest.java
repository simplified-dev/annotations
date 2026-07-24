package dev.simplified.equality.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit-time behaviour of {@link ReplaceableEqualityInspection}: the pairs it
 * offers to replace, what the replacement writes, and the shapes it must stay
 * quiet on.
 *
 * <p>The negatives are the point. This one carries a fix that deletes two
 * methods, so a false match does not merely annoy - it changes what equality
 * means on a type that compiled and worked.
 */
public class ReplaceableEqualityInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(new ReplaceableEqualityInspection());
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

    private void configure(String path, String source) {
        PsiFile file = myFixture.addFileToProject(path, source);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
    }

    private boolean reports(String fragment) {
        return myFixture.doHighlighting().stream()
            .anyMatch(info -> HighlightSeverity.WEAK_WARNING.equals(info.getSeverity())
                && info.getDescription() != null && info.getDescription().contains(fragment));
    }

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

    /**
     * The fix is offered where its problem was registered, which is the
     * {@code equals} name identifier rather than the top of the file.
     */
    private @NotNull IntentionAction intention(String familyName) {
        int offset = myFixture.getFile().getText().indexOf("equals(Object");
        assertTrue("no equals(Object ...) in the fixture", offset >= 0);
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();
        IntentionAction fix = myFixture.getAvailableIntention(familyName);
        assertNotNull("no fix named '" + familyName + "'", fix);
        return fix;
    }

    /** Applies the replacement and returns the resulting file text. */
    private String applyFix(String familyName) {
        myFixture.launchAction(intention(familyName));
        return myFixture.getFile().getText();
    }

    // ------------------------------------------------------------------
    // The pairs it offers to replace
    // ------------------------------------------------------------------

    public void testExactMatchIsOffered() {
        configure("app/Token.java",
            """
            package app;
            import java.util.Objects;
            public class Token {
                private String value;
                private int issued;
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return Objects.equals(this.value, that.value) && this.issued == that.issued;
                }
                public int hashCode() { return Objects.hash(this.value, this.issued); }
            }
            """);
        assertTrue(reports("@EqualsAndHashCode generates this pair - replacing the two methods "
            + "with the annotation emits the same relation"));
    }

    public void testTheFixWritesTheAnnotationAndDeletesBothMethods() {
        configure("app/Token.java",
            """
            package app;
            import java.util.Objects;
            public class Token {
                private String value;
                private int issued;
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return Objects.equals(this.value, that.value) && this.issued == that.issued;
                }
                public int hashCode() { return Objects.hash(this.value, this.issued); }
            }
            """);
        String result = applyFix("Replace equals/hashCode with @EqualsAndHashCode");
        assertTrue("the annotation is written on the class",
            result.contains("@EqualsAndHashCode\npublic class Token"));
        assertTrue("and its reference is shortened rather than left fully qualified",
            result.contains("import dev.simplified.annotations.EqualsAndHashCode;"));
        assertFalse("equals is gone", result.contains("boolean equals"));
        assertFalse("hashCode is gone", result.contains("int hashCode"));
        assertTrue("the state it read is untouched", result.contains("private String value;"));
    }

    /** A member the pair ignores has to be named, or the annotation would widen the relation. */
    public void testAnIgnoredFieldBecomesAnExclusion() {
        configure("app/Token.java",
            """
            package app;
            import java.util.Objects;
            public class Token {
                private String value;
                private long createdAt;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return Objects.equals(this.value, that.value);
                }
                public int hashCode() { return Objects.hash(this.value); }
            }
            """);
        assertTrue(reports("@EqualsAndHashCode(exclude = \"createdAt\") generates this pair"));
    }

    public void testInstanceOfIdentityIsCarriedOver() {
        configure("app/Token.java",
            """
            package app;
            import java.util.Objects;
            public class Token {
                private String value;
                public boolean equals(Object o) {
                    if (!(o instanceof Token)) return false;
                    Token that = (Token) o;
                    return Objects.equals(this.value, that.value);
                }
                public int hashCode() { return Objects.hash(this.value); }
            }
            """);
        assertTrue(reports("identity = dev.simplified.annotations.EqualsAndHashCode.Identity.INSTANCE_OF"));
    }

    /** A derived value has no field to select, so the fix writes the include marker. */
    public void testADerivedAccessorGetsAnIncludeMarker() {
        configure("app/Token.java",
            """
            package app;
            import java.util.Objects;
            public class Token {
                private String name;
                public String key() { return this.name.toLowerCase(); }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return Objects.equals(this.key(), that.key());
                }
                public int hashCode() { return Objects.hash(this.key()); }
            }
            """);
        assertTrue(reports("@EqualsAndHashCode(exclude = \"name\") generates this pair"));

        String result = applyFix("Replace equals/hashCode with @EqualsAndHashCode");
        assertTrue("the derived accessor carries the marker", result.contains("@EqualsInclude"));
        assertTrue("the field it derives from is excluded", result.contains("exclude = \"name\""));
    }

    // ------------------------------------------------------------------
    // The array difference, reported in its own words
    // ------------------------------------------------------------------

    /**
     * The written equals compares the array by reference and the annotation
     * compares it by content, so adopting it changes equality. Reported rather
     * than hidden, since that change is the reason the annotation exists.
     */
    public void testAnArrayComparedByReferenceIsItsOwnFinding() {
        configure("app/Payload.java",
            """
            package app;
            import java.util.Objects;
            public class Payload {
                private byte[] data;
                private int id;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Payload that = (Payload) o;
                    return Objects.equals(this.data, that.data) && this.id == that.id;
                }
                public int hashCode() { return Objects.hash(this.data, this.id); }
            }
            """);
        assertTrue(reports("compares 'data' by content where this equals compares it by "
            + "reference. Adopting it changes - and most likely fixes - equality for that member"));
        assertNotNull("the fix says what it changes",
            intention("Replace equals/hashCode with @EqualsAndHashCode (compares arrays by content)"));
    }

    /** Already comparing by content is an ordinary exact match. */
    public void testAnArrayComparedByContentIsAnExactMatch() {
        configure("app/Payload.java",
            """
            package app;
            import java.util.Arrays;
            public class Payload {
                private byte[] data;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Payload that = (Payload) o;
                    return Arrays.equals(this.data, that.data);
                }
                public int hashCode() { return Arrays.hashCode(this.data); }
            }
            """);
        assertTrue(reports("emits the same relation"));
    }

    // ------------------------------------------------------------------
    // The shapes it must not report
    // ------------------------------------------------------------------

    /** A split pair belongs to the consistency check, which has a different answer. */
    public void testASplitPairIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import java.util.Objects;
            public class Token {
                private String value;
                private int issued;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return Objects.equals(this.value, that.value) && this.issued == that.issued;
                }
                public int hashCode() { return Objects.hash(this.value); }
            }
            """);
        assertSilent("the other inspection owns this one");
    }

    /**
     * The annotation emits {@code Float.compare}, which orders NaN and -0.0
     * differently from {@code ==}. Replacing would change the relation.
     */
    public void testAFloatComparedWithEqualsOperatorIsSilent() {
        configure("app/Point.java",
            """
            package app;
            public class Point {
                private float x;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Point that = (Point) o;
                    return this.x == that.x;
                }
                public int hashCode() { return (int) this.x; }
            }
            """);
        assertSilent("== on a float is not what the annotation emits");
    }

    public void testAFloatComparedWithCompareIsOffered() {
        configure("app/Point.java",
            """
            package app;
            public class Point {
                private float x;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Point that = (Point) o;
                    return Float.compare(this.x, that.x) == 0;
                }
                public int hashCode() { return Float.hashCode(this.x); }
            }
            """);
        assertTrue(reports("emits the same relation"));
    }

    /**
     * {@code Enum.equals} is final and identity-based, so the {@code Objects.equals}
     * the annotation emits and a written {@code ==} agree on every pair, two nulls
     * included. Only the spelling differs, so the pair is still replaceable.
     */
    public void testAnEnumComparedWithEqualsOperatorIsOffered() {
        configure("app/Signal.java",
            """
            package app;
            public class Signal {
                public enum Kind { UP, DOWN }
                private Kind kind;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Signal that = (Signal) o;
                    return this.kind == that.kind;
                }
                public int hashCode() { return this.kind.hashCode(); }
            }
            """);
        assertTrue(reports("emits the same relation"));

        String result = applyFix("Replace equals/hashCode with @EqualsAndHashCode");
        assertTrue("the annotation is written on the class",
            result.contains("@EqualsAndHashCode\npublic class Signal"));
        assertFalse("equals is gone", result.contains("boolean equals"));
        assertFalse("hashCode is gone", result.contains("int hashCode"));
        assertTrue("the nested enum survives", result.contains("public enum Kind { UP, DOWN }"));
    }

    /**
     * The enum reasoning does not generalise. On any other reference type
     * {@code ==} asks about identity where the annotation asks about value, and
     * two equal strings that were never interned answer differently.
     */
    public void testANonEnumReferenceComparedWithEqualsOperatorIsSilent() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private String value;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return this.value == that.value;
                }
                public int hashCode() { return this.value.hashCode(); }
            }
            """);
        assertSilent("== on a reference is identity, not the value comparison the annotation emits");
    }

    /**
     * The type is read as declared rather than as whatever the field can hold.
     * A constant reaching this member is an enum, but the slot is an interface,
     * so a non-enum implementation could be assigned to it tomorrow.
     */
    public void testAnEnumBehindAnInterfaceTypeIsSilent() {
        configure("app/Route.java",
            """
            package app;
            public class Route {
                public interface Step { }
                public enum Hop implements Step { A, B }
                private Step step;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Route that = (Route) o;
                    return this.step == that.step;
                }
                public int hashCode() { return this.step.hashCode(); }
            }
            """);
        assertSilent("the declared type is an interface, so == is not Objects.equals");
    }

    /** A guard reading the object's own state is doing work the annotation does not. */
    public void testAGuardThatReadsStateIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import java.util.Objects;
            public class Token {
                private String value;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    if (this.value == null) return false;
                    Token that = (Token) o;
                    return Objects.equals(this.value, that.value);
                }
                public int hashCode() { return Objects.hash(this.value); }
            }
            """);
        assertSilent("the null guard is behaviour the annotation would drop");
    }

    /** A term this does not recognise could mean anything. */
    public void testAnUnrecognisedTermIsSilent() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private String value;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return this.value.equalsIgnoreCase(that.value);
                }
                public int hashCode() { return this.value.toLowerCase().hashCode(); }
            }
            """);
        assertSilent("a normalising comparison is not what the annotation emits");
    }

    public void testATargetAlreadyCarryingTheAnnotationIsSilent() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            import java.util.Objects;
            @EqualsAndHashCode
            public class Token {
                private String value;
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return Objects.equals(this.value, that.value);
                }
                public int hashCode() { return Objects.hash(this.value); }
            }
            """);
        assertSilent("nothing to propose on a class that already has it");
    }

}
