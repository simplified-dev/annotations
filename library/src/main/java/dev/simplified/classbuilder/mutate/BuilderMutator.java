package dev.simplified.classbuilder.mutate;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.args.mutate.ArgsConstructorMutator;
import dev.simplified.classbuilder.apt.BuilderConfig;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.lazy.mutate.LazyFieldMutator;
import dev.simplified.lazy.mutate.LazyHolders;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level orchestrator that turns a {@code @ClassBuilder}-annotated type
 * into an injected nested {@code Builder} class plus (later phases) bootstrap
 * methods on the target. Returns {@code true} when mutation succeeded and
 * {@code false} when the caller should fall back to the sibling emitter.
 */
public final class BuilderMutator {

    /** This pass's idempotency key. */
    private static final String PASS = "classbuilder";

    /** The annotation naming the constructor this pass emits. */
    private static final String BUILDER_ARGS_CONSTRUCTOR = "dev.simplified.annotations.BuilderArgsConstructor";

    private final JavacBridge bridge;
    private final Messager messager;

    public BuilderMutator(JavacBridge bridge, Messager messager) {
        this.bridge = bridge;
        this.messager = messager;
    }

    /**
     * Warns that a target handing construction to a factory has its
     * {@code @BuildFlag} constraints checked only as far as the declared type
     * describes them.
     *
     * <p>Constraints are resolved where the builder is generated, so a subtype
     * the factory happens to return carries constraints nothing at that point
     * can enumerate. Saying so is the whole obligation: enforcing a subset in
     * silence is the failure {@code @BuildFlag} exists to prevent.
     */
    private void warnFactoryValidation(TypeElement targetElement, MutationContext ctx) {
        if (!ctx.config().validate() || !ctx.constructsViaFactory()) return;
        if (dev.simplified.classbuilder.apt.BuildFlags.of(targetElement).isEmpty()) return;
        messager.printMessage(Diagnostic.Kind.WARNING,
            "@ClassBuilder(validate = true) with a factory checks only the constraints declared on '"
                + targetElement.getSimpleName()
                + "' - a @BuildFlag on a subtype the factory returns is not enforced",
            targetElement);
    }

