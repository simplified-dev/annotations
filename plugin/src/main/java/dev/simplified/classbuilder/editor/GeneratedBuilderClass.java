package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.SyntheticElement;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import com.intellij.psi.impl.light.LightTypeParameterBuilder;
import com.intellij.psi.impl.light.LightTypeParameterListBuilder;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
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
        copyTypeParameters(containingClass);
    }

    /**
     * Re-declares the target's type parameters on this class, bounds included.
     * The synthesised Builder is {@code static} and so cannot see the enclosing
     * type's variables - without its own copies, a generic target's setters and
     * {@code build()} would resolve against a raw builder and the editor would
     * report {@code Object} where the target's parameter belongs.
     *
     * <p>The copies are distinct {@link PsiTypeParameter}s from the target's,
     * matching what javac emits, so callers building a type for use inside this
     * class must apply <em>these</em> parameters rather than the target's.
     */
    private void copyTypeParameters(@NotNull PsiClass containingClass) {
        PsiTypeParameter[] sources = containingClass.getTypeParameters();
        if (sources.length == 0) return;
        LightTypeParameterListBuilder list = typeParameterList();
        if (list == null) return;
        for (int i = 0; i < sources.length; i++) {
            LightTypeParameterBuilder copy =
                new LightTypeParameterBuilder(sources[i].getName(), this, i);
            for (PsiClassType bound : sources[i].getExtendsListTypes()) {
                copy.getExtendsList().addReference(bound);
            }
            list.addParameter(copy);
        }
    }

    /**
     * Appends a further type parameter and returns it so the caller can attach
     * bounds afterwards. Used for the SuperBuilder self-types, whose bounds are
     * self-referential ({@code B extends Builder<T, B>}) and so cannot be built
     * until both parameters exist.
     *
     * @param name the parameter's name
     * @return the appended parameter, or {@code null} if the list is unavailable
     */
    @Nullable LightTypeParameterBuilder addTypeParameter(@NotNull String name) {
        LightTypeParameterListBuilder list = typeParameterList();
        if (list == null) return null;
        LightTypeParameterBuilder param =
            new LightTypeParameterBuilder(name, this, list.getTypeParameters().length);
        list.addParameter(param);
        return param;
    }

    /**
     * Declares this Builder's supertype - the parent's synthesised Builder, for
     * a link in a SuperBuilder chain. Without it the platform's inherited-member
     * lookup has nothing to walk and every setter declared further up the chain
     * reads as unresolved.
     *
     * @param superType the parameterised parent builder type
     */
    void setSuperType(@NotNull PsiClassType superType) {
        getExtendsList().addReference(superType);
    }

    private @Nullable LightTypeParameterListBuilder typeParameterList() {
        return getTypeParameterList();
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
     * {@link CachedValuesManager} keyed to
     * {@link PsiModificationTracker#MODIFICATION_COUNT},
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
     * <p>{@link PsiClass#findMethodsByName} and the bulk of
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
