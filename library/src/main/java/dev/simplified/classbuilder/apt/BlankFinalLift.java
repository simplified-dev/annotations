package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The rule deciding whether {@code @ClassBuilder} lifts a {@code final} field's
 * initializer off it, leaving a blank final for a constructor to assign, and
 * which writes to such a field javac then accepts.
 *
 * <p>The lift exists for the constructor {@code build()} reaches, which assigns
 * every field the builder selects: a {@code final} field keeping its own
 * initializer beside that assignment is written twice, and javac rejects it. A
 * constructor the author wrote answers for itself. Where one of them assigns
 * the field nowhere, the field keeps its initializer - lifting it would leave
 * that constructor with a blank final it never assigns, which javac reports on
 * the author's own line. A constructor calling {@code this(..)} leaves its
 * fields to the constructor it delegates to and has no say. With no author
 * constructor, the field is lifted only where a constructor the generator
 * appends assigns it; under a {@code factoryMethod}, or beside an author's own
 * {@code build()}, none does, and the initializer stays.
 *
 * <p>A constructor assigns a field where javac's definite-assignment rules find
 * it definitely assigned at every {@code return} and wherever the body
 * completes normally, and javac accepts a write where they find the field
 * definitely unassigned ahead of it. Both are walked here the way javac's flow
 * analysis walks them, over every statement a constructor body holds - blocks,
 * {@code if}, the four loops with javac's second pass over a loop body that
 * assigns the field, {@code switch} statements and expressions, {@code try}
 * with its {@code catch} and {@code finally} blocks, labelled and
 * {@code synchronized} statements, declarations, and the {@code return},
 * {@code throw}, {@code break}, {@code continue} and {@code yield} that leave
 * them. A condition is read by its shape: the literals {@code true} and
 * {@code false} are the constants the rules read, {@code !}, {@code &&},
 * {@code ||} and {@code ?:} combine what their operands leave, and every other
 * condition may go either way. A write inside a lambda or a nested class is no
 * write of the constructor's. Where a constructor leaves the field unassigned
 * on some way out - a branch that does not write it, a {@code return} or a
 * {@code break} ahead of the write, a loop that may not run - the field keeps
 * its initializer, and javac refuses the write as a second assignment on the
 * author's line, where the editor reports it too.
 *
 * <p>Answered from names alone, so both halves reach the same verdict: the
 * processor reads each constructor off the javac tree, the editor off PSI it
 * does not resolve, each into the {@link Statement} shapes here, and neither
 * re-derives the rule.
 */
public final class BlankFinalLift {

    /** Lombok's no-argument constructor annotation. */
    public static final String LOMBOK_NO_ARGS = "lombok.NoArgsConstructor";

    /** Lombok's required-arguments constructor annotation. */
    public static final String LOMBOK_REQUIRED_ARGS = "lombok.RequiredArgsConstructor";

    /** Lombok's all-arguments constructor annotation. */
    public static final String LOMBOK_ALL_ARGS = "lombok.AllArgsConstructor";

    /** Lombok's {@code @Data}, which implies a required-arguments constructor where none is written. */
    public static final String LOMBOK_DATA = "lombok.Data";

    /** Lombok's {@code @Value}, which implies an all-arguments constructor where none is written. */
    public static final String LOMBOK_VALUE = "lombok.Value";

    /** Every Lombok annotation that writes or implies a constructor, by qualified name. */
    public static final List<String> LOMBOK_CONSTRUCTOR_ANNOTATIONS =
        List.of(LOMBOK_NO_ARGS, LOMBOK_REQUIRED_ARGS, LOMBOK_ALL_ARGS, LOMBOK_DATA, LOMBOK_VALUE);

    private BlankFinalLift() {
    }

    /**
     * A statement of a constructor body, read by its shape and the names it writes.
     *
     * @param <S> what each half identifies a written reference by
     */
    public sealed interface Statement<S> permits Expression, Block, Branch, Loop, Switch, Try, Labelled, Jump, Assert {
    }

    /**
     * An expression of a constructor body, read by its shape and the names it writes.
     *
     * @param <S> what each half identifies a written reference by
     */
    public sealed interface Value<S> permits Constant, Assign, Not, And, Or, Choice, Evaluate, SwitchValue {
    }

