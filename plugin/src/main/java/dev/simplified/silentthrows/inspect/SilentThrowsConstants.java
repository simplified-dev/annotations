package dev.simplified.silentthrows.inspect;

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiType;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared FQN and {@code value} reader for the {@code @SilentThrows} PSI side, so
 * the exception handler and the inspection answer "does this annotation absorb
 * that type" the same way.
 *
 * <p>The reader keeps the written class literals rather than only their types.
 * The inspection has to underline the one offending literal in a list, which a
 * bare {@link PsiClassType} cannot locate.
 */
public final class SilentThrowsConstants {

    public static final String ANNOTATION_FQN = "dev.simplified.annotations.SilentThrows";

    private static final String VALUE = "value";

    private SilentThrowsConstants() {
    }

    /**
     * The class literals written as {@code value}.
     *
     * @param annotation a {@code @SilentThrows}
     * @return the literals in written order, empty when the attribute is not
     *         written or holds something other than class literals
     */
    public static List<PsiClassObjectAccessExpression> writtenLiterals(PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(VALUE);
        if (value == null) return List.of();
        List<PsiClassObjectAccessExpression> out = new ArrayList<>(2);
        if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue element : array.getInitializers()) addLiteral(out, element);
        } else {
            addLiteral(out, value);
        }
        return out;
    }

    /** The resolved types the written literals name, in written order. */
    public static PsiClassType[] caughtTypes(PsiAnnotation annotation) {
        List<PsiClassType> out = new ArrayList<>(2);
        for (PsiClassObjectAccessExpression literal : writtenLiterals(annotation)) {
            PsiType operand = literal.getOperand().getType();
            if (operand instanceof PsiClassType caught) out.add(caught);
        }
        return out.toArray(PsiClassType.EMPTY_ARRAY);
    }

    /**
     * Whether the annotation's emitted catch clauses absorb the type.
     *
     * <p>An unwritten {@code value} is the bare form, whose single
     * {@code Throwable} clause absorbs everything, and is answered without
     * resolving anything. An explicitly empty {@code {}} is the same shape and
     * gets the same answer: the processor names no type to catch, falls back to
     * {@code Throwable}, and the build succeeds - answering {@code false} here
     * would paint unhandled-exception errors on code that compiles clean.
     *
     * <p>A written one that names something and resolves to nothing still answers
     * {@code false}: half-typed source should keep showing the error javac would
     * give rather than go green on a narrowing nobody has finished writing.
     *
     * @param annotation a {@code @SilentThrows}
     * @param exceptionType the type the editor is about to report as unhandled
     * @return whether a catch clause the wrap emits would handle it
     */
    public static boolean covers(PsiAnnotation annotation, PsiClassType exceptionType) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(VALUE);
        if (value == null) return true;
        if (value instanceof PsiArrayInitializerMemberValue array
            && array.getInitializers().length == 0) {
            return true;
        }
        PsiClassType[] caught = caughtTypes(annotation);
        if (caught.length == 0) return false;
        return ExceptionUtil.isHandledBy(exceptionType, caught);
    }

    private static void addLiteral(List<PsiClassObjectAccessExpression> out,
                                   PsiAnnotationMemberValue value) {
        if (value instanceof PsiClassObjectAccessExpression literal) out.add(literal);
    }

}
