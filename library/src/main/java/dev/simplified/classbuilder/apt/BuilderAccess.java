package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;

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
     * The error for {@code access = NONE}, reported on the annotation.
     *
     * @return the sentence both halves report
     */
    public static String notExpressible() {
        return "@ClassBuilder(access = NONE) is not expressible - the builder class is always "
            + "generated, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC";
    }

}
