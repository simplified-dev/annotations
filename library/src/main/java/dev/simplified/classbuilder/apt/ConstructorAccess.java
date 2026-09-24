package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;
import org.jetbrains.annotations.Nullable;

/**
 * The rule deciding which values {@code @ClassBuilder(constructorAccess)} can
 * take, the diagnostic for the one it cannot, and the access the constructor
 * {@code build()} calls is generated at beside a written
 * {@code @BuilderArgsConstructor}.
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
     * Reads the access {@code constructorAccess} generates at from the
     * attribute's constant as written.
     *
     * @param written the name of the {@link AccessLevel} constant written, or null when the attribute is not written
     * @return the level, {@link #DEFAULT} where none is written, the name is no constant, or the constant is not
     *     {@linkplain #expressible expressible}
     */
    public static AccessLevel generatedAt(@Nullable String written) {
        if (written == null) return DEFAULT;
        for (AccessLevel level : AccessLevel.values())
            if (level.name().equals(written)) return expressible(level) ? level : DEFAULT;
        return DEFAULT;
    }

    /**
     * The access the constructor {@code build()} calls is generated at.
     *
     * <p>A value written on {@code @BuilderArgsConstructor} wins, then
     * {@code constructorAccess}. Only the written value is read, so a bare
     * {@code @BuilderArgsConstructor} states nothing about visibility and leaves
     * {@code constructorAccess} in force. {@code access = NONE} reads as
     * unwritten: that annotation reports it, and {@code build()} still calls a
     * constructor, so it is generated as though the annotation were absent. Read
     * from the constant's name, so the processor asks it of the annotation's
     * value and the editor of the reference written in PSI.
     *
     * @param constructorAccess the level {@code @ClassBuilder(constructorAccess)} generates at
     * @param builderArgsAccess the constant name written as {@code @BuilderArgsConstructor(access)},
     *     or {@code null} when the annotation or the attribute is not written
     * @return the level the constructor carries
     */
    public static AccessLevel allArgs(AccessLevel constructorAccess, @Nullable String builderArgsAccess) {
        if (builderArgsAccess == null) return constructorAccess;
        for (AccessLevel level : AccessLevel.values())
            if (level.name().equals(builderArgsAccess)) return level.emits() ? level : constructorAccess;
        return constructorAccess;
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
