package dev.simplified.shared.psi;

import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Redirects a rename started on a synthesised member to the slot behind it.
 *
 * <p>The member has no declaration to rewrite, so renaming it directly is not a
 * refactoring the platform can carry out. The slot is: renaming it moves the
 * declaration, and the member follows because it is spelled from the slot -
 * which {@link GeneratedMemberRenameProcessor} is what carries through to the
 * call sites.
 *
 * <p>Only a slot is ever substituted. A member that navigates to the annotation
 * that asked for it, or to the type it was hung on, has nothing here to offer
 * and is left to {@link GeneratedMemberRenameVetoHandler}.
 */
public final class GeneratedMemberRenameSubstitutor extends RenamePsiElementProcessor {

    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return element instanceof PsiMethod method
            && GeneratedMemberMarker.isGenerated(method)
            && GeneratedMembers.isSlot(method.getNavigationElement());
    }

    @Override
    public @Nullable PsiElement substituteElementToRename(@NotNull PsiElement element,
                                                          @Nullable Editor editor) {
        return element.getNavigationElement();
    }

}
