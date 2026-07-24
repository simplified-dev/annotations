package dev.simplified.classbuilder.editor;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.IdempotenceChecker;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
public final class ClassBuilderAugmentProvider extends AbstractRecursionSafeAugmentProvider {

    /**
     * Memoises per-target synthesised members. {@link CachedValuesManager}
     * periodically re-runs producers and requires the results be equal across
     * invocations ({@link IdempotenceChecker}). Building a
     * fresh {@code LightPsiClassBuilder} / {@code LightMethodBuilder} on each
     * call yields new-identity instances that fail that check, so we keep one
     * cached pair on the target's user data keyed by the resolved
     * {@link GeneratedMemberFactory.EditorBuilderConfig}. When the annotation
     * changes, the config differs, and the lambda synthesises fresh members
     * (and replaces the cache). When the producer is re-invoked for the same
     * config, it returns the already-stored instances - idempotent.
     */
    private static final Key<SynthesizedMembers> SYNTHESIZED =
        Key.create("dev.simplified.classbuilder.synthesized");

    private record SynthesizedMembers(GeneratedMemberFactory.EditorBuilderConfig config,
                                      List<PsiMethod> bootstrapMethods,
                                      PsiClass builderClass,
                                      @Nullable PsiMethod allArgsConstructor) {

        /** Bootstrap methods plus the all-args constructor when one was synthesised. */
        List<PsiMethod> allMethods() {
            if (allArgsConstructor == null) return bootstrapMethods;
            List<PsiMethod> out = new ArrayList<>(bootstrapMethods.size() + 1);
            out.addAll(bootstrapMethods);
            out.add(allArgsConstructor);
            return out;
        }
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
                members.allMethods(),
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
     * {@link IdempotenceChecker} re-invocation failures:
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
            PsiMethod ctor = needsAllArgsConstructor(target, config)
                ? GeneratedMemberFactory.allArgsConstructor(target, config)
                : null;
            SynthesizedMembers fresh = new SynthesizedMembers(config, bootstrap, builderClass, ctor);
            target.putUserData(SYNTHESIZED, fresh);
            return fresh;
        } finally {
            IN_PROGRESS.get().remove(target);
        }
    }

    /**
     * Mirrors the APT-side gate in {@code BuilderMutator.needsAllArgsConstructor}
     * so the editor surfaces a constructor exactly when javac will inject one.
     * Records keep their canonical constructor, a set {@code factoryMethod} means
     * {@code build()} never calls {@code new}, and any author-declared
     * constructor suppresses synthesis outright.
     *
     * <p>Reads {@code getOwnMethods()} rather than {@code getConstructors()}:
     * the latter is augment-aware and would recurse back into this provider.
     */
    private static boolean needsAllArgsConstructor(PsiClass target,
                                                   GeneratedMemberFactory.EditorBuilderConfig config) {
        if (target.isRecord() || target.isInterface() || target.isEnum()) return false;
        if (target.hasModifierProperty(PsiModifier.ABSTRACT)) return false;
        if (!config.factoryMethod().isEmpty()) return false;
        // A concrete subclass of an annotated super sits in a SuperBuilder chain
        // and takes CopyConstructorFactory's Target(Builder b) instead.
        PsiClass superClass = target.getSuperClass();
        if (superClass != null && findClassBuilderAnnotation(superClass) != null) return false;
        if (target instanceof PsiExtensibleClass extensible) {
            for (PsiMethod own : extensible.getOwnMethods()) {
                if (own.isConstructor()) return false;
            }
            for (PsiClass nested : extensible.getOwnInnerClasses()) {
                if (config.builderName().equals(nested.getName())) return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code @ClassBuilder} synthesises a constructor on this target.
     *
     * <p>The one public reading of that gate. The inferred-annotation surface
     * and the gutter tooltip both have to answer the same question - "is there
     * a constructor here, and what does it look like" - and a second copy of a
     * six-clause predicate is exactly how the signature in the gutter comes to
     * contradict the one javac emits.
     *
     * @param target the class to test
     * @return whether a constructor is synthesised for it
     */
    public static boolean synthesisesConstructor(@NotNull PsiClass target) {
        PsiAnnotation annotation = findClassBuilderAnnotation(target);
        if (annotation == null) return false;
        return needsAllArgsConstructor(target,
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(annotation));
    }

    /**
     * The target's {@code @ClassBuilder}, or {@code null}.
     *
     * @param target the class to read
     * @return the annotation, when present
     */
    public static @Nullable PsiAnnotation classBuilderAnnotation(@NotNull PsiClass target) {
        return findClassBuilderAnnotation(target);
    }

    private static PsiAnnotation findClassBuilderAnnotation(PsiClass target) {
        for (PsiAnnotation a : target.getAnnotations()) {
            if (ClassBuilderConstants.ANNOTATION_FQN.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

}
