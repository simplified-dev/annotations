package dev.simplified.tostring.apt;

import dev.simplified.annotations.CallSuper;
import dev.simplified.annotations.ToString;
import dev.simplified.shared.apt.MemberPolicy;

import javax.lang.model.element.TypeElement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@code @ToString}'s attributes, resolved against their defaults.
 *
 * @param callSuper the written attribute, before {@code AUTO} is resolved
 * @param includeFieldNames whether each value is prefixed with its member name
 * @param style the rendered shape
 * @param of member names to take to the exclusion of every other
 * @param exclude member names to skip
 * @param useAccessors whether members are read through a declared accessor
 * @param emitContracts whether to emit {@code @XContract}
 * @param emitGenerated whether to emit {@code @Generated}
 */
public record ToStringConfig(
    CallSuper callSuper,
    boolean includeFieldNames,
    ToString.Style style,
    Set<String> of,
    Set<String> exclude,
    boolean useAccessors,
    boolean emitContracts,
    boolean emitGenerated
) {

    /** This annotation, spelled as a diagnostic names it. */
    public static final String LABEL = "@ToString";

    /** This annotation's fully qualified name. */
    public static final String FQN = "dev.simplified.annotations.ToString";

    /** The marker removing a member. */
    public static final String EXCLUDE_FQN = "dev.simplified.annotations.ToStringExclude";

    /** The marker adding one back, renaming it or reordering it. */
    public static final String INCLUDE_FQN = "dev.simplified.annotations.ToStringInclude";

    /**
     * Reads the annotation off a target.
     *
     * @param target the annotated type
     * @return the resolved configuration
     */
    public static ToStringConfig from(TypeElement target) {
        ToString written = target.getAnnotation(ToString.class);
        if (written == null) {
            return new ToStringConfig(CallSuper.AUTO, true, ToString.Style.SIMPLIFIED,
                Set.of(), Set.of(), false, true, true);
        }
        return new ToStringConfig(
            written.callSuper(),
            written.includeFieldNames(),
            written.style(),
            setOf(written.of()),
            setOf(written.exclude()),
            written.useAccessors(),
            written.emitContracts(),
            written.emitGenerated()
        );
    }

    /**
     * The selection rules this annotation hands the shared selector.
     *
     * <p>{@code transient} is kept, which is the one place the two annotations
     * sharing that selector genuinely differ: a field excluded from
     * serialization is still state a debugger dump wants to see.
     */
    public MemberPolicy policy() {
        return new MemberPolicy(LABEL, true, EXCLUDE_FQN, INCLUDE_FQN, of, exclude, true);
    }

    /** The bracket opening the rendered output. */
    public String open() {
        return style == ToString.Style.LOMBOK ? "(" : "[";
    }

    /** The bracket closing it. */
    public String close() {
        return style == ToString.Style.LOMBOK ? ")" : "]";
    }

    private static Set<String> setOf(String[] names) {
        return names.length == 0 ? Set.of() : new LinkedHashSet<>(Arrays.asList(names));
    }

}
