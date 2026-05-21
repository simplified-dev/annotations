package dev.simplified.shared.psi;

import com.intellij.lang.Language;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.light.LightModifierList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link LightModifierList} subclass that exposes a caller-populated annotation
 * set. The platform's default implementation returns an empty annotation array
 * and throws {@link com.intellij.util.IncorrectOperationException} from
 * {@link #addAnnotation(String)}; this subclass lets augment-provider synthesis
 * ride pre-built {@link PsiAnnotation}s (typically from
 * {@link com.intellij.psi.PsiElementFactory#createAnnotationFromText}) so
 * IntelliJ inspections that walk {@code getModifierList().getAnnotations()}
 * (printf, nullability, etc.) see them.
 *
 * <p>Mirrors Lombok's {@code LombokLightModifierList}: keyed map keeps lookups
 * O(1), language propagated to the platform base for language-aware checks,
 * {@link #addAnnotation(String)} stores rather than throwing.
 */
public final class AnnotatedLightModifierList extends LightModifierList {

    private final Map<String, PsiAnnotation> annotations = new LinkedHashMap<>(2);

    public AnnotatedLightModifierList(PsiManager manager, Language language) {
        super(manager, language);
    }

    /** Stores a pre-built annotation under the supplied qualified name. */
    public void add(String qualifiedName, PsiAnnotation annotation) {
        annotations.put(qualifiedName, annotation);
    }

    @Override
    public @NotNull PsiAnnotation addAnnotation(@NotNull String qualifiedName) {
        PsiAnnotation annotation = JavaPsiFacade.getElementFactory(getProject())
            .createAnnotationFromText("@" + qualifiedName, null);
        annotations.put(qualifiedName, annotation);
        return annotation;
    }

    @Override
    public @NotNull PsiAnnotation[] getAnnotations() {
        return annotations.isEmpty()
            ? PsiAnnotation.EMPTY_ARRAY
            : annotations.values().toArray(PsiAnnotation.EMPTY_ARRAY);
    }

    @Override
    public @Nullable PsiAnnotation findAnnotation(@NotNull String qualifiedName) {
        return annotations.get(qualifiedName);
    }

    @Override
    public boolean hasAnnotation(@NotNull String qualifiedName) {
        return annotations.containsKey(qualifiedName);
    }
}
