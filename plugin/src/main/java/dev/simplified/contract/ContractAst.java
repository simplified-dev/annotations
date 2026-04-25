package dev.simplified.contract;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * AST node types produced by {@link ContractParser}.
 *
 * <p>All types are immutable value objects (records) arranged as sealed hierarchies
 * so exhaustive {@code instanceof} chains can cover every case without a default branch.
 */
public final class ContractAst {

    private ContractAst() {}

    // ─── Expressions (left side of ->) ──────────────────────────────────────

    /** Any expression that can appear inside the condition parentheses. */
    public sealed interface Expr permits OrExpr, AndExpr, CompExpr, NegExpr, ValExpr, InstanceOfExpr {}

    /** {@code left || right} - logical OR, lower precedence than AND. */
    public record OrExpr(@NotNull Expr left, @NotNull Expr right) implements Expr {}

    /** {@code left && right} */
    public record AndExpr(@NotNull Expr left, @NotNull Expr right) implements Expr {}

    /** {@code left op right} - e.g. {@code param1 > 0} */
    public record CompExpr(@NotNull Value left, @NotNull CompOp op, @NotNull Value right) implements Expr {}

    /** {@code !value} - value must evaluate to boolean */
    public record NegExpr(@NotNull Value value) implements Expr {}

    /** A bare value used as a boolean condition (e.g. {@code param1}) */
    public record ValExpr(@NotNull Value value) implements Expr {}

    /** {@code value instanceof TypeName} - e.g. {@code param1 instanceof Number} */
    public record InstanceOfExpr(@NotNull Value value, @NotNull String typeName) implements Expr {}

    // ─── Values ─────────────────────────────────────────────────────────────

    /** Anything that can be used as an operand. */
    public sealed interface Value permits NullConst, BoolConst, IntConst, ParamRef, ParamNameRef, ThisRef {}

    /** The literal {@code null}. */
    public record NullConst() implements Value {}

    /** A boolean constant {@code true} or {@code false}. */
    public record BoolConst(boolean value) implements Value {}

    /** An integer constant such as {@code 0}, {@code 1}, or {@code -1}. */
    public record IntConst(int value) implements Value {}

    /** {@code paramN} with an optional chain of {@link SpecialField} accesses. */
    public record ParamRef(int index, @NotNull List<SpecialField> fields) implements Value {}

    /**
     * Parameter reference by name (e.g. {@code index}, {@code name.empty()}).
     *
     * <p>Parsing only records the name - resolution against the declaring method's
     * parameter list happens at inspection time when the PSI is available.
     */
    public record ParamNameRef(@NotNull String name, @NotNull List<SpecialField> fields) implements Value {}

    /** {@code this} with an optional chain of {@link SpecialField} accesses. */
    public record ThisRef(@NotNull List<SpecialField> fields) implements Value {}

    // ─── Supporting enums ───────────────────────────────────────────────────

    public enum CompOp {

        LT("<"),
        GT(">"),
        LTE("<="),
        GTE(">="),
        EQ("=="),
        NEQ("!=");

        public final String symbol;
        CompOp(String symbol) { this.symbol = symbol; }

    }

    public enum SpecialField {

        SIZE("size()"),
        LENGTH_METHOD("length()"),
        LENGTH_FIELD("length"),
        EMPTY("empty()");

        public final String text;
        SpecialField(String text) { this.text = text; }

    }

    // ─── Return values (right side of ->) ───────────────────────────────────

    /** Anything that can appear on the right side of {@code ->}. */
    public sealed interface ReturnVal permits
            TrueRet, FalseRet, NullRet, NotNullRet, FailRet,
            ThisRet, NewRet, ParamRet, ParamNameRet, IntRet, ThrowsRet {}

    public record TrueRet()         implements ReturnVal {}
    public record FalseRet()        implements ReturnVal {}
    public record NullRet()         implements ReturnVal {}
    public record NotNullRet()      implements ReturnVal {}
    public record FailRet()         implements ReturnVal {}
    public record ThisRet()         implements ReturnVal {}
    public record NewRet()          implements ReturnVal {}

    /** {@code paramN} - the method returns its N-th argument. */
    public record ParamRet(int index) implements ReturnVal {}

    /** Named-parameter return (e.g. {@code -> index}) - resolved against the method signature at inspection time. */
    public record ParamNameRet(@NotNull String name) implements ReturnVal {}

    /** An integer constant return (e.g. {@code 0}, {@code -1}). */
    public record IntRet(int value) implements ReturnVal {}

    /** {@code throws TypeName} - declares that the method throws a specific exception type. */
    public record ThrowsRet(@NotNull String typeName) implements ReturnVal {}

    // ─── Top-level clause ───────────────────────────────────────────────────

    /**
     * A single {@code (condition) -> returnVal} clause.
     * {@code condition} is {@code null} for the empty {@code ()} form,
     * which acts as an unconditional/else branch.
     */
    public record Clause(@Nullable Expr condition, @NotNull ReturnVal returnVal) {}

}
