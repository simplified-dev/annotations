package dev.simplified.classbuilder.mutate;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import dev.simplified.classbuilder.apt.BuilderConfig;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.lazy.mutate.LazyFieldMutator;
import dev.simplified.shared.apt.AnnotationLookup;
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

    private final JavacBridge bridge;
    private final Messager messager;

    public BuilderMutator(JavacBridge bridge, Messager messager) {
        this.bridge = bridge;
        this.messager = messager;
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

        MutationContext ctx = new MutationContext(bridge, targetElement, target, config, fields);

        // Position subsequent tree construction at the target's start so error
        // messages on synthesised members point at the @ClassBuilder declaration.
        bridge.treeMaker().at(target.pos);

        warnUnbuildableCustomCollectors(targetElement, fields);

        boolean isAbstract = targetElement.getModifiers().contains(Modifier.ABSTRACT);
        AnnotatedSuper annotatedSuper = findAnnotatedDirectSuper(targetElement);

        // The build() we emit calls new Target(f1, f2, ...) positionally, and a
        // plain class that declares no constructor gets only javac's no-arg
        // default - so synthesise the matching all-args form. Injected ahead of
        // LazyFieldMutator so a @Lazy field's parameter and assignment are
        // rewritten here exactly as they would be in a hand-written ctor.
        if (needsAllArgsConstructor(targetElement, target, ctx, isAbstract, annotatedSuper)) {
            bridge.compat().appendDef(target, new AllArgsConstructorFactory(ctx).build());
        }

        // @Lazy fields: rewrite storage type to Lazy<T>, wrap initialisers,
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
            List<FieldSpec> chainFields = isAbstract ? fields : collectChainFields(targetElement, fields);
            new SuperBuilderMutator(ctx, messager, annotatedSuper, chainFields).mutate();
            return true;
        }

        if (hasExistingNested(target, ctx.builderName())) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@ClassBuilder skipped injection: class " + ctx.targetSimpleName()
                    + " already declares a nested '" + ctx.builderName() + "' type",
                targetElement
            );
            return true;
        }

        // $default$<fieldName>() providers for retained-initializer fields.
        // Must run before the nested Builder is built so FieldMutators'
        // Target.$default$<name>() references resolve at javac attribution.
        new RetainedInitFactory(ctx, messager).appendAll();

        JCClassDecl nested = new NestedBuilderFactory(ctx).build();
        bridge.compat().appendDef(target, nested);

        new BootstrapMethodFactory(ctx, messager).appendAll();
        return true;
    }

    /**
     * Decides whether the target needs a synthesised all-args constructor.
     * Skipped for records (the canonical constructor already has the shape), for
     * SuperBuilder targets (they take a copy constructor instead), when a
     * {@code factoryMethod} means {@code build()} never calls {@code new}, when
     * the author declared any constructor, when a hand-written nested builder
     * suppresses injection wholesale, and when there are no fields to pass -
     * that last case would collide with javac's own default constructor.
     *
     * @param targetElement the annotated type
     * @param target the target's class declaration
     * @param ctx the per-target mutation context
     * @param isAbstract whether the target is abstract
     * @param annotatedSuper the annotated direct super, or {@code null}
     * @return whether an all-args constructor should be injected
     */
    private boolean needsAllArgsConstructor(TypeElement targetElement,
                                            JCClassDecl target,
                                            MutationContext ctx,
                                            boolean isAbstract,
                                            AnnotatedSuper annotatedSuper) {
        if (targetElement.getKind() == ElementKind.RECORD) return false;
        if (isAbstract || annotatedSuper != null) return false;
        if (!ctx.config().factoryMethod().isEmpty()) return false;
        if (ctx.fields().isEmpty()) return false;
        if (AllArgsConstructorFactory.hasExplicitConstructor(target)) return false;
        return !hasExistingNested(target, ctx.builderName());
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

    private static boolean hasExistingNested(JCClassDecl target, String nestedName) {
        for (var def : target.defs) {
            if (def instanceof JCClassDecl c && c.name.toString().equals(nestedName)) return true;
        }
        return false;
    }

    /**
     * Returns the simple name of the direct superclass when it also carries
     * {@code @ClassBuilder}; {@code null} otherwise. Matches Lombok's policy
     * of checking only the immediate superclass - skipping annotation between
     * two annotated ancestors still lets inherited builder setters flow
     * through, but the generated Builder extends only the nearest annotated
     * parent's Builder.
     */
    /**
     * Collects this type's fields plus every ancestor's fields up to and
     * including the nearest ancestor carrying {@code @ClassBuilder}. Fields
     * discovered higher in the chain come FIRST so generated
     * {@code from(T)} populates parent slots before child slots, matching
     * the invocation order of inherited setters.
     */
    private List<FieldSpec> collectChainFields(TypeElement start, List<FieldSpec> ownFields) {
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
                // Inherited fields use the plain classification (no Types walk):
                // their initializers aren't accessible cross-class, so a custom
                // container on a parent falls back to a plain setter here.
                FieldSpec spec = FieldSpec.from((VariableElement) enc, lookup, null, null, superRetainInit);
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
                return new AnnotatedSuper(superType.getSimpleName().toString(), args);
            }
        }
        return null;
    }

}
