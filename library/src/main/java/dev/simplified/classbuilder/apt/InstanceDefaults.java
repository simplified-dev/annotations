package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * The rule deciding which slots take a default computed on the built instance
 * rather than one hoisted into a static provider evaluated when the builder is
 * created.
 *
 * <p>Shared because the form a builder holds a slot in follows from it - a slot
 * whose retained initializer reads instance state is held as a supplier - and
 * both halves have to agree on that form: the processor to generate the slot,
 * the editor to contribute the same slot into a merged builder and to judge an
 * author's field of its name. Neither side has a resolved symbol to ask - the
 * processor reads an initializer that is parsed and entered but not attributed,
 * and the editor must not resolve from inside an augment provider - so the rule
 * is stated over the names the initializer spells, and each half lists those
 * from its own model.
 *
 * <p>Deliberately over-eager: a lambda parameter sharing a name with an instance
 * member counts as a read. That costs a later evaluation and nothing else,
 * because the instance-computed path handles a static-safe expression too.
 */
public final class InstanceDefaults {

    private InstanceDefaults() {
    }

    /**
     * Resolves whether a field's initializer is kept as its builder default.
     *
     * @param written the value written on the field's {@code @BuilderDefault}, or {@code null} when it carries none
     * @param retainInit the class-wide policy written on {@code @ClassBuilder}
     * @return whether the initializer is the builder default
     */
    public static boolean builderDefault(@Nullable Boolean written, boolean retainInit) {
        return written != null ? written : retainInit;
    }

    /**
     * Whether a field's initializer is captured for the builder at all.
     *
     * <p>A kept default is captured, and so is the initializer of a
     * {@code @Collector} over a custom container whatever the policy says, that
     * initializer being the only way the builder has to make a fresh instance of
     * the container. Only a captured initializer can be an instance default.
     *
     * @param builderDefault whether the initializer is the builder default, per {@link #builderDefault}
     * @param customCollector whether the field is a {@code @Collector} over a custom container
     * @return whether the initializer is captured
     */
    public static boolean captures(boolean builderDefault, boolean customCollector) {
        return builderDefault || customCollector;
    }

    /**
     * Whether an initializer spelling these names reads instance state.
     *
     * <p>It does when it spells {@code this} or {@code super}, or the name of
     * any instance field or method the target declares or inherits.
     *
     * @param spelled every name the initializer spells without a qualifier, with {@code this} once
     *     more for each qualified {@code Outer.this}
     * @param instanceMembers the names of every non-static field and method visible on the target
     * @return whether the default has to be computed on the instance
     */
    public static boolean readsInstanceState(@NotNull Iterable<String> spelled,
                                             @NotNull Set<String> instanceMembers) {
        for (String name : spelled) {
            if (name.equals("this") || name.equals("super") || instanceMembers.contains(name)) return true;
        }
        return false;
    }

}