    /**
     * One written target, named through its bare name or an unqualified {@code this}.
     *
     * @param name the written name
     * @param qualified whether it is written through {@code this}
     * @param site the written reference
     * @param <S> what the half identifies the reference by
     */
    public record Write<S>(@NotNull String name, boolean qualified, S site) {
    }

    /**
     * An expression statement, or a local variable declaration evaluating its initializer.
     *
     * @param value the evaluated expression
     * @param <S> what the half identifies a written reference by
     */
    public record Expression<S>(@NotNull Value<S> value) implements Statement<S> {
    }

    /**
     * A block, or any statement run as a sequence of others.
     *
     * @param statements its statements
     * @param <S> what the half identifies a written reference by
     */
    public record Block<S>(@NotNull List<Statement<S>> statements) implements Statement<S> {
    }

    /**
     * An {@code if}, with or without an {@code else}.
     *
     * @param condition the condition
     * @param then the statement run when the condition holds
     * @param otherwise the {@code else} statement, or {@code null} when none is written
     * @param <S> what the half identifies a written reference by
     */
    public record Branch<S>(@NotNull Value<S> condition, @NotNull Statement<S> then,
                            @Nullable Statement<S> otherwise) implements Statement<S> {
    }

    /** The four loops, which javac walks each in its own order. */
    public enum LoopKind {

        /** A {@code while} loop, its condition ahead of its body. */
        WHILE,

        /** A {@code do} loop, its condition after its body. */
        DO,

        /** A basic {@code for} loop. */
        FOR,

        /** An enhanced {@code for} loop, which may run its body no time at all. */
        FOREACH

    }

    /**
     * A loop.
     *
     * @param kind which loop it is
     * @param init what runs once ahead of it - a {@code for} loop's initializers, or the expression an
     *     enhanced {@code for} loop iterates
     * @param condition the condition, or {@code null} on an enhanced {@code for} loop and a {@code for} loop
     *     written without one
     * @param update a {@code for} loop's update statements
     * @param body the body
     * @param <S> what the half identifies a written reference by
     */
    public record Loop<S>(@NotNull LoopKind kind, @NotNull List<Statement<S>> init, @Nullable Value<S> condition,
                          @NotNull List<Statement<S>> update, @NotNull Statement<S> body) implements Statement<S> {
    }

    /**
     * A {@code switch} statement.
     *
     * @param selector the selector expression
     * @param exhaustive whether it has a {@code default} label, or a pattern or {@code null} label, with
     *     which javac requires it to cover every value
     * @param arms its arms in source order, consecutive colon labels read as one arm
     * @param <S> what the half identifies a written reference by
     */
    public record Switch<S>(@NotNull Value<S> selector, boolean exhaustive,
                            @NotNull List<Arm<S>> arms) implements Statement<S> {
    }

    /**
     * One arm of a {@code switch} statement or expression.
     *
     * @param rule whether it is an arrow arm, which never falls through
     * @param statements the arm's statements; an arrow arm's body is its only one, and an arrow arm of a
     *     {@code switch} expression whose body is an expression yields it
     * @param <S> what the half identifies a written reference by
     */
    public record Arm<S>(boolean rule, @NotNull List<Statement<S>> statements) {
    }

    /**
     * A {@code try} statement.
     *
     * @param body its resources, then its block's statements
     * @param catches each {@code catch} block's statements
     * @param finalizer the {@code finally} block's statements, or {@code null} when none is written
     * @param <S> what the half identifies a written reference by
     */
    public record Try<S>(@NotNull List<Statement<S>> body, @NotNull List<List<Statement<S>>> catches,
                         @Nullable List<Statement<S>> finalizer) implements Statement<S> {
    }

    /**
     * A labelled statement.
     *
     * @param label the label
     * @param body the labelled statement
     * @param <S> what the half identifies a written reference by
     */
    public record Labelled<S>(@NotNull String label, @NotNull Statement<S> body) implements Statement<S> {
    }

    /** The statements that leave the statement around them. */
    public enum JumpKind {

        /** A {@code return}, which leaves the constructor. */
        RETURN,

        /** A {@code throw}. */
        THROW,

        /** A {@code break}. */
        BREAK,

        /** A {@code continue}. */
        CONTINUE,

