package dev.sbs.classbuilder.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import dev.sbs.classbuilder.inspect.ClassBuilderConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Surfaces the bootstrap methods ({@code builder()}, {@code from(T)},
 * {@code mutate()}) injected by the APT mutation pipeline to the PSI layer
 * so autocompletion, goto-symbol, and type resolution all work before the
 * first {@code javac} round.
 *
 * <p>v1 scope: only bootstrap methods. The nested {@code Builder} class
 * itself is not yet synthesised at editor time - references to
 * {@code Target.Builder} remain unresolved until a build runs, at which
 * point the compiled class file makes the injected nested class visible.
 * Expanding to full nested-class synthesis is a planned follow-up.
 */
public final class ClassBuilderAugmentProvider extends PsiAugmentProvider {

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element,
                                                                     @NotNull Class<Psi> type,
                                                                     @Nullable String nameHint) {
        if (!(element instanceof PsiClass target)) return Collections.emptyList();
        if (!PsiMethod.class.isAssignableFrom(type)) return Collections.emptyList();
        if (target.hasModifierProperty(PsiModifier.ABSTRACT)) return Collections.emptyList();

        if (findClassBuilderAnnotation(target) == null) return Collections.emptyList();

        // CachedValueProvider must not capture PsiElement instances - IntelliJ's
        // leak-check fails the test otherwise. Re-resolve the annotation each
        // time the cache recomputes (cheap: linear scan of class annotations).
        @SuppressWarnings("unchecked")
        List<Psi> cached = (List<Psi>) CachedValuesManager.getCachedValue(target, () -> {
            PsiAnnotation resolved = findClassBuilderAnnotation(target);
            if (resolved == null) {
                return CachedValueProvider.Result.create(Collections.<PsiMethod>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            GeneratedMemberFactory.EditorBuilderConfig config =
                GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(resolved);
            List<PsiMethod> methods = GeneratedMemberFactory.bootstrapMethods(target, config);
            return CachedValueProvider.Result.create(methods, PsiModificationTracker.MODIFICATION_COUNT);
        });
        return cached;
    }

    private static PsiAnnotation findClassBuilderAnnotation(PsiClass target) {
        for (PsiAnnotation a : target.getAnnotations()) {
            if (ClassBuilderConstants.ANNOTATION_FQN.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

}
