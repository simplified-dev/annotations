package dev.sbs.classbuilder.mutate;

import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

/**
 * Builds {@code @XContract} {@link JCAnnotation}s for the AST-mutation path.
 * Mirrors {@link dev.sbs.classbuilder.apt.BuilderEmitter#emitContract} so the
 * two pipelines emit identical contract values.
 *
 * <p>Every method returns a {@link List} suitable for splicing into
 * {@code make.Modifiers(flags, annotations)}. When
 * {@code config.emitContracts()} is {@code false} the list is
 * {@link List#nil() empty}, so call sites don't need a conditional around
 * the attachment - the single gate lives here.
 *
 * <p>Five convenience methods cover the shapes the generators use:
 * <ul>
 *   <li>{@link #thisReturnNullary()} - {@code @XContract(value = "-> this", mutates = "this")}
 *       for zero-arg setters ({@code isActive()}, {@code clearTags()}).</li>
 *   <li>{@link #thisReturnUnary()} - {@code @XContract(value = "_ -> this", mutates = "this")}
 *       for single-arg setters.</li>
 *   <li>{@link #thisReturnBinary()} - {@code @XContract(value = "_, _ -> this", mutates = "this")}
 *       for two-arg setters ({@code putEntry(K, V)}, {@code @Formattable}'s
 *       {@code (String, Object...)} overloads).</li>
 *   <li>{@link #newReturnNullary()} - {@code @XContract("-> new")} for zero-arg
 *       factories that always construct a fresh instance ({@code builder()},
 *       {@code build()}, {@code mutate()}).</li>
 *   <li>{@link #newReturnPureUnary()} - {@code @XContract(value = "_ -> new", pure = true)}
 *       for {@code from(T)} - reads the parameter without mutating it,
 *       always returns a fresh builder.</li>
 * </ul>
 */
final class ContractAnnotations {

    private static final String XCONTRACT_FQN = "dev.sbs.annotation.XContract";

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;

    ContractAnnotations(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
    }

    /** @return {@code @XContract("-> this", mutates="this")} or an empty list when disabled. */
    List<JCAnnotation> thisReturnNullary() {
        return list("-> this", false, "this");
    }

    /** @return {@code @XContract("_ -> this", mutates="this")} or an empty list when disabled. */
    List<JCAnnotation> thisReturnUnary() {
        return list("_ -> this", false, "this");
    }

    /** @return {@code @XContract("_, _ -> this", mutates="this")} or an empty list when disabled. */
    List<JCAnnotation> thisReturnBinary() {
        return list("_, _ -> this", false, "this");
    }

    /** @return {@code @XContract("-> new")} or an empty list when disabled. */
    List<JCAnnotation> newReturnNullary() {
        return list("-> new", false, null);
    }

    /** @return {@code @XContract(value="_ -> new", pure=true)} or an empty list when disabled. */
    List<JCAnnotation> newReturnPureUnary() {
        return list("_ -> new", true, null);
    }

    /**
     * Core factory. Returns an empty list (no annotation attached) when
     * {@code config.emitContracts()} is false; otherwise wraps a single
     * {@link JCAnnotation} carrying the supplied attributes.
     */
    private List<JCAnnotation> list(String value, boolean pure, String mutates) {
        if (!ctx.config().emitContracts()) return List.nil();

        ListBuffer<JCExpression> args = new ListBuffer<>();
        if (value != null) args.append(assign("value", stringLit(value)));
        if (pure) args.append(assign("pure", boolLit(true)));
        if (mutates != null) args.append(assign("mutates", stringLit(mutates)));

        JCAnnotation annotation = make.Annotation(
            types.qualIdent(XCONTRACT_FQN),
            args.toList()
        );
        return List.of(annotation);
    }

    private JCExpression assign(String name, JCExpression value) {
        return make.Assign(make.Ident(names.fromString(name)), value);
    }

    private JCExpression stringLit(String value) {
        return make.Literal(value);
    }

    private JCExpression boolLit(boolean value) {
        return make.Literal(value);
    }

}