        /** A {@code yield}, which leaves a {@code switch} expression. */
        YIELD

    }

    /**
     * A {@code return}, {@code throw}, {@code break}, {@code continue} or {@code yield}.
     *
     * @param kind which it is
     * @param label the label a {@code break} or {@code continue} names, or {@code null}
     * @param value the thrown or yielded expression, or {@code null}
     * @param <S> what the half identifies a written reference by
     */
    public record Jump<S>(@NotNull JumpKind kind, @Nullable String label, @Nullable Value<S> value)
        implements Statement<S> {
    }

    /**
     * An {@code assert} statement.
     *
     * @param condition the asserted condition
     * @param detail the detail expression, or {@code null}
     * @param <S> what the half identifies a written reference by
     */
    public record Assert<S>(@NotNull Value<S> condition, @Nullable Value<S> detail) implements Statement<S> {
    }

    /**
     * The literal {@code true} or {@code false}.
     *
     * @param value the literal's value
     * @param <S> what the half identifies a written reference by
     */
    public record Constant<S>(boolean value) implements Value<S> {
    }

    /**
     * An assignment, a compound assignment, or an increment or decrement.
     *
     * @param operands what is evaluated ahead of the write, in order
     * @param target the written name, or {@code null} where the target is no bare name or unqualified
     *     {@code this} one
     * @param <S> what the half identifies a written reference by
     */
    public record Assign<S>(@NotNull List<Value<S>> operands, @Nullable Write<S> target) implements Value<S> {
    }

    /**
     * A {@code !}.
     *
     * @param operand the negated expression
     * @param <S> what the half identifies a written reference by
     */
    public record Not<S>(@NotNull Value<S> operand) implements Value<S> {
    }

    /**
     * A {@code &&}.
     *
     * @param left the operand always evaluated
     * @param right the operand evaluated where the left one holds
     * @param <S> what the half identifies a written reference by
     */
    public record And<S>(@NotNull Value<S> left, @NotNull Value<S> right) implements Value<S> {
    }

    /**
     * A {@code ||}.
     *
     * @param left the operand always evaluated
     * @param right the operand evaluated where the left one fails
     * @param <S> what the half identifies a written reference by
     */
    public record Or<S>(@NotNull Value<S> left, @NotNull Value<S> right) implements Value<S> {
    }

    /**
     * A {@code ?:}.
     *
     * @param condition the condition
     * @param then the operand evaluated where the condition holds
     * @param otherwise the operand evaluated where it fails
     * @param <S> what the half identifies a written reference by
     */
    public record Choice<S>(@NotNull Value<S> condition, @NotNull Value<S> then,
                            @NotNull Value<S> otherwise) implements Value<S> {
    }

    /**
     * Any other expression.
     *
     * @param operands its operands in the order they are evaluated, a lambda's body and a nested class's
     *     left out
     * @param <S> what the half identifies a written reference by
     */
    public record Evaluate<S>(@NotNull List<Value<S>> operands) implements Value<S> {
    }

    /**
     * A {@code switch} expression.
     *
     * @param selector the selector expression
     * @param arms its arms in source order, whose {@code yield} statements leave it
     * @param <S> what the half identifies a written reference by
     */
    public record SwitchValue<S>(@NotNull Value<S> selector, @NotNull List<Arm<S>> arms) implements Value<S> {
    }

    /**
     * What one constructor writes, read off its body by name.
     *
     * @param delegates whether one of the body's statements is a {@code this(..)} call
     * @param assigned the field names the body assigns
     */
    public record Writes(boolean delegates, Set<String> assigned) {

        /**
         * Summarises a constructor body from its statements and the names it declares.
         *
         * <p>A write through {@code this.name} always names the field. A bare
         * {@code name = ..} names it only where the body declares no parameter
         * or local of that name, since such a write assigns the variable
         * instead. The names a body declares are read at any depth but a nested
         * class's body. A name is assigned where it is definitely assigned at
         * every {@code return} and wherever the body completes normally.
         *
         * @param delegates whether one of the body's statements is a {@code this(..)} call
         * @param declared the parameter and local variable names the constructor declares
         * @param body the body's statements
         * @param <S> what the half identifies a written reference by
         * @return the summary
         */
        public static <S> Writes of(boolean delegates, Collection<String> declared, List<Statement<S>> body) {
            Set<String> written = new HashSet<>();
            for (Statement<S> statement : body) collectNames(statement, written);
            Set<String> assigned = new HashSet<>();
            for (String name : written) {
                if (new Walk<S>(name, declared).assigns(body)) assigned.add(name);
            }
            return new Writes(delegates, Set.copyOf(assigned));
        }

    }

