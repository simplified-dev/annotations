package dev.simplified.args.editor;

import com.intellij.codeInsight.InferredAnnotationProvider;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiModifierListOwner;
import dev.simplified.args.inspect.ArgsConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Renders the constructor annotation a {@code @ClassBuilder} target infers, so
 * quick documentation shows it under "Inferred annotations".
 *
 * <p>This is the whole point of inferring rather than writing: the annotation
 * has to be visible somewhere, and the platform's hook for "an annotation that
 * is not written but should be seen" is this extension point. It is not the
 * only surface - a class-level inferred annotation is not reliably rendered as
 * an inlay - which is why the gutter tooltip carries the same information and
 * the resolved signature besides.
 */
public final class ArgsInferredAnnotationProvider implements InferredAnnotationProvider {

    @Override
    public @Nullable PsiAnnotation findInferredAnnotation(@NotNull PsiModifierListOwner listOwner,
                                                          @NotNull String annotationFQN) {
        ArgsInference inference = inferenceFor(listOwner);
        if (inference == null) return null;
        if (!ArgsConstants.fqnOf(inference.mode()).equals(annotationFQN)) return null;
        return create(listOwner, inference);
    }

    @Override
    public @NotNull List<PsiAnnotation> findInferredAnnotations(
        @NotNull PsiModifierListOwner listOwner) {
        ArgsInference inference = inferenceFor(listOwner);
        if (inference == null) return List.of();
        PsiAnnotation annotation = create(listOwner, inference);
        return annotation == null ? List.of() : List.of(annotation);
    }

    private static @Nullable ArgsInference inferenceFor(@NotNull PsiModifierListOwner owner) {
        if (!(owner instanceof PsiClass target)) return null;
        return ArgsInference.of(target);
    }

    private static @Nullable PsiAnnotation create(@NotNull PsiModifierListOwner owner,
                                                  @NotNull ArgsInference inference) {
        String text = "@" + ArgsConstants.fqnOf(inference.mode())
            + accessSuffix(inference.accessKeyword());
        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(owner.getProject());
            return factory.createAnnotationFromText(text, owner);
        } catch (Exception ignored) {
            // An annotation the IDE cannot reconstruct is skipped rather than
            // failing the whole quick-documentation render.
            return null;
        }
    }

    private static String accessSuffix(String keyword) {
        String constant = keyword.isEmpty() ? "PACKAGE"
            : keyword.toUpperCase(java.util.Locale.ROOT);
        return "(access = dev.simplified.annotations.AccessLevel." + constant + ")";
    }

}
