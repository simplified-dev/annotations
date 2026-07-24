package dev.simplified.log.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.GeneratedAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Injects, via javac AST mutation, the single {@code private static final}
 * log4j2 logger field the {@code @Log} contract promises.
 *
 * <p>Three details are load-bearing.
 *
 * <p><b>The class literal is raw.</b> It is built from a bare identifier over
 * the target's simple name rather than from the shared target-type factory,
 * which re-applies the target's type arguments - and {@code Foo<T>.class} is not
 * an expression, so a generic target would stop compiling on a line its author
 * never wrote.
 *
 * <p><b>The log4j2 reference is textual.</b> Both the field type and the factory
 * are emitted as fully-qualified identifier chains through
 * {@link JavacTypeFactory#qualIdent(String)}, never as a resolved
 * {@code Class} - which is what keeps this library's own classpath free of
 * log4j2 while still emitting code that names it. Resolution happens in the
 * target's compilation, against the target module's classpath.
 *
 * <p><b>A declared field of the same name wins.</b> The collision test walks the
 * target's own definitions only, so an inherited field is deliberately not a
 * match: the generated field is {@code private} and shadows rather than clashes,
 * and a superclass's private field is not even visible here. On a real collision
 * nothing is generated and the author keeps the field they wrote.
 */
public final class LogFieldMutator {

    /**
     * The logger type the generated field is declared with. Public so the
     * processor can test the target's classpath for it against the one spelling
     * this pipeline emits.
     */
    public static final String FQN_LOGGER = "org.apache.logging.log4j.Logger";

    private static final String FQN_LOG_MANAGER = "org.apache.logging.log4j.LogManager";

    private final JavacBridge bridge;
    private final Messager messager;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;

    public LogFieldMutator(JavacBridge bridge, Messager messager) {
        this.bridge = bridge;
        this.messager = messager;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(make, names);
    }

    /**
     * Runs the mutation for a single {@code @Log}-annotated type.
     *
     * @param targetElement the annotated class or enum
     * @param fieldName the resolved logger field name
     * @param topic the logger topic, empty for the target's class literal
     * @param emitGenerated whether the field carries the coverage marker
     * @return {@code true} when mutation completed or was skipped on a
     *         collision; {@code false} when the element has no resolvable
     *         source tree
     */
    public boolean mutate(TypeElement targetElement, String fieldName, String topic, boolean emitGenerated) {
        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;

        make.at(target.pos);

        String simpleName = targetElement.getSimpleName().toString();
        if (hasFieldNamed(target, fieldName)) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                simpleName + " already declares a field named '" + fieldName
                    + "' - @Log generated nothing",
                targetElement);
            return true;
        }

        GeneratedAnnotations generated = new GeneratedAnnotations(make, types, emitGenerated);

        JCExpression factory = make.Select(types.qualIdent(FQN_LOG_MANAGER), names.fromString("getLogger"));
        // Raw class literal - the target's type arguments must not come with it.
        JCExpression arg = topic.isEmpty()
            ? make.Select(make.Ident(names.fromString(simpleName)), names.fromString("class"))
            : make.Literal(topic);

        JCVariableDecl field = make.VarDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC | Flags.FINAL),
            names.fromString(fieldName),
            types.qualIdent(FQN_LOGGER),
            make.Apply(List.nil(), factory, List.of(arg))
        );
        AstMarkers.markGenerated(field, generated);
        bridge.compat().appendDef(target, field);
        return true;
    }

    private static boolean hasFieldNamed(JCClassDecl target, String name) {
        for (JCTree def : target.defs) {
            if (def instanceof JCVariableDecl v && v.name.toString().equals(name)) return true;
        }
        return false;
    }

}