    /**
     * Decides whether the field's initializer is lifted off it.
     *
     * <p>Asked only of a {@code final} field the builder selects.
     *
     * @param field the field's name
     * @param constructors the target's author-written constructors, and those Lombok writes beside them
     * @param generatedConstructorAssigns whether a constructor the generator appends assigns every field
     *     the builder selects
     * @return whether the field is left a blank final
     */
    public static boolean lifts(String field, List<Writes> constructors, boolean generatedConstructorAssigns) {
        if (constructors.isEmpty()) return generatedConstructorAssigns;
        for (Writes constructor : constructors) {
            if (!constructor.delegates() && !constructor.assigned().contains(field)) return false;
        }
        return true;
    }

    /**
     * Summarises the constructors Lombok writes on a class beside an author's.
     *
     * <p>Lombok never assigns a {@code final} field that carries an
     * initializer, so each of its constructors assigns none of the fields the
     * lift is asked about. Each constructor annotation written adds one;
     * {@code @Data} and {@code @Value} imply none beside a written constructor.
     * With no author constructor Lombok's constructors are left out: what
     * Lombok then writes depends on whether it runs before this processor.
     *
     * @param annotations the qualified names of the annotations written on the class
     * @param declaresConstructor whether the author wrote a constructor on the class
     * @return one summary per constructor Lombok writes there
     */
    public static List<Writes> lombokConstructors(Collection<String> annotations, boolean declaresConstructor) {
        List<Writes> out = new ArrayList<>();
        if (!declaresConstructor) return out;
        for (String name : List.of(LOMBOK_NO_ARGS, LOMBOK_REQUIRED_ARGS, LOMBOK_ALL_ARGS)) {
            if (annotations.contains(name)) out.add(new Writes(false, Set.of()));
        }
        return out;
    }

    /**
     * Collects the writes to a field javac accepts in one constructor, the
     * field being a blank final.
     *
     * <p>A write is accepted where the field is definitely unassigned ahead of
     * it, by the rules the class describes: on its first pass through a loop
     * body a write may be accepted that the second pass, run where the body
     * assigns the field, refuses; a write refused on either pass is refused.
     * After {@code this(..)} javac leaves the field to the constructor called,
     * and refuses every write.
     *
     * @param field the field's name
     * @param delegates whether one of the body's statements is a {@code this(..)} call
     * @param declared the parameter and local variable names the constructor declares
     * @param body the body's statements
     * @param <S> what the half identifies a written reference by
     * @return the sites of the accepted writes
     */
    public static <S> Set<S> acceptedWrites(String field, boolean delegates, Collection<String> declared,
                                            List<Statement<S>> body) {
        if (delegates) return Set.of();
        Walk<S> walk = new Walk<>(field, declared);
        walk.sequence(body, State.START);
        Set<S> accepted = new LinkedHashSet<>(walk.seen);
        accepted.removeAll(walk.refused);
        return accepted;
    }

    /**
     * Whether a write names the field.
     *
     * @param write the write
     * @param field the field's name
     * @param declared the names the constructor declares
     * @return whether it assigns the field
     */
    private static boolean names(Write<?> write, String field, Collection<String> declared) {
        return write.name().equals(field) && (write.qualified() || !declared.contains(field));
    }

