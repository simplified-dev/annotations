package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SyntheticElement;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * The synthesised nested {@code Builder} class surfaced by
 * {@link ClassBuilderAugmentProvider}. Mirrors Lombok's
 * {@code LombokLightClassBuilder}: stores no own members and implements
 * {@link PsiExtensibleClass} so the platform's augment-aware resolution
 * paths know to consult {@link PsiAugmentProvider} for the inner class's
 * methods at query time.
 *
 * <p>The plain platform {@link LightPsiClassBuilder} does NOT implement
 * {@code PsiExtensibleClass}, so when the IDE evaluates members on a
 * synth class derived from it, augment providers are never queried for
 * the members - explaining why pre-adding via {@code addMethod} (or even
 * routing through {@code collectAugments} from a custom {@code getMethods}
 * override) was invisible to completion / structure-view / data-flow in
 * IntelliJ 2023.3+. Implementing {@code PsiExtensibleClass} here makes
 * the platform's standard merge logic (own members + augment results) kick
 * in for our synth class too.
 *
 * <p>{@link #getOwnMethods()}, {@link #getOwnFields()},
 * {@link #getOwnInnerClasses()} all return empty - methods come solely
 * from the augment-provider re-entry handled in
 * {@link ClassBuilderAugmentProvider#getAugments}. {@link SyntheticElement}
 * is also asserted explicitly so anything walking the PSI tree treats this
 * class as a synthetic node.
 */
final class GeneratedBuilderClass extends LightPsiClassBuilder
    implements PsiExtensibleClass, SyntheticElement {

    private final String myQualifiedName;

    GeneratedBuilderClass(@NotNull PsiClass containingClass, @NotNull String name) {
        super(containingClass, name);
        String parentFqn = containingClass.getQualifiedName();
        this.myQualifiedName = (parentFqn != null ? parentFqn : containingClass.getName()) + "." + name;
    }

    // ------------------------------------------------------------------
    // PSI-tree placement
    //
    // The platform default for LightElement.getParent() is null. That
    // breaks LightPsiClassBase.getQualifiedName() (which walks getParent()
    // looking for PsiJavaFile or PsiClass), so the IDE labels the class as
    // belonging to the "default package" and several lookup paths fail to
    // associate it with the target's namespace. Lombok works around this by
    // overriding getParent / getQualifiedName / getContainingFile / getScope
    // to delegate to the containing class - mirror that here.
    // ------------------------------------------------------------------

    @Override
    public PsiElement getParent() {
        return getContainingClass();
    }

    @Override
    public @Nullable String getQualifiedName() {
        return myQualifiedName;
    }

    @Override
    public PsiFile getContainingFile() {
        PsiClass containing = getContainingClass();
        return containing != null ? containing.getContainingFile() : super.getContainingFile();
    }

    @Override
    public PsiElement getScope() {
        PsiClass containing = getContainingClass();
        return containing != null ? containing.getScope() : super.getScope();
    }

    @Override
    public @NotNull List<PsiField> getOwnFields() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull List<PsiMethod> getOwnMethods() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull List<PsiClass> getOwnInnerClasses() {
        return Collections.emptyList();
    }

    /**
     * Materialises the setters + {@code build()} via
     * {@link PsiAugmentProvider#collectAugments} on every call. The augment
     * provider itself caches the result via
     * {@link com.intellij.psi.util.CachedValuesManager} keyed to
     * {@link com.intellij.psi.util.PsiModificationTracker#MODIFICATION_COUNT},
     * so this stays cheap while staying invalidated on any PSI edit.
     *
     * <p>An earlier per-instance {@code volatile} cache here was a bug:
     * the synth Builder instance is cached across PSI revisions (via
     * {@code SYNTHESIZED} user-data on the target, used to defeat
     * {@code IdempotenceChecker}), so a per-instance cache would freeze
     * the method list at whichever fields existed when the first call
     * landed. Adding a new field to the target after that didn't refresh
     * the methods.
     *
     * <p>{@link com.intellij.psi.PsiClass#findMethodsByName} and the bulk of
     * IntelliJ's name-lookup paths walk {@code getMethods()} directly rather
     * than going through {@link PsiExtensibleClass}'s
     * {@code getOwnMethods() + augments} aggregation. Without this override
     * those paths would return the platform default of {@code myMethods}
     * (always empty for us) and miss every setter.
     */
    @Override
    public PsiMethod @NotNull [] getMethods() {
        return PsiAugmentProvider.collectAugments(this, PsiMethod.class, null)
            .toArray(PsiMethod.EMPTY_ARRAY);
    }

}
