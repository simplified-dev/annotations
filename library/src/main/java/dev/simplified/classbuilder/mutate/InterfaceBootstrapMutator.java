package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
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
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.GeneratedAnnotations;
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
 * <p>Where {@code from} is suppressed the sibling has no static copy factory to
 * delegate to, so {@code mutate()} seeds a fresh sibling builder inline through
 * its setters, reading each slot off {@code this} as the sibling's
 * {@code from(T)} reads it off its argument - the way a class target's
 * {@code mutate()} is always seeded.
 *
 * <p>Each of the three carries the {@code @XContract} a class target's entry
 * point of the same role carries, under the same {@code emitContracts} gate.
 *
 * <p>Collision policy matches {@code BootstrapMethodFactory}: a method the
 * author already declared that would collide wins - per
 * {@link BootstrapCollisions}, shared with the AST path - and a
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
    private final java.util.List<FieldSpec> fields;
    private final GeneratedAnnotations generated;
    private final ContractAnnotations contracts;

    /**
     * Prepares the entry points for one interface target.
     *
     * @param bridge the javac bridge the trees are made through
     * @param messager sink for diagnostics
     * @param target the annotated interface
     * @param targetTree the interface's tree, which the entry points are appended to
     * @param config the resolved configuration
     * @param builderName the sibling builder's simple name
     * @param fields the slots the sibling builder holds, one per abstract accessor
     */
    public InterfaceBootstrapMutator(JavacBridge bridge, Messager messager, TypeElement target,
                                     JCClassDecl targetTree, BuilderConfig config, String builderName,
                                     java.util.List<FieldSpec> fields) {
        this.bridge = bridge;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(this.make, this.names);
        this.generated = new GeneratedAnnotations(this.make, this.types, config.emitGenerated());
        this.contracts = new ContractAnnotations(this.make, this.names, this.types, config.emitContracts());
        this.messager = messager;
        this.target = target;
        this.targetTree = targetTree;
        this.config = config;
        this.builderName = builderName;
        this.fields = fields;
    }

    /** Appends whichever bootstrap methods are missing from the interface. */
    public void appendAll() {
        make.at(targetTree.pos);
        String builderMethod = config.builderMethodName();
        String fromMethod = config.fromMethodName();
        String mutateMethod = config.toBuilderMethodName();

        if (!builderMethod.isEmpty()
            && absent(builderMethod, "/0", BootstrapCollisions.declaresNullary(targetTree, builderMethod))) {
            append(builderFactory(builderMethod));
        }
        if (!fromMethod.isEmpty()
            && absent(fromMethod, "(" + target.getSimpleName() + ")",
                      BootstrapCollisions.declaresCopyFactory(target, fromMethod))) {
            append(fromFactory(fromMethod));
        }
        if (!mutateMethod.isEmpty()
            && absent(mutateMethod, "/0", BootstrapCollisions.declaresNullary(targetTree, mutateMethod))) {
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
        return method(name, Flags.STATIC, contracts.newReturnNullary(), typeParams, List.nil(), returnType,
            List.of(body));
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
        return method(name, Flags.STATIC, contracts.newReturnPureUnary(), typeParams, List.of(param),
            builderType(typeArgsFrom(typeParams)), List.of(body));
    }

    /**
     * {@code default RepoBuilder<T> mutate() { return RepoBuilder.from(this); }}
     * - a default rather than a static, so it has a receiver to read. The
     * interface's own type parameters are in scope here, so it declares none.
     *
     * <p>With {@code from} suppressed there is no static copy factory to call,
     * so the body seeds a fresh builder inline instead - see {@link #inlineSeed()}.
     */
    private JCMethodDecl mutateMethod(String name) {
        List<JCStatement> body = config.fromMethodName().isEmpty()
            ? inlineSeed()
            : List.of(make.Return(make.Apply(
                List.nil(),
                make.Select(make.Ident(names.fromString(builderName)),
                    names.fromString(config.fromMethodName())),
                List.of(make.Ident(names._this))
            )));
        return method(name, Flags.DEFAULT, contracts.newReturnNullary(), List.nil(), List.nil(),
            builderType(ownTypeArgs()), body);
    }

    /**
     * The body of a {@code mutate()} that seeds the sibling builder itself:
     * {@code RepoBuilder<T> b = new RepoBuilder<>(); b.head(this.head()); ... return b;}.
     *
     * <p>Each slot is read off {@code this} exactly as the sibling's
     * {@code from(T)} reads it off its argument - through an {@code @ObtainVia}
     * override where one is written, the accessor otherwise - and copied
     * defensively where it is a mutable collection. It is assigned through the
     * slot's {@code set}-role setter, the one overload of each slot's setters
     * that takes the slot's whole value, since the sibling's fields are private
     * to it.
     *
     * @return the statements of the body
     */
    private List<JCStatement> inlineSeed() {
        ListBuffer<JCStatement> body = new ListBuffer<>();
        JCExpression builderClass = make.Ident(names.fromString(builderName));
        JCExpression instantiated = target.getTypeParameters().isEmpty()
            ? builderClass
            : make.TypeApply(builderClass, List.nil());
        body.append(make.VarDef(make.Modifiers(0), names.fromString("b"), builderType(ownTypeArgs()),
            make.NewClass(null, List.nil(), instantiated, List.nil(), null)));
        for (FieldSpec f : fields) {
            body.append(make.Exec(make.Apply(
                List.nil(),
                make.Select(make.Ident(names.fromString("b")),
                    names.fromString(f.setters.setName(f.name, f.isBoolean))),
                List.of(readFromThis(f))
            )));
        }
        body.append(make.Return(make.Ident(names.fromString("b"))));
        return body.toList();
    }

    /**
     * Reads one slot's value off {@code this}, in the order the sibling's
     * {@code from(T)} reads it off its argument, copying a mutable collection.
     *
     * @param f the slot
     * @return the read expression
     */
    private JCExpression readFromThis(FieldSpec f) {
        JCExpression read;
        if (f.obtainViaStatic && f.obtainViaMethod != null) {
            read = make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString(target.getSimpleName().toString())),
                    names.fromString(f.obtainViaMethod)),
                List.of(make.Ident(names._this)));
        } else if (f.obtainViaMethod != null) {
            read = call(f.obtainViaMethod);
        } else if (f.obtainViaField != null) {
            read = make.Select(make.Ident(names._this), names.fromString(f.obtainViaField));
        } else {
            read = call(f.name);
        }
        if (f.isListLike && !f.isSet) return copy("java.util.ArrayList", read);
        if (f.isSet) return copy("java.util.LinkedHashSet", read);
        if (f.isMap) return copy("java.util.LinkedHashMap", read);
        return read;
    }

    /** {@code this.<method>()}. */
    private JCExpression call(String method) {
        return make.Apply(List.nil(), make.Select(make.Ident(names._this), names.fromString(method)), List.nil());
    }

    /** {@code new <collection><>(source)}. */
    private JCExpression copy(String collection, JCExpression source) {
        return make.NewClass(null, List.nil(), make.TypeApply(types.qualIdent(collection), List.nil()),
            List.of(source), null);
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

    private JCMethodDecl method(String name, long flags, List<JCAnnotation> annotations,
                                List<JCTypeParameter> typeParams, List<JCVariableDecl> params,
                                JCExpression returnType, List<JCStatement> body) {
        JCBlock block = make.Block(0, body);
        // Interface members are implicitly public; PUBLIC is set explicitly so
        // the flag set reads the same as the class path's.
        JCMethodDecl m = make.MethodDef(
            make.Modifiers(flags | Flags.PUBLIC, annotations),
            names.fromString(name),
            returnType,
            typeParams,
            params,
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(m, generated);
        return m;
    }

    private void append(JCMethodDecl method) {
        bridge.compat().appendDef(targetTree, method);
    }

    private boolean absent(String name, String signature, boolean collides) {
        if (!collides) return true;
        messager.printMessage(Diagnostic.Kind.NOTE,
            "@ClassBuilder skipped bootstrap '" + name + "' - target already declares "
                + name + signature,
            target);
        return false;
    }

}
