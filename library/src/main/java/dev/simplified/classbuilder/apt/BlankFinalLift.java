package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 * <p>A constructor assigns a field by a structural reading of its body, by name
 * and with no further flow: a statement assigns it when it is a plain
 * {@code =} to it, possibly chained; a block one of whose statements assigns it
 * with no {@code break} ahead of that statement; an {@code if} with an
 * {@code else} both of whose branches assign it; or a {@code switch} with a
 * {@code default} every arm of which assigns it and leaves the switch - an
 * arrow arm, or a colon arm ending in {@code break}. A write inside a loop, a
 * {@code try}, {@code catch} or {@code finally}, a lambda or a nested class is
 * never counted: the field keeps its initializer, and javac refuses the write
 * as a second assignment on the author's line, where the editor reports it
 * too.
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
    public sealed interface Statement<S> permits Assignment, Block, Branch, Switch, Exit, Break, Other {
    }

    /**
     * One target of a plain {@code =}, written through its bare name or an
     * unqualified {@code this}.
     *
     * @param name the written name
     * @param qualified whether it is written through {@code this}
     * @param site the written reference
     * @param <S> what the half identifies the reference by
     */
    public record Write<S>(@NotNull String name, boolean qualified, S site) {
    }

    /**
     * An expression statement that is a plain {@code =}, or a chain of them.
     *
     * @param writes the chain's targets, in the order javac assigns them - the innermost first
     * @param <S> what the half identifies a written reference by
     */
    public record Assignment<S>(@NotNull List<Write<S>> writes) implements Statement<S> {
    }

    /**
     * A block.
     *
     * @param statements its statements
     * @param <S> what the half identifies a written reference by
     */
    public record Block<S>(@NotNull List<Statement<S>> statements) implements Statement<S> {
    }

    /**
     * An {@code if}, with or without an {@code else}.
     *
     * @param then the statement run when the condition holds
     * @param otherwise the {@code else} statement, or {@code null} when none is written
     * @param <S> what the half identifies a written reference by
     */
    public record Branch<S>(@NotNull Statement<S> then, @Nullable Statement<S> otherwise) implements Statement<S> {
    }

    /**
     * A {@code switch} statement.
     *
     * @param hasDefault whether one of its labels is {@code default}
     * @param arms its arms in source order, consecutive colon labels read as one arm
     * @param <S> what the half identifies a written reference by
     */
    public record Switch<S>(boolean hasDefault, @NotNull List<Arm<S>> arms) implements Statement<S> {
    }

    /**
     * One arm of a {@code switch}.
     *
     * @param rule whether it is an arrow arm, which never falls through
     * @param statements the arm's statements; an arrow arm's body is its only one
     * @param <S> what the half identifies a written reference by
     */
    public record Arm<S>(boolean rule, @NotNull List<Statement<S>> statements) {
    }

    /**
     * A {@code return} or a {@code throw}.
     *
     * @param <S> what the half identifies a written reference by
     */
    public record Exit<S>() implements Statement<S> {
    }

    /**
     * A {@code break} with no label, which leaves the nearest enclosing {@code switch}.
     *
     * @param <S> what the half identifies a written reference by
     */
    public record Break<S>() implements Statement<S> {
    }

    /**
     * Any other statement - a loop, a {@code try}, a labelled or
     * {@code synchronized} statement, a declaration, or an expression statement
     * that is no plain {@code =}.
     *
     * @param writes the plain {@code =} writes inside it, outside any lambda or nested class
     * @param <S> what the half identifies a written reference by
     */
    public record Other<S>(@NotNull List<Write<S>> writes) implements Statement<S> {
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
         * class's body.
         *
         * @param delegates whether one of the body's statements is a {@code this(..)} call
         * @param declared the parameter and local variable names the constructor declares
         * @param body the body's statements
         * @param <S> what the half identifies a written reference by
         * @return the summary
         */
        public static <S> Writes of(boolean delegates, Collection<String> declared, List<Statement<S>> body) {
            Block<S> whole = new Block<>(body);
            Set<String> written = new HashSet<>();
            collectNames(whole, written);
            Set<String> assigned = new HashSet<>();
            for (String name : written) {
                if (assigns(whole, name, declared)) assigned.add(name);
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
     * <p>A write is accepted where the field is still unassigned on every path
     * reaching it: the paths run through blocks in order, through both sides of
     * an {@code if}, and through each arm of a {@code switch}, a colon arm
     * falling through into the next; a {@code return}, a {@code throw} or a
     * {@code break} ends its path. A write inside any other statement - a loop
     * or a {@code try} among them - is never accepted and leaves the state it
     * found. After {@code this(..)} the field is assigned already.
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
        Walk<S> walk = new Walk<>(field, declared);
        walk.sequence(body, delegates ? Flow.ASSIGNED : Flow.UNASSIGNED, null);
        return walk.accepted;
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
     * Whether a statement assigns the field on every way out of it, by the structural rule.
     *
     * @param statement the statement
     * @param field the field's name
     * @param declared the names the constructor declares
     * @param <S> what the half identifies a written reference by
     * @return whether it assigns the field
     */
    private static <S> boolean assigns(Statement<S> statement, String field, Collection<String> declared) {
        if (statement instanceof Assignment<S> assignment) {
            for (Write<S> write : assignment.writes()) {
                if (names(write, field, declared)) return true;
            }
            return false;
        }
        if (statement instanceof Block<S> block) return sequenceAssigns(block.statements(), field, declared);
        if (statement instanceof Branch<S> branch) {
            return branch.otherwise() != null && assigns(branch.then(), field, declared)
                && assigns(branch.otherwise(), field, declared);
        }
        if (statement instanceof Switch<S> choice) {
            if (!choice.hasDefault() || choice.arms().isEmpty()) return false;
            for (Arm<S> arm : choice.arms()) {
                List<Statement<S>> statements = arm.statements();
                boolean leaves = arm.rule() || !statements.isEmpty() && statements.get(statements.size() - 1) instanceof Break;
                if (!leaves || !sequenceAssigns(statements, field, declared)) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Whether a statement list assigns the field before any {@code break} can leave it.
     *
     * @param statements the statements
     * @param field the field's name
     * @param declared the names the constructor declares
     * @param <S> what the half identifies a written reference by
     * @return whether the list assigns the field
     */
    private static <S> boolean sequenceAssigns(List<Statement<S>> statements, String field,
                                               Collection<String> declared) {
        for (Statement<S> statement : statements) {
            if (assigns(statement, field, declared)) return true;
            if (mayBreak(statement)) return false;
        }
        return false;
    }

    /**
     * Whether a statement holds a {@code break} leaving a {@code switch} around it.
     *
     * @param statement the statement
     * @return whether it may break out
     */
    private static boolean mayBreak(Statement<?> statement) {
        if (statement instanceof Break<?>) return true;
        if (statement instanceof Block<?> block) {
            for (Statement<?> inner : block.statements()) {
                if (mayBreak(inner)) return true;
            }
            return false;
        }
        if (statement instanceof Branch<?> branch)
            return mayBreak(branch.then()) || branch.otherwise() != null && mayBreak(branch.otherwise());
        return false;
    }

    /**
     * Collects every name a plain {@code =} the structural rule reads writes.
     *
     * @param statement the statement
     * @param out where the names go
     */
    private static void collectNames(Statement<?> statement, Set<String> out) {
        if (statement instanceof Assignment<?> assignment) {
            for (Write<?> write : assignment.writes()) out.add(write.name());
        } else if (statement instanceof Block<?> block) {
            for (Statement<?> inner : block.statements()) collectNames(inner, out);
        } else if (statement instanceof Branch<?> branch) {
            collectNames(branch.then(), out);
            if (branch.otherwise() != null) collectNames(branch.otherwise(), out);
        } else if (statement instanceof Switch<?> choice) {
            for (Arm<?> arm : choice.arms()) {
                for (Statement<?> inner : arm.statements()) collectNames(inner, out);
            }
        }
    }

    /** What a path knows of the field at one point of a constructor body. */
    private enum Flow {

        /** Assigned on no path reaching the point. */
        UNASSIGNED,

        /** Possibly assigned on some path reaching the point. */
        ASSIGNED,

        /** Reached by no path. */
        UNREACHABLE;

        /**
         * Merges two paths meeting at one point.
         *
         * @param other the other path's state
         * @return the merged state
         */
        Flow join(Flow other) {
            if (this == UNREACHABLE) return other;
            if (other == UNREACHABLE) return this;
            return this == UNASSIGNED && other == UNASSIGNED ? UNASSIGNED : ASSIGNED;
        }

    }

    /**
     * One walk of a constructor body for {@link #acceptedWrites}.
     *
     * @param <S> what the half identifies a written reference by
     */
    private static final class Walk<S> {

        private final String field;
        private final Collection<String> declared;
        private final Set<S> accepted = new LinkedHashSet<>();

        Walk(String field, Collection<String> declared) {
            this.field = field;
            this.declared = declared;
        }

        /**
         * Walks statements in order.
         *
         * @param statements the statements
         * @param in the state ahead of the first
         * @param breaks the states leaving the nearest enclosing {@code switch}, or {@code null} outside one
         * @return the state after the last
         */
        Flow sequence(List<Statement<S>> statements, Flow in, @Nullable List<Flow> breaks) {
            Flow state = in;
            for (Statement<S> statement : statements) state = statement(statement, state, breaks);
            return state;
        }

        /**
         * Walks one statement.
         *
         * @param statement the statement
         * @param in the state ahead of it
         * @param breaks the states leaving the nearest enclosing {@code switch}, or {@code null} outside one
         * @return the state after it
         */
        Flow statement(Statement<S> statement, Flow in, @Nullable List<Flow> breaks) {
            if (statement instanceof Assignment<S> assignment) {
                Flow state = in;
                for (Write<S> write : assignment.writes()) {
                    if (!names(write, field, declared)) continue;
                    if (state != Flow.ASSIGNED) accepted.add(write.site());
                    if (state == Flow.UNASSIGNED) state = Flow.ASSIGNED;
                }
                return state;
            }
            if (statement instanceof Block<S> block) return sequence(block.statements(), in, breaks);
            if (statement instanceof Branch<S> branch) {
                Flow then = statement(branch.then(), in, breaks);
                return then.join(branch.otherwise() == null ? in : statement(branch.otherwise(), in, breaks));
            }
            if (statement instanceof Switch<S> choice) {
                List<Flow> exits = new ArrayList<>();
                Flow carried = Flow.UNREACHABLE;
                for (Arm<S> arm : choice.arms()) {
                    Flow end = sequence(arm.statements(), in.join(carried), exits);
                    if (arm.rule()) {
                        exits.add(end);
                        carried = Flow.UNREACHABLE;
                    } else {
                        carried = end;
                    }
                }
                exits.add(carried);
                if (!choice.hasDefault()) exits.add(in);
                Flow out = Flow.UNREACHABLE;
                for (Flow exit : exits) out = out.join(exit);
                return out;
            }
            if (statement instanceof Exit<S>) return Flow.UNREACHABLE;
            if (statement instanceof Break<S>) {
                if (breaks != null) breaks.add(in);
                return Flow.UNREACHABLE;
            }
            return in;
        }

    }

}
