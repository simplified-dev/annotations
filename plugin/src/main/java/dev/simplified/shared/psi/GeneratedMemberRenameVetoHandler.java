package dev.simplified.shared.psi;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.rename.PsiElementRenameHandler;
import com.intellij.refactoring.rename.RenameHandler;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Refuses a rename started on a synthesised member that stands for no slot.
 *
 * <p>The nested builder, the bootstrap methods, and the whole-object trio are
 * spelled from an annotation attribute or from the type, not from a field, so
 * there is no declaration a rename could move and nothing for
 * {@link GeneratedMemberRenameSubstitutor} to redirect to. Left alone the
 * platform accepts the refactoring and fails partway through it, on an element
 * that has no name to set; saying so up front is the same answer delivered
 * before anything has been touched.
 */
public final class GeneratedMemberRenameVetoHandler implements RenameHandler {

    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        PsiElement element = PsiElementRenameHandler.getElement(dataContext);
        return element != null
            && GeneratedMemberMarker.isGenerated(element)
            && !GeneratedMembers.isSlot(element.getNavigationElement());
    }

    @Override
    public boolean isRenaming(@NotNull DataContext dataContext) {
        return isAvailableOnDataContext(dataContext);
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file,
                       @Nullable DataContext dataContext) {
        refuse(project, editor);
    }

    @Override
    public void invoke(@NotNull Project project, PsiElement @NotNull [] elements,
                       @Nullable DataContext dataContext) {
        refuse(project, dataContext == null ? null : CommonDataKeys.EDITOR.getData(dataContext));
    }

    /**
     * Reports that the element cannot be renamed.
     *
     * @param project the project the rename was started in
     * @param editor the editor to anchor the hint to, or {@code null}
     */
    private static void refuse(@NotNull Project project, @Nullable Editor editor) {
        CommonRefactoringUtil.showErrorHint(project, editor,
            RefactoringBundle.getCannotRefactorMessage(
                "This member is generated and has no declaration to rename."),
            RefactoringBundle.message("rename.title"), null);
    }

}
