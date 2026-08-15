package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.classbuilder.apt.BuilderConfig;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.javac.JavacBridge;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.List;

/**
 * Injects the nested {@code Builder} for a {@code @ClassBuilder} written on a
 * constructor or static factory, whose slots are that member's parameters.
 *
 * <p>The third emission path, and separate from {@link BuilderMutator} rather
 * than a branch inside it, because every assumption that mutator is built on
 * dissolves once the slots stop being the target's fields. There is no all-args
 * constructor to synthesise - the annotated member is what {@code build()}
 * calls. There is no {@code @Lazy} interaction, that pass rewriting field
 * storage. There is no SuperBuilder chain, a constructor having no chain to
 * find. There is no {@code retainInit}, a parameter carrying no initializer to
 * retain. And there is no {@code from(T)} or {@code mutate()}, since seeding a
 * slot back off a built instance needs a slot-to-accessor mapping that
 * parameters do not have.
 *
 * <p>What it does share, it shares outright: {@link FieldSpec} is the slot IR,
 * the naming trio resolves the names, {@link FieldMutators} emits the setter
 * shapes, and {@link NestedBuilderFactory} and {@link BootstrapMethodFactory}
 * build everything but the instantiation {@code build()} performs. This class is
 * the ordering and the guards, not a second emitter.
 */
public final class ExecutableBuilderMutator {

    private final JavacBridge bridge;
    private final Messager messager;

    public ExecutableBuilderMutator(JavacBridge bridge, Messager messager) {
        this.bridge = bridge;
        this.messager = messager;
    }

    /**
     * Runs the mutation for one annotated constructor or static factory.
     *
     * @param enclosing the type the builder nests in and is entered through
     * @param executable the annotated member, whose parameters are the slots
     * @param config resolved builder configuration
     * @param slots per-parameter IR, in declaration order
     * @return {@code true} when mutation completed; {@code false} when the
     *         enclosing type has no source tree and the caller should report it
     */
    public boolean mutate(TypeElement enclosing, ExecutableElement executable,
                          BuilderConfig config, List<FieldSpec> slots) {
        JCClassDecl target = bridge.treeOf(enclosing);
        if (target == null) return false;

        MutationContext ctx =
            new MutationContext(bridge, enclosing, target, config, slots, executable);

        // Position subsequent tree construction at the enclosing type's start so
        // errors on synthesised members point at a declaration the author wrote.
        bridge.treeMaker().at(target.pos);

        if (hasExistingNested(target, ctx.builderName())) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@ClassBuilder skipped injection: " + ctx.targetSimpleName()
                    + " already declares a nested '" + ctx.builderName() + "' type",
                executable
            );
            return true;
        }

        warnUnbuildableCustomCollectors(executable, slots);

        bridge.compat().appendDef(target, new NestedBuilderFactory(ctx).build());
        new BootstrapMethodFactory(ctx, messager).appendAll();
        return true;
    }

    /**
     * Emits a note for each {@code @Collector} slot whose type is a custom
     * (non-{@code java.util}) container.
     *
     * <p>A field in that position can enable the bulk API by declaring an
     * initializer the builder makes fresh instances from. A parameter has no
     * such expression to offer, so the shape is out of reach here and the slot
     * takes a plain replace setter - which is worth saying, since the annotation
     * would otherwise appear to have done nothing.
     */
    private void warnUnbuildableCustomCollectors(ExecutableElement executable, List<FieldSpec> slots) {
        for (FieldSpec slot : slots) {
            if (!slot.collector || !slot.isCustomContainer) continue;
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@ClassBuilder: @Collector on '" + slot.name + "' is a custom collection type, and "
                    + "a parameter has no initializer to build fresh instances of one from - using "
                    + "a plain replace setter. Declare the slot as a java.util type for the bulk API",
                executable);
        }
    }

    private static boolean hasExistingNested(JCClassDecl target, String nestedName) {
        for (JCTree def : target.defs) {
            if (def instanceof JCClassDecl c && c.name.toString().equals(nestedName)) return true;
        }
        return false;
    }

}
