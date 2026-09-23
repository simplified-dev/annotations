package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The rule deciding which builder constructor
 * {@code @ClassBuilder(builderConstructorAccess)} reaches, and the two
 * diagnostics that say where it cannot.
 *
 * <p>Both halves ask it: the processor of the tree it is mutating, the editor
 * of PSI it must not resolve. The answers are taken from names and flags alone,
 * so neither side can reach a different verdict by knowing more types than the
 * other.
 *
 * <p>The attribute reaches the builder whose entry points instantiate it - a
 * class or record target's and a constructor or factory target's, generated or
 * declared. On a generated builder it is the access of the constructor the
 * generator writes; on a declared builder that declares no constructor, the
 * access javac's default constructor is retyped to. A declared builder that
 * declares any constructor keeps the author's, which is what
 * {@link #hasNoEffect(String)} says. A chain role's builder carries javac's
 * default at the builder class's own access whether it is generated or
 * declared, because a subclass builder in another package reaches it through
 * {@code super()}; an interface target's sibling builder keeps its implicit
 * constructor. A value written on either reaches nothing, which is what
 * {@link #misplaced} says.
 */
public final class BuilderConstructorAccess {

    /** The attribute's name on {@code @ClassBuilder}. */
    public static final String ATTRIBUTE = "builderConstructorAccess";

    /** The attribute's default, which requests nothing wherever it is written. */
    public static final AccessLevel DEFAULT = AccessLevel.PACKAGE;

    private BuilderConstructorAccess() { }

    /**
     * Whether a builder in this position has its constructor at
     * {@code builderConstructorAccess}.
     *
     * @param role the target's position in a chain, {@link ChainRole#STANDALONE}
     *     for a constructor or factory target
     * @return whether the attribute reaches the builder's constructor
     */
    public static boolean appliesTo(ChainRole role) {
        return role == ChainRole.STANDALONE;
    }

    /**
     * Whether an access level can be written as {@code builderConstructorAccess}.
     *
     * @param access the written level
     * @return whether it names a modifier a constructor can carry
     */
    public static boolean expressible(AccessLevel access) {
        return access.emits();
    }

    /**
     * The error for {@code builderConstructorAccess = NONE}, reported on the
     * annotation. Every builder has a constructor, so there is no builder whose
     * constructor the value could suppress.
     *
     * @return the sentence both halves report
     */
    public static String notExpressible() {
        return "@ClassBuilder(builderConstructorAccess = NONE) is not expressible - every builder "
            + "has a constructor, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC";
    }

    /**
     * The warning for {@code builderConstructorAccess} written on a target whose
     * declared builder declares its own constructor, reported on the attribute.
     * The author's constructor wins, so the attribute changes nothing there.
     *
     * @param builderName the declared builder's simple name
     * @return the sentence both halves report
     */
    public static String hasNoEffect(String builderName) {
        return "@ClassBuilder(builderConstructorAccess) has no effect - the declared '" + builderName
            + "' declares its own constructor, which keeps the access it is written with. Write "
            + "the access on that constructor, or drop the attribute";
    }

    /**
     * The warning for {@code builderConstructorAccess} written on a type target
     * whose builder the attribute never reaches - a SuperBuilder chain role, or
     * an interface - reported on the attribute.
     *
     * <p>Asked of the written value alone, so a value equal to {@link #DEFAULT}
     * requests nothing and is not warned, and {@code NONE} is left to
     * {@link #notExpressible()}, the one diagnostic it gets.
     *
     * @param targetName the annotated type's simple name
     * @param interfaceTarget whether the annotated type is an interface
     * @param role the annotated type's position in a chain, {@link ChainRole#STANDALONE} for an interface
     * @param written the name of the access level written on the annotation, or {@code null} when unwritten
     * @return the sentence both halves report, or {@code null} when there is nothing to warn about
     */
    public static @Nullable String misplaced(@NotNull String targetName, boolean interfaceTarget,
                                             @NotNull ChainRole role, @Nullable String written) {
        if (written == null || written.equals(DEFAULT.name()) || written.equals(AccessLevel.NONE.name()))
            return null;
        if (!interfaceTarget && appliesTo(role)) return null;
        return "@ClassBuilder(builderConstructorAccess) has no effect on '" + targetName + "' - it applies "
            + "only to the builder of a class or record outside a SuperBuilder chain, or of a constructor or "
            + "factory target, never to a chain's builder or an interface's. Drop the attribute";
    }

}
