package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;

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
 * constructor.
 */
public final class BuilderConstructorAccess {

    /** The attribute's name on {@code @ClassBuilder}. */
    public static final String ATTRIBUTE = "builderConstructorAccess";

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

}