    /**
     * Collects every name a statement writes, at any depth.
     *
     * @param statement the statement
     * @param out where the names go
     */
    private static void collectNames(Statement<?> statement, Set<String> out) {
        if (statement instanceof Expression<?> expression) {
            collectNames(expression.value(), out);
        } else if (statement instanceof Block<?> block) {
            for (Statement<?> inner : block.statements()) collectNames(inner, out);
        } else if (statement instanceof Branch<?> branch) {
            collectNames(branch.condition(), out);
            collectNames(branch.then(), out);
            if (branch.otherwise() != null) collectNames(branch.otherwise(), out);
        } else if (statement instanceof Loop<?> loop) {
            for (Statement<?> inner : loop.init()) collectNames(inner, out);
            if (loop.condition() != null) collectNames(loop.condition(), out);
            for (Statement<?> inner : loop.update()) collectNames(inner, out);
            collectNames(loop.body(), out);
        } else if (statement instanceof Switch<?> choice) {
            collectNames(choice.selector(), out);
            collectArmNames(choice.arms(), out);
        } else if (statement instanceof Try<?> attempt) {
            for (Statement<?> inner : attempt.body()) collectNames(inner, out);
            for (List<? extends Statement<?>> handler : attempt.catches()) {
                for (Statement<?> inner : handler) collectNames(inner, out);
            }
            if (attempt.finalizer() != null) {
                for (Statement<?> inner : attempt.finalizer()) collectNames(inner, out);
            }
        } else if (statement instanceof Labelled<?> labelled) {
            collectNames(labelled.body(), out);
        } else if (statement instanceof Jump<?> jump) {
            if (jump.value() != null) collectNames(jump.value(), out);
        } else if (statement instanceof Assert<?> check) {
            collectNames(check.condition(), out);
            if (check.detail() != null) collectNames(check.detail(), out);
        }
    }

    /**
     * Collects every name an expression writes, at any depth.
     *
     * @param value the expression
     * @param out where the names go
     */
    private static void collectNames(Value<?> value, Set<String> out) {
        if (value instanceof Assign<?> assign) {
            for (Value<?> operand : assign.operands()) collectNames(operand, out);
            if (assign.target() != null) out.add(assign.target().name());
        } else if (value instanceof Not<?> not) {
            collectNames(not.operand(), out);
        } else if (value instanceof And<?> and) {
            collectNames(and.left(), out);
            collectNames(and.right(), out);
        } else if (value instanceof Or<?> or) {
            collectNames(or.left(), out);
            collectNames(or.right(), out);
        } else if (value instanceof Choice<?> choice) {
            collectNames(choice.condition(), out);
            collectNames(choice.then(), out);
            collectNames(choice.otherwise(), out);
        } else if (value instanceof Evaluate<?> evaluate) {
            for (Value<?> operand : evaluate.operands()) collectNames(operand, out);
        } else if (value instanceof SwitchValue<?> choice) {
            collectNames(choice.selector(), out);
            collectArmNames(choice.arms(), out);
        }
    }

    /**
     * Collects every name a {@code switch}'s arms write.
     *
     * @param arms the arms
     * @param out where the names go
     */
    private static void collectArmNames(List<? extends Arm<?>> arms, Set<String> out) {
        for (Arm<?> arm : arms) {
            for (Statement<?> inner : arm.statements()) collectNames(inner, out);
        }
    }

    /**
     * What one point of a constructor body knows of the field.
     *
     * @param assigned whether the field is definitely assigned there
     * @param unassigned whether it is definitely unassigned there
     * @param reachable whether the point can be reached, by the reachability rules javac reads
     */
    private record State(boolean assigned, boolean unassigned, boolean reachable) {

        /** The start of a constructor body. */
        static final State START = new State(false, true, true);

        /** A point no path reaches, where the field is vacuously both. */
        static final State DEAD = new State(true, true, false);

        /**
         * Merges two paths meeting at one point.
         *
         * @param other the other path's state
         * @return the merged state
         */
        State join(State other) {
            return new State(assigned && other.assigned, unassigned && other.unassigned, reachable || other.reachable);
        }

        /**
         * Reads the same knowledge at a point whose reachability is decided apart.
         *
         * @param reached whether the point can be reached
         * @return the state
         */
        State reached(boolean reached) {
            return new State(assigned, unassigned, reached);
        }

    }

    /**
     * What a condition leaves, apart for where it holds and where it fails.
     *
     * @param whenTrue the state where it holds
     * @param whenFalse the state where it fails
     */
    private record Split(State whenTrue, State whenFalse) {

        /**
         * The state after the condition read as a value.
         *
         * @return both sides merged
         */
        State merged() {
            return whenTrue.join(whenFalse);
        }

    }

