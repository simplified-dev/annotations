package dev.simplified.silentthrows.inspect;

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Reports {@code @SilentThrows} that cannot do what it was written for, while
 * the file is being edited.
 *
 * <ul>
 *   <li>ERROR - a bodyless declaration, which has nothing to wrap.</li>
 *   <li>WARNING - a narrowed {@code value} type the body cannot throw, which
 *       javac rejects as an unreachable catch clause against generated code the
 *       author cannot open.</li>
 *   <li>WEAK WARNING - a body throwing no checked exception at all, so the wrap
 *       absorbs nothing.</li>
 *   <li>WEAK WARNING - a type the declaration also lists in its own
 *       {@code throws} clause, where one of the two is meant to go.</li>
 * </ul>
 *
 * <p>Both weaker checks read the body, so both are skipped while it holds a
 * syntax error: a half-typed statement throws nothing yet, and reporting that
 * would put a squiggle on the annotation every time a line is opened.
 */
public final class SilentThrowsInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);
                PsiAnnotation annotation = method.getAnnotation(SilentThrowsConstants.ANNOTATION_FQN);
                if (annotation == null) return;

                PsiCodeBlock body = method.getBody();
                if (body == null) {
                    holder.registerProblem(annotation,
                        "@SilentThrows has nothing to wrap - " + method.getName() + " is "
                            + bodylessReason(method), ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                checkRedundantWithThrows(holder, method, annotation);
                if (PsiTreeUtil.hasErrorElements(body)) return;

                List<PsiClassType> thrown = ExceptionUtil.getThrownCheckedExceptions(body);
                if (thrown.isEmpty()) {
                    holder.registerProblem(annotation,
                        "@SilentThrows does nothing here - the body throws no checked exception",
                        ProblemHighlightType.WEAK_WARNING);
                    return;
                }
                checkNarrowing(holder, annotation, thrown);
            }
        };
    }

    private static String bodylessReason(PsiMethod method) {
        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) return "abstract";
        if (method.hasModifierProperty(PsiModifier.NATIVE)) return "native";
        return "declared without a body";
    }

    /**
     * A written type the declaration also throws. The wrap absorbs it either
     * way, so the {@code throws} clause is left describing an exception no
     * caller can catch and one of the two was meant to be deleted.
     */
    private static void checkRedundantWithThrows(ProblemsHolder holder, PsiMethod method,
                                                 PsiAnnotation annotation) {
        PsiClassType[] declared = method.getThrowsList().getReferencedTypes();
        if (declared.length == 0) return;
        for (PsiClassObjectAccessExpression literal :
            SilentThrowsConstants.writtenLiterals(annotation)) {
            PsiType caught = literal.getOperand().getType();
            for (PsiClassType type : declared) {
                if (!type.getCanonicalText().equals(caught.getCanonicalText())) continue;
                holder.registerProblem(literal,
                    caught.getPresentableText() + " is also declared in the throws clause - the "
                        + "wrap absorbs it, so no caller can catch it and one of the two is "
                        + "meant to go", ProblemHighlightType.WEAK_WARNING);
            }
        }
    }

    /**
     * A narrowed type nothing in the body can raise.
     *
     * <p>The test is javac's own rule for an unreachable catch clause rather
     * than plain assignability: a clause is legal when the body throws anything
     * that is a subtype <i>or</i> a supertype of the caught type, and
     * {@code Throwable} and {@code Exception} clauses are exempt outright. An
     * unchecked type is never unreachable either, and an unresolved one is not
     * something to have an opinion about.
     */
    private static void checkNarrowing(ProblemsHolder holder, PsiAnnotation annotation,
                                       List<PsiClassType> thrown) {
        for (PsiClassObjectAccessExpression literal :
            SilentThrowsConstants.writtenLiterals(annotation)) {
            if (!(literal.getOperand().getType() instanceof PsiClassType caught)) continue;
            if (caught.resolve() == null) continue;
            if (ExceptionUtil.isGeneralExceptionType(caught)) continue;
            if (ExceptionUtil.isUncheckedException(caught)) continue;
            if (reachable(caught, thrown)) continue;
            holder.registerProblem(literal,
                "The body cannot throw " + caught.getPresentableText()
                    + " - javac rejects the generated catch clause as unreachable",
                ProblemHighlightType.WARNING);
        }
    }

    private static boolean reachable(PsiClassType caught, List<PsiClassType> thrown) {
        for (PsiClassType type : thrown) {
            if (caught.isAssignableFrom(type) || type.isAssignableFrom(caught)) return true;
        }
        return false;
    }

}
