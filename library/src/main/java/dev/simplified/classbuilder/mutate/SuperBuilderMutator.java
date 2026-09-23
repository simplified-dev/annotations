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
import dev.simplified.classbuilder.apt.ChainMemberIndex;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

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

        // The extends clause a link generates names the ancestor's builder and
        // passes it the ancestor's own arguments plus the self-typed pair, so a
        // builder the ancestor's author wrote - which takes whatever they
        // declared, usually none - cannot receive it. Emitting the clause anyway
        // fails at attribution on a generated line, which is the one place an
        // author cannot act. Absent means "not generated yet" rather than "not
        // there", so only a builder that is present and cannot take the
        // arguments is refused.
        if (annotatedSuper != null && ancestorBuilderCannotBeExtended()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                DeclaredBuilderShape.ancestorDeclaresItsOwnBuilder(ctx.targetSimpleName(),
                    annotatedSuper.simpleName()),
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

    /**
     * Builds the abstract root's nested class, in the generator's names, around
     * {@link #rootBuilderMembers}.
     *
     * @return the generated builder class
     */
    private JCClassDecl buildAbstractRootBuilder() {
        List<JCTree> defs = rootBuilderMembers(selfParamT, selfParamB);
        JCClassDecl nested = make.ClassDef(
            make.Modifiers(ctx.accessFlag() | Flags.STATIC | Flags.ABSTRACT),
            names.fromString(ctx.builderName()),
            selfTypedTypeParameters(),
            null,
            List.nil(),
            defs
        );
        AstMarkers.markGenerated(nested, ctx.generated());
        return nested;
    }

    /**
     * Produces every member of an abstract root's builder, in emission order -
     * the slot fields with their replaced markers, the self-typed setters, then
     * the abstract {@code self()} and {@code build()}.
     *
     * <p>Separate from the class header so the same members can go into a
     * builder the author declared, spelled in the self-type names that
     * declaration gives its trailing pair.
     *
     * @param selfType the name of the type parameter {@code build()} returns
     * @param selfBuilder the name of the type parameter the setters and {@code self()} return
     * @return the generated members
     */
    private List<JCTree> rootBuilderMembers(String selfType, String selfBuilder) {
        ListBuffer<JCTree> defs = new ListBuffer<>();
        appendSlotFields(defs);
        // Self-typed setters
        SelfTypedSetters self = new SelfTypedSetters(ctx, selfBuilder);
        for (FieldSpec f : ctx.fields()) {
            for (JCMethodDecl s : self.setters(f)) defs.append(s);
        }
        // protected abstract B self(); - returns this under self-typed generics.
        defs.append(abstractMethod(Flags.PROTECTED, "self", identType(selfBuilder), List.nil(),
            contracts.thisReturnNullary()));
        // public abstract T build(); - each concrete subclass produces a fresh T.
        defs.append(abstractMethod(Flags.PUBLIC, ctx.config().buildMethodName(),
            identType(selfType), List.nil(), contracts.newReturnNullary()));
        return defs.toList();
    }

    // ------------------------------------------------------------------
    // Concrete link: Builder extends Super.Builder<Target, Builder>
    // ------------------------------------------------------------------

    /**
     * Builds a concrete link's nested class, extending the ancestor's builder
     * with the self-typed pair bound, around {@link #linkBuilderMembers}.
     *
     * @return the generated builder class
     */
    private JCClassDecl buildConcreteLinkBuilder() {
        List<JCTree> defs = linkBuilderMembers();

        // extends Super.Builder<superArgs..., Target, Builder>
        JCExpression extendsExpr = superBuilderType(List.of(ctx.targetType(), ctx.builderType()));

        JCClassDecl nested = make.ClassDef(
            make.Modifiers(ctx.accessFlag() | Flags.STATIC),
            names.fromString(ctx.builderName()),
            ctx.typeParams(),
            extendsExpr,
            List.nil(),
            defs
        );
        AstMarkers.markGenerated(nested, ctx.generated());
        return nested;
    }

    /**
     * Produces every member of a concrete link's builder, in emission order -
     * the slot fields with their replaced markers, the setters, then the
     * concrete {@code self()} and {@code build()} overriding the ancestor's.
     *
     * <p>The link's builder binds the self-typed pair rather than declaring it,
     * so its members name the builder and the target directly and need no
     * parameter names.
     *
     * @return the generated members
     */
    private List<JCTree> linkBuilderMembers() {
        ListBuffer<JCTree> defs = new ListBuffer<>();

        // Concrete-link setters return the unqualified Builder (the subclass's
        // own builder type), not the type parameter B - the subclass builder
        // is NOT generic. The SuperBuilder generics live only on the abstract
        // root; concrete links bind them.
        FieldMutators fm = appendSlotFields(defs);
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
        return defs.toList();
    }

    // ------------------------------------------------------------------
    // Chained abstract: abstract Builder<T, B> extends Super.Builder<T, B>
    // ------------------------------------------------------------------

    /**
     * Builds a chained abstract's nested class, forwarding its self-typed pair
     * to the ancestor's builder, around {@link #chainedAbstractBuilderMembers}.
     *
     * @return the generated builder class
     */
    private JCClassDecl buildChainedAbstractBuilder() {
        List<JCTree> defs = chainedAbstractBuilderMembers(selfParamT, selfParamB);

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
            defs
        );
        AstMarkers.markGenerated(nested, ctx.generated());
        return nested;
    }

    /**
     * Produces every member of a chained abstract's builder, in emission order -
     * the slot fields with their replaced markers, then the self-typed setters.
     * {@code self()} and {@code build()} stay abstract and are inherited.
     *
     * <p>Takes the pair of names the root producer takes, so both self-typed
     * roles are handed the same two names off a declared builder; only the
     * builder's is spelled, since nothing here returns the built type.
     *
     * @param selfType the name of the type parameter the inherited {@code build()} returns
     * @param selfBuilder the name of the type parameter the setters return
     * @return the generated members
     */
    private List<JCTree> chainedAbstractBuilderMembers(String selfType, String selfBuilder) {
        ListBuffer<JCTree> defs = new ListBuffer<>();
        appendSlotFields(defs);
        SelfTypedSetters self = new SelfTypedSetters(ctx, selfBuilder);
        for (FieldSpec f : ctx.fields()) {
            for (JCMethodDecl s : self.setters(f)) defs.append(s);
        }
        return defs.toList();
    }

    /**
     * Appends each slot's field, followed by its replaced marker where it has
     * one, both marked generated.
     *
     * @param defs the member list being built
     * @return the field mutator the fields were produced with
     */
    private FieldMutators appendSlotFields(ListBuffer<JCTree> defs) {
        FieldMutators fm = new FieldMutators(ctx);
        for (FieldSpec f : ctx.fields()) {
            JCVariableDecl fd = fm.fieldDecl(f);
            AstMarkers.markGenerated(fd, ctx.generated());
            defs.append(fd);
            JCVariableDecl marker = fm.replacedMarkerDecl(f);
            if (marker != null) {
                AstMarkers.markGenerated(marker, ctx.generated());
                defs.append(marker);
            }
        }
        return fm;
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
     * Whether the ancestor's builder is present and takes a different number of
     * type parameters than the generated extends clause passes it.
     *
     * <p>Read through the two-view index, so an ancestor compiled in this round
     * is asked of its tree and one compiled earlier of its element model. A
     * builder that is not there yet answers false: within a round the order
     * targets are processed in is unspecified, and one this pass will generate
     * arrives in exactly the shape the clause expects.
     *
     * @return whether the clause would name a builder that cannot take it
     */
    private boolean ancestorBuilderCannotBeExtended() {
        ChainMemberIndex ancestor = ChainMemberIndex.of(ctx.bridge(), annotatedSuper.element(),
            ctx.builderName(), annotatedSuper.role());
        if (!ancestor.builderPresent()) return false;
        return ancestor.builderTypeParameters() != annotatedSuper.typeArguments().size() + 2;
    }

    /**
     * Builds the {@code extends Super.Builder<...>} clause. The leading
     * arguments are whatever the target passes to its superclass
     * ({@code String} for {@code class StringBox extends Box<String>}), followed
     * by the two self-type arguments - bound to the concrete types on a concrete
     * link, and forwarded as this builder's own parameters on a chained abstract.
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
        AstMarkers.markGenerated(m, ctx.generated());
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
        AstMarkers.markGenerated(m, ctx.generated());
        return m;
    }

    private static boolean hasExistingNested(JCClassDecl target, String nestedName) {
        for (JCTree def : target.defs) {
            if (def instanceof JCClassDecl c && c.name.toString().equals(nestedName)) return true;
        }
        return false;
    }

}
