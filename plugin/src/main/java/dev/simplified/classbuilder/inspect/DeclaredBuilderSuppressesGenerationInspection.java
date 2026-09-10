package dev.simplified.classbuilder.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.annotations.NamingStyle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reports a {@code @ClassBuilder} that generates nothing because the target
 * already declares a nested type of the builder's name.
 *
 * <p>All three mutation paths skip on that declaration, and the only signal is a
 * compiler note nobody reads. Without this the three entry points and the whole
 * generated surface simply are not in completion and nothing on the screen says
 * why - which reads as the annotation being broken rather than as the
 * declaration having turned it off.
 *
 * <p>Weak, because the code is correct: the author's own builder is what runs,
 * and on a plain type target one attribute turns the generated members back on
 * beside it. What the message carries is which of the three positions the target
 * is in, since the opt-in is read on one of them and not the other two.
 */
public class DeclaredBuilderSuppressesGenerationInspection extends LocalInspectionTool {

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
                PsiClass target = targetOf(owner);
                if (target == null || target.getName() == null) return;

                NamingStyle style = ClassBuilderConstants.namingStyle(annotation);
                String builderName = ClassBuilderConstants
                    .builderScheme(annotation, style, target.getName()).type();
                boolean merge = ClassBuilderConstants.booleanAttr(annotation,
                    ClassBuilderConstants.ATTR_MERGE_DECLARED_BUILDER, false);
                if (!ClassBuilderConstants.suppressesGeneration(target, builderName, merge, executable)) {
                    return;
                }

                holder.registerProblem(annotation,
                    message(target.getName(), builderName, executable,
                        ClassBuilderConstants.chainRoleOf(target).isChained()),
                    ProblemHighlightType.WEAK_WARNING);
            }
        };
    }

    /**
     * The type the builder would nest in - the annotated class, or the class
     * around an annotated constructor or factory method.
     *
     * @param owner the member the annotation is written on
     * @return the target type, or {@code null} when the annotation sits on neither
     */
    private static @Nullable PsiClass targetOf(@Nullable PsiModifierListOwner owner) {
        if (owner instanceof PsiClass cls) return cls;
        if (owner instanceof PsiMethod method) return method.getContainingClass();
        return null;
    }

    /**
     * The reason generation was skipped, and whether the merge opt-in is read
     * where the target sits.
     *
     * @param targetName the annotated type's simple name
     * @param builderName the configured builder class name
     * @param executable whether the annotation sits on a constructor or factory method
     * @param chained whether the target sits in a SuperBuilder chain
     * @return the message to report on the annotation
     */
    private static String message(String targetName, String builderName,
                                  boolean executable, boolean chained) {
        String reason = "No builder is generated because '" + targetName
            + "' declares a nested type named '" + builderName + "'";
        if (executable) {
            return reason + ". A constructor or factory target has no merge to opt into, so the "
                + "declaration suppresses generation outright";
        }
        if (chained) {
            return reason + ". mergeDeclaredBuilder is not read on a SuperBuilder chain, so the "
                + "declaration suppresses generation whether it is written or not";
        }
        return reason + ". Write mergeDeclaredBuilder = true to have the generated members "
            + "appended to it";
    }

}
