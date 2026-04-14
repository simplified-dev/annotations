package dev.sbs.classbuilder.mutate;

import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import dev.sbs.classbuilder.apt.BuilderConfig;
import dev.sbs.classbuilder.apt.FieldSpec;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
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

        if (hasExistingNested(target, ctx.builderName())) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@ClassBuilder skipped injection: class " + ctx.targetSimpleName()
                    + " already declares a nested '" + ctx.builderName() + "' type",
                targetElement
            );
            return true;
        }

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

}
