package dev.simplified.cleanup.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiForStatement;
import com.intellij.psi.PsiForeachStatement;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiResourceVariable;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.annotations.Cleanup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reports {@code @Cleanup} on a declaration the mutation cannot turn into a
 * try-with-resources.
 *
 * <p>These are checks the annotation processor is structurally unable to make.
 * It runs before attribution, so it holds a syntax tree with no resolved type
 * for the local and cannot tell an {@link AutoCloseable} from anything else; the
 * IDE can. Left to the build, a non-closeable declared type surfaces as a type
 * error on a {@code try} the author never wrote.
 *
 * <ul>
 *   <li>A declaration with no initializer - there is no resource to close.</li>
 *   <li>A declared type that is not {@link AutoCloseable}, which the resource
 *       form requires.</li>
 *   <li>A {@code for} or {@code for}-each variable, which has no block remainder
 *       to be closed at.</li>
 *   <li>A variable assigned again after its declaration - the existing-variable
 *       resource form requires it to be effectively final.</li>
 * </ul>
 *
 * <p>A declared type whose {@code close()} happens to do nothing is not
 * reported. Whether a close is inert is a property of the library that wrote it,
 * not of the annotation, and answering it means reading through an override
 * chain to a body that may not be on the classpath. Every other check here
 * stands for something that fails to compile.
 *
 * @see Cleanup
 */
public final class CleanupInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitLocalVariable(@NotNull PsiLocalVariable variable) {
                PsiAnnotation annotation = CleanupConstants.resolved(variable);
                if (annotation == null) return;

                if (variable instanceof PsiResourceVariable) {
                    holder.registerProblem(annotation,
                        "@Cleanup does nothing on a resource declared by a try-with-resources - "
                            + "'" + variable.getName() + "' is already closed by that try",
                        ProblemHighlightType.WARNING);
                    return;
                }
                if (variable.getParent() != null
                    && variable.getParent().getParent() instanceof PsiForStatement) {
                    reportNoRemainder(holder, annotation, variable, "a 'for' initializer");
                    return;
                }
                if (variable.getInitializer() == null) {
                    holder.registerProblem(annotation,
                        "@Cleanup needs an initializer - '" + variable.getName()
                            + "' holds no resource to close",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }
                checkCloseable(holder, annotation, variable);
                checkEffectivelyFinal(holder, annotation, variable);
            }

            @Override
            public void visitParameter(@NotNull PsiParameter parameter) {
                if (!(parameter.getDeclarationScope() instanceof PsiForeachStatement)) return;
                PsiAnnotation annotation = CleanupConstants.resolved(parameter);
                if (annotation == null) return;
                reportNoRemainder(holder, annotation, parameter, "a 'for'-each variable");
            }
        };
    }

    /**
     * Reports a declaration that is not a statement of a block, so there is no
     * remainder for the resource to be closed at. The processor reports the same
     * thing against the enclosing type; this can point at the annotation.
     */
    private static void reportNoRemainder(ProblemsHolder holder, PsiAnnotation annotation,
                                          PsiVariable variable, String position) {
        holder.registerProblem(annotation,
            "@Cleanup is not supported on " + position + " - '" + variable.getName()
                + "' has no enclosing block remainder to be closed at. Declare it before the loop",
            ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * Reports a declared type the resource form cannot take. An unresolved type
     * is left alone - the file is mid-edit, and an error about an incomplete
     * reference is one the author is already looking at.
     */
    private static void checkCloseable(ProblemsHolder holder, PsiAnnotation annotation,
                                       PsiLocalVariable variable) {
        PsiType type = variable.getType();
        if (!isDefinitelyNotCloseable(type)) return;
        holder.registerProblem(annotation,
            "@Cleanup requires an AutoCloseable - '" + variable.getName() + "' is declared "
                + type.getPresentableText() + ", which the generated try-with-resources cannot take",
            ProblemHighlightType.GENERIC_ERROR);
    }

    private static boolean isDefinitelyNotCloseable(@Nullable PsiType type) {
        if (type == null) return false;
        if (type instanceof PsiPrimitiveType) return true;
        if (type.getArrayDimensions() > 0) return true;
        if (!(type instanceof PsiClassType classType)) return false;
        PsiClass resolved = classType.resolve();
        if (resolved == null) return false;
        return !InheritanceUtil.isInheritor(resolved, CleanupConstants.AUTO_CLOSEABLE_FQN);
    }

    /**
     * Reports a variable written to after its declaration, which the
     * existing-variable resource form rejects. A warning rather than an error:
     * the reassignment may well be the mistake, and the declaration is where the
     * fix is chosen.
     */
    private static void checkEffectivelyFinal(ProblemsHolder holder, PsiAnnotation annotation,
                                              PsiLocalVariable variable) {
        PsiCodeBlock scope = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class);
        if (scope == null) return;
        if (!isAssignedIn(scope, variable)) return;
        holder.registerProblem(annotation,
            "@Cleanup requires '" + variable.getName() + "' to be effectively final - the "
                + "generated try-with-resources closes the declared variable, and it is assigned "
                + "again below", ProblemHighlightType.WARNING);
    }

    /**
     * Whether the block assigns the variable anywhere below its declaration.
     *
     * <p>The scan is the whole enclosing block rather than the statements after
     * the declaration, because a lambda body or a nested block reaching the same
     * local counts just as much, and every one of them is a descendant of it.
     */
    private static boolean isAssignedIn(PsiCodeBlock scope, PsiLocalVariable variable) {
        boolean[] assigned = {false};
        scope.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitAssignmentExpression(@NotNull PsiAssignmentExpression expression) {
                super.visitAssignmentExpression(expression);
                if (references(expression.getLExpression(), variable)) assigned[0] = true;
            }
        });
        return assigned[0];
    }

    /**
     * Whether the expression is an unqualified read of this variable. The name
     * is compared before the reference is resolved, so the common case of an
     * assignment to something else costs a string comparison.
     */
    private static boolean references(@Nullable PsiExpression expression, PsiLocalVariable variable) {
        if (!(expression instanceof PsiReferenceExpression reference)) return false;
        if (reference.getQualifierExpression() != null) return false;
        if (!variable.getName().equals(reference.getReferenceName())) return false;
        PsiElement resolved = reference.resolve();
        return resolved == variable;
    }

}
