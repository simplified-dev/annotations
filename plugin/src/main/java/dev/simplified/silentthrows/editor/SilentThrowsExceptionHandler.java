package dev.simplified.silentthrows.editor;

import com.intellij.codeInsight.CustomExceptionHandler;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodReferenceExpression;
import dev.simplified.silentthrows.inspect.SilentThrowsConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tells the editor to stop reporting an unhandled checked exception inside a
 * {@code @SilentThrows} declaration, whose body the processor wraps in a
 * {@code catch} the editor cannot see.
 *
 * <p>This is the whole editor story for the annotation. Nothing is added to the
 * type's member list, so there is no augment provider - the synthetic rethrow
 * helper is deliberately invisible, and surfacing it would drop a {@code $}-named
 * method into every completion popup. What is needed instead is the opposite kind
 * of extension: without it every annotated file paints "Unhandled exception" in
 * red on each keystroke while the build succeeds.
 *
 * <p><b>The walk stops at the first lambda, method reference or class
 * boundary.</b> A lambda body, a method reference and an anonymous-class member
 * are each their own exception-analysis context and the wrap does not reach into
 * them - a method reference's thrown types are checked against the function type
 * it is assigned to, which no enclosing {@code try} changes. An unhandled
 * exception at any of the three is a genuine compile error, and walking through
 * one would take the editor green on code javac rejects, which is strictly worse
 * than the red squiggle this extension exists to remove.
 */
public final class SilentThrowsExceptionHandler extends CustomExceptionHandler {

    /**
     * Whether an enclosing {@code @SilentThrows} declaration absorbs the type.
     *
     * @param element the element the exception escapes from
     * @param exceptionType the type being reported as unhandled
     * @param topElement the element the platform's own search is bounded by
     * @return whether the editor should treat the exception as handled
     */
    @Override
    public boolean isHandled(@Nullable PsiElement element, @NotNull PsiClassType exceptionType,
                             PsiElement topElement) {
        for (PsiElement current = element; current != null; current = current.getParent()) {
            // A lambda body is its own context, so is a method reference - whose
            // thrown types are checked against the function type rather than
            // against any enclosing try - and so is every member of an anonymous
            // or local class, which arrives here as a PsiClass. Reaching any of
            // the three means the enclosing annotation, if any, does not cover
            // this element.
            if (current instanceof PsiLambdaExpression) return false;
            if (current instanceof PsiMethodReferenceExpression) return false;
            if (current instanceof PsiClass) return false;
            if (current instanceof PsiMethod method) {
                PsiAnnotation annotation = method.getAnnotation(SilentThrowsConstants.ANNOTATION_FQN);
                return annotation != null && SilentThrowsConstants.covers(annotation, exceptionType);
            }
        }
        return false;
    }

}
