package dev.simplified.classbuilder.apt;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads source-level information that is not surfaced by the {@link javax.lang.model}
 * API, via the javac-specific {@link Trees} bridge.
 *
 * <p>Used to resolve the declared initializer of a {@code @BuildRule(retainInit = true)} field
 * so the generated builder can reproduce it. Also harvests type references from
 * that initializer so the builder can import them alongside.
 *
 * <p>If {@link Trees} is unavailable (non-javac/ecj environment), every helper
 * returns {@code null} / empty, and callers should treat the feature as a no-op.
 */
final class SourceIntrospector {

    private final Trees trees;

    SourceIntrospector(ProcessingEnvironment env) {
        Trees t;
        try {
            t = Trees.instance(env);
        } catch (IllegalArgumentException | LinkageError ex) {
            t = null;
        }
        this.trees = t;
    }

    /** Whether the Trees API is available in the current environment. */
    boolean available() {
        return trees != null;
    }

    /**
     * Returns the declared initializer of the given field: source text, type
     * references, and the javac tree node itself. AST-mutation consumers use
     * the tree (cast to {@code JCExpression}, deep-cloned with symbols reset)
     * to embed the initializer in a synthesised {@code $default$<name>()}
     * method body. Returns {@code null} if the field has no initializer or
     * if Trees is unavailable.
     */
    InitializerInfo readFieldInitializer(VariableElement element) {
        if (trees == null) return null;
        Tree tree = trees.getTree(element);
        if (!(tree instanceof VariableTree var)) return null;
        ExpressionTree initializer = var.getInitializer();
        if (initializer == null) return null;

        TreePath path = trees.getPath(element);
        if (path == null) return null;
        TreePath initializerPath = TreePath.getPath(path, initializer);

        Set<String> imports = new LinkedHashSet<>();
        if (initializerPath != null) {
            new TypeRefCollector(trees, imports).scan(initializerPath, null);
        }
        return new InitializerInfo(initializer.toString(), imports, initializer);
    }

    /**
     * Source text, referenced type FQNs, and the javac tree node for the
     * initializer. {@code tree} is typed as {@link Tree} so the apt package
     * stays free of javac-internal imports; the mutate package casts to
     * {@code JCExpression} when embedding.
     */
    public record InitializerInfo(String text, Set<String> typeImports, Tree tree) { }

    /**
     * Walks an expression tree and records every identifier that resolves to a
     * {@link TypeElement}, yielding the FQN for each. Used to import the types
     * referenced by a copied initializer expression.
     */
    private static final class TypeRefCollector extends TreePathScanner<Void, Void> {

        private final Trees trees;
        private final Set<String> out;

        TypeRefCollector(Trees trees, Set<String> out) {
            this.trees = trees;
            this.out = out;
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            // trees.getElement forces attribution of the enclosing class.
            // Mid-round (our APT is still processing earlier @ClassBuilder
            // targets in the same compilation unit), attribution can hit
            // half-populated method symbols and throw NPE. A missed import
            // here only affects the sibling-emitter source text, not the
            // AST path - javac resolves identifiers there against the
            // original source's imports regardless of this scan's outcome.
            Element resolved;
            try {
                resolved = trees.getElement(getCurrentPath());
            } catch (RuntimeException ignored) {
                return super.visitIdentifier(node, unused);
            }
            if (resolved instanceof TypeElement type) {
                out.add(type.getQualifiedName().toString());
            }
            return super.visitIdentifier(node, unused);
        }

    }

}