    /**
     * A jump not yet resolved by the statement it leaves.
     *
     * @param kind which jump it is
     * @param label the label it names, or {@code null}
     * @param state the state where it jumps
     */
    private record Pending(JumpKind kind, @Nullable String label, State state) {
    }

    /**
     * One walk of a constructor body for one field, in javac's order.
     *
     * <p>Jumps are held until the statement they leave resolves them, a
     * {@code finally} block adding what it assigns to each jump passing
     * through it, and a {@code return} reaches the end of the body.
     *
     * @param <S> what the half identifies a written reference by
     */
    private static final class Walk<S> {

        private final String field;
        private final Collection<String> declared;
        private final Set<S> seen = new LinkedHashSet<>();
        private final Set<S> refused = new LinkedHashSet<>();
        private List<Pending> pending = new ArrayList<>();
        private boolean unassignedThroughoutTry = true;

        Walk(String field, Collection<String> declared) {
            this.field = field;
            this.declared = declared;
        }

        /**
         * Whether the body leaves the field definitely assigned wherever it
         * completes normally and at every {@code return}.
         *
         * @param body the body's statements
         * @return whether the constructor assigns the field
         */
        boolean assigns(List<Statement<S>> body) {
            if (!sequence(body, State.START).assigned()) return false;
            for (Pending exit : pending) {
                if (exit.kind() == JumpKind.RETURN && !exit.state().assigned()) return false;
            }
            return true;
        }

        /**
         * Walks statements in order.
         *
         * @param statements the statements
         * @param in the state ahead of the first
         * @return the state after the last
         */
        State sequence(List<Statement<S>> statements, State in) {
            State state = in;
            for (Statement<S> statement : statements) state = statement(statement, state, List.of());
            return state;
        }

        /**
         * Walks one statement.
         *
         * @param statement the statement
         * @param in the state ahead of it
         * @param labels the labels written directly on it
         * @return the state after it
         */
        State statement(Statement<S> statement, State in, List<String> labels) {
            if (statement instanceof Expression<S> expression) return value(expression.value(), in);
            if (statement instanceof Block<S> block) return sequence(block.statements(), in);
            if (statement instanceof Branch<S> branch) {
                Split split = condition(branch.condition(), in);
                State then = statement(branch.then(), split.whenTrue(), List.of());
                return then.join(branch.otherwise() == null
                    ? split.whenFalse()
                    : statement(branch.otherwise(), split.whenFalse(), List.of()));
            }
            if (statement instanceof Loop<S> loop) return loop(loop, in, labels);
            if (statement instanceof Switch<S> choice) {
                State selected = value(choice.selector(), in);
                List<Pending> outer = enter();
                State out = arms(choice.arms(), selected, true);
                if (!choice.exhaustive()) out = out.join(selected);
                return leave(outer, out, JumpKind.BREAK, null);
            }
            if (statement instanceof Try<S> attempt) return attempt(attempt, in);
            if (statement instanceof Labelled<S> labelled) {
                List<Pending> outer = enter();
                List<String> inner = new ArrayList<>(labels);
                inner.add(labelled.label());
                State out = statement(labelled.body(), in, inner);
                return leave(outer, out, JumpKind.BREAK, labelled.label());
            }
            if (statement instanceof Jump<S> jump) {
                State at = jump.value() == null ? in : value(jump.value(), in);
                if (jump.kind() != JumpKind.THROW) pending.add(new Pending(jump.kind(), jump.label(), at));
                return State.DEAD;
            }
            if (statement instanceof Assert<S> check) {
                Split split = condition(check.condition(), in);
                if (check.detail() != null) value(check.detail(), split.whenFalse());
                return new State(in.assigned(), in.unassigned() && split.whenTrue().unassigned(), in.reachable());
            }
            return in;
        }

