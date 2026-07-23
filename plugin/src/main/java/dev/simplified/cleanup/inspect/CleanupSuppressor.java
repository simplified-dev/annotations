package dev.simplified.cleanup.inspect;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.annotations.Cleanup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Silences the resource-leak inspections on a declaration that {@code @Cleanup}
 * closes.
 *
 * <p>Without it every annotated site carries a live warning saying the opposite
 * of the truth: the platform reads the unmutated source, where nothing closes
 * the resource, while the compiled form is a try-with-resources over that exact
 * variable. The annotation is the answer to the warning, so it is also the
 * suppression - there is nothing for a quick fix to add, and
 * {@link #getSuppressActions} returns an empty array rather than offering to
 * write a comment that says less.
 *
 * <p>What keeps a deliberately generic id such as {@code resource} from
 * silencing a warning about anything else is that both tests have to pass, not
 * the order they are made in - the two are side-effect free, so either order
 * gives the same answer. The id set is tested first because it is a hash lookup
 * and the declaration walk is a PSI climb plus a modifier-list scan, and this
 * runs for every element every other tool reports on in a Java file.
 *
 * @see Cleanup
 */
public final class CleanupSuppressor implements InspectionSuppressor {

    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!CleanupConstants.RESOURCE_TOOL_IDS.contains(toolId)) return false;
        PsiLocalVariable declaration = enclosingDeclaration(element);
        return declaration != null && CleanupConstants.writesCleanup(declaration);
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element,
                                                           @NotNull String toolId) {
        return SuppressQuickFix.EMPTY_ARRAY;
    }

    /**
     * The local variable the reported element belongs to.
     *
     * <p>A resource-leak warning lands on the expression that produced the
     * resource, which is the initializer of the declaration that holds it. The
     * walk is bounded at the enclosing block so an element that is not part of
     * any declaration costs one step rather than a climb to the file.
     *
     * @param element the element an inspection is reporting on
     * @return the declaration it sits in, or {@code null} when it sits in none
     */
    private static @Nullable PsiLocalVariable enclosingDeclaration(@NotNull PsiElement element) {
        if (element instanceof PsiLocalVariable local) return local;
        return PsiTreeUtil.getParentOfType(element, PsiLocalVariable.class, false,
            PsiCodeBlock.class, PsiLambdaExpression.class);
    }

}
