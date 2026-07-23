package dev.simplified.shared.javac;

import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import java.util.Set;

/**
 * Copies a field's nullness onto a member this pipeline generates from it.
 *
 * <p>Only the two JetBrains annotations travel. They are the pair the whole
 * project already models - {@code FieldSpec} captures them as booleans, the
 * builder re-emits them on its setter parameters - and widening the set would
 * mean deciding what a copyable annotation is, which is a surface this library
 * deliberately does not have.
 *
 * <p><b>Copy only onto a member whose type is the field's own.</b> Several
 * passes retype: a {@code @Lazy} field's constructor parameter becomes
 * {@code Supplier<T>}, an instance default's becomes {@code Supplier<T>} too, a
 * collected container's becomes the {@code java.util} interface, and the marker
 * beside it has no field behind it at all. The field's nullness describes
 * {@code T}, so on any of those it would be a claim about the wrong type -
 * {@code @NotNull Supplier<T>} where null is the sentinel meaning "never set" is
 * the sharpest case, since it asserts exactly what the slot uses to say the
 * opposite.
 */
public final class NullnessAnnotations {

    private static final Set<String> COPYABLE = Set.of(
        "org.jetbrains.annotations.NotNull",
        "org.jetbrains.annotations.Nullable"
    );

    private NullnessAnnotations() {}

    /**
     * The nullness annotations written on an element, rebuilt as fresh trees.
     *
     * <p>Fresh nodes on every call: a javac tree cannot be shared between two
     * parents, so a member and its parameter cannot hold the same instance.
     *
     * @param element the field to read, or null to read nothing
     * @param make the tree factory
     * @param types the type factory, for the qualified annotation name
     * @return the annotations to attach, empty when the field carries none
     */
    public static List<JCAnnotation> copy(Element element, TreeMaker make, JavacTypeFactory types) {
        List<JCAnnotation> out = List.nil();
        if (element == null) return out;
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String fqn = mirror.getAnnotationType().toString();
            if (!COPYABLE.contains(fqn)) continue;
            out = out.append(make.Annotation(types.qualIdent(fqn), List.nil()));
        }
        return out;
    }

}
