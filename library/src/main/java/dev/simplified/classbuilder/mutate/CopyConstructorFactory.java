package dev.simplified.classbuilder.mutate;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

/**
 * Produces the copy constructor used by the SuperBuilder chain:
 *
 * <pre>{@code
 * // abstract Target, no annotated super:
 * protected Target(Builder<?, ?> b) { this.f1 = b.f1; this.f2 = b.f2; ... }
 *
 * // concrete Target that extends an annotated super:
 * protected Target(Builder b) { super(b); this.ownField = b.ownField; ... }
 * }</pre>
 *
 * <p>Fields are assigned in declaration order. Nested {@code Builder.field}
 * access is legal here because {@code Builder} is declared inside
 * {@code Target}, so private members are reachable from the enclosing class.
 */
final class CopyConstructorFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;

    CopyConstructorFactory(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
    }

    /**
     * Builds the copy constructor for one link of a SuperBuilder chain.
     *
     * @param selfTypedBuilder whether this target's builder carries the
     *        self-typed parameters, in which case the constructor takes the
     *        wildcard form {@code Builder<?, ?>} so it accepts any subclass
     *        builder; a concrete link's builder binds them and is named plainly
     * @param callSuper whether to open with {@code super(b)}, letting an
     *        annotated parent drain the fields it declares before this type
     *        copies its own
     * @return the generated constructor
     */
    JCMethodDecl build(boolean selfTypedBuilder, boolean callSuper) {
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("b"),
            selfTypedBuilder ? selfTypedBuilderType() : ctx.builderType(),
            null
        );
        ListBuffer<JCStatement> body = new ListBuffer<>();
        if (callSuper) {
            body.append(make.Exec(make.Apply(
                List.nil(),
                make.Ident(names._super),
                List.of(make.Ident(names.fromString("b")))
            )));
        }
        for (FieldSpec f : ctx.fields()) body.append(assignFromBuilder(f));
        return buildCtor(List.of(param), body.toList(), Flags.PROTECTED);
    }

    /**
     * {@code Builder<?, ?>}, or on a generic target
     * {@code Builder<V, ?, ?>} - the target's own parameters are bound, since
     * the enclosing class supplies them, while the two self-type slots stay
     * wildcards so any subclass builder is accepted.
     */
    private JCExpression selfTypedBuilderType() {
        ListBuffer<JCExpression> args = new ListBuffer<>();
        args.appendList(ctx.typeArgs());
        args.append(make.Wildcard(make.TypeBoundKind(BoundKind.UNBOUND), null));
        args.append(make.Wildcard(make.TypeBoundKind(BoundKind.UNBOUND), null));
        return make.TypeApply(make.Ident(names.fromString(ctx.builderName())), args.toList());
    }

    private JCStatement assignFromBuilder(FieldSpec f) {
        JCExpression lhs = make.Select(make.Ident(names._this), names.fromString(f.name));
        // Builder slot is Supplier<T> for a @Lazy field while the target field
        // is Lazy<T>. Wrap the supplier as Lazy.of(...) at copy time so the
        // target stores a Lazy and the supplier's call is deferred to the first
        // getter invocation. The field-attributed overload names the field if
        // the slot was never filled, rather than letting a null supplier reach
        // the first get().
        // A collected instance default reads both the contributed container and
        // its replaced marker off the builder, and folds them against the
        // instance-computed default.
        JCExpression rhs = ctx.isCollectedInstanceDefault(f)
            ? AllArgsConstructorFactory.mergeCall(ctx, f, slotRead(f), markerRead(f))
            : ctx.isInstanceDefault(f.name) ? defaultingRhs(f)
            : f.lazy ? lazyOf(slotRead(f), f)
            : slotRead(f);
        return make.Exec(make.Assign(lhs, rhs));
    }

    /**
     * Right-hand side for a field whose default reads instance state, mirroring
     * {@code AllArgsConstructorFactory.defaultingRhs} for the SuperBuilder
     * chain. The slot is {@code Supplier<T>}, so null means it was never filled
     * and the instance {@code $default$} provider supplies the value instead.
     * A constructor is where {@code this} exists, exactly as it does in the
     * ordinary field initializer the expression came from.
     *
     * <pre>{@code
     * // plain:  b.name != null ? b.name.get() : $default$name()
     * // @Lazy:  b.v != null ? Lazy.of(b.v, owner, "v") : Lazy.of(() -> $default$v())
     * }</pre>
     */
    private JCExpression defaultingRhs(FieldSpec f) {
        JCExpression isSet = make.Binary(JCTree.Tag.NE, slotRead(f), make.Literal(TypeTag.BOT, null));
        JCExpression providerCall = make.Apply(
            List.nil(),
            make.Ident(names.fromString(RetainedInitFactory.providerName(f.name))),
            List.nil()
        );
        if (!f.lazy) {
            JCExpression get = make.Apply(
                List.nil(),
                make.Select(slotRead(f), names.fromString("get")),
                List.nil()
            );
            return make.Conditional(isSet, get, providerCall);
        }
        // A @Lazy field keeps its deferral on both branches: the supplied
        // supplier is wrapped verbatim, and the default becomes a lambda over
        // the provider so it is still not run until the first getter call.
        JCExpression deferred = make.Apply(
            List.nil(),
            make.Select(ctx.types().qualIdent("dev.simplified.lazy.Lazy"), names.fromString("of")),
            List.of(make.Lambda(List.nil(), providerCall))
        );
        return make.Conditional(isSet, lazyOf(slotRead(f), f), deferred);
    }

    /** {@code b.<fieldName>} - a fresh read of the builder slot. */
    private JCExpression slotRead(FieldSpec f) {
        return make.Select(make.Ident(names.fromString("b")), names.fromString(f.name));
    }

    /** {@code b.$replaced$<fieldName>} - a fresh read of the replaced marker. */
    private JCExpression markerRead(FieldSpec f) {
        return make.Select(make.Ident(names.fromString("b")),
            names.fromString(MutationContext.replacedMarker(f.name)));
    }

    /** {@code Lazy.of(<supplier>, "<owner>", "<fieldName>")}. */
    private JCExpression lazyOf(JCExpression supplier, FieldSpec f) {
        return make.Apply(
            List.nil(),
            make.Select(ctx.types().qualIdent("dev.simplified.lazy.Lazy"), names.fromString("of")),
            List.of(
                supplier,
                make.Literal(ctx.targetElement().getQualifiedName().toString()),
                make.Literal(f.name)
            )
        );
    }

    private JCMethodDecl buildCtor(List<JCVariableDecl> params, List<JCStatement> body, long modifiers) {
        JCBlock block = make.Block(0, body);
        // Javac spells the constructor name as <init>.
        JCMethodDecl ctor = make.MethodDef(
            make.Modifiers(modifiers),
            names.init,
            null,
            List.nil(),
            params,
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(ctor);
        return ctor;
    }

    /**
     * Detects a hand-written constructor with a single {@code Builder}-typed
     * parameter (by simple name) so we can respect the user's version.
     */
    static boolean hasCopyConstructor(com.sun.tools.javac.tree.JCTree.JCClassDecl target, String builderSimpleName) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl m)) continue;
            if (!m.name.toString().equals("<init>")) continue;
            if (m.params.size() != 1) continue;
            String paramType = m.params.head.vartype.toString();
            if (paramType.equals(builderSimpleName)) return true;
            if (paramType.startsWith(builderSimpleName + "<")) return true;
        }
        return false;
    }

}
