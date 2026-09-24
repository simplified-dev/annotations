package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;

/**
 * The rule deciding which values {@code @ClassBuilder(constructorAccess)} can
 * take, and the diagnostic for the one it cannot.
 *
 * <p>Both halves ask it: the processor of the annotation it reads, the editor of
 * the reference written in PSI. The attribute is the access of the constructor
 * a generated {@code build()} calls, so a level that names no modifier has
 * nothing to apply to. It is refused on every kind of target, as
 * {@link BuilderAccess} and {@link BuilderConstructorAccess} refuse theirs, and
 * the processor then generates as under {@link #DEFAULT}, which is what the
 * editor contributes beside the same error.
 */
public final class ConstructorAccess {

    /** The attribute's name on {@code @ClassBuilder}. */
    public static final String ATTRIBUTE = "constructorAccess";

    /** The level the attribute defaults to, and the one generated beside the error. */
    public static final AccessLevel DEFAULT = AccessLevel.PACKAGE;

    private ConstructorAccess() { }

    /**
     * Whether an access level can be written as {@code constructorAccess}.
     *
     * @param access the written level
     * @return whether it names a modifier a constructor can carry
     */
    public static boolean expressible(AccessLevel access) {
        return access.emits();
    }

    /**
     * The error for {@code constructorAccess = NONE}, reported on the annotation.
     *
     * @return the sentence both halves report
     */
    public static String notExpressible() {
        return "@ClassBuilder(constructorAccess = NONE) is not expressible - it is the access of the "
            + "constructor build() calls, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC";
    }

}
