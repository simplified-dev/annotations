package dev.simplified.cleanup.mutate;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCCase;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCEnhancedForLoop;
import com.sun.tools.javac.tree.JCTree.JCForLoop;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import dev.simplified.shared.javac.AnnotationSpelling;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.LazyOwnership;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Rewrites every block that declares a {@code @Cleanup} local into a
 * try-with-resources over that local.
 *
 * <p>The block is split at the annotated declaration: the declaration and
 * everything before it stay where they are, and the remainder of the block
 * becomes the body of a {@code try (name) { … }}. The tail is rewritten first,
 * so a second declaration further down becomes the inner try and resources close
 * in reverse declaration order.
 *
 * <p>A single {@code JCIdent} in the resource list produces exactly the tree
 * javac's parser builds for the existing-variable resource form, so javac's own
 * {@code Lower} generates the close call, the null skip and the
 * {@code addSuppressed} bookkeeping. Nothing here mints a member, a name or a
 * type.
 *
 * <p>Statements are <b>moved</b> rather than copied - no {@code TreeCopier}, no
 * {@code sym} or {@code type} reset - so every position stays exactly as parsed
 * and the checks that read positions (forward-reference detection, definite
 * assignment) see what they saw before. The one position that has to be chosen
 * is the synthesised try's: the close call can throw, javac reports that against
 * the try, and {@code make.at(decl.pos)} is what puts the message on the
 * annotated line.
 *
 * <p>Runs after {@code LazyFieldMutator}, which rewrites {@code this.foo = foo}
 * assignments by walking a constructor body's statement list flat. Relocating a
 * tail one level down before it runs hides those assignments from it.
 *
 * @see dev.simplified.annotations.Cleanup
 */
public final class CleanupBlockMutator {

    /** Key for this pass's idempotency marks. */
    private static final String PASS = "cleanup";

    private static final String CLEANUP_FQN = "dev.simplified.annotations.Cleanup";
    private static final String CLEANUP_SIMPLE = "Cleanup";

    private final JavacBridge bridge;
    private final Messager messager;
    private final TypeElement targetElement;
    private final TreeMaker make;

    /**
     * Resolution of the bare simple name {@code Cleanup} in the compilation unit
     * under rewrite. Computed on first need rather than up front: resolving it
     * walks the unit, and a compilation with no {@code @Cleanup} anywhere must
     * not pay for that.
     */
    private Boolean simpleNameResolves;

    public CleanupBlockMutator(JavacBridge bridge, Messager messager, TypeElement targetElement) {
        this.bridge = bridge;
        this.messager = messager;
        this.targetElement = targetElement;
        this.make = bridge.treeMaker();
    }

    /**
     * Rewrites every block under the given declaration, nested types, anonymous
     * and local classes, initialiser blocks and lambda bodies included.
     *
     * @param target the class declaration to walk
     * @return {@code true} when the walk ran; {@code false} for a null tree
     */
    public boolean mutate(JCClassDecl target) {
        if (target == null) return false;
        new BlockScanner().scan(target);
        return true;
    }

    /**
     * Whether the declaration or any type nested in it declares a {@code @Lazy}
     * field.
     *
     * <p>Such a tree is rewritten from {@code ClassBuilderProcessor} instead,
     * after the {@code @Lazy} pass has retyped its constructor assignments -
     * that pass reads a constructor body's statements flat, so a tail this one
     * relocated is invisible to it.
     *
     * @param target the class declaration to inspect
     * @param unit the compilation unit the declaration sits in
     * @return {@code true} when the builder pipeline owns this tree
     * @see LazyOwnership#declaresLazyField(JCClassDecl, CompilationUnitTree)
     */
    public static boolean declaresLazyField(JCClassDecl target, CompilationUnitTree unit) {
        return LazyOwnership.declaresLazyField(target, unit);
    }

    // ------------------------------------------------------------------
    // Walk
    // ------------------------------------------------------------------

    /**
     * Reaches every block in the tree. Blocks are rewritten on the way back up,
     * so a declaration inside an {@code if} has already claimed the remainder of
     * that block by the time the enclosing block claims the {@code if}.
     */
    private final class BlockScanner extends TreeScanner {

        @Override
        public void visitBlock(JCBlock block) {
            super.visitBlock(block);
            rewriteBlock(block);
        }

        /**
         * A colon-labelled case group holds its statements on the case itself -
         * a switch's braces are not a {@code JCBlock} - so the block walk never
         * reaches a declaration written there. Handled rather than skipped: a
         * dropped annotation is a resource that is never closed, with nothing
         * said about it. The arrow form with a braced body carries a real block
         * and arrives through {@link #visitBlock} as any other.
         */
        @Override
        public void visitCase(JCCase caseNode) {
            super.visitCase(caseNode);
            rewriteCase(caseNode);
        }

        @Override
        public void visitForLoop(JCForLoop loop) {
            for (JCStatement init : loop.init) {
                if (init instanceof JCVariableDecl decl && isCleanup(decl)) reportLoopVariable(decl);
            }
            super.visitForLoop(loop);
        }

        @Override
        public void visitForeachLoop(JCEnhancedForLoop loop) {
            JCVariableDecl decl = loop.getVariable();
            if (isCleanup(decl)) reportLoopVariable(decl);
            super.visitForeachLoop(loop);
        }

    }

