package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.args.mutate.ArgsConstructorFactory;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.lazy.mutate.LazyHolders;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.NullnessAnnotations;

/**
 * Shapes the parameters of the constructor the generated {@code build()}
 * invokes - the one a target would spell {@code @BuilderArgsConstructor}:
 *
 * <pre>{@code
 * Target(String name, int count) { this.name = name; this.count = count; }
 * }</pre>
 *
 * <p>Parameters follow field-declaration order so the positional
 * {@code new Target(f1, f2, ...)} call {@code NestedBuilderFactory} emits lines
 * up. Synthesis is suppressed when the target already declares a constructor of
 * its own, mirroring Lombok {@code @Builder}, which supplies its implicit
 * constructor only in the absence of an explicit one.
 *
 * <p>Only the shaping lives here; the declaration itself is minted by
 * {@link ArgsConstructorFactory}, shared with the constructor annotations. Two
 * fields are not a plain one-parameter-per-field translation and are why this
 * class exists at all: a field whose default reads instance state arrives as a
 * {@code Supplier<T>} so an unfilled slot is distinguishable from a filled one,
 * and a collected default arrives as its container plus a marker saying whether
 * the caller replaced it wholesale.
 *
 * <p>Runs before {@code LazyFieldMutator} so {@code @Lazy} fields get the same
 * parameter and assignment rewrite a hand-written constructor receives.
 */
