package dev.simplified.annotations;

/**
 * Whether a generated member folds in the superclass's own implementation.
 *
 * <p>Shared by {@link EqualsAndHashCode} and {@link ToString}, which face the
 * identical question and must not answer it two different ways: a type whose
 * {@code equals} counts an inherited field while its {@code toString} hides one
 * reads as a bug in whichever member the author looks at second.
 *
 * <p>{@link #AUTO} is the default on both. A flat {@code false} - Lombok's
 * default - silently drops inherited state, and the only signal is a warning
 * that fires on every subclass whether or not it wanted the call.
 */
public enum CallSuper {

    /**
     * Resolved per target from what the direct superclass actually provides.
     *
     * <p>A direct subclass of {@code Object} resolves to {@link #NO}; anything
     * whose superclass supplies its own implementation resolves to {@link #YES}.
     * The resolution is reported as a note, because it can change from
     * {@code NO} to {@code YES} when a superclass in another artifact gains the
     * annotation, with no edit to the subclass's own source.
     */
    AUTO,

    /**
     * Always call the superclass. An error on a direct subclass of
     * {@code Object}, whose identity implementation would defeat the generated
     * one outright.
     */
    YES,

    /** Never call the superclass, whatever it declares. */
    NO

}
