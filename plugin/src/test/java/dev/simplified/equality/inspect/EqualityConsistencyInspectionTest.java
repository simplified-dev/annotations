package dev.simplified.equality.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
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
 * Edit-time behaviour of {@link EqualityConsistencyInspection}: the two
 * directions of the split, and the five shapes it would be harmful to report.
 *
 * <p>The negatives carry the weight here. This is a check over hand-written code
 * that javac accepts, so every false positive lands on a member somebody wrote
 * deliberately.
 */
public class EqualityConsistencyInspectionTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(new EqualityConsistencyInspection());
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

    private boolean reports(String fragment, HighlightSeverity severity) {
        return myFixture.doHighlighting().stream()
            .anyMatch(info -> severity.equals(info.getSeverity())
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

    // ------------------------------------------------------------------
    // The split, in both directions
    // ------------------------------------------------------------------

    /** The shape four of the workspace's own types are in. */
    public void testHashCodeReadingLessThanEqualsIsAWeakWarning() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private long value;
                private int issued;
                public long getValue() { return this.value; }
                public int getIssued() { return this.issued; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return this.getValue() == that.getValue()
                        && this.getIssued() == that.getIssued();
                }
                public int hashCode() { return (int) this.getValue(); }
            }
            """);
        assertTrue(reports("equals compares 'issued', which hashCode ignores",
            HighlightSeverity.WEAK_WARNING));
    }

    /**
     * Folding the supertype in on one side only. The same divergence as a
     * dropped field, and the shape that is easiest to miss by eye.
     */
    public void testFoldingSuperOnOneSideOnlyIsReported() {
        configure("app/Child.java",
            """
            package app;
            class Base {
                private int id;
                public boolean equals(Object o) {
                    return o instanceof Base && ((Base) o).id == this.id;
                }
                public int hashCode() { return this.id; }
            }
            public class Child extends Base {
                private boolean flag;
                public boolean isFlag() { return this.flag; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    if (!super.equals(o)) return false;
                    Child that = (Child) o;
                    return this.isFlag() == that.isFlag();
                }
                public int hashCode() { return this.isFlag() ? 1 : 0; }
            }
            """);
        assertTrue(reports("equals compares the superclass, which hashCode ignores",
            HighlightSeverity.WEAK_WARNING));
    }

    /** The half that is actually broken, and the only one raised to a warning. */
    public void testHashCodeReadingMoreThanEqualsIsAWarning() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private long value;
                private int issued;
                public long getValue() { return this.value; }
                public int getIssued() { return this.issued; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return this.getValue() == that.getValue();
                }
                public int hashCode() { return (int) this.getValue() + this.getIssued(); }
            }
            """);
        assertTrue(reports("hashCode reads 'issued', which equals ignores",
            HighlightSeverity.WARNING));
        assertFalse("the contract break is reported instead of the loose hash, not beside it",
            reports("which hashCode ignores", HighlightSeverity.WEAK_WARNING));
    }

    // ------------------------------------------------------------------
    // The shapes actually found in the workspace
    // ------------------------------------------------------------------

    /**
     * {@code Attachment} and {@code FileUpload}, verbatim in shape. Every term
     * is wrapped in a {@code java.util.Objects} call, which is what the earlier
     * cases deliberately leave out - the terms have to be found through the
     * static call rather than beside it.
     */
    public void testTheAttachmentShapeIsReported() {
        configure("app/Attachment.java",
            """
            package app;
            import java.util.Objects;
            public class Attachment {
                private String mediaData;
                private int fileId;
                private long size;
                public String getMediaData() { return this.mediaData; }
                public int getFileId() { return this.fileId; }
                public long getSize() { return this.size; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Attachment that = (Attachment) o;
                    return Objects.equals(this.getMediaData(), that.getMediaData())
                        && this.getFileId() == that.getFileId()
                        && this.getSize() == that.getSize();
                }
                public int hashCode() { return Objects.hash(this.getMediaData()); }
            }
            """);
        assertTrue(reports("equals compares 'fileId' and 'size', which hashCode ignores",
            HighlightSeverity.WEAK_WARNING));
    }

    /**
     * {@code Button}, verbatim in shape: a folded {@code super.equals} and one
     * extra term on the equality side, neither reaching the hash.
     */
    public void testTheButtonShapeIsReported() {
        configure("app/Button.java",
            """
            package app;
            import java.util.Objects;
            class Component {
                private String key;
                public boolean equals(Object o) {
                    return o instanceof Component && Objects.equals(((Component) o).key, this.key);
                }
                public int hashCode() { return Objects.hash(this.key); }
            }
            public class Button extends Component {
                private String label;
                private boolean deferEdit;
                public String getLabel() { return this.label; }
                public boolean isDeferEdit() { return this.deferEdit; }
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (o == null || getClass() != o.getClass()) return false;
                    if (!super.equals(o)) return false;
                    Button button = (Button) o;
                    return Objects.equals(this.getLabel(), button.getLabel())
                        && this.isDeferEdit() == button.isDeferEdit();
                }
                public int hashCode() { return Objects.hash(this.getLabel()); }
            }
            """);
        assertTrue(reports("equals compares the superclass and 'deferEdit', which hashCode ignores",
            HighlightSeverity.WEAK_WARNING));
    }

    // ------------------------------------------------------------------
    // The shapes it must not report
    // ------------------------------------------------------------------

    public void testMatchingMemberSetsAreSilent() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private long value;
                private int issued;
                public long getValue() { return this.value; }
                public int getIssued() { return this.issued; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return this.getValue() == that.getValue()
                        && this.getIssued() == that.getIssued();
                }
                public int hashCode() { return (int) this.getValue() + this.getIssued(); }
            }
            """);
        assertSilent("a pair reading the same state says nothing");
    }

    /**
     * An accessor on one side and the field behind it on the other is one
     * member. The two spellings routinely differ between a pair, and the
     * divergence being looked for is about state rather than syntax.
     */
    public void testAccessorAndFieldSpellingsAreOneMember() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private long value;
                private int issued;
                public long getValue() { return this.value; }
                public int getIssued() { return this.issued; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return this.getValue() == that.getValue()
                        && this.getIssued() == that.getIssued();
                }
                public int hashCode() { return (int) this.value + this.issued; }
            }
            """);
        assertSilent("getValue() and value are the same member");
    }

    /** A memoized hash writes its own slot, which is a cache and not a member. */
    public void testCachedHashCodeIsSilent() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private long value;
                private int issued;
                private transient int memo;
                public long getValue() { return this.value; }
                public int getIssued() { return this.issued; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return this.getValue() == that.getValue()
                        && this.getIssued() == that.getIssued();
                }
                public int hashCode() {
                    if (this.memo == 0) this.memo = (int) this.value + this.issued;
                    return this.memo;
                }
            }
            """);
        assertSilent("the memo slot is not state the relation reads");
    }

    /**
     * A body that hands the comparison somewhere this walk cannot follow yields
     * no member set at all, and an unknown compared against a known one would
     * report every class written this way.
     */
    public void testDelegatingEqualsIsSilent() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private long value;
                private int issued;
                public long getValue() { return this.value; }
                public boolean sameAs(Token other) { return this.value == other.value; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    return this.sameAs((Token) o);
                }
                public int hashCode() { return (int) this.getValue() + this.issued; }
            }
            """);
        assertSilent("an opaque body is not evidence of a split");
    }

    /** The annotation's own inspection owns a target carrying it. */
    public void testAnnotatedTargetIsLeftToTheOtherInspection() {
        configure("app/Token.java",
            """
            package app;
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public class Token {
                private long value;
                private int issued;
                public long getValue() { return this.value; }
                public int getIssued() { return this.issued; }
                public boolean equals(Object o) {
                    if (o == null || getClass() != o.getClass()) return false;
                    Token that = (Token) o;
                    return this.getValue() == that.getValue()
                        && this.getIssued() == that.getIssued();
                }
                public int hashCode() { return (int) this.getValue(); }
            }
            """);
        assertSilent("saying it twice in two vocabularies would read as two problems");
    }

    /** Half a pair is a different defect, and not this one. */
    public void testHashCodeWithoutEqualsIsSilent() {
        configure("app/Token.java",
            """
            package app;
            public class Token {
                private long value;
                public long getValue() { return this.value; }
                public int hashCode() { return (int) this.getValue(); }
            }
            """);
        assertSilent("there is no second member to disagree with");
    }

}
