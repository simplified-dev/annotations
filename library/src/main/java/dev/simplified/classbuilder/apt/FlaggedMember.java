package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.BuildFlag;

/**
 * One member a {@code @BuildFlag} constrains, resolved to what the generated
 * {@code build()} needs in order to check it.
 *
 * @param name the member's simple name
 * @param accessor whether the value is read by calling it rather than by reading a field
 * @param primitive whether the member's type is primitive, and so can be neither null nor empty
 * @param flag the constraint as written
 */
public record FlaggedMember(String name, boolean accessor, boolean primitive, BuildFlag flag) {

    /**
     * Whether the flag asks for anything at all.
     *
     * <p>Every attribute at its default is a constraint that can never fail, so
     * such a member is dropped at read time and never reaches the emitter -
     * which is what lets the emitter treat "any flagged member" as "something
     * to enforce".
     *
     * @param flag the constraint as written
     * @return whether it carries an enforceable constraint
     */
    public static boolean enforceable(BuildFlag flag) {
        return flag.nonNull()
            || flag.notEmpty()
            || !flag.pattern().isEmpty()
            || flag.limit() >= 0
            || flag.group().length > 0
            || flag.min() != Double.NEGATIVE_INFINITY
            || flag.max() != Double.POSITIVE_INFINITY;
    }

}
