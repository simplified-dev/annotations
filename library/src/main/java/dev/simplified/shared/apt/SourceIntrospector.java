package dev.simplified.shared.apt;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.VariableElement;
import java.util.Set;

/**
 * Reads source-level information that is not surfaced by the {@link javax.lang.model}
 * API, via the javac-specific {@link Trees} bridge.
 *
 * <p>Used to resolve the declared initializer of a retained-initializer field
 * ({@code @ClassBuilder(retainInit = true)}, or an explicit
 * {@code @BuilderDefault}) so the generated builder can reproduce it.
 *
 * <p>If {@link Trees} is unavailable (non-javac/ecj environment), every helper
 * returns {@code null} / empty, and callers should treat the feature as a no-op.
 */
public final class SourceIntrospector {

    private final Trees trees;

    public SourceIntrospector(ProcessingEnvironment env) {
        Trees t;
        try {
            t = Trees.instance(env);
        } catch (IllegalArgumentException | LinkageError ex) {
            t = null;
        }
        this.trees = t;
    }

    /** Whether the Trees API is available in the current environment. */
    public boolean available() {
        return trees != null;
    }

    /**
     * Returns the declared initializer of the given field: source text and the
     * javac tree node itself. AST-mutation consumers use the tree (cast to
     * {@code JCExpression}, deep-cloned with symbols reset) to embed the
     * initializer in a synthesised {@code $default$<name>()} method body.
     * Returns {@code null} if the field has no initializer or if Trees is
     * unavailable.
     *
     * <p>This deliberately does <b>not</b> resolve the identifiers inside the
     * initializer. Resolving them ({@code trees.getElement}) forces attribution
     * of the enclosing class mid-round; for a {@code final}
     * retained-initializer field that attribution runs the
     * constructor's definite-assignment check and emits
     * {@code "cannot assign a value to final variable"} <em>before</em> the
     * field's initializer can be lifted to a blank final. The AST-mutation path
     * clones the initializer tree and never needs a type-reference set, so none
     * is collected ({@link InitializerInfo#typeImports()} is always empty).
     */
    public InitializerInfo readFieldInitializer(VariableElement element) {
        if (trees == null) return null;
        Tree tree = trees.getTree(element);
        if (!(tree instanceof VariableTree var)) return null;
        ExpressionTree initializer = var.getInitializer();
        if (initializer == null) return null;
        return new InitializerInfo(initializer.toString(), Set.of(), initializer);
    }

    /**
     * Source text and the javac tree node for the initializer. {@code tree} is
     * typed as {@link Tree} so the apt package stays free of javac-internal
     * imports; the mutate package casts to {@code JCExpression} when embedding.
     * {@code typeImports} is retained for source-compatibility with the legacy
     * sibling emitter and is always empty (see {@link #readFieldInitializer}).
     */
    public record InitializerInfo(String text, Set<String> typeImports, Tree tree) { }

}
