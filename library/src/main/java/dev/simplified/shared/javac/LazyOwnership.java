package dev.simplified.shared.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;

/**
 * Partitions ownership of a source tree between a standalone body-rewriting
 * processor and the {@code ClassBuilder} pipeline.
 *
 * <p>A body rewrite that changes the depth of a constructor's statements has to
 * run after the {@code @Lazy} pass, which walks a constructor body's statement
 * list flat and only rewrites an assignment that is a direct child of it.
 * Processor order within a round is unspecified, so a tree that declares a
 * {@code @Lazy} field is rewritten from the shared processor - where the
 * ordering is a call sequence rather than a hope - and the standalone processor
 * stands back from exactly that set.
 *
 * <p><b>The match has to name this project's annotation exactly.</b> A written
 * simple name alone is not enough: {@code @Lazy} is a common spelling - Spring
 * declares one that targets fields - and a false positive is not the harmless
 * hand-off it looks like. The deferral only lands somewhere when
 * {@code ClassBuilderProcessor} runs at all, and it runs only for a round that
 * carries one of the annotations it claims. A foreign {@code @Lazy} therefore
 * takes the tree off the standalone path without putting it on any other, and
 * the annotated member is silently never rewritten.
 */
public final class LazyOwnership {

    private static final String LAZY_FQN = "dev.simplified.annotations.Lazy";

    private LazyOwnership() {
    }

    /**
     * Whether the declaration or any type nested in it declares a {@code @Lazy}
     * field.
     *
     * @param target the class declaration to inspect
     * @param unit the compilation unit the declaration sits in, resolving the
     *        bare simple-name spelling
     * @return {@code true} when the builder pipeline owns this tree
     */
    public static boolean declaresLazyField(JCClassDecl target, CompilationUnitTree unit) {
        if (target == null) return false;
        for (JCTree def : target.defs) {
            if (def instanceof JCVariableDecl field && named(field.mods, unit)) return true;
            if (def instanceof JCClassDecl nested && declaresLazyField(nested, unit)) return true;
        }
        return false;
    }

    /** Whether the modifiers carry this project's {@code @Lazy}, in either spelling. */
    private static boolean named(JCModifiers mods, CompilationUnitTree unit) {
        if (mods == null) return false;
        for (JCAnnotation annotation : mods.annotations) {
            if (AnnotationSpelling.names(annotation.annotationType.toString(), LAZY_FQN, unit)) return true;
        }
        return false;
    }

}
