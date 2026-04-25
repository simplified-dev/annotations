package dev.simplified.xcontract;

import com.intellij.codeInsight.InferredAnnotationProvider;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Bridges {@code @dev.simplified.annotations.XContract} into IntelliJ's data-flow
 * analysis by synthesising an equivalent
 * {@link org.jetbrains.annotations.Contract} on demand.
 *
 * <p>IntelliJ asks every registered {@code InferredAnnotationProvider} for
 * annotations on each method it analyses. When the requested annotation is
 * {@code @Contract}, this provider looks for an {@code @XContract} on the
 * same method and translates it via {@link XContractTranslator}. Result:
 * callers do not need to write {@code @Contract} alongside
 * {@code @XContract} - the IDE treats them as equivalent for null-safety
 * and purity analysis.
 */
public final class XContractInferredAnnotationProvider implements InferredAnnotationProvider {

    private static final String CONTRACT_FQN  = Contract.class.getName();
    private static final String XCONTRACT_FQN = "dev.simplified.annotations.XContract";

    @Override
    public @Nullable PsiAnnotation findInferredAnnotation(
        @NotNull PsiModifierListOwner listOwner,
        @NotNull String annotationFQN
    ) {
        if (!CONTRACT_FQN.equals(annotationFQN)) return null;
        if (!(listOwner instanceof PsiMethod method)) return null;

        PsiAnnotation xContract = method.getAnnotation(XCONTRACT_FQN);
        if (xContract == null) return null;

        String synthetic = XContractTranslator.toJetBrainsContract(method, xContract);
        if (synthetic == null) return null;

        try {
            return JavaPsiFacade.getElementFactory(method.getProject())
                .createAnnotationFromText(synthetic, method);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public @NotNull List<PsiAnnotation> findInferredAnnotations(@NotNull PsiModifierListOwner listOwner) {
        PsiAnnotation ann = findInferredAnnotation(listOwner, CONTRACT_FQN);
        return ann == null ? List.of() : List.of(ann);
    }

}
