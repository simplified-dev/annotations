package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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
     * @param instanceMembers the names of every non-static field and method visible on the target,
     *     the accessors {@link #generatedAccessorNames} lists included
     * @return whether the default has to be computed on the instance
     */
    public static boolean readsInstanceState(@NotNull Iterable<String> spelled,
                                             @NotNull Set<String> instanceMembers) {
        for (String name : spelled) {
            if (name.equals("this") || name.equals("super") || instanceMembers.contains(name)) return true;
        }
        return false;
    }

    /**
     * Names the instance accessors another pass generates on a type from one
     * field's written annotations, which belong among the instance members
     * {@link #readsInstanceState} is asked of.
     *
     * <p>Neither half can list them off its model: the processor reads the
     * target before the accessor and {@code @Lazy} passes append anything, and
     * the editor reads each type's own declarations, which leave out every
     * member a provider contributes. So both derive them from the annotations as
     * written, each named through the scheme the annotation writes - a
     * {@code @Getter}'s read accessor, a {@code @Setter}'s write accessor, and a
     * {@code @Lazy} field's getter. Over-eager in the way the rule already is:
     * an access level of {@code NONE}, a {@code final} field's setter or an
     * excluded field still lists its name, which costs a slot computed on the
     * instance and nothing else. A static field's accessors are static, and are
     * none of them.
     *
     * @param fieldName the field's name
     * @param isBoolean whether the field's declared type is {@code boolean}
     * @param isStatic whether the field is {@code static}
     * @param getter the scheme of the {@code @Getter} written on the field, or failing that on
     *     its type, or {@code null} when neither carries one
     * @param setter the same for {@code @Setter}
     * @param lazy the scheme of the {@code @Lazy} written on the field, or {@code null} when it
     *     carries none
     * @return the generated instance accessors' names, getter then setter then lazy getter
     */
    public static @NotNull List<String> generatedAccessorNames(@NotNull String fieldName, boolean isBoolean,
                                                               boolean isStatic,
                                                               @Nullable AccessorScheme getter,
                                                               @Nullable AccessorScheme setter,
                                                               @Nullable AccessorScheme lazy) {
        if (isStatic) return List.of();
        List<String> out = new ArrayList<>(3);
        if (getter != null) out.add(getter.readName(fieldName, isBoolean));
        if (setter != null) out.add(setter.writeName(fieldName, isBoolean));
        if (lazy != null) out.add(lazy.readName(fieldName, isBoolean));
        return out;
    }

}
