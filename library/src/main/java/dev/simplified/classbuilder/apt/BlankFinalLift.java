package dev.simplified.classbuilder.apt;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rule deciding whether {@code @ClassBuilder} lifts a {@code final} field's
 * initializer off it, leaving a blank final for a constructor to assign.
 *
 * <p>The lift exists for the constructor {@code build()} reaches, which assigns
 * every field the builder selects: a {@code final} field keeping its own
 * initializer beside that assignment is written twice, and javac rejects it. A
 * constructor the author wrote answers for itself. Where one of them assigns
 * the field nowhere, the field keeps its initializer - lifting it would leave
 * that constructor with a blank final it never assigns, which javac reports on
 * the author's own line. A constructor calling {@code this(..)} leaves its
 * fields to the constructor it delegates to and has no say.
 *
 * <p>Answered from names alone, so both halves reach the same verdict: the
 * processor summarises each constructor off the javac tree, the editor off
 * PSI it does not resolve, and neither re-derives the rule.
 */
public final class BlankFinalLift {

    private BlankFinalLift() {
    }

    /**
     * What one author-written constructor writes, read off its body by name.
     *
     * @param delegates whether one of the body's statements is a {@code this(..)} call
     * @param assigned the field names the body assigns
     */
    public record Writes(boolean delegates, Set<String> assigned) {

        /**
         * Summarises a constructor body from the names it writes and declares.
         *
         * <p>A write through {@code this.name} always names the field. A bare
         * {@code name = ..} names it only where the body declares no parameter
         * or local of that name, since such a write assigns the variable
         * instead. A nested class's body is not read by either half.
         *
         * @param delegates whether one of the body's statements is a {@code this(..)} call
         * @param declared the parameter and local variable names the constructor declares
         * @param thisWrites the names assigned through an unqualified {@code this}
         * @param bareWrites the simple names assigned without a qualifier
         * @return the summary
         */
        public static Writes of(boolean delegates, Collection<String> declared,
                                Collection<String> thisWrites, Collection<String> bareWrites) {
            Set<String> assigned = new HashSet<>(thisWrites);
            for (String name : bareWrites) {
                if (!declared.contains(name)) assigned.add(name);
            }
            return new Writes(delegates, Set.copyOf(assigned));
        }

    }

    /**
     * Decides whether the field's initializer is lifted off it.
     *
     * <p>Asked only of a {@code final} field the builder selects; a generated
     * constructor assigns every such field and is not among the summaries.
     *
     * @param field the field's name
     * @param authorConstructors the target's author-written constructors
     * @return whether the field is left a blank final
     */
    public static boolean lifts(String field, List<Writes> authorConstructors) {
        for (Writes constructor : authorConstructors) {
            if (!constructor.delegates() && !constructor.assigned().contains(field)) return false;
        }
        return true;
    }

}
