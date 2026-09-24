package dev.simplified.classbuilder.apt;

/**
 * Where a {@code @ClassBuilder} target sits in a SuperBuilder chain, deciding
 * the shape of the builder generated for it.
 *
 * <p>The classification rests on two questions - is the target abstract, and
 * does its direct superclass carry {@code @ClassBuilder} - and both halves ask
 * them of their own model: the processor of the element model, the editor of
 * PSI. The enum is here rather than beside either so the answer they act on is
 * one set of constants and one set of predicates, since a role decided
 * differently on the two sides is a builder the editor draws one shape and the
 * build emits another.
 */
public enum ChainRole {

    /** Not in a chain: a plain nested {@code Builder} with {@code build()} returning the target. */
    STANDALONE,

    /**
     * Abstract, no annotated super. Root of a chain: the builder is abstract and
     * self-typed {@code <T extends Target, B extends Builder<T, B>>}, with
     * abstract {@code self()} and {@code build()}.
     */
    ABSTRACT_ROOT,

    /**
     * Concrete, annotated super. The builder binds the parent's self types -
     * {@code extends Super.Builder<Target, Builder>} - and overrides
     * {@code self()} and {@code build()}.
     */
    CONCRETE_LINK,

    /**
     * Abstract, annotated super. Stays self-typed and forwards both parameters
     * up ({@code extends Super.Builder<T, B>}), leaving {@code self()} and
     * {@code build()} abstract.
     */
    CHAINED_ABSTRACT;

    /**
     * Classifies a target from the two questions the role rests on.
     *
     * @param isAbstract whether the target is an abstract class
     * @param hasAnnotatedSuper whether its direct superclass carries {@code @ClassBuilder}
     * @return the role, never null
     */
    public static ChainRole of(boolean isAbstract, boolean hasAnnotatedSuper) {
        if (isAbstract) return hasAnnotatedSuper ? CHAINED_ABSTRACT : ABSTRACT_ROOT;
        return hasAnnotatedSuper ? CONCRETE_LINK : STANDALONE;
    }

    /** Whether the builder carries the self-typed {@code T} / {@code B} parameters. */
    public boolean isSelfTyped() {
        return this == ABSTRACT_ROOT || this == CHAINED_ABSTRACT;
    }

    /** Whether the builder extends a parent builder. */
    public boolean hasAnnotatedSuper() {
        return this == CONCRETE_LINK || this == CHAINED_ABSTRACT;
    }

    /** Whether the target sits in a chain at all, as opposed to standing alone. */
    public boolean isChained() {
        return this != STANDALONE;
    }

}
