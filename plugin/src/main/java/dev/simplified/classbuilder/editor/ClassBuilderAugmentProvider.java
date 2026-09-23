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
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.IdempotenceChecker;
import dev.simplified.args.inspect.ArgsConstants;
import dev.simplified.classbuilder.apt.BuilderConstructorAccess;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
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
     * {@link GeneratedMemberFactory.EditorBuilderConfig} and the builder class
     * the target declares, if any. When the annotation or the declaration
     * changes, the key differs, and the lambda synthesises fresh members
     * (and replaces the cache). When the producer is re-invoked for the same
     * key, it returns the already-stored instances - idempotent.
     */
    private static final Key<SynthesizedMembers> SYNTHESIZED =
        Key.create("dev.simplified.classbuilder.synthesized");

    private record SynthesizedMembers(GeneratedMemberFactory.EditorBuilderConfig config,
                                      @Nullable PsiClass declaredBuilder,
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
     * <p>Whether an author constructor is the copy constructor is answered by
     * {@link DeclaredBuilderShape#namesOwnBuilder} from the parameter type as
     * written, the rule the processor applies to the same source, so a
     * qualified spelling such as {@code Link.Builder} is the author's version
     * on both halves.
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
        String targetName = target.getName();
        if (targetName == null) return false;
        String builderName = config.builderName();
        for (PsiMethod own : extensible.getOwnMethods()) {
            if (!own.isConstructor()) continue;
            PsiParameter[] parameters = own.getParameterList().getParameters();
            if (parameters.length != 1) continue;
            PsiTypeElement written = parameters[0].getTypeElement();
            if (written == null) continue;
            if (DeclaredBuilderShape.namesOwnBuilder(written.getText(), targetName, builderName)) return false;
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
     * only when the declared class spells no method of that name and those
     * erased parameter types, {@link DeclaredBuilderShape#methodKey}, and the
     * generated constructor is never offered, that class always
     * having one by the time either half looks. Where the author wrote none on a
     * class, record, constructor or factory target, the default javac retypes
     * to {@code builderConstructorAccess} is offered in its place. Read through
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
        // A constructor or factory target merges into the builder its enclosing
        // type declares exactly as a type target does, so the site may be
        // either.
        BuilderSite site = BuilderSite.of(owner);
        if (site == null) return null;
        // An interface type target's builder is a sibling file, and the
        // processor never looks at a class nested in the interface body, so
        // nothing merges into one - contributing here would list members javac
        // never appends. A static factory inside an interface is an executable
        // target, which merges into that class as anywhere else.
        if (owner.isInterface() && !site.isExecutable()) return null;

        GeneratedMemberFactory.EditorBuilderConfig config =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(site.annotation());
        if (!name.equals(config.builderName())) return null;
        // The chain pass refuses a link whose ancestor's declared builder cannot
        // take the extends clause before it looks at the link's own declaration,
        // so nothing is appended to that declaration either.
        if (ClassBuilderConstants.ancestorBlockingGeneration(owner, name, site.isExecutable()) != null)
            return null;
        // The shape the processor accepts, asked of the same facts. Contributing
        // into a builder javac rejects leaves the author reading a populated
        // completion list right up to the moment the build fails on it.
        if (ClassBuilderConstants.mergeRejection(owner, site.executable(), declared,
            config.names()) != null) {
            return null;
        }
        return new MergeTarget(site, owner, config);
    }

    private static List<PsiMethod> mergedBuilderMethods(PsiClass declared) {
        MergeTarget merge = mergeTargetOf(declared);
        if (merge == null) return Collections.emptyList();
        Set<String> spelled = new HashSet<>();
        for (PsiMethod own : GeneratedMemberFactory.ownMethods(declared))
            spelled.add(MergedSlotStorage.writtenKey(own));
        // Guarded for the reason the field contribution is, and opened in the
        // same place: synthesising a setter resolves the type of the slot it
        // assigns, which re-enters this provider for the class that wrote it.
        return withInProgress(merge.owner(), () -> {
            List<PsiMethod> out = new ArrayList<>();
            for (PsiMethod generated : GeneratedMemberFactory.synthesizeBuilderMethods(
                merge.site(), merge.config(), declared)) {
                // The generated constructor is never appended as it stands; the
                // one below takes its place where the author wrote none.
                if (generated.isConstructor()) continue;
                if (spelled.contains(MergedSlotStorage.generatedKey(generated))) continue;
                out.add(generated);
            }
            // The processor retypes javac's default to builderConstructorAccess
            // on a builder left with no other constructor - neither the
            // author's nor one a constructor annotation on it appends - on the
            // roles the attribute reaches. PSI carries no default to retype, so
            // the retyped one is contributed; elsewhere PSI's implicit default
            // is javac's, at the class's access.
            if (BuilderConstructorAccess.appliesTo(GeneratedMemberFactory.roleOf(merge.site()))
                && ClassBuilderConstants.keepsOnlyTheDefaultConstructor(declared)) {
                out.add(GeneratedMemberFactory.retypedDefaultConstructor(declared, merge.config()));
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
            // A chain role's refused declaration stops the pass ahead of the copy
            // constructor as well as the entry points, where a class target's
            // all-args constructor is decided before the merge and kept.
            boolean refused = rejectsDeclaredBuilder(site, config);
            if (refused && !site.isExecutable()
                && ClassBuilderConstants.chainRoleOf(target).isChained()) {
                return CachedValueProvider.Result.create(Collections.<PsiMethod>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            SynthesizedMembers members = synthesizeOrReuse(site);
            // Bootstrap methods (builder/from/mutate) only on concrete targets;
            // an abstract class gets its entry points from concrete subclasses.
            // Its constructor is a separate question and is answered separately,
            // the processor emitting the chain's copy constructor above the gate
            // that withholds the entry points. An executable target is never in a
            // chain, so an abstract enclosing type is no reason to withhold its
            // entry point. PSI answers ABSTRACT for every interface, and an
            // interface target takes its three onto its own body, typed to the
            // sibling builder, so the test is of an abstract class alone.
            // The second cause is a merged builder with no constructor taking
            // what the entry points pass - nothing on a type target, the seeds'
            // types in order on an executable one. It withholds the entry points and nothing
            // else - the merge still runs and a class target still gets the
            // all-args constructor build() calls, so answering the whole request
            // empty here took that constructor with it and put a same-package
            // new Target(...) red over source that builds.
            // The third is a declared builder whose shape the merge refuses. The
            // processor reports it and returns before the entry points, having
            // already decided the all-args constructor, so the split is the
            // second cause's.
            boolean entryPointsWithheld = (!site.isExecutable() && !target.isInterface()
                && target.hasModifierProperty(PsiModifier.ABSTRACT))
                || ClassBuilderConstants.withholdsEntryPointsOnly(target, config.builderName(),
                    site.isExecutable(), site.seedTypes())
                || refused;
            return CachedValueProvider.Result.create(
                entryPointsWithheld ? members.constructorOnly() : members.allMethods(),
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    /**
     * Whether the target declares a builder whose shape the merge refuses.
     *
     * <p>The processor reports the rejection and returns ahead of the entry
     * points, so none of the three is emitted beside a refused declaration, and
     * offering them would leave {@code Target.builder()} green at a call site in
     * another file while the build fails. The decision is
     * {@link ClassBuilderConstants#mergeRejection}, the one the shape inspection
     * reports, and it is asked wherever a merge runs - a class or record target,
     * a chain role among them, and a constructor or factory target, one inside
     * an interface included - and never of an interface type target.
     *
     * @param site the annotated site
     * @param config the resolved configuration for it
     * @return whether a declared builder exists and its shape is refused
     */
    private static boolean rejectsDeclaredBuilder(BuilderSite site,
                                                  GeneratedMemberFactory.EditorBuilderConfig config) {
        PsiClass target = site.owner();
        if (target.isInterface() && !site.isExecutable()) return false;
        PsiClass declared = ClassBuilderConstants.declaredBuilderOf(target, config.builderName());
        return declared != null && ClassBuilderConstants.mergeRejection(target, site.executable(),
            declared, config.names()) != null;
    }

    /**
     * Whether no builder is generated for this target at all - an annotated
     * supertype whose own declared builder the extends clause cannot name - so
     * the entry points and the copy constructor are withheld with it.
     *
     * <p>The decision is
     * {@link ClassBuilderConstants#ancestorBlockingGeneration(PsiClass, String, boolean)},
     * the one the shape inspection reports, so the withholding and the error
     * explaining it cannot disagree about when it happens.
     *
     * @param site the annotated site
     * @param config the resolved configuration for it
     * @return whether every member the pass would contribute to the target is withheld
     */
    private static boolean suppressesEntryPoints(BuilderSite site,
                                                 GeneratedMemberFactory.EditorBuilderConfig config) {
        return ClassBuilderConstants.ancestorBlockingGeneration(site.owner(), config.builderName(),
            site.isExecutable()) != null;
    }

    private static List<PsiClass> cachedNestedClasses(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            BuilderSite site = BuilderSite.of(target);
            if (site == null) {
                return CachedValueProvider.Result.create(Collections.<PsiClass>emptyList(),
                    PsiModificationTracker.MODIFICATION_COUNT);
            }
            // Skip when the target already declares a nested class with the
            // configured Builder name - the user's hand-written version wins and
            // is merged into rather than joined by a second class - and
            // skip when the ancestor's own declared builder leaves the extends
            // clause unformable, which is the shape the processor refuses.
            GeneratedMemberFactory.EditorBuilderConfig config =
                GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(site.annotation());
            if (ClassBuilderConstants.declaredBuilderOf(target, config.builderName()) != null
                || ClassBuilderConstants.ancestorBlockingGeneration(target,
                    config.builderName(), site.isExecutable()) != null) {
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
     * Returns cached {@link SynthesizedMembers} when the stored config and the
     * declared builder both match the current source, otherwise re-synthesises
     * and replaces the cache. Pairs with {@link #SYNTHESIZED} to defeat
     * {@link IdempotenceChecker} re-invocation failures:
     * whoever wins the synthesis race stores its result under the key, and
     * subsequent calls (including the checker's rerun) retrieve the same
     * {@link PsiClass} / {@link PsiMethod} instances.
     *
     * <p>The entry points return the builder javac emits, which on a target
     * declaring its own is the author's class with the merged members in it,
     * not the synthesised one. That class is withheld from the target's nested
     * classes and carries only the generated set, so entry points typed against
     * it would put a call chaining a generated setter into an author's own verb
     * red over source that builds. Which class that is decides the members
     * cached here, so a cached value is reused only while the target declares
     * the same class it was built against.
     */
    private static SynthesizedMembers synthesizeOrReuse(BuilderSite site) {
        PsiClass target = site.owner();
        GeneratedMemberFactory.EditorBuilderConfig config =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(site.annotation());
        PsiClass declared = entryPointBuilderOf(site, config);
        SynthesizedMembers cached = target.getUserData(SYNTHESIZED);
        if (cached != null && Objects.equals(cached.config(), config)
            && cached.declaredBuilder() == declared) {
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
            PsiClass emitted = declared != null ? declared : builderClass;
            List<PsiMethod> bootstrap = GeneratedMemberFactory.bootstrapMethods(site, config, emitted);
            SynthesizedMembers fresh = new SynthesizedMembers(config, declared, bootstrap,
                builderClass, new DeferredConstructor(site, config, emitted));
            target.putUserData(SYNTHESIZED, fresh);
            return fresh;
        });
    }

    /**
     * The builder class the target declares and the merge runs into, which is
     * the class its entry points return.
     *
     * <p>A class or record target, a chain role among them, and a constructor or
     * factory target all merge, so the declared class is what the entry points
     * and a chain's copy constructor are typed against. An interface type
     * target's entry points return its sibling builder, never a class nested in
     * the interface body; a static factory inside an interface is an executable
     * target and returns that class.
     *
     * @param site the annotated site
     * @param config the resolved configuration for it
     * @return the declared builder, or {@code null} when the entry points return the synthesised one
     */
    private static @Nullable PsiClass entryPointBuilderOf(BuilderSite site,
                                                          GeneratedMemberFactory.EditorBuilderConfig config) {
        if (site.owner().isInterface() && !site.isExecutable()) return null;
        return ClassBuilderConstants.declaredBuilderOf(site.owner(), config.builderName());
    }

    /**
     * Mirrors the APT-side gate in {@code BuilderMutator.needsAllArgsConstructor}
     * so the editor surfaces a constructor exactly when javac will inject one.
     * Records keep their canonical constructor, a set {@code factoryMethod} means
     * {@code build()} never calls {@code new}, and any author-declared
     * constructor suppresses synthesis outright.
     *
     * <p>A declared nested builder spelling its own {@code build()}, which the
     * merge keeps, withholds it too, through
     * {@link DeclaredBuilderShape#withholdsAllArgsConstructor} - the rule the
     * processor asks - over the names that builder's own methods are written
     * with and whether {@code @BuilderArgsConstructor} is written on the target.
     * javac's no-argument default then stays for an author {@code build()}
     * calling {@code new Target()}. A declared builder leaving {@code build()}
     * to the generator keeps the constructor, which the generated one calls.
     *
     * <p>Reads {@code getOwnMethods()} rather than {@code getConstructors()}:
     * the latter is augment-aware and would recurse back into this provider.
     */
    private static boolean needsAllArgsConstructor(PsiClass target,
                                                   GeneratedMemberFactory.EditorBuilderConfig config) {
        return owesAllArgsConstructor(target, config) && !authorBuildSurvives(target, config);
    }

    /**
     * Whether the target's shape calls for the all-args constructor at all,
     * before the declared builder is asked.
     *
     * @param target the annotated type
     * @param config the resolved configuration for it
     * @return whether the target is owed the constructor
     */
    private static boolean owesAllArgsConstructor(PsiClass target,
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
        }
        return true;
    }

    /**
     * Whether the declared builder's own build method survives the merge, as
     * {@link DeclaredBuilderShape#withholdsAllArgsConstructor} answers it from
     * names alone.
     *
     * <p>The builder's methods are read through {@code getOwnMethods()}, keyed
     * by the parameter types as written, and the constructor annotation by the
     * name it is written with - nothing here resolves.
     *
     * @param target the annotated type
     * @param config the resolved configuration for it
     * @return whether the all-args constructor is withheld for it
     */
    private static boolean authorBuildSurvives(PsiClass target,
                                               GeneratedMemberFactory.EditorBuilderConfig config) {
        PsiClass declared = ClassBuilderConstants.declaredBuilderOf(target, config.builderName());
        if (declared == null) return false;
        Set<String> keys = new HashSet<>();
        for (PsiMethod own : GeneratedMemberFactory.ownMethods(declared)) keys.add(MergedSlotStorage.writtenKey(own));
        return DeclaredBuilderShape.withholdsAllArgsConstructor(config.buildMethodName(), keys,
            WrittenAnnotations.find(target, ArgsConstants.BUILDER_ARGS_FQN) != null);
    }

    /**
     * Whether the all-args constructor this target is otherwise owed is
     * withheld beside an author's own {@code build()}.
     *
     * <p>Every {@code final} initializer then stays on its field on both halves,
     * nothing generated being left to assign it, which is what the blank-final
     * lift has to know.
     *
     * @param target the class to test
     * @return whether the constructor is withheld
     */
    public static boolean withholdsAllArgsConstructor(@NotNull PsiClass target) {
        PsiAnnotation annotation = findClassBuilderAnnotation(target);
        if (annotation == null) return false;
        GeneratedMemberFactory.EditorBuilderConfig config =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(annotation);
        return owesAllArgsConstructor(target, config) && authorBuildSurvives(target, config);
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
