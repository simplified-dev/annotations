package dev.simplified.shared.psi;

import com.intellij.codeInsight.highlighting.JavaReadWriteAccessDetector;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;

/**
 * Classifies a call to a generated setter as a write of the slot behind it.
 *
 * <p>Java's own detector already does this for a setter it recognises, and it
 * recognises one by name and signature: {@code set} plus an uppercase letter,
 * one parameter, returning {@code void} or the declaring type. That test is
 * exactly what a generated setter is free not to satisfy. A fluent
 * {@code @Setter} mints {@code label(String)}, a {@code name} attribute mints
 * whatever it was given, and every {@code @ClassBuilder} setter is named after
 * its slot rather than prefixed - so a whole builder chain reads as a series of
 * reads of the fields it assigns.
 *
 * <p>The provider that synthesised the member recorded which it was, so the
 * answer is a marker read rather than a name guess. Anything without that
 * marker, and every question other than this one, goes to
 * {@link JavaReadWriteAccessDetector}.
 */
public final class GeneratedMemberReadWriteAccessDetector extends JavaReadWriteAccessDetector {

    @Override
    public @NotNull Access getExpressionAccess(@NotNull PsiElement expression) {
        if (expression instanceof PsiReferenceExpression reference
            && reference.resolve() instanceof PsiMethod method
            && GeneratedMemberMarker.isWrite(method)) {
            return Access.Write;
        }
        // Only the write case is added. A generated reader lands on the same
        // answer through the inherited analysis, and deferring keeps that one
        // path rather than growing a second that has to agree with it.
        return super.getExpressionAccess(expression);
    }

}
