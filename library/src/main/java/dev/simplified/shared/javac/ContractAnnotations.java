package dev.simplified.shared.javac;

import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

/**
 * Builds {@code @XContract} {@link JCAnnotation}s for AST-mutation pipelines.
 * Generalised from the original {@code @ClassBuilder}-only helper so any
 * mutator can emit contracts via the same constructor.
 *
 * <p>Every method returns a {@link List} suitable for splicing into
 * {@code make.Modifiers(flags, annotations)}. When the {@code emit} flag
 * passed at construction is {@code false}, every method returns
 * {@link List#nil()}, so call sites don't need a conditional around the
 * attachment - the single gate lives here.
 *
 * <h2>Convenience shapes</h2>
 *
 * ClassBuilder-side (mutating setters and factories):
 * <ul>
 *   <li>{@link #thisReturnNullary()} - {@code @XContract("-> this", mutates="this")}</li>
 *   <li>{@link #thisReturnUnary()} - {@code @XContract("_ -> this", mutates="this")}</li>
 *   <li>{@link #thisReturnBinary()} - {@code @XContract("_, _ -> this", mutates="this")}</li>
 *   <li>{@link #newReturnNullary()} - {@code @XContract("-> new")}</li>
 *   <li>{@link #newReturnPureUnary()} - {@code @XContract(value="_ -> new", pure=true)}</li>
 * </ul>
 *
 * EnumLookup-side (pure lookups / iteration):
 * <ul>
 *   <li>{@link #pure()} - {@code @XContract(pure=true)}</li>
 *   <li>{@link #pureReturnNonNull()} - {@code @XContract(value="-> !null", pure=true)}</li>
 *   <li>{@link #pureNullParamNullReturn()} - {@code @XContract(value="null -> null", pure=true)}</li>
 *   <li>{@link #pureUnaryReturnNonNull()} - {@code @XContract(value="_ -> !null", pure=true)}</li>
 *   <li>{@link #nullParamFails()} - {@code @XContract("null -> fail")}</li>
 * </ul>
 *
 * <p>For shapes outside this set, call {@link #contract(String, boolean, String)}
 * directly.
 */
public final class ContractAnnotations {

    private static final String XCONTRACT_FQN = "dev.simplified.annotations.XContract";

    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final boolean emit;

    public ContractAnnotations(TreeMaker make, Names names, JavacTypeFactory types, boolean emit) {
        this.make = make;
        this.names = names;
        this.types = types;
        this.emit = emit;
    }

    // ------------------------------------------------------------------
    // ClassBuilder-side shapes
    // ------------------------------------------------------------------

    /** @return {@code @XContract("-> this", mutates="this")} or an empty list when disabled. */
    public List<JCAnnotation> thisReturnNullary() {
        return contract("-> this", false, "this");
    }

    /** @return {@code @XContract("_ -> this", mutates="this")} or an empty list when disabled. */
    public List<JCAnnotation> thisReturnUnary() {
        return contract("_ -> this", false, "this");
    }

    /** @return {@code @XContract("_, _ -> this", mutates="this")} or an empty list when disabled. */
    public List<JCAnnotation> thisReturnBinary() {
        return contract("_, _ -> this", false, "this");
    }

    /** @return {@code @XContract("-> new")} or an empty list when disabled. */
    public List<JCAnnotation> newReturnNullary() {
        return contract("-> new", false, null);
    }

    /** @return {@code @XContract(value="_ -> new", pure=true)} or an empty list when disabled. */
    public List<JCAnnotation> newReturnPureUnary() {
        return contract("_ -> new", true, null);
    }

    // ------------------------------------------------------------------
    // EnumLookup-side shapes
    // ------------------------------------------------------------------

    /** @return {@code @XContract(pure=true)} or an empty list when disabled. */
    public List<JCAnnotation> pure() {
        return contract(null, true, null);
    }

    /** @return {@code @XContract(value="-> !null", pure=true)} or an empty list when disabled. */
    public List<JCAnnotation> pureReturnNonNull() {
        return contract("-> !null", true, null);
    }

    /** @return {@code @XContract(value="null -> null", pure=true)} or an empty list when disabled. */
    public List<JCAnnotation> pureNullParamNullReturn() {
        return contract("null -> null", true, null);
    }

    /** @return {@code @XContract(value="_ -> !null", pure=true)} or an empty list when disabled. */
    public List<JCAnnotation> pureUnaryReturnNonNull() {
        return contract("_ -> !null", true, null);
    }

    /** @return {@code @XContract("null -> fail")} or an empty list when disabled. */
    public List<JCAnnotation> nullParamFails() {
        return contract("null -> fail", false, null);
    }

    // ------------------------------------------------------------------
    // Generic builder
    // ------------------------------------------------------------------

    /**
     * Returns an empty list (no annotation attached) when the {@code emit} flag
     * is {@code false}; otherwise wraps a single {@link JCAnnotation} carrying
     * the supplied attributes. {@code value}, {@code mutates}, and {@code pure}
     * are each omitted when null / false.
     */
    public List<JCAnnotation> contract(String value, boolean pure, String mutates) {
        if (!emit) return List.nil();

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
