package dev.simplified.classbuilder.editor;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.TypeAnnotationProvider;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightModifierList;
import com.intellij.psi.impl.light.LightParameter;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import com.intellij.psi.impl.light.LightTypeParameterBuilder;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.BuilderScheme;
import dev.simplified.classbuilder.apt.SetterScheme;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.shared.psi.AnnotatedLightModifierList;
import dev.simplified.shared.psi.DocProxyingLightMethodBuilder;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import dev.simplified.shared.psi.WrittenAnnotations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Factory for the synthetic PSI members the augment provider surfaces on a
 * {@code @ClassBuilder}-annotated class:
 * <ul>
 *   <li>the three bootstrap methods {@code static Builder builder()},
 *       {@code static Builder from(T)}, instance {@code Builder mutate()},</li>
 *   <li>the nested {@code Builder} class with one or more setters per
 *       discoverable field and a {@code build()} returning the target.</li>
 * </ul>
 *
 * <p>The synthesised Builder's setter matrix mirrors
 * {@code FieldMutators.setters} so editor autocompletion lines up with what
 * the APT mutator emits: plain, boolean zero-arg/typed pair plus optional
 * {@code @Negate} inverse pair, {@code Optional} nullable-raw/wrapped pair
 * plus optional {@code @Formattable} overload, {@code @Collector}
 * collection/map add/put/clear, array varargs, String {@code @Formattable}
 * overload.
 *
 * <p>Parameter-level annotations are emitted inline during synthesis through
 * {@link #buildParam}: {@code @PrintFormat} on String format args,
 * {@code @Nullable} on {@code Object... args} varargs, and
 * {@code @Nullable} / {@code @NotNull} on primary setter params driven by the
 * field's companion annotations (including {@code @BuildFlag(nonNull)}). Only
 * no-attribute annotations can ride on a {@code LightModifierList}; attribute-
 * bearing annotations ({@code @XContract}, {@code @Contract}) are delivered at
 * query time by
 * {@link ClassBuilderInferredAnnotationProvider}.
 *
 * <p>The class is public for {@link #buildParam} and {@link #nullnessFqns}
 * alone. Both are read by the constructor family's augment provider, which mints
 * the other PSI copy of a generated constructor: one parameter-annotation
 * mechanism serving both is what keeps the two copies from claiming different
 * nullness for members javac emits identically. Everything else here stays
 * package-private.
 */
public final class GeneratedMemberFactory {

    private static final String PRINT_FORMAT_FQN = "org.intellij.lang.annotations.PrintFormat";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";
    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";
    private static final String[] NO_ANNOTATIONS = new String[0];

    private GeneratedMemberFactory() {
    }

    static List<PsiMethod> bootstrapMethods(BuilderSite site, EditorBuilderConfig config,
                                            PsiClass builderClass) {
        PsiClass target = site.owner();
        Project project = target.getProject();
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(project);

        // An executable target gets the entry point and nothing else. from(T)
        // and mutate() seed every slot by reading a built instance, and a
        // parameter has no accessor to be read through - so the processor emits
        // neither, and offering them here would put two methods in completion
        // that the build does not produce.
        if (site.isExecutable()) {
            if (config.builderMethodName().isEmpty()) return List.of();
            return List.of(buildEntryPoint(psiManager, elements, site, builderClass,
                config.builderMethodName(), config.access()));
        }

        // Use createType(builderClass) instead of createTypeFromText(FQN):
        // textual resolution requires the IDE's symbol-lookup chain to find
        // the inner Builder class via our augment provider every time, and
        // the access-check pass in cross-package highlighting can't always
        // re-resolve cleanly through that chain - it then flags the bootstrap
        // call with "Cannot access ...Builder". Threading the actual PsiClass
        // instance bypasses resolution entirely.
        List<PsiMethod> out = new ArrayList<>(3);
        if (!config.builderMethodName().isEmpty()
            && !declaresNullary(target, config.builderMethodName())) {
            out.add(buildStaticNoArg(psiManager, elements, target, builderClass,
                config.builderMethodName(), config.access()));
        }
        if (!config.fromMethodName().isEmpty()
            && !declaresCopyFactory(target, config.fromMethodName())) {
            out.add(buildStaticOneArg(psiManager, elements, target, builderClass,
                config.fromMethodName(), "instance", config.access()));
        }
        if (!config.toBuilderMethodName().isEmpty()
            && !declaresNullary(target, config.toBuilderMethodName())) {
            // An instance method, so the target's own parameters are in scope
            // and it needs none of its own.
            PsiClassType builderType = applied(elements, builderClass, target.getTypeParameters());
            out.add(buildInstanceNoArg(psiManager, target, config.toBuilderMethodName(), builderType, config.access()));
        }
        return out;
    }

    /**
     * The PSI half of the processor's bootstrap collision policy, which
     * {@code BootstrapCollisions} states on the javac side: an author's own
     * method wins and nothing is synthesised beside it.
     *
     * <p>Own methods rather than {@link PsiClass#getMethods()}, and for two
     * reasons. Augmented members are in the latter, so a provider asking it
     * while it is running would see whatever it contributed last and never
     * settle. And the question is about what the author wrote, which is exactly
     * what {@link PsiExtensibleClass#getOwnMethods()} answers.
     *
     * @param target the annotated type
     * @param name the bootstrap name being considered
     * @return whether the author already declares a zero-parameter method of
     *         that name
     */
    private static boolean declaresNullary(PsiClass target, String name) {
        for (PsiMethod method : ownMethods(target)) {
            if (name.equals(method.getName()) && method.getParameterList().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Whether the author already declares a copy factory over the target's own
     * type.
     *
     * <p>The parameter type is what separates a copy factory from an unrelated
     * one-argument method that happens to share its name - a {@code from(String)}
     * parser is not a collision, and treating it as one takes the copy factory
     * away with nothing to say so.
     *
     * @param target the annotated type
     * @param name the copy factory's name
     * @return whether the author already declares it
     */
    private static boolean declaresCopyFactory(PsiClass target, String name) {
        for (PsiMethod method : ownMethods(target)) {
            if (!name.equals(method.getName())) continue;
            PsiParameter[] parameters = method.getParameterList().getParameters();
            if (parameters.length != 1) continue;
            if (parameters[0].getType() instanceof PsiClassType declared
                && target.getManager().areElementsEquivalent(declared.resolve(), target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The methods a class declares itself, excluding anything an augment
     * provider contributed - which is what a provider asking while it runs would
     * otherwise see, including whatever it added last.
     *
     * @param target the class to read
     * @return its own methods
     */
    static List<PsiMethod> ownMethods(PsiClass target) {
        return target instanceof PsiExtensibleClass extensible
            ? extensible.getOwnMethods()
            : List.of(target.getMethods());
    }

    /**
     * Synthesises the all-args constructor the APT pipeline injects, so the
     * editor resolves a same-package {@code new Target(...)} before the first
     * {@code javac} round. Parameter order and types mirror
     * {@code AllArgsConstructorFactory}, including the {@code Supplier<T>}
     * rewrite {@code @Lazy} fields receive.
     *
     * <p>Each parameter carries the backing field's nullness, because the class
     * file javac produces does - a PSI copy without it puts IntelliJ's own
     * nullability inspections at odds with the compiled result. The
     * {@code @Lazy} parameter is the exception, and for the reason the processor
     * makes it one: its type is no longer the field's.
     *
     * @param target the annotated type
     * @param config resolved editor-side builder configuration
     * @return the synthesised constructor, or {@code null} when the target has
     *         no builder-visible fields to pass
     */
    static @Nullable PsiMethod allArgsConstructor(PsiClass target, EditorBuilderConfig config) {
        Project project = target.getProject();
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(project);

        List<PsiFieldShape> fields =
            PsiFieldShapeExtractor.fromClass(target, excludedNames(target), config.setters());
        if (fields.isEmpty()) return null;

        String name = target.getName();
        if (name == null) return null;

        LightMethodBuilder ctor = new LightMethodBuilder(psiManager, name)
            .setConstructor(true)
            .setContainingClass(target);
        for (PsiFieldShape field : fields) {
            PsiType type = field.lazy
                ? elements.createTypeFromText(
                    "java.util.function.Supplier<" + field.type.getCanonicalText() + ">", target)
                : field.type;
            // The field's nullness describes T. A @Lazy parameter is Supplier<T>,
            // whose null is the slot's own sentinel for "never set", so copying
            // @NotNull there would assert the opposite of what the slot means.
            String[] nullness = field.lazy
                ? NO_ANNOTATIONS
                : nullnessFqns(ownField(target, field.name));
            ctor.addParameter(buildParam(ctor, field.name, type, false, nullness));
        }
        applyAccess(ctor, config.constructorAccess());
        GeneratedMemberMarker.mark(ctor);
        ctor.setNavigationElement(target);
        return ctor;
    }

    private static String builderTypeFqn(PsiClass target, EditorBuilderConfig config) {
        String qualified = target.getQualifiedName();
        String base = qualified != null ? qualified : target.getName();
        return base + "." + config.builderName();
    }

    /**
     * A class type with the given type parameters applied, or the plain type
     * when there are none. Which parameters to pass matters: a member declared
     * inside the synth Builder must use the <em>Builder's</em> copies, while a
     * static member on the target uses its own method-level copies - the
     * target's own parameters are out of scope in a static context and would
     * leave the type unsubstitutable at the call site.
     */
    /**
     * A substitutor rewriting {@code from}'s type parameters into {@code to}'s,
     * positionally. Used to re-express the target's declared field types in the
     * synth Builder's own parameters.
     *
     * @param elements the element factory
     * @param from the parameters appearing in the types to rewrite
     * @param to the parameters to rewrite them into
     * @return the substitutor, empty when either side has no parameters
     */
    private static PsiSubstitutor remap(PsiElementFactory elements,
                                        PsiTypeParameter[] from, PsiTypeParameter[] to) {
        PsiSubstitutor out = PsiSubstitutor.EMPTY;
        for (int i = 0; i < from.length && i < to.length; i++) {
            out = out.put(from[i], elements.createType(to[i]));
        }
        return out;
    }

    private static PsiClassType applied(PsiElementFactory elements, PsiClass cls,
                                        PsiTypeParameter[] arguments) {
        if (arguments.length == 0) return elements.createType(cls);
        PsiType[] args = new PsiType[arguments.length];
        for (int i = 0; i < arguments.length; i++) args[i] = elements.createType(arguments[i]);
        return elements.createType(cls, args);
    }

    /**
     * A {@code static} bootstrap on a generic target declares its own copies of
     * the target's type parameters, so the caller can infer or witness them -
     * {@code static <V> Builder<V> builder()}. The return type is built after
     * the copies exist, since it has to reference them rather than the target's.
     */
    private static PsiMethod buildStaticNoArg(PsiManager manager, PsiElementFactory elements,
                                              PsiClass target, PsiClass builderClass,
                                              String name, String access) {
        DocProxyingLightMethodBuilder m = new DocProxyingLightMethodBuilder(manager, name);
        m.withTypeParameters(target.getTypeParameters());
        m.setMethodReturnType(applied(elements, builderClass, m.getTypeParameters()))
            .addModifier(PsiModifier.STATIC)
            .setContainingClass(target);
        applyAccess(m, access);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    /**
     * The entry point for an executable target: {@code static Builder builder()},
     * carrying one parameter per {@code @BuilderSeed} slot.
     *
     * <p>A seeded slot has no setter, so this is the only place its value can be
     * given - which is what makes it required rather than optional. The
     * parameters carry no nullness of their own, matching the processor, which
     * forwards them verbatim to the builder's constructor.
     *
     * <p>Type parameters come from the site rather than from the class: a
     * {@code static} factory runs under its own and cannot name the enclosing
     * type's, so the seeds' declared types have to be re-expressed in this
     * method's copies before they mean anything at a call site.
     */
    private static PsiMethod buildEntryPoint(PsiManager manager, PsiElementFactory elements,
                                             BuilderSite site, PsiClass builderClass,
                                             String name, String access) {
        DocProxyingLightMethodBuilder m = new DocProxyingLightMethodBuilder(manager, name);
        m.withTypeParameters(site.typeParameterSource());
        PsiTypeParameter[] own = m.getTypeParameters();
        m.setMethodReturnType(applied(elements, builderClass, own))
            .addModifier(PsiModifier.STATIC)
            .setContainingClass(site.owner());
        PsiSubstitutor toMethod = remap(elements, site.typeParameterSource(), own);
        for (PsiFieldShape slot : PsiFieldShapeExtractor.fromExecutable(
            site.executable(), toMethod, SetterScheme.of(NamingStyle.SIMPLIFIED))) {
            if (!slot.seed) continue;
            m.addParameter(buildParam(m, slot.name, slot.type, false));
        }
        applyAccess(m, access);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(site.executable());
        return m;
    }

    private static PsiMethod buildStaticOneArg(PsiManager manager, PsiElementFactory elements,
                                               PsiClass target, PsiClass builderClass,
                                               String name, String paramName, String access) {
        DocProxyingLightMethodBuilder m = new DocProxyingLightMethodBuilder(manager, name);
        m.withTypeParameters(target.getTypeParameters());
        PsiTypeParameter[] own = m.getTypeParameters();
        m.setMethodReturnType(applied(elements, builderClass, own))
            .addModifier(PsiModifier.STATIC)
            .setContainingClass(target);
        m.addParameter(buildParam(m, paramName, applied(elements, target, own), false, NOT_NULL_FQN));
        applyAccess(m, access);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    /**
     * Builds a {@link LightParameter} carrying the given annotation FQNs on its
     * modifier list. Platform's {@link LightModifierList#addAnnotation(String)}
     * throws {@link IncorrectOperationException}, so this
     * helper hands the parameter a custom modifier list ({@link AnnotatedLightModifierList})
     * that stores pre-built {@link PsiAnnotation}s from
     * {@link PsiElementFactory#createAnnotationFromText}. Only FQN-only
     * (no-attribute) annotations are passed through this path; attribute-
     * bearing annotations ({@code @XContract} / {@code @Contract}) are
     * delivered at query time by
     * {@link ClassBuilderInferredAnnotationProvider}.
     *
     * <p>{@code declarationScope} must be the {@link PsiMethod}
     * the parameter belongs to (matches what
     * {@code LightMethodBuilder.addParameter(name, type)} does internally).
     * Passing the containing class instead causes IntelliJ 233+ to silently
     * filter the whole synthetic method out during PSI enumeration.
     *
     * @param declarationScope the method the parameter belongs to
     * @param name the parameter name
     * @param type the parameter type, before any varargs wrapping
     * @param varargs whether the parameter is the trailing varargs slot
     * @param annotationFqns the no-attribute annotations to attach
     * @return the parameter, carrying the annotations on its modifier list and,
     *         for the nullness pair, on its type
     */
    public static LightParameter buildParam(PsiMethod declarationScope, String name, PsiType type,
                                            boolean varargs, String... annotationFqns) {
        PsiManager manager = declarationScope.getManager();
        AnnotatedLightModifierList modifiers = new AnnotatedLightModifierList(manager, JavaLanguage.INSTANCE);
        List<PsiAnnotation> typeUseAnnotations = new ArrayList<>(annotationFqns.length);
        if (annotationFqns.length > 0) {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(declarationScope.getProject());
            for (String fqn : annotationFqns) {
                try {
                    PsiAnnotation annotation = factory.createAnnotationFromText("@" + fqn, declarationScope);
                    // Nullability annotations (@NotNull / @Nullable) also ride on
                    // the type so JavaDocumentationProvider's brief hover shows
                    // them (it renders each param via generateType(..., annotated=true),
                    // which walks the TYPE's annotations, not the modifier list).
                    // Declaration-only annotations like @PrintFormat stay only on
                    // the modifier list - attaching them to the type too makes the
                    // hover show them twice.
                    if (NOT_NULL_FQN.equals(fqn) || NULLABLE_FQN.equals(fqn)) {
                        typeUseAnnotations.add(annotation);
                    }
                    modifiers.add(fqn, annotation);
                } catch (Exception ignored) {
                    // Unresolvable FQN in this project's classpath - skip silently
                    // rather than break synthesis.
                }
            }
        }

        // Varargs params need PsiEllipsisType wrapping the component type, not
        // the raw component itself. Platform's LightMethodBuilder.addParameter
        // (name, type, isVarArgs) does this wrap internally; our direct
        // LightParameter construction must do it by hand. Without the wrap the
        // parameter's getType() returns Object, so calls with more than one
        // vararg argument resolve to a different overload (or fail).
        PsiType shapedType = varargs && !(type instanceof PsiEllipsisType)
            ? new PsiEllipsisType(type)
            : type;

        PsiType effectiveType = typeUseAnnotations.isEmpty()
            ? shapedType
            : shapedType.annotate(TypeAnnotationProvider.Static.create(
                typeUseAnnotations.toArray(PsiAnnotation.EMPTY_ARRAY)));

        return new LightParameter(name, effectiveType, declarationScope, JavaLanguage.INSTANCE, modifiers, varargs);
    }


    private static PsiMethod buildInstanceNoArg(PsiManager manager, PsiClass target,
                                                String name, PsiType returnType, String access) {
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .setContainingClass(target);
        applyAccess(m, access);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    /**
     * Adds the PUBLIC/PROTECTED/PRIVATE modifier to a light-method builder when
     * the access level requires one; PACKAGE-private is represented by the
     * absence of any of those three modifiers.
     */
    private static void applyAccess(LightMethodBuilder m, String access) {
        if (!access.isEmpty()) m.addModifier(access);
    }

    /**
     * Synthesises the empty nested {@code Builder} shell so
     * {@code Target.Builder} references resolve before the first javac round.
     * Methods are produced lazily by {@link #synthesizeBuilderMethods} when
     * the augment provider is re-entered for the synth class.
     *
     * <p>This pattern mirrors Lombok's {@code LombokLightClassBuilder}: in
     * IntelliJ 2023.3+, the IDE consults
     * {@link PsiAugmentProvider#collectAugments}
     * for the inner class's members rather than reading the
     * {@code LightPsiClassBuilder.myMethods} field that {@code addMethod}
     * populates. Pre-attaching methods to that field leaves them invisible
     * to completion / structure-view / inferred annotations. Routing all
     * paths through the augment provider's re-entry is the only way to
     * keep things in sync.
     */
    static PsiClass synthesizeBuilderClass(BuilderSite site, EditorBuilderConfig config) {
        PsiClass target = site.owner();
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(target.getProject());
        ChainRole role = roleOf(site);

        GeneratedBuilderClass builder =
            new GeneratedBuilderClass(target, config.builderName(), site.typeParameterSource());
        if (!config.access().isEmpty()) builder.getModifierList().addModifier(config.access());
        builder.getModifierList().addModifier(PsiModifier.STATIC);
        if (role.isSelfTyped()) {
            builder.getModifierList().addModifier(PsiModifier.ABSTRACT);
            addSelfTypeParameters(elements, target, builder);
        }
        if (role.hasAnnotatedSuper()) {
            applySuperBuilder(elements, target, builder, role);
        }
        builder.setContainingClass(target);
        builder.setNavigationElement(target);
        GeneratedMemberMarker.mark(builder);
        return builder;
    }

    /**
     * Where the target sits in a SuperBuilder chain.
     *
     * <p>An executable target is never in one: a constructor has no chain to
     * find, so the processor's third path never looks for an annotated super and
     * neither does this. Asking {@link ChainRole#of} anyway would read the
     * enclosing class's own shape - abstract, or extending an annotated parent -
     * and synthesise a self-typed Builder javac does not emit.
     *
     * @param site where the annotation is written
     * @return the chain role, {@link ChainRole#STANDALONE} for an executable target
     */
    private static ChainRole roleOf(BuilderSite site) {
        return site.isExecutable() ? ChainRole.STANDALONE : ChainRole.of(site.owner());
    }

    /**
     * Appends {@code <T extends Target, B extends Builder<T, B>>} to a
     * self-typed Builder. {@code B}'s bound names the Builder being built, so
     * both parameters have to exist before either bound can be attached.
     *
     * <p>The names dodge whatever the target declares, since a generic target
     * may itself use {@code T} or {@code B} - the same collision the APT side
     * resolves in {@code MutationContext.selfTypeName}.
     */
    private static void addSelfTypeParameters(PsiElementFactory elements, PsiClass target,
                                              GeneratedBuilderClass builder) {
        Set<String> taken = new HashSet<>();
        for (PsiTypeParameter tp : target.getTypeParameters()) taken.add(tp.getName());
        PsiTypeParameter[] ownCopies = builder.getTypeParameters();

        LightTypeParameterBuilder t = builder.addTypeParameter(freeTypeParamName("T", taken));
        if (t == null) return;
        taken.add(t.getName());
        LightTypeParameterBuilder b = builder.addTypeParameter(freeTypeParamName("B", taken));
        if (b == null) return;

        t.getExtendsList().addReference(applied(elements, target, ownCopies));
        // B extends Builder<own..., T, B>
        PsiType[] args = new PsiType[ownCopies.length + 2];
        for (int i = 0; i < ownCopies.length; i++) args[i] = elements.createType(ownCopies[i]);
        args[ownCopies.length] = elements.createType(t);
        args[ownCopies.length + 1] = elements.createType(b);
        b.getExtendsList().addReference(elements.createType(builder, args));
    }

    /**
     * Points a chain link's Builder at its parent's synthesised Builder. The
     * leading arguments are whatever the target passes to its superclass, so
     * {@code class StringBox extends Box<String>} yields
     * {@code extends Box.Builder<String, StringBox, StringBox.Builder>}; the two
     * trailing self-type arguments bind on a concrete link and forward on a
     * chained abstract.
     *
     * <p>The parent's Builder is resolved through the augment provider rather
     * than by name, so the reference points at the same synth instance the
     * platform hands out elsewhere. Re-entering for the <em>parent</em> is safe:
     * the recursion guard is keyed per target, and a root has no super of its
     * own to walk to.
     */
    private static void applySuperBuilder(PsiElementFactory elements, PsiClass target,
                                          GeneratedBuilderClass builder, ChainRole role) {
        PsiClass parent = ChainRole.annotatedSuperOf(target);
        if (parent == null) return;
        PsiClass parentBuilder = synthBuilderOf(parent);
        if (parentBuilder == null) return;

        PsiTypeParameter[] ownCopies = ownTypeParameters(builder, target.getTypeParameters().length);
        // Field types and superclass arguments are written in the target's type
        // parameters; re-express them in this Builder's copies.
        PsiSubstitutor toBuilder = remap(elements, target.getTypeParameters(), ownCopies);

        List<PsiType> args = new ArrayList<>();
        for (PsiClassType superType : target.getExtendsList() == null
            ? PsiClassType.EMPTY_ARRAY
            : target.getExtendsList().getReferencedTypes()) {
            for (PsiType arg : superType.getParameters()) args.add(toBuilder.substitute(arg));
        }
        if (role.isSelfTyped()) {
            PsiTypeParameter[] selfTypes = selfTypeParameters(builder, target.getTypeParameters().length);
            if (selfTypes.length != 2) return;
            args.add(elements.createType(selfTypes[0]));
            args.add(elements.createType(selfTypes[1]));
        } else {
            args.add(applied(elements, target, ownCopies));
            args.add(applied(elements, builder, ownCopies));
        }
        if (args.size() != parentBuilder.getTypeParameters().length) return;
        builder.setSuperType(elements.createType(parentBuilder, args.toArray(PsiType.EMPTY_ARRAY)));
    }

    /**
     * The parent's synthesised Builder, obtained through the augment-aware
     * {@code getInnerClasses()} so a hand-written nested Builder on the parent
     * is found too.
     */
    private static @Nullable PsiClass synthBuilderOf(PsiClass parent) {
        PsiAnnotation annotation = PsiFieldShapeExtractor.classBuilderAnnotation(parent);
        if (annotation == null) return null;
        String name = EditorBuilderConfig.fromAnnotation(annotation).builderName();
        for (PsiClass nested : parent.getInnerClasses()) {
            if (name.equals(nested.getName())) return nested;
        }
        return null;
    }

    /** The Builder's copies of the target's own parameters, excluding any self-types. */
    private static PsiTypeParameter[] ownTypeParameters(PsiClass builder, int own) {
        PsiTypeParameter[] all = builder.getTypeParameters();
        if (all.length <= own) return all;
        return java.util.Arrays.copyOfRange(all, 0, own);
    }

    /** The trailing {@code T} / {@code B} parameters on a self-typed Builder. */
    private static PsiTypeParameter[] selfTypeParameters(PsiClass builder, int own) {
        PsiTypeParameter[] all = builder.getTypeParameters();
        if (all.length != own + 2) return PsiTypeParameter.EMPTY_ARRAY;
        return java.util.Arrays.copyOfRange(all, own, all.length);
    }

    /** Appends {@code $} until the name is not one the target already declares. */
    private static String freeTypeParamName(String preferred, Set<String> taken) {
        String candidate = preferred;
        while (taken.contains(candidate)) candidate = candidate + "$";
        return candidate;
    }

    /**
     * Builds the setters and {@code build()} that live on the synth Builder
     * class. Called by
     * {@link ClassBuilderAugmentProvider#getAugments} when the IDE asks
     * "what are the augmented methods of {@code Target.Builder}?".
     */
    static List<PsiMethod> synthesizeBuilderMethods(BuilderSite site, EditorBuilderConfig config,
                                                    PsiClass builder) {
        PsiClass target = site.owner();
        Project project = target.getProject();
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(project);

        // Use createType(builder) rather than createTypeFromText(FQN) so the
        // self-type resolves directly to our synth class instance, bypassing
        // the inner-class lookup chain that fails the access-check pass during
        // cross-package highlighting.
        //
        // Both types are applied to the BUILDER's type parameters, not the
        // target's: these members are declared inside the synth Builder, which
        // is static and carries its own copies. Using the target's would leave
        // the setter chain returning a type nothing can substitute, and
        // build() yielding a raw target whose getters read as Object.
        ChainRole role = roleOf(site);
        PsiTypeParameter[] sourceParams = site.typeParameterSource();
        PsiTypeParameter[] ownParams = ownTypeParameters(builder, sourceParams.length);

        // Slot types are declared in the source's type parameters; re-express
        // them in the Builder's copies so a Builder<String> receiver actually
        // substitutes them.
        PsiSubstitutor toBuilder = remap(elements, sourceParams, ownParams);

        // On a self-typed Builder the setters return the B parameter, so a
        // subclass builder flows through inherited setters as its own type and
        // the chain can be called in any order. Elsewhere they return the
        // Builder itself.
        PsiClassType selfType;
        PsiType targetType;
        if (role.isSelfTyped()) {
            PsiTypeParameter[] selfTypes = selfTypeParameters(builder, sourceParams.length);
            selfType = selfTypes.length == 2
                ? elements.createType(selfTypes[1])
                : applied(elements, builder, ownParams);
            targetType = selfTypes.length == 2
                ? elements.createType(selfTypes[0])
                : applied(elements, target, ownParams);
        } else {
            selfType = applied(elements, builder, ownParams);
            targetType = builtType(elements, site, ownParams, toBuilder);
        }

        List<PsiFieldShape> fields = slotsOf(site, toBuilder, config.setters());

        SetterCtx ctx = new SetterCtx(psiManager, elements, target, builder, selfType, config);
        List<PsiMethod> methods = new ArrayList<>();
        for (PsiFieldShape field : fields) {
            // A seeded slot is supplied to builder(...) and is final from there
            // on, so every setter shape would write over a committed value.
            if (field.seed) continue;
            methods.addAll(settersFor(ctx, field));
        }

        // self() exists only inside a chain: abstract on a self-typed Builder,
        // overridden to return this on a concrete link. A standalone builder
        // chains on its own type and needs none.
        if (role.isSelfTyped()) {
            methods.add(chainMethod(psiManager, target, builder, "self", selfType,
                PsiModifier.PROTECTED, true));
        } else if (role == ChainRole.CONCRETE_LINK) {
            methods.add(chainMethod(psiManager, target, builder, "self", selfType,
                PsiModifier.PROTECTED, false));
        }

        // build() - always public (access attribute governs the enclosing
        // builder class + bootstraps, not the terminal build method). Abstract
        // on a self-typed Builder, where each concrete link produces its own T.
        methods.add(chainMethod(psiManager, target, builder, config.buildMethodName(), targetType,
            PsiModifier.PUBLIC, role.isSelfTyped()));

        // The builder's own constructor, declared rather than left implicit for
        // the reason the processor declares it: an implicit one takes the
        // class's access, so a public builder class would offer
        // `new Target.Builder()` as a second entry point the processor no longer
        // publishes. Without this the editor resolves a cross-package call that
        // javac then refuses.
        methods.add(builderConstructor(psiManager, builder, config.builderConstructorAccess(), fields));

        return methods;
    }

    /**
     * The slots the builder is built from - the target's fields or record
     * components, or the annotated member's parameters.
     *
     * @param site where the annotation is written
     * @param toBuilder mapping into the synth Builder's own type parameters
     * @return the slot shapes, in declaration order
     */
    private static List<PsiFieldShape> slotsOf(BuilderSite site, PsiSubstitutor toBuilder,
                                               SetterScheme setters) {
        if (site.isExecutable()) {
            return PsiFieldShapeExtractor.fromExecutable(site.executable(), toBuilder, setters);
        }
        PsiClass target = site.owner();
        Set<String> excluded = excludedNames(target);
        return target.isRecord()
            ? PsiFieldShapeExtractor.fromRecord(target, excluded, toBuilder, setters)
            : PsiFieldShapeExtractor.fromClass(target, excluded, toBuilder, setters);
    }

    /**
     * The type {@code build()} returns.
     *
     * <p>A {@code static} factory is the one case where this is not the target:
     * it hands back whatever it declares, under its own type parameters, which
     * have to be re-expressed in the Builder's copies before a call site can
     * substitute them.
     */
    private static PsiType builtType(PsiElementFactory elements, BuilderSite site,
                                     PsiTypeParameter[] ownParams, PsiSubstitutor toBuilder) {
        if (!site.isStaticFactory()) return applied(elements, site.owner(), ownParams);
        PsiType declared = site.executable().getReturnType();
        return declared == null
            ? applied(elements, site.owner(), ownParams)
            : toBuilder.substitute(declared);
    }

    /**
     * The synth Builder's constructor, at the configured access and carrying one
     * parameter per seeded slot - the only way a slot with no setter is filled.
     */
    private static PsiMethod builderConstructor(PsiManager manager, PsiClass builder, String access,
                                                List<PsiFieldShape> slots) {
        LightMethodBuilder ctor = new LightMethodBuilder(manager, JavaLanguage.INSTANCE, builder.getName());
        ctor.setConstructor(true);
        ctor.setContainingClass(builder);
        ctor.setNavigationElement(builder);
        if (!access.isEmpty()) ctor.addModifier(access);
        for (PsiFieldShape slot : slots) {
            if (slot.seed) ctor.addParameter(buildParam(ctor, slot.name, slot.type, false));
        }
        GeneratedMemberMarker.mark(ctor);
        return ctor;
    }

    /** A nullary method on the synth Builder, optionally abstract. */
    private static PsiMethod chainMethod(PsiManager manager, PsiClass target, PsiClass builder,
                                         String name, PsiType returnType, String access,
                                         boolean isAbstract) {
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .addModifier(access)
            .setContainingClass(builder);
        if (isAbstract) m.addModifier(PsiModifier.ABSTRACT);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    // ------------------------------------------------------------------
    // Setter dispatch
    // ------------------------------------------------------------------

    /**
     * Mirrors the dispatch in {@code FieldMutators.setters}: picks one or
     * more shape-specific setter methods per field.
     */
    private static List<PsiMethod> settersFor(SetterCtx ctx, PsiFieldShape field) {
        List<PsiMethod> out = new ArrayList<>();
        if (field.lazy) {
            out.add(lazyValueSetter(ctx, field));
            out.add(lazySupplierSetter(ctx, field));
            return out;
        }
        if (field.isBoolean) {
            // Typed setter is the ordinary `set` role; the zero-arg form is the
            // separate `flag` role and drops out when a style suppresses it.
            if (field.setters.emitsFlag()) out.add(booleanZeroArg(ctx, field, field.name, false));
            out.add(booleanTyped(ctx, field, field.name));
            if (field.negateName != null && !field.negateName.isEmpty()) {
                if (field.setters.emitsFlag()) out.add(booleanZeroArg(ctx, field, field.negateName, true));
                out.add(booleanTyped(ctx, field, field.negateName));
            }
        } else if (field.isOptional) {
            out.add(optionalNullableRaw(ctx, field));
            out.add(optionalWrapped(ctx, field));
            if (field.formattable && field.isOptionalString) {
                out.add(optionalFormattable(ctx, field));
            }
        } else if (field.isArray) {
            out.add(arrayVarargs(ctx, field));
        } else if ((field.isListLike || field.isMap) && field.collector) {
            if (field.isMap) {
                out.add(singularMapReplace(ctx, field));
                if (field.singular && field.setters.emitsPut()) out.add(singularMapPut(ctx, field));
                if (field.compute && field.setters.emitsCompute()) out.add(singularMapPutIfAbsent(ctx, field));
            } else {
                out.add(singularCollectionVarargsReplace(ctx, field));
                out.add(singularCollectionIterableReplace(ctx, field));
                if (field.singular && field.setters.emitsAdd()) out.add(singularCollectionAdd(ctx, field));
            }
            if (field.clearable && field.setters.emitsClear()) out.add(singularClear(ctx, field));
            if (field.removable && field.setters.emitsRemove()) out.add(singularRemove(ctx, field));
        } else if (field.isString && field.formattable) {
            out.add(plainSetter(ctx, field));
            out.add(stringFormattable(ctx, field));
        } else {
            out.add(plainSetter(ctx, field));
        }
        appendAssignViaOverloads(ctx, field, out);
        return out;
    }

    /**
     * Appends one setter per {@code @AssignVia} taking a type of its own,
     * mirroring {@code FieldMutators.appendAssignViaOverloads}. A direct
     * transform contributes nothing here, changing only what the setter's body
     * does; a {@code @Collector} slot contributes nothing either, the processor
     * rejecting that pairing outright.
     */
    private static void appendAssignViaOverloads(SetterCtx ctx, PsiFieldShape field,
                                                 List<PsiMethod> out) {
        if (field.collector) return;
        for (PsiFieldShape.AssignTransform transform : field.assignVia) {
            if (transform.direct()) continue;
            LightMethodBuilder m = newSetter(ctx, field,
                field.setters.setName(field.name, field.isBoolean));
            m.addParameter(buildParam(m, field.name, transform.paramType(), false));
            out.add(m);
        }
    }

    // ------------------------------------------------------------------
    // @Lazy shapes
    // ------------------------------------------------------------------

    /** {@code Builder withFoo(T value)} - eager value form for a @Lazy field. */
    private static PsiMethod lazyValueSetter(SetterCtx ctx, PsiFieldShape field) {
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, field.type, false, primaryNullability(field)));
        return m;
    }

    /** {@code Builder withFoo(Supplier<T> supplier)} - true lazy form for a @Lazy field. */
    private static PsiMethod lazySupplierSetter(SetterCtx ctx, PsiFieldShape field) {
        PsiType supplierType = ctx.elements.createTypeFromText(
            "java.util.function.Supplier<" + field.type.getCanonicalText() + ">", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, supplierType, false, NOT_NULL_FQN));
        return m;
    }

    // ------------------------------------------------------------------
    // Plain / boolean / array shapes
    // ------------------------------------------------------------------

    private static PsiMethod plainSetter(SetterCtx ctx, PsiFieldShape field) {
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, field.type, false, primaryNullability(field)));
        return m;
    }

    private static PsiMethod arrayVarargs(SetterCtx ctx, PsiFieldShape field) {
        // fall back to plain setter if the component type is unknown
        PsiType component = field.arrayComponent != null ? field.arrayComponent : PsiTypes.nullType();
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, component, true, primaryNullability(field)));
        return m;
    }

    private static PsiMethod booleanZeroArg(SetterCtx ctx, PsiFieldShape field, String methodBase, boolean inverse) {
        return newSetter(ctx, field, field.setters.flagName(methodBase));
    }

    private static PsiMethod booleanTyped(SetterCtx ctx, PsiFieldShape field, String methodBase) {
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(methodBase, true));
        m.addParameter(buildParam(m, methodBase, PsiTypes.booleanType(), false));
        return m;
    }

    // ------------------------------------------------------------------
    // Optional shapes
    // ------------------------------------------------------------------

    /** {@code Builder withX(T x)} - nullable-raw overload for an Optional field. */
    private static PsiMethod optionalNullableRaw(SetterCtx ctx, PsiFieldShape field) {
        PsiType inner = field.optionalInner != null
            ? field.optionalInner
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        // Raw overload wraps via Optional.ofNullable - null is explicitly allowed
        // regardless of whether the field carries @BuildFlag(nonNull).
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, inner, false, NULLABLE_FQN));
        return m;
    }

    /** {@code Builder withX(Optional<T> x)} - wrapped overload for an Optional field. */
    private static PsiMethod optionalWrapped(SetterCtx ctx, PsiFieldShape field) {
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, field.type, false, NOT_NULL_FQN));
        return m;
    }

    // ------------------------------------------------------------------
    // @Formattable shapes
    // ------------------------------------------------------------------

    /** {@code Builder withName(@PrintFormat String format, Object... args)} - String @Formattable. */
    private static PsiMethod stringFormattable(SetterCtx ctx, PsiFieldShape field) {
        PsiType stringType = ctx.elements.createTypeFromText("java.lang.String", ctx.target);
        PsiType objectType = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        // @PrintFormat on the format arg always. Nullability mirrors the
        // field: @NotNull when @BuildFlag(nonNull) or field-level @NotNull,
        // @Nullable when field-level @Nullable, otherwise neither (neutral
        // default - don't impose a nullability the user didn't ask for).
        // Varargs always @Nullable.
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        String[] formatAnnotations = prepend(PRINT_FORMAT_FQN, primaryNullability(field));
        m.addParameter(buildParam(m, field.name, stringType, false, formatAnnotations));
        m.addParameter(buildParam(m, "args", objectType, true, NULLABLE_FQN));
        return m;
    }

    /** {@code Builder withDescription(@PrintFormat @Nullable String format, Object... args)} - Optional<String> @Formattable. */
    private static PsiMethod optionalFormattable(SetterCtx ctx, PsiFieldShape field) {
        PsiType stringType = ctx.elements.createTypeFromText("java.lang.String", ctx.target);
        PsiType objectType = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        // Mirrors library FieldMutators.optionalFormattable: format is always
        // @Nullable because the generated setter stores a null format as-is.
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, stringType, false, PRINT_FORMAT_FQN, NULLABLE_FQN));
        m.addParameter(buildParam(m, "args", objectType, true, NULLABLE_FQN));
        return m;
    }

    // ------------------------------------------------------------------
    // @Collector shapes
    // ------------------------------------------------------------------

    /** {@code Builder withEntries(T... entries)} - replace-collection varargs form. */
    private static PsiMethod singularCollectionVarargsReplace(SetterCtx ctx, PsiFieldShape field) {
        PsiType element = field.collectionElement != null
            ? field.collectionElement
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, element, true, primaryNullability(field)));
        return m;
    }

    /** {@code Builder withEntries(Iterable<T> entries)} - replace-collection iterable form. */
    private static PsiMethod singularCollectionIterableReplace(SetterCtx ctx, PsiFieldShape field) {
        String elementText = field.collectionElement != null
            ? field.collectionElement.getCanonicalText()
            : "java.lang.Object";
        PsiType iterableType = ctx.elements.createTypeFromText(
            "java.lang.Iterable<" + elementText + ">", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, iterableType, false, primaryNullability(field)));
        return m;
    }

    /** {@code Builder addEntry(T entry)} - append one element to the existing collection. */
    private static PsiMethod singularCollectionAdd(SetterCtx ctx, PsiFieldShape field) {
        String name = field.setters.addName(field.singularName);
        PsiType element = field.collectionElement != null
            ? field.collectionElement
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, name);
        m.addParameter(buildParam(m, field.singularName, element, false, primaryNullability(field)));
        return m;
    }

    /** {@code Builder withEntries(Map<K, V> entries)} - replace with fresh LinkedHashMap. */
    private static PsiMethod singularMapReplace(SetterCtx ctx, PsiFieldShape field) {
        LightMethodBuilder m = newSetter(ctx, field, field.setters.setName(field.name, field.isBoolean));
        m.addParameter(buildParam(m, field.name, field.type, false, primaryNullability(field)));
        return m;
    }

    /**
     * {@code Builder putEntry(K key, V value)} - put a single entry into the
     * existing map, or {@code Builder putEntry(V value)} when
     * {@code @Collector(key)} says the value supplies its own key. The one
     * collector opt-in that moves a signature, so the editor has to follow it.
     */
    private static PsiMethod singularMapPut(SetterCtx ctx, PsiFieldShape field) {
        String name = field.setters.putName(field.singularName);
        PsiType keyType = field.mapKey != null
            ? field.mapKey
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        PsiType valueType = field.mapValue != null
            ? field.mapValue
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, name);
        if (field.keyMethod == null) {
            m.addParameter(buildParam(m, "key", keyType, false, primaryNullability(field)));
        }
        m.addParameter(buildParam(m, "value", valueType, false, primaryNullability(field)));
        return m;
    }

    /**
     * {@code Builder removeEntry(T entry)} on a collection or
     * {@code Builder removeEntry(K key)} on a map - one element or entry back
     * out. Gated on {@code @Collector(removable = true)}.
     */
    private static PsiMethod singularRemove(SetterCtx ctx, PsiFieldShape field) {
        String name = field.setters.removeName(field.singularName);
        PsiType subject = field.isMap ? field.mapKey : field.collectionElement;
        if (subject == null) subject = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, name);
        m.addParameter(buildParam(m, field.singularName, subject, false, primaryNullability(field)));
        return m;
    }

    /**
     * {@code Builder putEntryIfAbsent(K key, Supplier<V> valueSupplier)} -
     * puts only when the map doesn't already contain the key, calling the
     * supplier lazily for the value. Gated on {@code @Collector(compute = true)}.
     */
    private static PsiMethod singularMapPutIfAbsent(SetterCtx ctx, PsiFieldShape field) {
        String name = field.setters.computeName(field.singularName);
        PsiType keyType = field.mapKey != null
            ? field.mapKey
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        String valueText = field.mapValue != null
            ? field.mapValue.getCanonicalText()
            : "java.lang.Object";
        PsiType supplierType = ctx.elements.createTypeFromText(
            "java.util.function.Supplier<" + valueText + ">", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, name);
        m.addParameter(buildParam(m, "key", keyType, false, primaryNullability(field)));
        m.addParameter(buildParam(m, "valueSupplier", supplierType, false, NOT_NULL_FQN));
        return m;
    }

    /** {@code Builder clearEntries()} - empties the underlying collection or map. */
    private static PsiMethod singularClear(SetterCtx ctx, PsiFieldShape field) {
        String name = field.setters.clearName(field.name);
        return newSetter(ctx, field, name);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Shared half-built setter: public, returns the nested Builder self-type,
     * lives on the synthesised builder class, navigates to the backing field
     * so Ctrl-click jumps to the right place, and exposes the field's Javadoc
     * as the setter's Javadoc so Ctrl-Q / brief-hover show the field doc on
     * the setter call. Callers chain {@code addParameter} calls then hand
     * the builder back; {@link DocProxyingLightMethodBuilder} doubles as the
     * resulting {@link PsiMethod}.
     */
    private static DocProxyingLightMethodBuilder newSetter(SetterCtx ctx, PsiFieldShape field, String name) {
        DocProxyingLightMethodBuilder m = (DocProxyingLightMethodBuilder) new DocProxyingLightMethodBuilder(ctx.manager, name)
            .setMethodReturnType(ctx.selfType)
            .addModifier(PsiModifier.PUBLIC)
            .setContainingClass(ctx.builder);
        m.withDocSource(field != null ? field.docSource : null);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(field != null && field.docSource != null ? field.docSource : ctx.target);
        return m;
    }

    /**
     * Returns the nullability FQN the primary setter parameter should carry.
     * Precedence:
     * <ol>
     *   <li>{@code @BuildFlag(nonNull=true)} - enforced at {@code build()},
     *       overrides any source-level annotation.</li>
     *   <li>Field-level {@code @NotNull} (any recognised variant) - propagates
     *       unchanged to the setter parameter.</li>
     *   <li>Field-level {@code @Nullable} (any recognised variant) - same.</li>
     *   <li>No annotation - default varargs-friendly shape.</li>
     * </ol>
     * Returns an empty array when no annotation applies, which
     * {@link #buildParam} accepts as a no-op.
     */
    private static String[] primaryNullability(PsiFieldShape field) {
        if (field.nonNullByBuildFlag) return new String[] {NOT_NULL_FQN};
        if (field.notNull) return new String[] {NOT_NULL_FQN};
        if (field.nullable) return new String[] {NULLABLE_FQN};
        return new String[0];
    }

    /**
     * The nullness a generated constructor parameter inherits from its backing
     * field, as FQNs {@link #buildParam} can attach.
     *
     * <p>Deliberately not {@link #primaryNullability}, which is the builder
     * setter's rule and folds in {@code @BuildFlag(nonNull)}. That constraint is
     * enforced by the validator {@code build()} calls on the finished object,
     * not by the constructor, so the constructor's parameter carries nothing for
     * it in the class file and must carry nothing here.
     *
     * <p>Matched on the two JetBrains names exactly, which is the set the
     * processor copies, and matched without resolving - this reads a field, a
     * declaration inside a class body, and a resolve started there re-enters the
     * provider that asked.
     *
     * @param field the backing field, or {@code null} when there is none
     * @return the annotation to attach, empty when the field carries neither
     */
    public static String[] nullnessFqns(@Nullable PsiField field) {
        if (field == null) return NO_ANNOTATIONS;
        PsiModifierList modifiers = field.getModifierList();
        if (modifiers == null) return NO_ANNOTATIONS;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            String fqn = WrittenAnnotations.spelledAmongOnMember(annotation, NOT_NULL_FQN, NULLABLE_FQN);
            if (fqn != null) return new String[] {fqn};
        }
        return NO_ANNOTATIONS;
    }

    /**
     * The field the target itself declares under this name.
     *
     * <p>{@code getOwnFields()} rather than {@code findFieldByName}: the latter
     * is augment-aware and re-enters every provider, this one's caller included.
     */
    private static @Nullable PsiField ownField(PsiClass target, String name) {
        Iterable<PsiField> declared = target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
        for (PsiField field : declared) {
            if (name.equals(field.getName())) return field;
        }
        return null;
    }

    /** Returns an array that prepends {@code head} to {@code tail}. */
    private static String[] prepend(String head, String[] tail) {
        String[] out = new String[tail.length + 1];
        out[0] = head;
        System.arraycopy(tail, 0, out, 1, tail.length);
        return out;
    }

    private static Set<String> excludedNames(PsiClass target) {
        Set<String> out = new HashSet<>();
        PsiAnnotation annotation = PsiFieldShapeExtractor.classBuilderAnnotation(target);
        if (annotation == null) return out;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("exclude");
        if (value instanceof PsiArrayInitializerMemberValue arr) {
            for (PsiAnnotationMemberValue elem : arr.getInitializers()) {
                if (elem instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
                    out.add(s);
                }
            }
        } else if (value instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
            out.add(s);
        }
        return out;
    }

    /** Immutable per-synthesis context passed to every setter-building helper. */
    private record SetterCtx(PsiManager manager, PsiElementFactory elements, PsiClass target,
                             PsiClass builder, PsiClassType selfType,
                             EditorBuilderConfig config) {
    }

    /**
     * Resolved builder configuration from the editor's PSI view of the
     * annotation. Carries every attribute the synthesiser needs to match the
     * AST-mutation output, so the two pipelines stay aligned.
     *
     * <p>{@code access} holds the PSI modifier keyword ({@code "public"},
     * {@code "protected"}, {@code "private"}, or {@code ""} for package-
     * private) so call sites can pass it straight into
     * {@code LightMethodBuilder.addModifier} without a second translation.
     */
    record EditorBuilderConfig(BuilderScheme names, SetterScheme setters,
                               String access, String constructorAccess,
                               String builderConstructorAccess,
                               boolean mergeDeclaredBuilder,
                               String factoryMethod) {
        static EditorBuilderConfig fromAnnotation(PsiAnnotation annotation) {
            NamingStyle style = ClassBuilderConstants.namingStyle(annotation);
            String access = ClassBuilderConstants.accessKeyword(annotation);
            // Package-private default, matching the ctor Lombok @Builder supplies.
            String constructorAccess = ClassBuilderConstants.accessKeyword(annotation,
                ClassBuilderConstants.ATTR_CONSTRUCTOR_ACCESS, "");
            // Same default one level down, so builder() is the one way in.
            String builderConstructorAccess = ClassBuilderConstants.accessKeyword(annotation,
                ClassBuilderConstants.ATTR_BUILDER_CONSTRUCTOR_ACCESS, "");
            boolean mergeDeclaredBuilder = ClassBuilderConstants.booleanAttr(annotation,
                ClassBuilderConstants.ATTR_MERGE_DECLARED_BUILDER, false);
            String factoryMethod = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_FACTORY_METHOD, "");
            return new EditorBuilderConfig(
                ClassBuilderConstants.builderScheme(annotation, style, targetSimpleName(annotation)),
                ClassBuilderConstants.setterScheme(annotation, style),
                access, constructorAccess, builderConstructorAccess, mergeDeclaredBuilder,
                factoryMethod);
        }

        /**
         * Simple name of the type the annotation sits on, which the builder
         * class name is resolved against. Falls back to the empty string for an
         * annotation not attached to a class.
         */
        private static String targetSimpleName(PsiAnnotation annotation) {
            PsiClass owner = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
            String name = owner == null ? null : owner.getName();
            return name == null ? "" : name;
        }

        /** Simple name of the generated builder class. */
        String builderName() {
            return names.type();
        }

        /** Name of the static factory returning a fresh builder, empty when suppressed. */
        String builderMethodName() {
            return names.builder();
        }

        /** Name of the terminal method returning the constructed instance. */
        String buildMethodName() {
            return names.build();
        }

        /** Name of the static copy factory, empty when suppressed. */
        String fromMethodName() {
            return names.from();
        }

        /** Name of the instance seed method, empty when suppressed. */
        String toBuilderMethodName() {
            return names.toBuilder();
        }
    }

}