    // ------------------------------------------------------------------
    // Rewrite
    // ------------------------------------------------------------------

    /**
     * Splits the block at its first {@code @Cleanup} declaration and wraps the
     * remainder in a try-with-resources over it.
     *
     * <p>A rewritten block is marked, and only a rewritten one. It still holds
     * the declaration in its head, so a second visit would split at the same
     * statement with an empty tail and close the resource twice; a block that was
     * left alone re-reads as the same no-op, so marking it buys nothing and
     * writing to the shared pass set once per block of every class in every
     * compilation - which is what a {@code "*"} processor visits - is a cost
     * paid for nothing. The mark is this pass's own rather than the shared
     * generated one: these are the author's blocks, and claiming them as
     * generated would both misattribute them and hide them from every other pass
     * that reads that flag.
     */
    private void rewriteBlock(JCBlock block) {
        if (AstMarkers.isPassMarked(block, PASS)) return;
        List<JCStatement> rewritten = rewriteStatements(block.stats);
        if (rewritten == null) return;
        AstMarkers.markPass(block, PASS);
        block.stats = rewritten;
    }

    /**
     * The same split, applied to a colon-labelled case group's own statement
     * list. The group's statements hang off the case rather than off any block,
     * so this is the only place a declaration written there can be reached.
     */
    private void rewriteCase(JCCase caseNode) {
        if (AstMarkers.isPassMarked(caseNode, PASS)) return;
        List<JCStatement> rewritten = rewriteStatements(caseNode.stats);
        if (rewritten == null) return;
        AstMarkers.markPass(caseNode, PASS);
        caseNode.stats = rewritten;
    }

    /**
     * Splits a statement list at its first {@code @Cleanup} declaration and
     * wraps the remainder in a try-with-resources over it.
     *
     * <p>An empty tail is wrapped like any other. That is the case a
     * {@code tail.isEmpty()} shortcut would skip, and skipping it means the
     * resource is never closed - silently, and past any test that only checks
     * that the file compiled.
     *
     * @param stats the statements to split
     * @return the rewritten list, or {@code null} when the list declares no
     *         {@code @Cleanup} and must be left exactly as it was
     */
    private List<JCStatement> rewriteStatements(List<JCStatement> stats) {
        ListBuffer<JCStatement> head = new ListBuffer<>();
        List<JCStatement> rest = stats;
        JCVariableDecl decl = null;
        while (rest.nonEmpty()) {
            JCStatement stmt = rest.head;
            rest = rest.tail;
            head.append(stmt);
            if (stmt instanceof JCVariableDecl candidate && isCleanup(candidate)) {
                decl = candidate;
                break;
            }
        }
        if (decl == null) return null;

        make.at(decl.pos);
        JCBlock body = make.Block(0, rest);
        // The tail first, so a later declaration becomes the inner try and the
        // resources close in reverse declaration order.
        rewriteBlock(body);

        make.at(decl.pos);
        JCStatement wrapped = make.Try(
            List.<JCTree>of(make.Ident(decl.name)),
            body,
            List.nil(),
            null
        );
        AstMarkers.markGenerated(wrapped);
        return head.append(wrapped).toList();
    }

    // ------------------------------------------------------------------
    // Annotation matching
    // ------------------------------------------------------------------

    /**
     * Whether the declaration carries {@code @Cleanup}.
     *
     * <p>A local variable's annotations are never attributed during annotation
     * processing, so the match is on what was written. The fully-qualified
     * spelling is always accepted; the bare simple name only when the
     * compilation unit is one where it resolves to this annotation, which keeps
     * a same-named annotation from another library out.
     */
    private boolean isCleanup(JCVariableDecl decl) {
        if (decl == null || decl.mods == null) return false;
        for (JCAnnotation annotation : decl.mods.annotations) {
            String written = annotation.annotationType.toString();
            if (CLEANUP_FQN.equals(written)) return true;
            if (CLEANUP_SIMPLE.equals(written) && simpleNameResolves()) return true;
        }
        return false;
    }

    /**
     * Whether a bare {@code Cleanup} in this compilation unit names this
     * annotation - by single-type import, by an on-demand import of its package,
     * or by the unit being in that package itself, with a single-type import of
     * another {@code Cleanup} shadowing all three.
     */
    private boolean simpleNameResolves() {
        if (simpleNameResolves != null) return simpleNameResolves;
        TreePath path = bridge.trees().getPath(targetElement);
        CompilationUnitTree unit = path == null ? null : path.getCompilationUnit();
        simpleNameResolves = AnnotationSpelling.simpleNameResolves(unit, CLEANUP_FQN);
        return simpleNameResolves;
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    /**
     * Reports a {@code @Cleanup} on a loop variable, where there is no block
     * remainder to close at. Reported rather than skipped: a silently ignored
     * annotation leaves a resource open with nothing said about it.
     */
    private void reportLoopVariable(JCVariableDecl decl) {
        messager.printMessage(Diagnostic.Kind.ERROR,
            "@Cleanup is not supported on a loop variable - '" + decl.name
                + "' has no enclosing block remainder to be closed at. Declare it before the loop",
            targetElement);
    }

}
