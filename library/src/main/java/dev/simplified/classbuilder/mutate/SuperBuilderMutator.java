package dev.simplified.classbuilder.mutate;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.FieldSpec;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.Collection;

/**
 * SuperBuilder mutator for abstract targets and concrete subclasses whose
 * direct super carries {@code @ClassBuilder}. Produces a self-typed nested
 * {@code Builder} plus a protected copy constructor on the target.
 *
 * <h2>Shape matrix</h2>
 * <ul>
 *   <li><b>Abstract target, no annotated super</b> - root of a chain. The
 *       injected {@code Builder} is {@code abstract}, parameterised as
 *       {@code <T extends Target, B extends Builder<T, B>>}, with
 *       {@code protected abstract B self()} and {@code public abstract T build()}.
 *       The target gains {@code protected Target(Builder<?, ?> b)}.</li>
 *   <li><b>Concrete target, direct super annotated</b> - link in a chain. The
 *       injected {@code Builder} extends {@code Super.Builder<Target, Builder>},
 *       declares only this type's own fields, overrides {@code self()} to
 *       return {@code this}, and overrides {@code build()} to call
 *       {@code new Target(this)}. The target gains {@code protected Target(Builder b)}
 *       whose body is {@code super(b); this.ownField = b.ownField; ...} plus
 *       the regular bootstrap methods ({@code builder}, {@code from}, {@code mutate}).</li>
 * </ul>
 */
final class SuperBuilderMutator {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final Messager messager;
    private final AnnotatedSuper annotatedSuper; // null when root of chain
    private final Collection<FieldSpec> chainFields;
    private final ContractAnnotations contracts;
    private final String selfParamT;
    private final String selfParamB;

