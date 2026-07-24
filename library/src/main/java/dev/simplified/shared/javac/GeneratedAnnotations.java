package dev.simplified.shared.javac;

import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;

/**
 * Builds {@code @Generated} {@link JCAnnotation}s for AST-mutation pipelines.
 * Sibling of {@link ContractAnnotations} and gated the same way: when the
 * {@code emit} flag passed at construction is {@code false} every method
 * returns {@link List#nil()}, so call sites never wrap the attachment in a
 * conditional.
 *
 * <p>The annotation type is written as a fully-qualified identifier, so no
 * import is ever added to the target's compilation unit and a target already
 * importing {@code javax.annotation.processing.Generated} or
 * {@code jakarta.annotation.Generated} cannot collide with it.
 *
 * <p>Every call mints a fresh node. javac trees cannot be shared between
 * parents, so a cached instance would corrupt the second member it was
 * attached to.
 */
public final class GeneratedAnnotations {

    private static final String GENERATED_FQN = "dev.simplified.annotations.Generated";

    private final TreeMaker make;
    private final JavacTypeFactory types;
    private final boolean emit;

    public GeneratedAnnotations(TreeMaker make, JavacTypeFactory types, boolean emit) {
        this.make = make;
        this.types = types;
        this.emit = emit;
    }

    /** A pipeline that always marks its output, for mutators with no opt-out attribute. */
    public static GeneratedAnnotations always(TreeMaker make, JavacTypeFactory types) {
        return new GeneratedAnnotations(make, types, true);
    }

    /** @return {@code @Generated} as a splice-ready list, or an empty list when disabled */
    public List<JCAnnotation> generated() {
        if (!emit) return List.nil();
        return List.of(make.Annotation(types.qualIdent(GENERATED_FQN), List.nil()));
    }

    /**
     * Appends the marker to an existing annotation list.
     *
     * @param annotations the annotations already destined for the member
     * @return the same list when disabled, otherwise one with the marker appended
     */
    public List<JCAnnotation> on(List<JCAnnotation> annotations) {
        return emit ? annotations.appendList(generated()) : annotations;
    }

    /** Whether the marker is being emitted at all. */
    public boolean emits() {
        return emit;
    }

}