        /**
         * Walks a loop: once, and a second time where the first pass leaves
         * the field no longer unassigned at the loop's head and refuses no
         * write of it - the second pass is where a write that may run again is
         * refused.
         *
         * @param loop the loop
         * @param in the state ahead of it
         * @param labels the labels written directly on it, which a {@code continue} may name
         * @return the state after it
         */
        private State loop(Loop<S> loop, State in, List<String> labels) {
            State start = sequence(loop.init(), in);
            List<Pending> outer = enter();
            int refusedBefore = refused.size();
            boolean constantTrue = loop.condition() instanceof Constant<S> constant && constant.value();
            State state = start;
            State skip = State.DEAD;
            for (boolean first = true; ; first = false) {
                boolean unassignedAtHead = state.unassigned();
                State body = state;
                if (loop.kind() == LoopKind.WHILE || loop.kind() == LoopKind.FOR) {
                    if (loop.condition() != null) {
                        Split split = condition(loop.condition(), state);
                        if (first) skip = split.whenFalse().reached(start.reachable() && !constantTrue);
                        body = split.whenTrue();
                    }
                }
                State end = resolve(statement(loop.body(), body, List.of()), JumpKind.CONTINUE, labels);
                State back = end;
                if (loop.kind() == LoopKind.DO) {
                    Split split = condition(loop.condition(), end);
                    if (first) skip = split.whenFalse().reached(split.whenFalse().reachable() && !constantTrue);
                    back = split.whenTrue();
                } else if (loop.kind() == LoopKind.FOR) {
                    back = sequence(loop.update(), end);
                }
                boolean again = first && refused.size() == refusedBefore && unassignedAtHead && !back.unassigned();
                if (!again) {
                    if (loop.kind() == LoopKind.FOREACH)
                        skip = new State(start.assigned(), start.unassigned() && back.unassigned(), start.reachable());
                    break;
                }
                state = new State(back.assigned(), false, start.reachable());
            }
            return leave(outer, skip, JumpKind.BREAK, null);
        }

        /**
         * Walks a {@code try} statement: each {@code catch} block starts where
         * the field is unassigned only if the {@code try} block assigns it
         * nowhere, and so does the {@code finally} block, where no
         * {@code catch} block assigns it either.
         *
         * @param attempt the statement
         * @param in the state ahead of it
         * @return the state after it
         */
        private State attempt(Try<S> attempt, State in) {
            boolean outerThroughout = unassignedThroughoutTry;
            List<Pending> outer = enter();
            unassignedThroughoutTry = in.unassigned();
            State end = sequence(attempt.body(), in);
            unassignedThroughoutTry &= end.unassigned();
            State handlerStart = new State(in.assigned(), unassignedThroughoutTry, in.reachable());
            for (List<Statement<S>> handler : attempt.catches()) end = end.join(sequence(handler, handlerStart));
            State out;
            if (attempt.finalizer() == null) {
                outer.addAll(pending);
                pending = outer;
                out = end;
            } else {
                List<Pending> exits = pending;
                pending = outer;
                State last = sequence(attempt.finalizer(),
                    new State(in.assigned(), unassignedThroughoutTry, in.reachable()));
                if (!last.reachable()) {
                    out = last;
                } else {
                    for (Pending exit : exits) {
                        State through = new State(exit.state().assigned() || last.assigned(),
                            exit.state().unassigned() && last.unassigned(), exit.state().reachable());
                        pending.add(new Pending(exit.kind(), exit.label(), through));
                    }
                    out = new State(last.assigned() || end.assigned(), last.unassigned() && end.unassigned(),
                        end.reachable());
                }
            }
            unassignedThroughoutTry = outerThroughout && unassignedThroughoutTry && out.unassigned();
            return out;
        }

        /**
         * Walks a {@code switch}'s arms: each starts where the selector leaves
         * the field, a colon arm falling through carrying what it leaves
         * unassigned into the next, and an arrow arm of a statement that
         * completes leaving the {@code switch}.
         *
         * @param arms the arms
         * @param selected the state after the selector
         * @param statement whether the arms are a {@code switch} statement's
         * @return the state after the last arm
         */
        private State arms(List<Arm<S>> arms, State selected, boolean statement) {
            State carried = State.DEAD;
            for (Arm<S> arm : arms) {
                State entry = new State(selected.assigned(), selected.unassigned() && carried.unassigned(),
                    selected.reachable());
                State end = sequence(arm.statements(), entry);
                if (arm.rule() && end.reachable()) {
                    if (statement) pending.add(new Pending(JumpKind.BREAK, null, end));
                    end = State.DEAD;
                }
                carried = end;
            }
            return carried;
        }

