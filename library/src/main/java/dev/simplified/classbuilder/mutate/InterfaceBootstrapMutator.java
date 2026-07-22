package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.BuilderConfig;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Injects the bootstrap methods onto a {@code @ClassBuilder} interface, so an
 * interface target is entered the same way a class is - {@code Repo.builder()}
 * rather than {@code new RepoBuilder<>()}.
 *
 * <p>The builder for an interface is a sibling top-level class, since there is
 * no in-source mutation surface for a nested one on an interface body. The
 * interface body itself is still a perfectly good place for methods, though:
 * {@code static} interface methods have been legal since Java 8 and
 * {@code default} ones give {@code mutate()} a receiver. That is enough to make
 * the entry points match a class's without moving interfaces onto the
 * AST-mutation path wholesale.
 *
 * <pre>{@code
 * static <T> RepoBuilder<T> builder()                      { return new RepoBuilder<>(); }
 * static <T> RepoBuilder<T> from(Repo<T> instance)         { return RepoBuilder.from(instance); }
 * default RepoBuilder<T> mutate()                          { return RepoBuilder.from(this); }
 * }</pre>
 *
 * <p>Collision policy matches {@code BootstrapMethodFactory}: a method the
 * author already declared with the same name and arity wins, and a
 * {@link Diagnostic.Kind#NOTE} records the skip.
 */
public final class InterfaceBootstrapMutator {

    private final JavacBridge bridge;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final Messager messager;
    private final TypeElement target;
    private final JCClassDecl targetTree;
    private final BuilderConfig config;
    private final String builderName;

    public InterfaceBootstrapMutator(JavacBridge bridge, Messager messager, TypeElement target,
                                     JCClassDecl targetTree, BuilderConfig config, String builderName) {
        this.bridge = bridge;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(this.make, this.names);
        this.messager = messager;
        this.target = target;
        this.targetTree = targetTree;
        this.config = config;
        this.builderName = builderName;
    }

    /** Appends whichever bootstrap methods are missing from the interface. */
    public void appendAll() {
        make.at(targetTree.pos);
        String builderMethod = config.builderMethodName();
        String fromMethod = config.fromMethodName();
        String mutateMethod = config.toBuilderMethodName();

        if (!builderMethod.isEmpty() && absent(builderMethod, 0)) {
            append(builderFactory(builderMethod));
        }
        if (!fromMethod.isEmpty() && absent(fromMethod, 1)) {
            append(fromFactory(fromMethod));
        }
        if (!mutateMethod.isEmpty() && absent(mutateMethod, 0)) {
            append(mutateMethod(mutateMethod));
        }
    }

    /** {@code static <T> RepoBuilder<T> builder() { return new RepoBuilder<>(); }} */
    private JCMethodDecl builderFactory(String name) {
        List<JCTypeParameter> typeParams = typeParams();
        JCExpression returnType = builderType(typeArgsFrom(typeParams));
        // Diamond only when there is something to infer - `new Foo<>()` is
        // rejected outright on a non-generic class.
        JCExpression instantiated = typeParams.isEmpty()
            ? make.Ident(names.fromString(builderName))
            : make.TypeApply(make.Ident(names.fromString(builderName)), List.nil());
        JCStatement body = make.Return(make.NewClass(
            null, List.nil(), instantiated, List.nil(), null));
        return method(name, Flags.STATIC, typeParams, List.nil(), returnType, body);
    }

    /** {@code static <T> RepoBuilder<T> from(Repo<T> i) { return RepoBuilder.from(i); }} */
    private JCMethodDecl fromFactory(String name) {
        List<JCTypeParameter> typeParams = typeParams();
        List<JCExpression> typeArgs = typeArgsFrom(typeParams);
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("instance"),
            applied(target.getSimpleName().toString(), typeArgs),
            null
        );
        JCStatement body = make.Return(make.Apply(
            List.nil(),
            make.Select(make.Ident(names.fromString(builderName)),
                names.fromString(config.fromMethodName())),
            List.of(make.Ident(names.fromString("instance")))
        ));
        return method(name, Flags.STATIC, typeParams, List.of(param),
            builderType(typeArgsFrom(typeParams)), body);
    }

    /**
     * {@code default RepoBuilder<T> mutate() { return RepoBuilder.from(this); }}
     * - a default rather than a static, so it has a receiver to read. The
     * interface's own type parameters are in scope here, so it declares none.
     */
    private JCMethodDecl mutateMethod(String name) {
        JCStatement body = make.Return(make.Apply(
            List.nil(),
            make.Select(make.Ident(names.fromString(builderName)),
                names.fromString(config.fromMethodName())),
            List.of(make.Ident(names._this))
        ));
        return method(name, Flags.DEFAULT, List.nil(), List.nil(),
            builderType(ownTypeArgs()), body);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Fresh copies of the interface's type parameters, for a {@code static}
     * method - which cannot see the interface's own.
     */
    private List<JCTypeParameter> typeParams() {
        ListBuffer<JCTypeParameter> out = new ListBuffer<>();
        for (TypeParameterElement tp : target.getTypeParameters()) {
            ListBuffer<JCExpression> bounds = new ListBuffer<>();
            for (TypeMirror bound : tp.getBounds()) {
                if ("java.lang.Object".equals(bound.toString())) continue;
                bounds.append(types.parseType(bound.toString()));
            }
            out.append(make.TypeParameter(
                names.fromString(tp.getSimpleName().toString()), bounds.toList()));
        }
        return out.toList();
    }

    /** References to the given declarations, for applying to a type. */
    private List<JCExpression> typeArgsFrom(List<JCTypeParameter> params) {
        ListBuffer<JCExpression> out = new ListBuffer<>();
        for (JCTypeParameter tp : params) out.append(make.Ident(tp.name));
        return out.toList();
    }

    /** References to the interface's own parameters, in scope inside a default method. */
    private List<JCExpression> ownTypeArgs() {
        ListBuffer<JCExpression> out = new ListBuffer<>();
        for (TypeParameterElement tp : target.getTypeParameters()) {
            out.append(make.Ident(names.fromString(tp.getSimpleName().toString())));
        }
        return out.toList();
    }

    private JCExpression builderType(List<JCExpression> typeArgs) {
        return applied(builderName, typeArgs);
    }

    private JCExpression applied(String simpleName, List<JCExpression> typeArgs) {
        JCExpression raw = make.Ident(names.fromString(simpleName));
        return typeArgs.isEmpty() ? raw : make.TypeApply(raw, typeArgs);
    }

    private JCMethodDecl method(String name, long flags, List<JCTypeParameter> typeParams,
                                List<JCVariableDecl> params, JCExpression returnType, JCStatement body) {
        JCBlock block = make.Block(0, List.of(body));
        // Interface members are implicitly public; PUBLIC is set explicitly so
        // the flag set reads the same as the class path's.
        JCMethodDecl m = make.MethodDef(
            make.Modifiers(flags | Flags.PUBLIC),
            names.fromString(name),
            returnType,
            typeParams,
            params,
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(m);
        return m;
    }

    private void append(JCMethodDecl method) {
        bridge.compat().appendDef(targetTree, method);
    }

    private boolean absent(String name, int arity) {
        for (JCTree def : targetTree.defs) {
            if (def instanceof JCMethodDecl m
                && m.name.toString().equals(name)
                && m.params.size() == arity) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "@ClassBuilder skipped bootstrap '" + name + "' - target already declares "
                        + name + "/" + arity,
                    target);
                return false;
            }
        }
        return true;
    }

}
