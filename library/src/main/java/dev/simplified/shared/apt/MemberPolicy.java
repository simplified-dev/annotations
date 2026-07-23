package dev.simplified.shared.apt;

import java.util.Set;

/**
 * The one difference between how {@code @EqualsAndHashCode} and
 * {@code @ToString} choose members, plus the names each reads its markers under.
 *
 * <p>{@link #keepTransient} is the whole reason this record takes a flag rather
 * than the selector hard-coding one rule. A {@code transient} field is by
 * definition not part of the value and must not reach an equality relation,
 * while it is still state a debugger dump wants. A shared selector that
 * silently applied the equality rule to both would be a correctness bug with no
 * compile signal, so the divergence is a parameter rather than an assumption.
 *
 * @param label the annotation's own spelling, for diagnostics
 * @param keepTransient whether a {@code transient} member is selected
 * @param excludeFqn the marker annotation removing a member
 * @param includeFqn the marker annotation adding one back
 * @param of member names to take to the exclusion of every other
 * @param exclude member names to skip
 * @param honourRank whether the include marker carries a sort key to apply
 */
public record MemberPolicy(
    String label,
    boolean keepTransient,
    String excludeFqn,
    String includeFqn,
    Set<String> of,
    Set<String> exclude,
    boolean honourRank
) {
}