    SuperBuilderMutator(MutationContext ctx, Messager messager, AnnotatedSuper annotatedSuper,
                        Collection<FieldSpec> chainFields) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.messager = messager;
        this.annotatedSuper = annotatedSuper;
        this.chainFields = chainFields;
        this.contracts = ctx.contracts();
        this.selfParamT = ctx.selfTypeName();
        this.selfParamB = ctx.selfBuilderName();
    }

    void mutate() {
        boolean isAbstract = ctx.target().getModifiers().flags == 0
            ? false
            : (ctx.target().getModifiers().flags & Flags.ABSTRACT) != 0;

        // Guard against duplicate-nested from a re-run.
        if (hasExistingNested(ctx.target(), ctx.builderName())) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@ClassBuilder skipped injection: class " + ctx.targetSimpleName()
                    + " already declares a nested '" + ctx.builderName() + "' type",
                ctx.targetElement());
            return;
        }

        // $default$<fieldName>() providers for this target's retainInit fields
        // land on the target itself (not on the nested Builder), so inherited
        // chain fields keep their providers on their respective declaring
        // classes. FieldMutators.defaultInitializer references them by
        // ctx.targetSimpleName() - always the field's own class.
        new RetainedInitFactory(ctx, messager).appendAll();

        JCClassDecl nested;
        if (annotatedSuper == null) {
            // Abstract root
            nested = buildAbstractRootBuilder();
        } else if (!isAbstract) {
            // Concrete link
            nested = buildConcreteLinkBuilder();
        } else {
            // Chained abstract - abstract builder extending super's abstract builder
            nested = buildChainedAbstractBuilder();
        }
        ctx.bridge().compat().appendDef(ctx.target(), nested);

        // Copy constructor. Its parameter type follows the builder's shape, not
        // the target's: a root and a chained abstract both carry the self-typed
        // parameters and so take the wildcard form, while a concrete link's
        // builder binds them and is referenced plainly. Only the root has no
        // annotated super to delegate to.
        if (ctx.config().generateCopyConstructor()
            && !CopyConstructorFactory.hasCopyConstructor(ctx.target(), ctx.builderName())) {
            boolean selfTypedBuilder = annotatedSuper == null || isAbstract;
            JCMethodDecl ctor = new CopyConstructorFactory(ctx)
                .build(selfTypedBuilder, annotatedSuper != null);
            ctx.bridge().compat().appendDef(ctx.target(), ctor);
        }

        // Bootstrap methods only on concrete targets.
        if (!isAbstract) {
            new BootstrapMethodFactory(ctx, messager, chainFields).appendAll();
        }
    }

    // ------------------------------------------------------------------
    // Abstract root: Builder<T extends Target, B extends Builder<T, B>>
    // ------------------------------------------------------------------

    private JCClassDecl buildAbstractRootBuilder() {
        ListBuffer<JCTree> defs = new ListBuffer<>();
        // Fields
        FieldMutators fm = new FieldMutators(ctx);
        for (FieldSpec f : ctx.fields()) {
            JCVariableDecl fd = fm.fieldDecl(f);
            AstMarkers.markGenerated(fd);
            defs.append(fd);
            JCVariableDecl marker = fm.replacedMarkerDecl(f);
            if (marker != null) {
                AstMarkers.markGenerated(marker);
                defs.append(marker);
            }
        }
        // Self-typed setters
        SelfTypedSetters self = new SelfTypedSetters(ctx);
        for (FieldSpec f : ctx.fields()) {
            for (JCMethodDecl s : self.setters(f)) defs.append(s);
        }
        // protected abstract B self(); - returns this under self-typed generics.
        defs.append(abstractMethod(Flags.PROTECTED, "self", identType(selfParamB), List.nil(),
            contracts.thisReturnNullary()));
        // public abstract T build(); - each concrete subclass produces a fresh T.
        defs.append(abstractMethod(Flags.PUBLIC, ctx.config().buildMethodName(),
            identType(selfParamT), List.nil(), contracts.newReturnNullary()));

        JCClassDecl nested = make.ClassDef(
            make.Modifiers(ctx.accessFlag() | Flags.STATIC | Flags.ABSTRACT),
            names.fromString(ctx.builderName()),
            selfTypedTypeParameters(),
            null,
            List.nil(),
            defs.toList()
        );
        AstMarkers.markGenerated(nested);
        return nested;
    }

    // ------------------------------------------------------------------
    // Concrete link: Builder extends Super.Builder<Target, Builder>
    // ------------------------------------------------------------------

    private JCClassDecl buildConcreteLinkBuilder() {
        ListBuffer<JCTree> defs = new ListBuffer<>();

        // Concrete-link setters return the unqualified Builder (the subclass's
        // own builder type), not the type parameter B - the subclass builder
        // is NOT generic. The SuperBuilder generics live only on the abstract
        // root; concrete links bind them.
        FieldMutators fm = new FieldMutators(ctx);
        for (FieldSpec f : ctx.fields()) {
            JCVariableDecl fd = fm.fieldDecl(f);
            AstMarkers.markGenerated(fd);
            defs.append(fd);
            JCVariableDecl marker = fm.replacedMarkerDecl(f);
            if (marker != null) {
                AstMarkers.markGenerated(marker);
                defs.append(marker);
            }
        }
        for (FieldSpec f : ctx.fields()) {
            for (JCMethodDecl s : fm.setters(f)) defs.append(s);
        }

        // @Override protected Builder self() { return this; }
        defs.append(concreteMethod(Flags.PROTECTED, "self", ctx.builderType(),
            List.nil(), List.of(make.Return(make.Ident(names._this))),
            contracts.thisReturnNullary()));

        // @Override public Target build() { return new Target(this); }
        JCStatement buildReturn = make.Return(make.NewClass(
            null, List.nil(),
            ctx.targetType(),
            List.of(make.Ident(names._this)),
            null
        ));
        defs.append(concreteMethod(Flags.PUBLIC, ctx.config().buildMethodName(),
            ctx.targetType(),
            List.nil(),
            List.of(buildReturn),
            contracts.newReturnNullary()));

        // extends Super.Builder<superArgs..., Target, Builder>
        JCExpression extendsExpr = superBuilderType(List.of(ctx.targetType(), ctx.builderType()));

        JCClassDecl nested = make.ClassDef(
            make.Modifiers(ctx.accessFlag() | Flags.STATIC),
            names.fromString(ctx.builderName()),
            ctx.typeParams(),
            extendsExpr,
            List.nil(),
            defs.toList()
        );
        AstMarkers.markGenerated(nested);
        return nested;
    }

    // ------------------------------------------------------------------
    // Chained abstract: abstract Builder<T, B> extends Super.Builder<T, B>
    // ------------------------------------------------------------------

    private JCClassDecl buildChainedAbstractBuilder() {
        ListBuffer<JCTree> defs = new ListBuffer<>();
        FieldMutators fm = new FieldMutators(ctx);
        for (FieldSpec f : ctx.fields()) {
            JCVariableDecl fd = fm.fieldDecl(f);
            AstMarkers.markGenerated(fd);
            defs.append(fd);
            JCVariableDecl marker = fm.replacedMarkerDecl(f);
            if (marker != null) {
                AstMarkers.markGenerated(marker);
                defs.append(marker);
            }
        }
        SelfTypedSetters self = new SelfTypedSetters(ctx);
        for (FieldSpec f : ctx.fields()) {
            for (JCMethodDecl s : self.setters(f)) defs.append(s);
        }
        // self() and build() stay abstract - inherited.

        // The two self-type arguments forward this builder's own parameters up
        // the chain rather than binding them, keeping the link abstract.
        JCExpression extendsExpr = superBuilderType(List.of(
            make.Ident(names.fromString(selfParamT)),
            make.Ident(names.fromString(selfParamB))
        ));

        JCClassDecl nested = make.ClassDef(
            make.Modifiers(ctx.accessFlag() | Flags.STATIC | Flags.ABSTRACT),
            names.fromString(ctx.builderName()),
            selfTypedTypeParameters(),
            extendsExpr,
            List.nil(),
            defs.toList()
        );
        AstMarkers.markGenerated(nested);
        return nested;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Builds {@code <T extends Target, B extends Builder<T, B>>}, or on a
     * generic target {@code <V, T extends Target<V>, B extends Builder<V, T, B>>}
     * - the target's own parameters lead, so a subclass binds them in the same
     * order the target declares them.
     */
    private List<JCTypeParameter> selfTypedTypeParameters() {
        ListBuffer<JCTypeParameter> out = new ListBuffer<>();
        out.appendList(ctx.typeParams());
        JCTypeParameter tpT = make.TypeParameter(
            names.fromString(selfParamT),
            List.of(ctx.targetType())
        );
        ListBuffer<JCExpression> builderArgs = new ListBuffer<>();
        builderArgs.appendList(ctx.typeArgs());
        builderArgs.append(make.Ident(names.fromString(selfParamT)));
        builderArgs.append(make.Ident(names.fromString(selfParamB)));
        JCExpression builderApplied = make.TypeApply(
            make.Ident(names.fromString(ctx.builderName())),
            builderArgs.toList()
        );
        JCTypeParameter tpB = make.TypeParameter(names.fromString(selfParamB), List.of(builderApplied));
        out.append(tpT);
        out.append(tpB);
        return out.toList();
    }

    /**
     * The {@code extends Super.Builder<...>} clause. The leading arguments are
     * whatever the target passes to its superclass ({@code String} for
     * {@code class StringBox extends Box<String>}), followed by the two
     * self-type arguments - bound to the concrete types on a concrete link, and
     * forwarded as this builder's own parameters on a chained abstract.
     *
     * @param selfArgs the two trailing self-type arguments
     * @return the parameterised supertype expression
     */
    private JCExpression superBuilderType(List<JCExpression> selfArgs) {
        ListBuffer<JCExpression> args = new ListBuffer<>();
        for (String arg : annotatedSuper.typeArguments()) args.append(ctx.types().parseType(arg));
        args.appendList(selfArgs);
        return make.TypeApply(
            make.Select(
                make.Ident(names.fromString(annotatedSuper.simpleName())),
                names.fromString(ctx.builderName())
            ),
            args.toList()
        );
    }

    private JCExpression identType(String simpleName) {
        return make.Ident(names.fromString(simpleName));
    }

    private JCMethodDecl abstractMethod(long flags, String name, JCExpression returnType,
                                        List<JCVariableDecl> params,
                                        List<com.sun.tools.javac.tree.JCTree.JCAnnotation> annotations) {
        JCMethodDecl m = make.MethodDef(
            make.Modifiers(flags | Flags.ABSTRACT, annotations),
            names.fromString(name),
            returnType,
            List.nil(),
            params,
            List.nil(),
            null,
            null
        );
        AstMarkers.markGenerated(m);
        return m;
    }

    private JCMethodDecl concreteMethod(long flags, String name, JCExpression returnType,
                                        List<JCVariableDecl> params, List<JCStatement> body,
                                        List<com.sun.tools.javac.tree.JCTree.JCAnnotation> annotations) {
        JCBlock block = make.Block(0, body);
        JCMethodDecl m = make.MethodDef(
            make.Modifiers(flags, annotations),
            names.fromString(name),
            returnType,
            List.nil(),
            params,
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(m);
        return m;
    }

    private static boolean hasExistingNested(JCClassDecl target, String nestedName) {
        for (JCTree def : target.defs) {
            if (def instanceof JCClassDecl c && c.name.toString().equals(nestedName)) return true;
        }
        return false;
    }

}