        /**
         * Walks an expression read for its value.
         *
         * @param value the expression
         * @param in the state ahead of it
         * @return the state after it
         */
        State value(Value<S> value, State in) {
            if (value instanceof Assign<S> assign) {
                State state = in;
                for (Value<S> operand : assign.operands()) state = value(operand, state);
                return assign.target() == null ? state : write(assign.target(), state);
            }
            if (value instanceof Evaluate<S> evaluate) {
                State state = in;
                for (Value<S> operand : evaluate.operands()) state = value(operand, state);
                return state;
            }
            if (value instanceof SwitchValue<S> choice) {
                State selected = value(choice.selector(), in);
                List<Pending> outer = enter();
                arms(choice.arms(), selected, false);
                return leave(outer, State.DEAD, JumpKind.YIELD, null);
            }
            if (value instanceof Constant<S>) return in;
            return condition(value, in).merged();
        }

        /**
         * Walks an expression read as a condition.
         *
         * @param value the expression
         * @param in the state ahead of it
         * @return what it leaves where it holds and where it fails
         */
        Split condition(Value<S> value, State in) {
            if (value instanceof Constant<S> constant) {
                State vacuous = new State(true, true, in.reachable());
                return constant.value() ? new Split(in, vacuous) : new Split(vacuous, in);
            }
            if (value instanceof Not<S> not) {
                Split operand = condition(not.operand(), in);
                return new Split(operand.whenFalse(), operand.whenTrue());
            }
            if (value instanceof And<S> and) {
                Split left = condition(and.left(), in);
                Split right = condition(and.right(), left.whenTrue());
                return new Split(right.whenTrue(), left.whenFalse().join(right.whenFalse()));
            }
            if (value instanceof Or<S> or) {
                Split left = condition(or.left(), in);
                Split right = condition(or.right(), left.whenFalse());
                return new Split(left.whenTrue().join(right.whenTrue()), right.whenFalse());
            }
            if (value instanceof Choice<S> choice) {
                Split test = condition(choice.condition(), in);
                Split then = condition(choice.then(), test.whenTrue());
                Split otherwise = condition(choice.otherwise(), test.whenFalse());
                return new Split(then.whenTrue().join(otherwise.whenTrue()),
                    then.whenFalse().join(otherwise.whenFalse()));
            }
            State after = value(value, in);
            return new Split(after, after);
        }

        /**
         * Walks one write, refusing it where the field may already be assigned.
         *
         * @param write the write
         * @param in the state ahead of it
         * @return the state after it
         */
        private State write(Write<S> write, State in) {
            if (!names(write, field, declared)) return in;
            seen.add(write.site());
            if (!in.unassigned()) refused.add(write.site());
            unassignedThroughoutTry = false;
            return new State(true, in.assigned() && in.unassigned(), in.reachable());
        }

        /**
         * Starts collecting the jumps of a statement that resolves some of them.
         *
         * @return the jumps collected outside it
         */
        private List<Pending> enter() {
            List<Pending> outer = pending;
            pending = new ArrayList<>();
            return outer;
        }

        /**
         * Resolves the jumps a statement ends, merging each into the state
         * after it, and hands every other one to the statements outside.
         *
         * @param outer the jumps collected outside the statement
         * @param out the state where the statement completes normally
         * @param kind the kind of jump it ends
         * @param label the label a jump has to name, or {@code null} for one naming none
         * @return the state after the statement
         */
        private State leave(List<Pending> outer, State out, JumpKind kind, @Nullable String label) {
            State state = out;
            for (Pending exit : pending) {
                if (exit.kind() == kind && Objects.equals(exit.label(), label)) state = state.join(exit.state());
                else outer.add(exit);
            }
            pending = outer;
            return state;
        }

        /**
         * Resolves the jumps that go back to a loop's head, merging each into
         * the state at the end of its body.
         *
         * @param end the state at the end of the body
         * @param kind the kind of jump resolved
         * @param labels the labels written on the loop
         * @return the state the loop goes round with
         */
        private State resolve(State end, JumpKind kind, List<String> labels) {
            State state = end;
            List<Pending> rest = new ArrayList<>();
            for (Pending exit : pending) {
                if (exit.kind() == kind && (exit.label() == null || labels.contains(exit.label())))
                    state = state.join(exit.state());
                else rest.add(exit);
            }
            pending = rest;
            return state;
        }

    }

}
