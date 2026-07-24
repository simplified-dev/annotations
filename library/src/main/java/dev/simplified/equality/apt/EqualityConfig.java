package dev.simplified.equality.apt;

import dev.simplified.annotations.CallSuper;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.shared.apt.MemberPolicy;

import javax.lang.model.element.TypeElement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code @EqualsAndHashCode}'s attributes, resolved against their defaults.
 *
 * @param identity the relation deciding candidacy for equality
 * @param callSuper the written attribute, before {@code AUTO} is resolved
 * @param of member names to take to the exclusion of every other
 * @param exclude member names to skip
 * @param cacheHashCode whether the hash is memoized
 * @param useAccessors whether members are read through a declared accessor
 * @param emitContracts whether to emit {@code @XContract}
 * @param emitGenerated whether to emit {@code @Generated}
 */
public record EqualityConfig(
    EqualsAndHashCode.Identity identity,
    CallSuper callSuper,
    Set<String> of,
    Set<String> exclude,
    boolean cacheHashCode,
    boolean useAccessors,
    boolean emitContracts,
    boolean emitGenerated
) {

    /** This annotation, spelled as a diagnostic names it. */
    public static final String LABEL = "@EqualsAndHashCode";

    /** This annotation's fully qualified name. */
    public static final String FQN = "dev.simplified.annotations.EqualsAndHashCode";

    /** The marker removing a member. */
    public static final String EXCLUDE_FQN = "dev.simplified.annotations.EqualsExclude";

    /** The marker adding one back. */
    public static final String INCLUDE_FQN = "dev.simplified.annotations.EqualsInclude";

    /**
     * Reads the annotation off a target.
     *
     * <p>Through {@code getAnnotation} rather than the mirror, since every
     * attribute here wants its default when unwritten and none of them needs
     * the written-versus-defaulted distinction.
     *
     * @param target the annotated type
     * @return the resolved configuration
     */
    public static EqualityConfig from(TypeElement target) {
        EqualsAndHashCode written = target.getAnnotation(EqualsAndHashCode.class);
        if (written == null) {
            return new EqualityConfig(EqualsAndHashCode.Identity.EXACT_CLASS, CallSuper.AUTO,
                Set.of(), Set.of(), false, false, true, true);
        }
        return new EqualityConfig(
            written.identity(),
            written.callSuper(),
            setOf(written.of()),
            setOf(written.exclude()),
            written.cacheHashCode(),
            written.useAccessors(),
            written.emitContracts(),
            written.emitGenerated()
        );
    }

    /** The selection rules this annotation hands the shared selector. */
    public MemberPolicy policy() {
        return new MemberPolicy(LABEL, false, EXCLUDE_FQN, INCLUDE_FQN, of, exclude, false);
    }

    private static Set<String> setOf(String[] names) {
        return names.length == 0 ? Set.of() : new LinkedHashSet<>(Arrays.asList(names));
    }

}
