package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Whether a target already declares a bootstrap method the builder would
 * otherwise inject, and therefore whether the author's own wins.
 *
 * <p>One statement of the rule for both emission paths - the in-place AST
 * mutation a class or record takes, and the sibling emission an interface takes
 * - because the two carry the same policy and drift apart whenever either
 * states it privately.
 *
 * <p>{@code builder()} and {@code mutate()} take no parameters, so a name and an
 * arity settle them and the tree is the right place to ask: it holds the
 * author's methods and anything an earlier pass appended alike.
 *
 * <p>{@code from(T)} is different, and it is the reason this class exists. Its
 * arity is shared with methods that mean something else entirely - a
 * {@code from(String)} parser, a {@code from(Config)} adapter - so matching on
 * name and arity alone suppresses the copy factory with nothing louder than a
 * note to say it happened. The parameter has to denote the target itself, which
 * is asked of the <b>element model</b> rather than of the tree: a tree
 * parameter's declared type is an unattributed expression no type test applies
 * to, while every method the author wrote is resolved before the round begins.
 * Comparing the parameter's {@link DeclaredType#asElement()} to the target
 * element is exact and needs no name matching, so a generic {@code Widget<T>}
 * parameter answers the same as a raw {@code Widget}.
 */
final class BootstrapCollisions {

    private BootstrapCollisions() {
    }

    /**
     * Whether a zero-parameter method of that name is already on the tree.
     *
     * @param targetTree the target's source tree
     * @param name the bootstrap name being considered
     * @return whether the injection should be skipped
     */
    static boolean declaresNullary(JCClassDecl targetTree, String name) {
        return declaresArity(targetTree, name, 0);
    }

    /**
     * Whether a method of that name and parameter count is already on the tree.
     *
     * <p>A seeded {@code builder(...)} carries the seeds as parameters, so the
     * arity it collides at is not zero. Everything else the nullary rule says
     * still holds: the tree is the right place to ask, and it is what the author
     * wrote plus whatever an earlier pass appended.
     *
     * @param targetTree the target's source tree
     * @param name the bootstrap name being considered
     * @param arity how many parameters the injection would declare
     * @return whether the injection should be skipped
     */
    static boolean declaresArity(JCClassDecl targetTree, String name, int arity) {
        for (JCTree def : targetTree.defs) {
            if (def instanceof JCMethodDecl m
                && m.name.toString().equals(name)
                && m.params.size() == arity) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a copy factory over the target's own type is already declared.
     *
     * <p>A one-parameter method of the same name over any other type is not a
     * collision - it is a different method that happens to share a name.
     *
     * @param target the annotated type
     * @param name the copy factory's name
     * @return whether the injection should be skipped
     */
    static boolean declaresCopyFactory(TypeElement target, String name) {
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            if (!enclosed.getSimpleName().contentEquals(name)) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (method.getParameters().size() != 1) continue;
            TypeMirror parameter = method.getParameters().getFirst().asType();
            if (parameter instanceof DeclaredType declared && declared.asElement().equals(target)) {
                return true;
            }
        }
        return false;
    }

}
