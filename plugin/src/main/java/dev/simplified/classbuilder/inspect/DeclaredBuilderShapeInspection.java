package dev.simplified.classbuilder.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.BuilderScheme;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import org.jetbrains.annotations.NotNull;

/**
 * Reports a declared builder the merge cannot append to.
 *
 * <p>The processor refuses these shapes with an error and the editor has had no
 * analogue, so an author whose builder is non-static, carries the wrong
 * parameter list, is declared abstract where the entry points instantiate it, or
 * spells a build method that cannot stand in for the generated one saw a fully
 * populated class until the build failed. The rejection and its wording both
 * come from the shared decision, so what is red here is red there, in the same
 * sentence.
 *
 * <p>Reported on the declared builder's name identifier - the element the author
 * would act on, and one no other {@code classbuilder} inspection claims. The
 * processor reports on the annotated class instead, which is where it has an
 * element to report on at all.
 */
public class DeclaredBuilderShapeInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (!ClassBuilderConstants.ANNOTATION_FQN.equals(annotation.getQualifiedName())) return;

                PsiModifierListOwner owner =
                    PsiTreeUtil.getParentOfType(annotation, PsiModifierListOwner.class);
                boolean executable = owner instanceof PsiMethod;
                PsiClass target = owner instanceof PsiClass cls
                    ? cls
                    : owner instanceof PsiMethod method ? method.getContainingClass() : null;
                if (target == null || target.getName() == null) return;

                NamingStyle style = ClassBuilderConstants.namingStyle(annotation);
                BuilderScheme names =
                    ClassBuilderConstants.builderScheme(annotation, style, target.getName());

                // The ancestor case is reported on the annotation rather than on
                // a declaration, the offending class being one the author did
                // not write and may not own.
                PsiClass blocking = ClassBuilderConstants.ancestorBlockingGeneration(target,
                    names.type(), executable);
                if (blocking != null && blocking.getName() != null) {
                    holder.registerProblem(annotation,
                        DeclaredBuilderShape.ancestorDeclaresItsOwnBuilder(target.getName(),
                            blocking.getName()),
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                // The merge runs on a type target and nowhere else, so the shape
                // of a declared builder is only a question there.
                if (executable) return;
                if (!ClassBuilderConstants.booleanAttr(annotation,
                    ClassBuilderConstants.ATTR_MERGE_DECLARED_BUILDER, false)) {
                    return;
                }
                PsiClass declared = ClassBuilderConstants.declaredBuilderOf(target, names.type());
                if (declared == null || declared.getName() == null) return;

                String rejection = ClassBuilderConstants.mergeRejection(target, declared, names);
                if (rejection == null) return;

                PsiElement anchor = declared.getNameIdentifier();
                holder.registerProblem(anchor == null ? declared : anchor, rejection,
                    ProblemHighlightType.GENERIC_ERROR);
            }
        };
    }

}
