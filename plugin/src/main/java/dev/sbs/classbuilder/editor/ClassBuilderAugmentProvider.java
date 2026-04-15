package dev.sbs.classbuilder.editor;

import com.intellij.openapi.util.Key;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    /**
     * Tracks targets currently undergoing synthesis on this thread. Editor-time
     * type creation ({@code createTypeFromText}, {@code createType}) eagerly
     * resolves nested-class references, which re-enters this provider for the
     * same target. The guard breaks the cycle by returning empty for the
     * inner call.
     */
    private static final ThreadLocal<Set<PsiClass>> IN_PROGRESS =
        ThreadLocal.withInitial(HashSet::new);

    /**
     * Memoises per-target synthesised members. {@link CachedValuesManager}
     * periodically re-runs producers and requires the results be equal across
     * invocations ({@link com.intellij.util.IdempotenceChecker}). Building a
     * fresh {@code LightPsiClassBuilder} / {@code LightMethodBuilder} on each
     * call yields new-identity instances that fail that check, so we keep one
     * cached pair on the target's user data keyed by the resolved
     * {@link GeneratedMemberFactory.EditorBuilderConfig}. When the annotation
     * changes, the config differs, and the lambda synthesises fresh members
     * (and replaces the cache). When the producer is re-invoked for the same
     * config, it returns the already-stored instances - idempotent.
     */
    private static final Key<SynthesizedMembers> SYNTHESIZED =
        Key.create("dev.sbs.classbuilder.synthesized");

    private record SynthesizedMembers(GeneratedMemberFactory.EditorBuilderConfig config,
                                      List<PsiMethod> bootstrapMethods,
                                      PsiClass builderClass) {
    }

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element,
                                                                     @NotNull Class<Psi> type,
                                                                     @Nullable String nameHint) {
        if (!(element instanceof PsiClass target)) return Collections.emptyList();

        // Lombok-pattern second pass: when the IDE asks for the methods of
        // our synth Builder class, materialise the setters + build() now.
        // GeneratedBuilderClass.getMethods() routes through here too, so
        // there is exactly one source of truth for the inner class's members.
        if (target instanceof GeneratedBuilderClass synthBuilder
            && PsiMethod.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            List<Psi> methods = (List<Psi>) cachedSynthBuilderMethods(synthBuilder);
            return methods;
        }

        if (findClassBuilderAnnotation(target) == null) return Collections.emptyList();
        if (IN_PROGRESS.get().contains(target)) return Collections.emptyList();

        if (PsiMethod.class.isAssignableFrom(type)) {
            // Bootstrap methods (builder/from/mutate) only on concrete targets;
            // abstract targets get their bootstraps from concrete subclasses.
            if (target.hasModifierProperty(PsiModifier.ABSTRACT)) return Collections.emptyList();
            @SuppressWarnings("unchecked")
            List<Psi> methods = (List<Psi>) cachedMethods(target);
            return methods;
        }
        if (PsiClass.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            List<Psi> classes = (List<Psi>) cachedNestedClasses(target);
            return classes;
        }
        return Collections.emptyList();
    }

    /**
     * Materialises the setters + {@code build()} for a synth Builder class.
     * Cached on the synth class itself (keyed to
     * {@link PsiModificationTracker#MODIFICATION_COUNT}) so repeated augment
     * queries within the same PSI revision don't re-walk every field.
     */
    private static List<PsiMethod> cachedSynthBuilderMethods(GeneratedBuilderClass synthBuilder) {
        return CachedValuesManager.getCachedValue(synthBuilder, () -> {
            PsiClass parentTarget = synthBuilder.getContainingClass();
            if (parentTarget == null) {
                return CachedValueProvider.Result.create(Collections.<PsiMethod>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            PsiAnnotation cb = findClassBuilderAnnotation(parentTarget);
            if (cb == null) {
                return CachedValueProvider.Result.create(Collections.<PsiMethod>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            GeneratedMemberFactory.EditorBuilderConfig config =
                GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(cb);
            List<PsiMethod> methods = GeneratedMemberFactory.synthesizeBuilderMethods(
                parentTarget, config, synthBuilder);
            return CachedValueProvider.Result.create(methods,
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiMethod> cachedMethods(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            PsiAnnotation resolved = findClassBuilderAnnotation(target);
            if (resolved == null) {
                return CachedValueProvider.Result.create(Collections.<PsiMethod>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            SynthesizedMembers members = synthesizeOrReuse(target, resolved);
            return CachedValueProvider.Result.create(
                members.bootstrapMethods(),
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiClass> cachedNestedClasses(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            PsiAnnotation resolved = findClassBuilderAnnotation(target);
            if (resolved == null) {
                return CachedValueProvider.Result.create(Collections.<PsiClass>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            // Skip when the target already declares a nested class with the
            // configured Builder name - the user's hand-written version wins.
            // Use the stub-based getOwnInnerClasses() instead of getChildren()
            // or getInnerClasses(): getChildren() forces full AST load (illegal
            // for files that aren't open in the editor - throws during cross-
            // file highlighting); getInnerClasses() is augment-aware and would
            // recurse back into this provider.
            GeneratedMemberFactory.EditorBuilderConfig config =
                GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(resolved);
            if (target instanceof com.intellij.psi.impl.source.PsiExtensibleClass extensible) {
                for (PsiClass nested : extensible.getOwnInnerClasses()) {
                    if (config.builderName().equals(nested.getName())) {
                        return CachedValueProvider.Result.create(Collections.<PsiClass>emptyList(),
                            PsiModificationTracker.MODIFICATION_COUNT);
                    }
                }
            }
            SynthesizedMembers members = synthesizeOrReuse(target, resolved);
            return CachedValueProvider.Result.create(
                List.of(members.builderClass()),
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    /**
     * Returns cached {@link SynthesizedMembers} when the stored config matches
     * the current annotation, otherwise re-synthesises and replaces the cache.
     * Pairs with {@link #SYNTHESIZED} to defeat
     * {@link com.intellij.util.IdempotenceChecker} re-invocation failures:
     * whoever wins the synthesis race stores its result under the key, and
     * subsequent calls (including the checker's rerun) retrieve the same
     * {@link PsiClass} / {@link PsiMethod} instances.
     */
    private static SynthesizedMembers synthesizeOrReuse(PsiClass target, PsiAnnotation resolved) {
        GeneratedMemberFactory.EditorBuilderConfig config =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(resolved);
        SynthesizedMembers cached = target.getUserData(SYNTHESIZED);
        if (cached != null && Objects.equals(cached.config(), config)) {
            return cached;
        }
        // Wrap synthesis in the recursion guard. Eagerly resolving the self-
        // reference type re-enters getAugments() for the same target via the
        // inner-class lookup; the guard ensures the inner call returns empty
        // rather than looping until stack overflow.
        IN_PROGRESS.get().add(target);
        try {
            PsiClass builderClass = GeneratedMemberFactory.synthesizeBuilderClass(target, config);
            List<PsiMethod> bootstrap = GeneratedMemberFactory.bootstrapMethods(target, config, builderClass);
            SynthesizedMembers fresh = new SynthesizedMembers(config, bootstrap, builderClass);
            target.putUserData(SYNTHESIZED, fresh);
            return fresh;
        } finally {
            IN_PROGRESS.get().remove(target);
        }
    }

    private static PsiAnnotation findClassBuilderAnnotation(PsiClass target) {
        for (PsiAnnotation a : target.getAnnotations()) {
            if (ClassBuilderConstants.ANNOTATION_FQN.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

}
