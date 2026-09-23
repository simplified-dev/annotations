package dev.simplified.shared.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Drops the two compiler-parity errors the platform raises about fields a
 * generated constructor assigns.
 *
 * <p>The only extension point that reaches them. Both are produced by the Java
 * highlighter rather than by an inspection, so no {@code InspectionSuppressor}
 * sees them and no severity setting turns them off - they are exactly as
 * unsilenceable as a real syntax error, and they land on a red squiggle under a
 * field declaration in source that compiles.
 *
 * <p>Definite-assignment analysis walks written constructors, and the
 * constructor an augment provider contributes is not one. So a
 * {@code @RequiredArgsConstructor} class reports every {@code final} field as
 * possibly uninitialized, and a {@code @ClassBuilder} class whose {@code final}
 * initializers were lifted into builder defaults reports every assignment to one
 * as a write to an already-assigned final. Neither survives contact with javac.
 *
 * <p>Two gates, both of which must pass: the message has to be one of the two
 * this filter owns, and {@link GeneratedFieldAccess} has to agree the field is
 * one a generated member assigns. The message test alone would swallow the same
 * error on a class carrying no annotation of ours; the field test alone would
 * swallow every other error reported on an annotated class's field. A write to
 * a lifted blank final also has to sit where javac accepts one - a plain
 * assignment directly in a constructor of the field's class - or the report
 * stands, since javac rejects that write on the same line.
 *
 * <p>The two messages anchor in different places, so each has its own resolver.
 * Definite assignment ranges over a declaration, where the element under the
 * report is some part of the field's annotations, modifiers or type and the
 * field is above it. The blank-final write ranges over an assignment's left-hand
 * reference inside a body, where the field is what the reference resolves to and
 * is nowhere above. One resolver for both would have to try the walk and the
 * resolve in some order, and that order - not the report - would decide which
 * field a write inside a field initializer counts against.
 */
public final class GeneratedMemberHighlightFilter implements HighlightInfoFilter {

    /**
     * Definite assignment, ranged over the declaration of the field it names.
     *
     * <p>Matched as a substring because the platform words it over both
     * "Variable" and "Field" depending on version, and the run-together
     * remainder is identical in both.
     */
    private static final String NOT_INITIALIZED = "might not have been initialized";

    /** The blank-final lift, reported on the assignment's left-hand reference. */
    private static final String FINAL_ASSIGNMENT = "Cannot assign a value to final variable";

    @Override
    public boolean accept(@NotNull HighlightInfo info, @Nullable PsiFile file) {
        if (file == null) return true;
        if (info.getSeverity() != HighlightSeverity.ERROR) return true;
        String description = info.getDescription();
        if (description == null) return true;

        if (description.contains(NOT_INITIALIZED)) {
            PsiField declared = declaredField(file, info.getStartOffset());
            return declared == null || !GeneratedFieldAccess.constructorAssigns(declared);
        }
        if (description.contains(FINAL_ASSIGNMENT)) {
            PsiReferenceExpression written = writtenReference(file, info.getStartOffset());
            if (written == null || !(written.resolve() instanceof PsiField assigned)) return true;
            return !constructorAssigns(written, assigned) || !GeneratedFieldAccess.liftedBlankFinal(assigned);
        }
        return true;
    }

    /**
     * Whether a write is one javac accepts to a blank final.
     *
     * <p>A blank final is assigned only by a plain {@code =} directly in a
     * constructor of its own class, through its bare name or an unqualified
     * {@code this}. Everywhere else javac rejects the write whether or not the
     * field is lifted: a method, an instance initializer beside the
     * constructors that all assign it, a lambda or a local class inside a
     * constructor, another instance's field, and a compound assignment or an
     * increment, which read the field as well.
     *
     * @param written the written reference
     * @param field the field it resolves to
     * @return whether the write sits where javac accepts it
     */
    private static boolean constructorAssigns(@NotNull PsiReferenceExpression written, @NotNull PsiField field) {
        PsiExpression qualifier = PsiUtil.skipParenthesizedExprDown(written.getQualifierExpression());
        if (qualifier != null && !(qualifier instanceof PsiThisExpression self && self.getQualifier() == null))
            return false;
        if (!(PsiUtil.skipParenthesizedExprUp(written.getParent()) instanceof PsiAssignmentExpression assignment))
            return false;
        if (assignment.getOperationTokenType() != JavaTokenType.EQ) return false;
        if (PsiUtil.skipParenthesizedExprDown(assignment.getLExpression()) != written) return false;
        PsiElement scope = PsiTreeUtil.getParentOfType(written, PsiMethod.class, PsiLambdaExpression.class,
            PsiClass.class, PsiClassInitializer.class, PsiField.class);
        return scope instanceof PsiMethod method && method.isConstructor()
            && field.getManager().areElementsEquivalent(method.getContainingClass(), field.getContainingClass());
    }

    /**
     * Resolves the field a declaration-anchored report declares.
     *
     * @param file the file being highlighted
     * @param offset the report's start offset
     * @return the field, or {@code null} when the report is not on a field declaration
     */
    private static @Nullable PsiField declaredField(@NotNull PsiFile file, int offset) {
        PsiElement anchor = file.findElementAt(offset);
        return anchor == null ? null : ReportAnchors.declaredField(anchor);
    }

    /**
     * Finds the reference an assignment-anchored report writes through.
     *
     * <p>A qualified write reports from its first token, so {@code this.x = x}
     * anchors on {@code this} and {@code other.x = x} on {@code other}, itself a
     * reference. The written one is the outermost reference starting where the
     * report does. Bounded at the statement so an element in no reference costs
     * a step or two rather than a climb to the file.
     *
     * @param file the file being highlighted
     * @param offset the report's start offset
     * @return the reference, or {@code null} when the report is not on one
     */
    private static @Nullable PsiReferenceExpression writtenReference(@NotNull PsiFile file, int offset) {
        PsiElement anchor = file.findElementAt(offset);
        PsiReferenceExpression written = null;
        for (PsiElement at = anchor; at != null && !(at instanceof PsiStatement) && !(at instanceof PsiFile)
            && at.getTextRange().getStartOffset() == offset; at = at.getParent())
            if (at instanceof PsiReferenceExpression reference) written = reference;
        return written;
    }

}
