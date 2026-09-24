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
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.classbuilder.apt.ChainBuilderReach;
import dev.simplified.classbuilder.apt.ChainMemberIndex;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
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
 *   <li><b>Abstract target, direct super annotated</b> - chained abstract. The
 *       injected {@code Builder} stays abstract and self-typed, forwarding its
 *       pair to {@code Super.Builder<T, B>}, and carries the setters only -
 *       {@code self()} and {@code build()} are inherited.</li>
 * </ul>
 *
 * <p>A target that declares a nested class of the builder's name has the same
 * members merged into that class instead, spelled in the declaration's own
 * self-type names, once the declaration passes the shape its role requires.
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

        // A declared class of the builder's name is merged into below. One this
        // pipeline generated is a builder an earlier run already produced, and
        // merging into it would skip every member by name and report them all.
        JCClassDecl declared = DeclaredBuilderMerge.declaredBuilder(ctx.target(), ctx.builderName());
        if (declared != null && AstMarkers.isGenerated(declared)) return;

        // The extends clause a link generates passes the ancestor's builder the
        // arguments the link's own extends clause gives the ancestor, so a raw
        // clause over a generic ancestor leaves it too few whoever wrote that
        // builder - one generated in this round, one read off a class file, or
        // the author's. Asked first, of the two counts alone, and reported on
        // the link's annotation.
        String raw = annotatedSuper == null
            ? null
            : DeclaredBuilderShape.rawGenericAncestor(ctx.targetSimpleName(), annotatedSuper.simpleName(),
                annotatedSuper.element().getTypeParameters().size(), annotatedSuper.typeArguments().size());
        if (raw != null) {
            messager.printMessage(Diagnostic.Kind.ERROR, raw, ctx.targetElement(),
                new AnnotationLookup().findMirror(ctx.targetElement(), ClassBuilder.class.getName()));
            return;
        }

        // The extends clause a link generates names the ancestor's builder and
        // passes it the ancestor's own arguments plus the self-typed pair, so a
        // builder the ancestor's author wrote - which takes whatever they
        // declared, usually none - cannot receive it. Emitting the clause anyway
        // fails at attribution on a generated line, which is the one place an
        // author cannot act. Asked of a target declaring its own builder too,
        // whose extends clause has to name the ancestor's builder just as a
        // generated one does. Absent means "not generated yet" rather than "not
        // there", so only a builder that is present and cannot take the
        // arguments is refused.
        if (annotatedSuper != null && ancestorBuilderCannotBeExtended()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                DeclaredBuilderShape.ancestorDeclaresItsOwnBuilder(ctx.targetSimpleName(),
                    annotatedSuper.simpleName()),
                ctx.targetElement());
            return;
        }

        // The same clause has to name the ancestor's builder from the link's
        // package and top-level class, and the constructor javac gives the
        // link's builder calls the ancestor's no-argument one through its
        // implicit super(). A builder the ancestor's author wrote, or the
        // generator writes at the ancestor's access, out of either's reach fails
        // on a generated line, so the link is refused on its own annotation
        // instead.
        String unreachable = annotatedSuper == null ? null : ancestorBuilderUnreachable();
        if (unreachable != null) {
            messager.printMessage(Diagnostic.Kind.ERROR, unreachable, ctx.targetElement());
            return;
        }

        // $default$<fieldName>() providers for this target's retainInit fields
        // land on the target itself (not on the nested Builder), so inherited
        // chain fields keep their providers on their respective declaring
        // classes. FieldMutators.defaultInitializer references them by
        // ctx.targetSimpleName() - always the field's own class.
        // The copy constructor is appended below, where it assigns every field
        // the builder selects, and is the target's only one when no other is
        // declared.
        boolean copyConstructorGenerated = ctx.config().generateCopyConstructor()
            && !CopyConstructorFactory.hasCopyConstructor(ctx.target(), ctx.builderName());
        boolean onlyBuilderConstructor = copyConstructorGenerated
            && AllArgsConstructorFactory.onlyBuilderConstructor(ctx.target(), null);
        new RetainedInitFactory(ctx, messager, copyConstructorGenerated, onlyBuilderConstructor).appendAll();

        // A declared builder gets the role's members appended into it, in the
        // declaration's own names for the self-typed pair; a refused shape has
        // been reported, and nothing below it can compile against that class.
        ChainRole role = ChainRole.of(isAbstract, annotatedSuper != null);
        if (declared == null) {
            JCClassDecl built = buildBuilder(role);
            BuilderMutator.rejectUnoverridableObjectMethods(ctx, messager, built.defs);
            ctx.bridge().compat().appendDef(ctx.target(), built);
        } else if (!new DeclaredBuilderMerge(ctx, messager).merge(ctx.target(), ctx.targetElement(),
            declared, role, membersFor(role, declared), annotatedSuper)) {
            return;
        }

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

        // Bootstrap methods only on concrete targets. Handed the declared builder
        // when there is one, since every entry point instantiates it and an
        // author's class may have no constructor they can call.
        if (!isAbstract) {
            new BootstrapMethodFactory(ctx, messager, chainFields, declared).appendAll();
        }
    }

    /**
     * Builds the nested class the role generates when the target declares none.
     *
     * @param role the target's position in the chain
     * @return the generated builder class
     */
    private JCClassDecl buildBuilder(ChainRole role) {
        return switch (role) {
            case CONCRETE_LINK -> buildConcreteLinkBuilder();
            case CHAINED_ABSTRACT -> buildChainedAbstractBuilder();
            default -> buildAbstractRootBuilder();
        };
    }

    /**
     * Produces the members the role merges into a declared builder.
     *
     * <p>The self-typed roles spell them in the declaration's own trailing pair,
     * which is the pair the shape check measured. A declaration too short to
     * carry one is refused by that check before any member is appended, so the
     * generator's names the list is then spelled in are never emitted.
     *
     * @param role the target's position in the chain
     * @param declared the builder the author wrote
     * @return the members, in emission order
     */
    private List<JCTree> membersFor(ChainRole role, JCClassDecl declared) {
        java.util.List<String> pair = DeclaredBuilderShape.selfNames(role,
            DeclaredBuilderMerge.targetParameterNames(ctx),
            DeclaredBuilderMerge.declaredParameterNames(declared));
        return switch (role) {
            case CONCRETE_LINK -> linkBuilderMembers();
            case CHAINED_ABSTRACT -> chainedAbstractBuilderMembers(pair.get(0), pair.get(1));
            default -> rootBuilderMembers(pair.get(0), pair.get(1));
        };
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

        // @Override protected Builder self() { return this; } - public where an
        // ancestor's author made the one it overrides public.
        long selfAccess = ChainBuilderReach.linkSelfPublic(nearestAuthoredSelfPublic())
            ? Flags.PUBLIC
            : Flags.PROTECTED;
        defs.append(concreteMethod(selfAccess, ChainBuilderReach.SELF, ctx.builderType(),
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
     * Renders why the link's builder cannot extend its ancestor's builder, as
     * {@link ChainBuilderReach#unreachable} decides it for one the ancestor's
     * author declared and {@link ChainBuilderReach#unreachableGenerated} for one
     * the generator writes at the access the ancestor's annotation asks for.
     *
     * <p>Read through the two-view index, so an ancestor in this round is asked
     * of its tree and annotation, and one compiled earlier of its class file.
     *
     * @return the error, or null when the link can extend it
     */
    private String ancestorBuilderUnreachable() {
        ChainMemberIndex ancestor = ChainMemberIndex.of(ctx.bridge(), annotatedSuper.element(),
            ctx.builderName(), annotatedSuper.role());
        Elements elements = ctx.bridge().processingEnvironment().getElementUtils();
        boolean samePackage = elements.getPackageOf(ctx.targetElement())
            .equals(elements.getPackageOf(annotatedSuper.element()));
        boolean sameTopLevel = outermost(ctx.targetElement()).equals(outermost(annotatedSuper.element()));
        ChainBuilderReach.Unreachable reason = ancestor.unreachable(samePackage, sameTopLevel);
        return reason == null
            ? null
            : ChainBuilderReach.unreachableAncestorBuilder(reason, ctx.targetSimpleName(),
                annotatedSuper.simpleName(), ctx.builderName());
    }

    /**
     * The top-level class a type is nested in, or the type itself when it is
     * one.
     *
     * @param type the type
     * @return its outermost enclosing class
     */
    private static TypeElement outermost(TypeElement type) {
        TypeElement out = type;
        while (out.getEnclosingElement() instanceof TypeElement enclosing) out = enclosing;
        return out;
    }

    /**
     * Whether the nearest {@code self()} an ancestor's author wrote, or a root's
     * builder inherits, is public, walking the annotated ancestors upward from
     * the direct one.
     *
     * <p>A builder the generator writes, or one with no {@code self()} of either
     * kind, says nothing and the walk goes on to the ancestor's own annotated
     * superclass, so a leaf below a generated chained abstract reads its root's.
     *
     * @return whether it is public, or null when no ancestor has one
     */
    private Boolean nearestAuthoredSelfPublic() {
        for (AnnotatedSuper ancestor = annotatedSuper; ancestor != null;
             ancestor = BuilderMutator.findAnnotatedDirectSuper(ancestor.element())) {
            Boolean selfPublic = ChainMemberIndex.of(ctx.bridge(), ancestor.element(), ctx.builderName(),
                ancestor.role()).selfPublic();
            if (selfPublic != null) return selfPublic;
        }
        return null;
    }

    /**
     * Builds the {@code extends Super.Builder<...>} clause. The leading
     * arguments are whatever the target passes to its superclass
     * ({@code String} for {@code class StringBox extends Box<String>}), followed
     * by the two self-type arguments - bound to the concrete types on a concrete
     * link, and forwarded as this builder's own parameters on a chained abstract.
     *
     * <p>The ancestor's builder is spelled by its canonical name - package,
     * enclosing classes, ancestor, builder - since the clause lands in the
     * link's file, which may name the ancestor fully qualified, or through a
     * class it nests in, and import nothing a shorter spelling would resolve
     * through.
     *
     * @param selfArgs the two trailing self-type arguments
     * @return the parameterised supertype expression
     */
    private JCExpression superBuilderType(List<JCExpression> selfArgs) {
        ListBuffer<JCExpression> args = new ListBuffer<>();
        for (String arg : annotatedSuper.typeArguments()) args.append(ctx.types().parseType(arg));
        args.appendList(selfArgs);
        String canonical = annotatedSuper.element().getQualifiedName() + "." + ctx.builderName();
        return make.TypeApply(ctx.types().parseType(canonical), args.toList());
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

}
