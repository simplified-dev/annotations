package dev.simplified.shared.inspect;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The field declaration a report is anchored on, for the extension points that
 * have to decide whether a report is about a field a generated member reaches.
 *
 * <p>A report about a field declaration rarely lands on the field's name. The
 * platform ranges one from the last annotation in the modifier list, or from the
 * modifier list itself when the field carries none, so the leaf under the
 * report's offset is the type reference's identifier on
 * {@code private final @NotNull String a}, the {@code private} keyword on
 * {@code private final int b}, and the {@code @} token of the annotation on a
 * nullability report. A field sharing a declaration with an earlier one holds
 * neither modifiers nor a type element of its own, and is ranged from its name.
 * Enumerating those shapes is how a resolver comes to answer {@code null} for
 * the declarations it exists to find, so every anchor is walked up to the field
 * that declares it rather than matched against a shape.
 *
 * <p>Resolved here rather than on each side, so an anchor the platform moves
 * reaches the highlight filter and the inspection suppressor together and the
 * two cannot come to disagree about which field a report belongs to.
 */
final class ReportAnchors {

    private ReportAnchors() {
    }

    /**
     * Resolves the field whose declaration holds the anchored element.
     *
     * <p>The walk stops at the first code block, method, lambda or class above
     * the anchor. An element written inside one of those belongs to what the
     * author wrote there rather than to a declaration the construct is nested
     * in, so the walk answers {@code null} instead of climbing past it, and
     * reaching one without having passed a field means the anchor is part of no
     * declaration at all. An initializer is part of the declaration and so
     * inside that bound, and is excluded on its own: a report about an
     * expression the author wrote is about that expression, not about the field
     * it initializes.
     *
     * @param anchor the element a report is anchored on
     * @return the field being declared, or {@code null} when the anchor is not part of a declaration
     */
    static @Nullable PsiField declaredField(@NotNull PsiElement anchor) {
        PsiField field = PsiTreeUtil.getParentOfType(anchor, PsiField.class, false,
            PsiCodeBlock.class, PsiMethod.class, PsiClass.class, PsiLambdaExpression.class);
        if (field == null) return null;
        PsiExpression initializer = field.getInitializer();
        if (initializer != null && PsiTreeUtil.isAncestor(initializer, anchor, false)) return null;
        return field;
    }

}
