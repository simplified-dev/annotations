package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import dev.simplified.classbuilder.apt.AnnotationLookup;
import dev.simplified.classbuilder.apt.BuilderConfig;
import dev.simplified.classbuilder.apt.FieldSpec;

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
    public boolean mutate(TypeElement targetElement, BuilderConfig config, List<FieldSpec> fields) {
        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;

        MutationContext ctx = new MutationContext(bridge, targetElement, target, config, fields);

        // Position subsequent tree construction at the target's start so error
        // messages on synthesised members point at the @ClassBuilder declaration.
        bridge.treeMaker().at(target.pos);

        boolean isAbstract = targetElement.getModifiers().contains(Modifier.ABSTRACT);
        String annotatedSuper = findAnnotatedDirectSuperSimpleName(targetElement);

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

        // $default$<fieldName>() providers for @BuildRule(retainInit) fields.
        // Must run before the nested Builder is built so FieldMutators'
        // Target.$default$<name>() references resolve at javac attribution.
        new RetainedInitFactory(ctx).appendAll();

        JCClassDecl nested = new NestedBuilderFactory(ctx).build();
        bridge.compat().appendDef(target, nested);

        new BootstrapMethodFactory(ctx, messager).appendAll();
        return true;
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
            // Collect this ancestor's own fields.
            for (Element enc : superType.getEnclosedElements()) {
                if (enc.getKind() != ElementKind.FIELD) continue;
                if (enc.getModifiers().contains(Modifier.STATIC)) continue;
                if (enc.getModifiers().contains(Modifier.TRANSIENT)) continue;
                FieldSpec spec = FieldSpec.from((VariableElement) enc, lookup, null);
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

    private static String findAnnotatedDirectSuperSimpleName(TypeElement target) {
        TypeMirror superMirror = target.getSuperclass();
        if (!(superMirror instanceof DeclaredType dt)) return null;
        Element superElement = dt.asElement();
        if (!(superElement instanceof TypeElement superType)) return null;
        String superQn = superType.getQualifiedName().toString();
        if ("java.lang.Object".equals(superQn)) return null;
        for (var m : superType.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals("dev.simplified.annotations.ClassBuilder")) {
                return superType.getSimpleName().toString();
            }
        }
        return null;
    }

}
