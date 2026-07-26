package dev.simplified.shared.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;
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
 * swallow every other error reported on an annotated class's field.
 */
public final class GeneratedMemberHighlightFilter implements HighlightInfoFilter {

    /**
     * Definite assignment, reported on the field's name identifier.
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

        boolean uninitialized = description.contains(NOT_INITIALIZED);
        if (!uninitialized && !description.contains(FINAL_ASSIGNMENT)) return true;

        PsiField field = reportedField(file, info.getStartOffset());
        if (field == null) return true;
        return uninitialized
            ? !GeneratedFieldAccess.constructorAssigns(field)
            : !GeneratedFieldAccess.liftedBlankFinal(field);
    }

    /**
     * The field a report at this offset is about.
     *
     * <p>The two messages land on different elements - a declaration's name
     * identifier and an assignment's left-hand reference - so both shapes are
     * resolved here rather than at either call site.
     *
     * @param file the file being highlighted
     * @param offset the report's start offset
     * @return the field, or {@code null} when the report is about something else
     */
    private static @Nullable PsiField reportedField(@NotNull PsiFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        if (element == null) return null;
        if (element instanceof PsiIdentifier && element.getParent() instanceof PsiField field)
            return field;
        // A qualified assignment reports from its first token, so `this.x = x`
        // anchors on `this` and the reference is two steps up rather than one.
        // Bounded at the statement so an element in no reference costs a step
        // or two rather than a climb to the file.
        PsiReferenceExpression reference =
            PsiTreeUtil.getParentOfType(element, PsiReferenceExpression.class, false,
                PsiStatement.class);
        if (reference != null && reference.resolve() instanceof PsiField field) return field;
        return null;
    }

}
