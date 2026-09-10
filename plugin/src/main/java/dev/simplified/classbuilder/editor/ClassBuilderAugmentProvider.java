package dev.simplified.classbuilder.editor;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import dev.simplified.classbuilder.apt.ChainRole;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.IdempotenceChecker;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.WrittenAnnotations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
                                      DeferredConstructor allArgsConstructor) {

        /** Bootstrap methods plus the synthesised constructor, when there is one. */
        List<PsiMethod> allMethods() {
            PsiMethod constructor = allArgsConstructor.get();
            if (constructor == null) return bootstrapMethods;
            List<PsiMethod> out = new ArrayList<>(bootstrapMethods.size() + 1);
            out.addAll(bootstrapMethods);
            out.add(constructor);
            return out;
        }

        /**
         * The constructor alone, for a target that gets no entry points.
         *
         * <p>An abstract target's bootstraps come from its concrete subclasses,
         * but its copy constructor is emitted on the target itself - the
         * processor writes it above the gate that withholds the entry points, so
         * withholding both together left a chain root with no constructor the
         * editor could see and an author's {@code super(builder)} red over source
         * that builds.
         */
        List<PsiMethod> constructorOnly() {
            PsiMethod constructor = allArgsConstructor.get();
            return constructor == null ? List.of() : List.of(constructor);
        }
    }

    /**
     * The all-args constructor, synthesised on the first read rather than beside
     * the rest of the members.
     *
     * <p>Reading it is what classifies every field, and classifying a field
     * resolves the type the author wrote on it. A type resolve started from
     * inside {@code getAugments} walks the target's own scope and arrives back
     * here, so the platform can already be resolving the very reference the
     * classification is about to ask about. It answers that by refusing to cache
     * the outer resolve, which costs every later pass the same walk and leaves
     * the classification reading a type that resolved to nothing.
     *
     * <p>Deferring is what separates the two. A reference to a type name asks a
     * class only for its nested classes, and the nested class is the one member
     * here that needs no field read at all - so the request that can be mid-
     * resolve is the request that no longer triggers one. The method request
     * still builds it, and nothing resolving a type name makes that request.
     *
     * <p>Memoised, and the value that wins the race is the value every caller
     * gets: {@link IdempotenceChecker} re-runs the producers around this one and
     * compares what they return, so a second call handing back an equal-but-new
     * {@link PsiMethod} fails the check. Held through an {@link Optional} so a
     * target that needs no constructor is a computed answer rather than an
     * unread one.
     */
    private static final class DeferredConstructor {

        private final BuilderSite site;
        private final GeneratedMemberFactory.EditorBuilderConfig config;
        private final PsiClass builderClass;
        private final AtomicReference<Optional<PsiMethod>> computed = new AtomicReference<>();

        DeferredConstructor(BuilderSite site, GeneratedMemberFactory.EditorBuilderConfig config,
                            PsiClass builderClass) {
            this.site = site;
            this.config = config;
            this.builderClass = builderClass;
        }

        /**
         * The constructor this target needs, or {@code null} when it needs none.
         *
         * @return the synthesised constructor, computed once
         */
        private @Nullable PsiMethod get() {
            Optional<PsiMethod> known = computed.get();
            if (known != null) return known.orElse(null);

            PsiMethod fresh = compute();
            return computed.compareAndSet(null, Optional.ofNullable(fresh))
                ? fresh
                : computed.get().orElse(null);
        }

        /**
         * Which of the two constructors the processor emits here, if either.
         *
         * <p>A target in a chain takes the builder-copying one and never the
         * all-args form, which is the split {@code needsAllArgsConstructor}
         * already makes by refusing an abstract target and a concrete subclass of
         * an annotated super. That refusal is why the chain's constructor was
         * contributed by nothing at all, leaving an author's own
         * {@code super(builder)} red over source that builds.
         *
         * @return the constructor, or {@code null} when the target needs none
         */
        private @Nullable PsiMethod compute() {
            PsiClass target = site.owner();
            // The annotated member is what build() calls on the executable path,
            // so there is no constructor to synthesise beside it.
            if (site.isExecutable()) return null;
            if (ClassBuilderConstants.chainRoleOf(target).isChained()) {
                if (!needsCopyConstructor(target, config)) return null;
                ChainRole role = ClassBuilderConstants.chainRoleOf(target);
                return withInProgress(target,
                    () -> GeneratedMemberFactory.copyConstructor(target, builderClass, role));
            }
            return needsAllArgsConstructor(target, config)
                ? withInProgress(target, () -> GeneratedMemberFactory.allArgsConstructor(target, config))
                : null;
        }
    }

    /**
     * Mirrors the two gates the processor emits the chain's copy constructor
     * under, and not the role alone.
     *
     * <p>Gating on the role would contribute a constructor javac omits whenever
     * {@code generateCopyConstructor = false} is written or the author declared
     * their own - the inverse of the divergence this closes, and the same class
     * of error.
     *
     * <p>Reads {@code getOwnMethods()} rather than {@code getConstructors()}:
     * the latter is augment-aware and would recurse back into this provider.
     *
     * @param target the annotated type
     * @param config the resolved configuration for it
     * @return whether the processor emits one here
     */
    private static boolean needsCopyConstructor(PsiClass target,
                                                GeneratedMemberFactory.EditorBuilderConfig config) {
        if (!config.generateCopyConstructor()) return false;
        if (!(target instanceof PsiExtensibleClass extensible)) return false;
        String builderName = config.builderName();
        for (PsiMethod own : extensible.getOwnMethods()) {
            if (!own.isConstructor()) continue;
            PsiParameter[] parameters = own.getParameterList().getParameters();
            if (parameters.length != 1) continue;
            PsiTypeElement written = parameters[0].getTypeElement();
            if (written == null) continue;
            String text = written.getText();
            if (text.equals(builderName) || text.startsWith(builderName + "<")) return false;
        }
        return true;
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

        // The guard first, before anything that could resolve: BuilderSite.of
        // matches an annotation by name, and a resolve started from in here
        // walks the class's nested types straight back into this method.
        if (IN_PROGRESS.get().contains(target)) return Collections.emptyList();
        BuilderSite site = BuilderSite.of(target);
        if (site == null) {
            // The class carries no annotation of its own, which is also what a
            // declared Builder being merged into looks like. Its members come
            // from the annotation on the class around it - the slot fields as
            // well as the methods, since the merge appends both and an author's
            // own verb in that class reads the fields.
            if (PsiMethod.class.isAssignableFrom(type)) {
                @SuppressWarnings("unchecked")
                List<Psi> merged = (List<Psi>) cachedMergedBuilderMethods(target);
                return merged;
            }
            if (PsiField.class.isAssignableFrom(type)) {
                @SuppressWarnings("unchecked")
                List<Psi> fields = (List<Psi>) cachedMergedBuilderFields(target);
                return fields;
            }
            return Collections.emptyList();
        }

        if (PsiMethod.class.isAssignableFrom(type)) {
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
            BuilderSite site = BuilderSite.of(parentTarget);
            if (site == null) {
                return CachedValueProvider.Result.create(Collections.<PsiMethod>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            GeneratedMemberFactory.EditorBuilderConfig config =
                GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(site.annotation());
            List<PsiMethod> methods = GeneratedMemberFactory.synthesizeBuilderMethods(
                site, config, synthBuilder);
            return CachedValueProvider.Result.create(methods,
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    /**
     * The generated members for a {@code Builder} the target declares itself and
     * asked to have merged into.
     *
     * <p>Without this the editor would show only what the author wrote there
     * while the build emits every setter beside it - completion missing the
     * whole generated surface, which is the drift in its most literal form.
     *
     * <p>The collision rule is the processor's: a generated method is offered
     * only when the declared class spells no method of that name and parameter
     * count, and the builder's constructor is never offered, that class always
     * having one by the time either half looks. Read through
     * {@code getOwnMethods()} rather than {@code getMethods()}, the latter being
     * augment-aware and answering with whatever this provider contributed last.
     *
     * @param declared the class that might be a merged builder
     * @return the members to add, empty when it is not one
     */
    private static List<PsiMethod> cachedMergedBuilderMethods(PsiClass declared) {
        return CachedValuesManager.getCachedValue(declared, () -> {
            List<PsiMethod> members = mergedBuilderMethods(declared);
            return CachedValueProvider.Result.create(members,
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    /**
     * The slot fields for a {@code Builder} the target declares itself and asked
     * to have merged into.
     *
     * <p>The merge writes them and the editor wrote none, so an author's own verb
     * inside that class referencing a slot was red over source that builds. A
     * generated field is offered only where the declared class spells no field of
     * that name, which is the processor's own rule.
     *
     * @param declared the class that might be a merged builder
     * @return the fields to add, empty when it is not one
     */
    private static List<PsiField> cachedMergedBuilderFields(PsiClass declared) {
        return CachedValuesManager.getCachedValue(declared, () -> {
            List<PsiField> members = mergedBuilderFields(declared);
            return CachedValueProvider.Result.create(members,
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiField> mergedBuilderFields(PsiClass declared) {
        MergeTarget merge = mergeTargetOf(declared);
        if (merge == null) return Collections.emptyList();

        Set<String> spelled = new HashSet<>();
        for (PsiField own : ownFields(declared)) spelled.add(own.getName());
        // Opened here rather than around the lookup above: BuilderSite.of
        // answers null for a class already in progress, so a guard taken before
        // it silently empties the whole merged path instead of protecting it.
        // Field-type resolution is this codebase's known augment-recursion
        // trigger, and every slot below resolves the type its field was written
        // with, so the guard is a prerequisite of the contribution rather than a
        // precaution beside it.
        return withInProgress(merge.owner(), () -> {
            List<PsiField> out = new ArrayList<>();
            for (PsiField generated : GeneratedMemberFactory.synthesizeBuilderFields(
                merge.site(), merge.config(), declared)) {
                if (spelled.contains(generated.getName())) continue;
                out.add(generated);
            }
            return out;
        });
    }

    /** The declared fields, read without the augment pass that is asking. */
    private static List<PsiField> ownFields(PsiClass declared) {
        return declared instanceof PsiExtensibleClass extensible
            ? extensible.getOwnFields()
            : List.of(declared.getFields());
    }

    /**
     * The annotated target a declared builder is being merged into, with the
     * configuration that decides it.
     *
     * @param site the annotated site around the declaration
     * @param owner the annotated type
     * @param config its resolved configuration
     */
    private record MergeTarget(BuilderSite site, PsiClass owner,
                               GeneratedMemberFactory.EditorBuilderConfig config) { }

    /**
     * Whether this class is a builder the merge runs into, and what decides it.
     *
     * <p>Every gate the processor applies, asked once for both the method and the
     * field contribution so the two cannot disagree about whether a merge is
     * happening at all.
     *
     * @param declared the class that might be a merged builder
     * @return the target, or {@code null} when no merge reaches this class
     */
    private static @Nullable MergeTarget mergeTargetOf(PsiClass declared) {
        String name = declared.getName();
        if (name == null) return null;
        PsiClass owner = declared.getContainingClass();
        if (owner == null || IN_PROGRESS.get().contains(owner)) return null;
        BuilderSite site = BuilderSite.of(owner);
        if (site == null || site.isExecutable()) return null;

        GeneratedMemberFactory.EditorBuilderConfig config =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(site.annotation());
        if (!config.mergeDeclaredBuilder()) return null;
        if (!name.equals(config.builderName())) return null;
        // The opt-in asks nothing about where the target sits in a chain, and
        // the chain branch returns ahead of the declared-builder check without
        // reading the attribute at all - so on a root, a link or a chained
        // abstract the author's builder is left exactly as written. Merging
        // here would list the setters, the self accessor and the build method
        // on a class javac appends nothing to, and a call to any of them fails
        // the build.
        if (ClassBuilderConstants.chainRoleOf(owner).isChained()) return null;
        // The shape the processor accepts, asked of the same facts. Contributing
        // into a builder javac rejects leaves the author reading a populated
        // completion list right up to the moment the build fails on it.
        if (ClassBuilderConstants.mergeRejection(owner, declared, config.names()) != null) return null;
        return new MergeTarget(site, owner, config);
    }

    private static List<PsiMethod> mergedBuilderMethods(PsiClass declared) {
        MergeTarget merge = mergeTargetOf(declared);
        if (merge == null) return Collections.emptyList();
        Set<String> spelled = new HashSet<>();
        for (PsiMethod own : GeneratedMemberFactory.ownMethods(declared)) {
            spelled.add(own.getName() + "/" + own.getParameterList().getParametersCount());
        }
        // Guarded for the reason the field contribution is, and opened in the
        // same place: synthesising a setter resolves the type of the slot it
        // assigns, which re-enters this provider for the class that wrote it.
        return withInProgress(merge.owner(), () -> {
            List<PsiMethod> out = new ArrayList<>();
            for (PsiMethod generated : GeneratedMemberFactory.synthesizeBuilderMethods(
                merge.site(), merge.config(), declared)) {
                if (generated.isConstructor()) continue;
                if (spelled.contains(generated.getName() + "/"
                    + generated.getParameterList().getParametersCount())) {
                    continue;
                }
                out.add(generated);
            }
            return out;
        });
    }

    private static List<PsiMethod> cachedMethods(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            BuilderSite site = BuilderSite.of(target);
            if (site == null) {
                return CachedValueProvider.Result.create(Collections.<PsiMethod>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            GeneratedMemberFactory.EditorBuilderConfig config =
                GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(site.annotation());
            if (suppressesEntryPoints(site, config)) {
                return CachedValueProvider.Result.create(Collections.<PsiMethod>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            SynthesizedMembers members = synthesizeOrReuse(site);
            // Bootstrap methods (builder/from/mutate) only on concrete targets;
            // an abstract target gets its entry points from concrete subclasses.
            // Its constructor is a separate question and is answered separately,
            // the processor emitting the chain's copy constructor above the gate
            // that withholds the entry points. An executable target is never in a
            // chain, so an abstract enclosing type is no reason to withhold its
            // entry point.
            boolean entryPointsWithheld = !site.isExecutable()
                && target.hasModifierProperty(PsiModifier.ABSTRACT);
            return CachedValueProvider.Result.create(
                entryPointsWithheld ? members.constructorOnly() : members.allMethods(),
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    /**
     * Whether the entry points are withheld because the target declares a nested
     * type of the configured builder name.
     *
     * <p>Offering any of the three where the build emits none is the shape a
     * hand-migration off a Lombok builder produces most naturally, and it
     * resolves green all the way to {@code cannot find symbol}. The decision
     * itself is
     * {@link ClassBuilderConstants#suppressesGeneration(PsiClass, String, boolean, boolean)},
     * so the inspection explaining the withholding and the withholding cannot
     * disagree about when it happens.
     *
     * @param site the annotated site
     * @param config the resolved configuration for it
     * @return whether {@code builder()}, {@code from(T)} and {@code mutate()} must be withheld
     */
    private static boolean suppressesEntryPoints(BuilderSite site,
                                                 GeneratedMemberFactory.EditorBuilderConfig config) {
        return suppressesGeneration(site.owner(), config, site.isExecutable());
    }

    /**
     * Whether no builder is generated for this target at all, from either cause -
     * a declared nested type of the builder's name, or an annotated supertype
     * whose own declared builder the generated extends clause cannot name.
     *
     * @param target the annotated type
     * @param config the resolved configuration for it
     * @param executable whether the annotation sits on a constructor or factory method
     * @return whether the builder and its entry points are both withheld
     */
    private static boolean suppressesGeneration(PsiClass target,
                                                GeneratedMemberFactory.EditorBuilderConfig config,
                                                boolean executable) {
        if (ClassBuilderConstants.suppressesGeneration(target, config.builderName(),
            config.mergeDeclaredBuilder(), executable)) {
            return true;
        }
        return ClassBuilderConstants.ancestorBlockingGeneration(target, config.builderName()) != null;
    }

    private static List<PsiClass> cachedNestedClasses(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            BuilderSite site = BuilderSite.of(target);
            if (site == null) {
                return CachedValueProvider.Result.create(Collections.<PsiClass>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            // Skip when the target already declares a nested class with the
            // configured Builder name - the user's hand-written version wins,
            // whether it is being merged into or is suppressing generation - and
            // skip when the ancestor's own declared builder leaves the extends
            // clause unformable, which is the shape the processor refuses.
            GeneratedMemberFactory.EditorBuilderConfig config =
                GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(site.annotation());
            if (ClassBuilderConstants.declaredBuilderOf(target, config.builderName()) != null
                || ClassBuilderConstants.ancestorBlockingGeneration(target,
                    config.builderName()) != null) {
                return CachedValueProvider.Result.create(Collections.<PsiClass>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            SynthesizedMembers members = synthesizeOrReuse(site);
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
    private static SynthesizedMembers synthesizeOrReuse(BuilderSite site) {
        PsiClass target = site.owner();
        GeneratedMemberFactory.EditorBuilderConfig config =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(site.annotation());
        SynthesizedMembers cached = target.getUserData(SYNTHESIZED);
        if (cached != null && Objects.equals(cached.config(), config)) {
            return cached;
        }
        // Wrap synthesis in the recursion guard. Eagerly resolving the self-
        // reference type re-enters getAugments() for the same target via the
        // inner-class lookup; the guard ensures the inner call returns empty
        // rather than looping until stack overflow. Through withInProgress
        // rather than a hand-rolled add/remove, so a nested guard for this same
        // target - BuilderSite.of takes one - cannot clear it on the way out.
        return withInProgress(target, () -> {
            PsiClass builderClass = GeneratedMemberFactory.synthesizeBuilderClass(site, config);
            List<PsiMethod> bootstrap = GeneratedMemberFactory.bootstrapMethods(site, config, builderClass);
            SynthesizedMembers fresh = new SynthesizedMembers(config, bootstrap, builderClass,
                new DeferredConstructor(site, config, builderClass));
            target.putUserData(SYNTHESIZED, fresh);
            return fresh;
        });
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
            // A merged declared builder is the one case where a nested builder
            // does not suppress the constructor: build() still calls
            // new Target(..), so the constructor it calls still has to exist.
            if (config.mergeDeclaredBuilder()) return true;
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
        return WrittenAnnotations.find(target, ClassBuilderConstants.ANNOTATION_FQN);
    }

}