    /**
     * Runs the mutation pipeline for a single annotated type.
     *
     * @param targetElement the annotated type
     * @param config resolved builder configuration
     * @param fields per-field IR collected up front
     * @return {@code true} when mutation completed; {@code false} when the
     *         element has no source tree (class-file origin, stub, etc.) and
     *         the caller should fall back
     */
    public boolean mutate(TypeElement targetElement, BuilderConfig config, List<FieldSpec> fields,
                          List<FieldSpec> allFields) {
        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;

        // A second run over a tree this pass already rewrote finds its own
        // output everywhere: the all-args constructor it appended, which it
        // would report as one a written annotation generates, and a declared
        // builder it merged into, on any role, which it would merge into again
        // and report its own members as the author's - appending a second
        // from(T) on a class target, since the element model the collision test
        // reads cannot see a method appended to the tree.
        if (AstMarkers.isPassMarked(target, PASS)) return true;
        AstMarkers.markPass(target, PASS);

        MutationContext ctx = new MutationContext(bridge, targetElement, target, config, fields);

        // Position subsequent tree construction at the target's start so error
        // messages on synthesised members point at the @ClassBuilder declaration.
        bridge.treeMaker().at(target.pos);

        warnUnbuildableCustomCollectors(targetElement, fields);
        warnFactoryValidation(targetElement, ctx);

        boolean isAbstract = targetElement.getModifiers().contains(Modifier.ABSTRACT);
        AnnotatedSuper annotatedSuper = findAnnotatedDirectSuper(targetElement);

        // The build() we emit calls new Target(f1, f2, ...) positionally, and a
        // plain class that declares no constructor gets only javac's no-arg
        // default - so synthesise the matching all-args form. Injected ahead of
        // LazyFieldMutator so a @Lazy field's parameter and assignment are
        // rewritten here exactly as they would be in a hand-written ctor.
        // Beside an author's own build() nothing generated calls it, and it is
        // withheld so javac's no-argument default stays.
        boolean allArgsWithheld = allArgsConstructorWithheld(targetElement, target, ctx, isAbstract, annotatedSuper);
        boolean onlyBuilderConstructor = false;
        if (needsAllArgsConstructor(targetElement, target, ctx, isAbstract, annotatedSuper)) {
            JCMethodDecl ctor = new AllArgsConstructorFactory(ctx).build(
                constructorAccess(targetElement, ctx));
            // A written @AllArgsConstructor whose field set happens to coincide
            // with the builder's has already produced this exact signature in
            // the constructor pass. build() binds to it, and emitting a second
            // would be a duplicate rather than an override.
            if (ArgsConstructorMutator.hasGeneratedConstructor(target, ctor.params)) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "@ClassBuilder used the constructor a written annotation already generates on "
                        + ctx.targetSimpleName() + "; constructorAccess is not applied",
                    targetElement);
            } else {
                bridge.compat().appendDef(target, ctor);
                onlyBuilderConstructor = AllArgsConstructorFactory.onlyBuilderConstructor(target, ctor);
            }
        }

        // @Lazy fields: rewrite storage to a deferred holder, wrap initialisers,
        // adjust matching constructor params + assignments, synthesise
        // memoizing getters. Runs before any other phase (SuperBuilder or
        // regular) so RetainedInitFactory + FieldMutators see the rewritten
        // field tree, and so the synthesised getter is in place before the
        // nested Builder generation considers method-name collisions.
        // allFields, not fields: @Lazy rewrites storage and synthesises a
        // getter, neither of which depends on the builder exposing the field.
        // A @BuilderIgnore'd lazy field keeps its own initializer and is never
        // touched by the constructor, so it behaves exactly as the standalone
        // case does.
        new LazyFieldMutator(ctx.bridge(), targetElement, target, allFields, true, messager).mutate();

        if (isAbstract || annotatedSuper != null) {
            // For SuperBuilder subclasses, the bootstrap from(T) must populate
            // inherited fields too - private fields on the parent Builder are
            // not accessible from the subclass, so we route through the
            // inherited public setters using the full chain of fields.
            List<FieldSpec> chainFields =
                isAbstract ? fields : collectChainFields(ctx, targetElement, fields);
            new SuperBuilderMutator(ctx, messager, annotatedSuper, chainFields).mutate();
            return true;
        }

        // A declared class of the builder's name is merged into below. One this
        // pipeline generated is a builder an earlier run already produced, and
        // merging into it would skip every member by name and report them all.
        JCClassDecl declared = DeclaredBuilderMerge.declaredBuilder(target, ctx.builderName());
        if (declared != null && AstMarkers.isGenerated(declared)) return true;

        // $default$<fieldName>() providers for retained-initializer fields.
        // Must run before the nested Builder is built so FieldMutators'
        // Target.$default$<name>() references resolve at javac attribution.
        new RetainedInitFactory(ctx, messager, allArgsWithheld, onlyBuilderConstructor).appendAll();

        if (declared != null) {
            if (!new DeclaredBuilderMerge(ctx, messager).merge(target, targetElement, declared,
                ChainRole.STANDALONE, new NestedBuilderFactory(ctx).members(), null)) {
                return true;
            }
        } else {
            JCClassDecl nested = new NestedBuilderFactory(ctx).build();
            bridge.compat().appendDef(target, nested);
        }

        new BootstrapMethodFactory(ctx, messager, ctx.fields(), declared).appendAll();
        return true;
    }

    /**
     * Resolves the visibility of the constructor {@code build()} calls.
     *
     * <p>A value written on {@code @BuilderArgsConstructor} wins, then one
     * written as {@code @ClassBuilder(constructorAccess)}, then package-private.
     * Both steps read the written value rather than the effective one - a bare
     * {@code @BuilderArgsConstructor} states nothing about visibility and must
     * not silently overrule a {@code constructorAccess} beside it.
     *
     * @param targetElement the annotated type
     * @param ctx the per-target mutation context
     * @return the resolved access level
     */
    private static AccessLevel constructorAccess(TypeElement targetElement, MutationContext ctx) {
        String written = new AnnotationLookup().stringAttr(
            targetElement, BUILDER_ARGS_CONSTRUCTOR, "access", null);
        if (written == null) return ctx.config().constructorAccess();
        try {
            return AccessLevel.valueOf(written);
        } catch (IllegalArgumentException e) {
            return ctx.config().constructorAccess();
        }
    }

    /**
     * Decides whether the target needs a synthesised all-args constructor.
     * Skipped for records (the canonical constructor already has the shape), for
     * SuperBuilder targets (they take a copy constructor instead), when a
     * {@code factoryMethod} means {@code build()} never calls {@code new}, when
     * the author declared any constructor, and when there are no fields to pass -
     * javac's own default constructor is then already the no-argument one
     * {@code build()} calls.
     *
     * <p>Withheld as well where a declared nested builder spells its own
     * {@code build()}, which the merge keeps in place of the generated one:
     * nothing generated calls the constructor there, and emitting it would take
     * the place of javac's no-argument default an author {@code build()} calling
     * {@code new Target()} relies on. The rule is
     * {@link DeclaredBuilderShape#withholdsAllArgsConstructor}, which the editor
     * asks of the same names; {@code @BuilderArgsConstructor} written on the
     * target keeps the constructor, and an author {@code build()} wanting the
     * all-args form without it writes {@code @AllArgsConstructor}. A declared
     * builder leaving {@code build()} to the generator keeps it, since the
     * generated one merged into it calls {@code new Target(..)}.
     *
     * @param targetElement the annotated type
     * @param target the target's class declaration
     * @param ctx the per-target mutation context
     * @param isAbstract whether the target is abstract
     * @param annotatedSuper the annotated direct super, or {@code null}
     * @return whether an all-args constructor should be injected
     */
    private static boolean needsAllArgsConstructor(TypeElement targetElement,
                                                   JCClassDecl target,
                                                   MutationContext ctx,
                                                   boolean isAbstract,
                                                   AnnotatedSuper annotatedSuper) {
        return owesAllArgsConstructor(targetElement, target, ctx, isAbstract, annotatedSuper)
            && !authorBuildSurvives(targetElement, target, ctx);
    }

    /**
     * Decides whether the all-args constructor the target is otherwise owed is
     * withheld beside an author's own {@code build()}, which leaves every
     * {@code final} initializer on its field.
     *
     * @param targetElement the annotated type
     * @param target the target's class declaration
     * @param ctx the per-target mutation context
     * @param isAbstract whether the target is abstract
     * @param annotatedSuper the annotated direct super, or {@code null}
     * @return whether the constructor is withheld
     */
    private static boolean allArgsConstructorWithheld(TypeElement targetElement,
                                                      JCClassDecl target,
                                                      MutationContext ctx,
                                                      boolean isAbstract,
                                                      AnnotatedSuper annotatedSuper) {
        return owesAllArgsConstructor(targetElement, target, ctx, isAbstract, annotatedSuper)
            && authorBuildSurvives(targetElement, target, ctx);
    }

    /**
     * Decides whether the target's shape calls for the all-args constructor at
     * all, before the declared builder is asked.
     *
     * @param targetElement the annotated type
     * @param target the target's class declaration
     * @param ctx the per-target mutation context
     * @param isAbstract whether the target is abstract
     * @param annotatedSuper the annotated direct super, or {@code null}
     * @return whether the target is owed the constructor
     */
    private static boolean owesAllArgsConstructor(TypeElement targetElement,
                                                  JCClassDecl target,
                                                  MutationContext ctx,
                                                  boolean isAbstract,
                                                  AnnotatedSuper annotatedSuper) {
        if (targetElement.getKind() == ElementKind.RECORD) return false;
        if (isAbstract || annotatedSuper != null) return false;
        if (!ctx.config().factoryMethod().isEmpty()) return false;
        if (ctx.fields().isEmpty()) return false;
        return !AllArgsConstructorFactory.hasExplicitConstructor(target);
    }

    /**
     * Decides whether the declared builder's own build method survives the
     * merge, as {@link DeclaredBuilderShape#withholdsAllArgsConstructor} answers
     * it from the names the builder declares and the constructor annotation
     * written on the target.
     *
     * @param targetElement the annotated type
     * @param target the target's class declaration
     * @param ctx the per-target mutation context
     * @return whether the all-args constructor is withheld for it
     */
    private static boolean authorBuildSurvives(TypeElement targetElement, JCClassDecl target,
                                               MutationContext ctx) {
        JCClassDecl declared = DeclaredBuilderMerge.declaredBuilder(target, ctx.builderName());
        if (declared == null || AstMarkers.isGenerated(declared)) return false;
        return DeclaredBuilderShape.withholdsAllArgsConstructor(ctx.config().buildMethodName(),
            DeclaredBuilderMerge.declaredMethodKeys(declared).keySet(),
            new AnnotationLookup().hasAnnotation(targetElement, BUILDER_ARGS_CONSTRUCTOR));
    }

    /**
     * Emits a {@link Diagnostic.Kind#NOTE} for each {@code @Collector} field
     * whose type is a custom (non-java.util) container with no declared
     * initializer. The builder has no way to construct a fresh instance of such
     * a type, so those fields get a plain replace setter instead of the
     * {@code @Collector} bulk API - the note tells the author how to enable it.
     */
    private void warnUnbuildableCustomCollectors(TypeElement target, List<FieldSpec> fields) {
        for (FieldSpec f : fields) {
            boolean noInit = f.sourceInitializer == null || f.sourceInitializer.isEmpty();
            if (f.collector && f.isCustomContainer && noInit) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "@ClassBuilder: @Collector on '" + f.name + "' has no field initializer to build "
                        + "fresh instances of a custom collection type from - using a plain replace "
                        + "setter. Give the field an initializer to enable the @Collector bulk API.",
                    target);
            }
        }
    }

    /**
     * Collects this type's fields plus every ancestor's fields up to and
     * including the nearest ancestor carrying {@code @ClassBuilder}. Fields
     * discovered higher in the chain come FIRST so generated
     * {@code from(T)} populates parent slots before child slots, matching
     * the invocation order of inherited setters.
     */
    private List<FieldSpec> collectChainFields(MutationContext ctx, TypeElement start,
                                               List<FieldSpec> ownFields) {
        List<FieldSpec> ancestors = new ArrayList<>();
        TypeMirror superMirror = start.getSuperclass();
        AnnotationLookup lookup = new AnnotationLookup();
        while (superMirror instanceof DeclaredType dt) {
            Element se = dt.asElement();
            if (!(se instanceof TypeElement superType)) break;
            if ("java.lang.Object".equals(superType.getQualifiedName().toString())) break;
            // retainInit is the ancestor's own policy, not the subclass's.
            // Inert on this path (no introspector, so no initializer is
            // captured), but reading it locally keeps the semantics honest.
            boolean superRetainInit = lookup.booleanAttr(
                superType, "dev.simplified.annotations.ClassBuilder", "retainInit", true);
            // Collect this ancestor's own fields.
            for (Element enc : superType.getEnclosedElements()) {
                if (enc.getKind() != ElementKind.FIELD) continue;
                if (enc.getModifiers().contains(Modifier.STATIC)) continue;
                if (enc.getModifiers().contains(Modifier.TRANSIENT)) continue;
                // The memoized-value sibling a lazy field is given is storage,
                // not a property: it holds what the supplier computed and is
                // written only by the generated getter. It is private, instance
                // and non-transient, so none of the tests above sees it, and a
                // subclass that collected it would publish a setter for a slot
                // the constructor never takes.
                if (LazyHolders.isValueField(enc.getSimpleName().toString())) continue;
                // Inherited fields use the plain classification (no Types walk):
                // their initializers aren't accessible cross-class, so a custom
                // container on a parent falls back to a plain setter here.
                //
                // The base scheme is this subclass's, not the ancestor's, which
                // is the assumption the chain already ran on - from(T) calls the
                // inherited setters through it. An ancestor field carrying its
                // own @SetterNames still wins, since that is read off the field
                // and both builders read the same one.
                FieldSpec spec = FieldSpec.from((VariableElement) enc, lookup, null, null,
                    superRetainInit, ctx.config().setters());
                if (spec.ignored) continue;
                ancestors.add(spec);
            }
            if (lookup.hasAnnotation(superType, "dev.simplified.annotations.ClassBuilder")) break;
            superMirror = superType.getSuperclass();
        }
        // Walk collected ancestor tail-first to get top-down field order.
        List<FieldSpec> out = new ArrayList<>(ancestors.size() + ownFields.size());
        out.addAll(ancestors);
        out.addAll(ownFields);
        return out;
    }

    /**
     * The direct superclass when it also carries {@code @ClassBuilder}, paired
     * with the type arguments the target passes it. Matches Lombok's policy of
     * checking only the immediate superclass - an unannotated class between two
     * annotated ancestors breaks the chain rather than being skipped over, so
     * the generated Builder extends the nearest annotated parent's Builder or
     * none at all.
     *
     * @param target the annotated type
     * @return the annotated superclass and its arguments, or {@code null}
     */
    private static AnnotatedSuper findAnnotatedDirectSuper(TypeElement target) {
        TypeMirror superMirror = target.getSuperclass();
        if (!(superMirror instanceof DeclaredType dt)) return null;
        Element superElement = dt.asElement();
        if (!(superElement instanceof TypeElement superType)) return null;
        String superQn = superType.getQualifiedName().toString();
        if ("java.lang.Object".equals(superQn)) return null;
        for (var m : superType.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals("dev.simplified.annotations.ClassBuilder")) {
                List<String> args = new ArrayList<>();
                for (TypeMirror arg : dt.getTypeArguments()) args.add(arg.toString());
                // The superclass's own role, walked one level further up. What it
                // is decides whether a concrete member found on its builder can
                // be read as the author's, since a self-typed role generates an
                // abstract pair and a concrete link generates a concrete one.
                ChainRole superRole = ChainRole.of(
                    superType.getModifiers().contains(Modifier.ABSTRACT),
                    findAnnotatedDirectSuper(superType) != null);
                return new AnnotatedSuper(superType.getSimpleName().toString(), args, superType,
                    superRole);
            }
        }
        return null;
    }

}