final class AllArgsConstructorFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;

    AllArgsConstructorFactory(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
    }

    /**
     * Builds the all-args constructor, assigning every builder-visible field
     * from a like-named parameter.
     *
     * @param access visibility of the generated constructor, already resolved
     *        against a written {@code @BuilderArgsConstructor}
     * @return the constructor declaration
     */
    JCMethodDecl build(AccessLevel access) {
        ListBuffer<JCVariableDecl> params = new ListBuffer<>();
        ListBuffer<JCStatement> body = new ListBuffer<>();
        for (FieldSpec f : ctx.fields()) {
            // A collected instance default arrives as the container the caller
            // contributed to plus the marker saying whether they replaced it
            // wholesale; the merge helper folds the two against the default.
            // Neither carries the field's nullness: the container is typed as
            // the java.util interface off the matched supertype rather than as
            // the declared type, and the marker has no field behind it at all,
            // so one annotation per field would shift onto the wrong slot for
            // the whole rest of the list.
            if (ctx.isCollectedInstanceDefault(f)) {
                String markerName = MutationContext.replacedMarker(f.name);
                params.append(make.VarDef(make.Modifiers(Flags.PARAMETER),
                    names.fromString(f.name), ctx.collectedSlotType(f), null));
                params.append(make.VarDef(make.Modifiers(Flags.PARAMETER),
                    names.fromString(markerName), make.TypeIdent(TypeTag.BOOLEAN), null));
                body.append(make.Exec(make.Assign(
                    make.Select(make.Ident(names._this), names.fromString(f.name)),
                    mergeCall(f, make.Ident(names.fromString(f.name)),
                        make.Ident(names.fromString(markerName)))
                )));
                continue;
            }
            boolean instanceDefault = ctx.isInstanceDefault(f.name);
            // An instance-default parameter arrives as Supplier<T> so null can
            // mean "the builder slot was never filled". @Lazy parameters are
            // retyped to Supplier<T> by LazyFieldMutator afterwards, so they
            // are declared as T here and left to that pass.
            JCExpression paramType = instanceDefault && !f.lazy
                ? make.TypeApply(ctx.types().qualIdent("java.util.function.Supplier"),
                    List.of(ctx.types().parseBoxedType(f.typeDisplay)))
                : ctx.types().parseType(f.typeDisplay);
            // Nullness travels only onto the slot that really holds T. Both
            // ways of arriving at Supplier<T> are excluded, and the @Lazy half
            // is the one no guard here can see: LazyFieldMutator replaces
            // param.vartype in place afterwards and leaves param.mods alone, so
            // an annotation attached now survives the retype and lands as
            // @NotNull Supplier<T> - asserting non-null of the very slot whose
            // null means "the builder was never told".
            boolean retyped = instanceDefault || f.lazy;
            List<JCAnnotation> nullness = retyped
                ? List.nil()
                : NullnessAnnotations.copy(f.element, make, ctx.types());
            params.append(make.VarDef(
                make.Modifiers(Flags.PARAMETER, nullness),
                names.fromString(f.name),
                paramType,
                null
            ));
            JCExpression lhs = make.Select(make.Ident(names._this), names.fromString(f.name));
            body.append(make.Exec(make.Assign(lhs, instanceDefault ? defaultingRhs(f) : make.Ident(names.fromString(f.name)))));
        }
        // A @ClassBuilder target is never an enum - the processor rejects the
        // kind before reaching here - so the enum access override is moot.
        return new ArgsConstructorFactory(make, names)
            .mint(access, false, params.toList(), body.toList(), ctx.generated());
    }

    /**
     * Right-hand side for a field whose default reads instance state. The
     * builder slot is {@code Supplier<T>}, so null means it was never filled
     * and the instance {@code $default$} provider supplies the value instead.
     * Evaluating here rather than at {@code builder()} is the whole point:
     * {@code this} exists in a constructor, exactly as it does in the ordinary
     * field initializer this expression came from.
     *
     * <pre>{@code
     * // plain:  name != null ? name.get() : $default$name()
     * // @Lazy:  new AtomicReference<>(v != null ? v : () -> $default$v())
     * }</pre>
     */
    private JCExpression defaultingRhs(FieldSpec f) {
        JCExpression isSet = make.Binary(JCTree.Tag.NE,
            make.Ident(names.fromString(f.name)),
            make.Literal(TypeTag.BOT, null));
        JCExpression providerCall = make.Apply(
            List.nil(),
            make.Ident(names.fromString(RetainedInitFactory.providerName(f.name))),
            List.nil()
        );
        if (!f.lazy) {
            JCExpression get = make.Apply(
                List.nil(),
                make.Select(make.Ident(names.fromString(f.name)), names.fromString("get")),
                List.nil()
            );
            return make.Conditional(isSet, get, providerCall);
        }
        // A @Lazy field keeps its deferral on both branches: the supplied
        // supplier is passed through verbatim, and the default becomes a lambda
        // over the provider so it is still not run until the first getter call.
        // One holder wraps the choice rather than each branch minting its own -
        // the null test above has already proved the supplied slot non-null, so
        // there is nothing left for a per-branch check to catch.
        return LazyHolders.newHolder(make, ctx.types(), f.typeDisplay,
            make.Conditional(isSet,
                make.Ident(names.fromString(f.name)),
                make.Lambda(List.nil(), providerCall)));
    }

    /** {@code $merge$<name>(contributed, replaced)} on the target. */
    static JCExpression mergeCall(MutationContext ctx, FieldSpec field,
                                  JCExpression contributed, JCExpression replaced) {
        return ctx.make().Apply(
            List.nil(),
            ctx.make().Ident(ctx.names().fromString(RetainedInitFactory.mergeName(field.name))),
            List.of(contributed, replaced)
        );
    }

    private JCExpression mergeCall(FieldSpec field, JCExpression contributed, JCExpression replaced) {
        return mergeCall(ctx, field, contributed, replaced);
    }

    /**
     * Detects a constructor the author wrote. Javac's own default constructor
     * carries {@link Flags#GENERATEDCONSTR} and is not treated as explicit, so a
     * class declaring no constructor at all still qualifies for synthesis.
     *
     * <p>Nor is one this pipeline synthesised. The constructor annotations run
     * an earlier pass over the same tree, so by the time the builder asks, a
     * {@code @NoArgsConstructor} on the same target has already appended one -
     * counting that as the author's would leave {@code build()} calling a
     * constructor nobody emits.
     *
     * @param target the class declaration to scan
     * @return whether the target declares a constructor of its own
     */
    static boolean hasExplicitConstructor(JCClassDecl target) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl m)) continue;
            if (!m.name.toString().equals("<init>")) continue;
            if ((m.mods.flags & Flags.GENERATEDCONSTR) != 0) continue;
            if (AstMarkers.isGenerated(m)) continue;
            return true;
        }
        return false;
    }

}
