package dev.simplified.classbuilder.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Reports a {@code @ClassBuilder} that generates nothing because the target
 * already declares a nested type of the builder's name.
 *
 * <p>No position does: a class or record target, each SuperBuilder chain role,
 * and a constructor or factory target all merge the generated members into the
 * declaration, and an interface target's builder is a sibling file. The
 * inspection stays registered and reports nothing, a declared shape the merge
 * refuses being {@link DeclaredBuilderShapeInspection}'s error.
 */
public class DeclaredBuilderSuppressesGenerationInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return PsiElementVisitor.EMPTY_VISITOR;
    }

}
