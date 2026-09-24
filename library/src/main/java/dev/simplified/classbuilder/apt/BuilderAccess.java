package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;
import org.jetbrains.annotations.Nullable;

/**
 * The rule deciding which values {@code @ClassBuilder(access)} can take, and
 * the diagnostic for the one it cannot.
 *
 * <p>Both halves ask it: the processor of the annotation it reads, the editor of
 * the reference written in PSI. The attribute is the access of the builder
 * class and of the entry points on every kind of target, and the builder class
 * is generated whatever else is suppressed, so a level that names no modifier
 * has nothing to apply to. The processor reports it at the annotation and then
 * generates as under {@link #DEFAULT}, which is what the editor contributes
 * beside the same error.
 */
public final class BuilderAccess {

    /** The attribute's name on {@code @ClassBuilder}. */
    public static final String ATTRIBUTE = "access";

    /** The level the attribute defaults to, and the one generated beside the error. */
    public static final AccessLevel DEFAULT = AccessLevel.PUBLIC;

    private BuilderAccess() { }

    /**
     * Whether an access level can be written as {@code access}.
     *
     * @param access the written level
     * @return whether it names a modifier the builder class can carry
     */
    public static boolean expressible(AccessLevel access) {
        return access.emits();
    }

    /**
     * Reads the access the builder class is generated at from the attribute's
     * constant as written.
     *
     * @param written the name of the {@link AccessLevel} constant written, or null when the attribute is not written
     * @return the level, {@link #DEFAULT} where none is written, the name is no constant, or the constant is not
     *     {@linkplain #expressible expressible}
     */
    public static AccessLevel generatedAt(@Nullable String written) {
        if (written == null) return DEFAULT;
        for (AccessLevel level : AccessLevel.values()) {
            if (level.name().equals(written)) return expressible(level) ? level : DEFAULT;
        }
        return DEFAULT;
    }

    /**
     * The error for {@code access = NONE}, reported on the annotation.
     *
     * @return the sentence both halves report
     */
    public static String notExpressible() {
        return "@ClassBuilder(access = NONE) is not expressible - the builder class is always "
            + "generated, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC";
    }

}
