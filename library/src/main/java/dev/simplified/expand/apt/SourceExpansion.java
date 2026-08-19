package dev.simplified.expand.apt;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.tools.javac.tree.DocCommentTable;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.shared.javac.AstMarkers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splices a compilation unit's generated members into a copy of its own source
 * text.
 *
 * <p>The original bytes are carried over untouched and the generated
 * declarations are inserted just inside each class's closing brace, so nothing
 * the author wrote is re-printed. That is what removes the need for both a
 * pretty-printer and the comment re-attachment a pretty-printer forces, since
 * every authored comment is still sitting where it was written.
 *
 * <p>javac supplies the insertion point. A class's end position is one past its
 * closing brace, so no brace matching is done here and the strings, comments and
 * text blocks that would defeat a scanner never come into it.
 */
final class SourceExpansion {

    private static final String STEP = "    ";

    private final CompilationUnitTree unit;
    private final SourcePositions positions;
    private final MemberRenderer renderer;

    /**
     * Constructs a new {@code SourceExpansion} over one compilation unit.
     *
     * @param unit the compilation unit, already mutated by every pass
     * @param positions the compiler's source-position lookup
     */
    SourceExpansion(CompilationUnitTree unit, SourcePositions positions) {
        this.unit = unit;
        this.positions = positions;
        DocCommentTable comments = unit instanceof JCCompilationUnit javac ? javac.docComments : null;
        this.renderer = new MemberRenderer(
            tree -> comments == null ? null : comments.getCommentText((JCTree) tree),
            this::needsWriting);
    }

    /**
     * Whether the member has to be written into the copy.
     *
     * <p>Two questions, and neither answers it alone. A node the pipeline marked
     * is not necessarily one it introduced - {@code @Lazy} retypes the author's
     * own field in place and marks it, and {@code @EnumLookup} marks an authored
     * static initialiser - and those are already in the copy, so writing them
     * again declares the field twice. A node with no source position is not
     * necessarily ours either, because javac's implicit default constructor has
     * none. Only a node that is both marked and absent from the original text is
     * a member the copy is missing.
     */
    private boolean needsWriting(Tree member) {
        return AstMarkers.isGenerated((JCTree) member)
            && this.positions.getEndPosition(this.unit, member) < 0;
    }

    /**
     * The compilation unit's source text with every generated member spliced in.
     *
     * @param source the unit's original text
     * @return the expanded text, identical to {@code source} when the unit
     *         generated nothing
     */
    String expand(CharSequence source) {
        List<Insert> inserts = new ArrayList<>();
        for (Tree type : this.unit.getTypeDecls())
            if (type instanceof ClassTree declared) collect(declared, inserts, STEP, source);

        if (inserts.isEmpty()) return source.toString();

        inserts.sort(Comparator.comparingInt(Insert::at).reversed());
        StringBuilder out = new StringBuilder(source);
        for (Insert insert : inserts) out.insert(insert.at(), insert.text());
        return out.toString();
    }

    /**
     * Records an insertion for the class and descends into the nested classes it
     * declares.
     *
     * <p>A class the pipeline generated whole has no source of its own to splice
     * into - it is rendered in full by its enclosing class instead, so the walk
     * does not follow it.
     */
    private void collect(ClassTree type, List<Insert> inserts, String indent, CharSequence source) {
        if (AstMarkers.isGenerated((JCTree) type)) return;

        long end = this.positions.getEndPosition(this.unit, type);
        if (end > 0) {
            String members = this.renderer.renderGeneratedMembers(type, indent);
            if (!members.isBlank()) {
                int at = anchor(source, (int) end);
                // An empty body carries no whitespace to sit the closing brace
                // on, so the members would run straight into it.
                boolean emptyBody = at > 0 && source.charAt(at - 1) == '{';
                String closing = emptyBody
                    ? "\n" + indent.substring(0, Math.max(0, indent.length() - STEP.length()))
                    : "";
                inserts.add(new Insert(at, members + closing));
            }
        }

        for (Tree member : type.getMembers())
            if (member instanceof ClassTree nested) collect(nested, inserts, indent + STEP, source);
    }

    /**
     * The offset to insert at for a class ending one past {@code end}, backed up
     * over the whitespace that indents the closing brace so the spliced members
     * land against the last authored one.
     */
    private static int anchor(CharSequence source, int end) {
        int at = Math.min(end, source.length()) - 1;
        while (at > 0 && Character.isWhitespace(source.charAt(at - 1))) at--;
        return at;
    }

    /** One block of rendered text and the offset it belongs at. */
    private record Insert(int at, String text) {
    }

}
