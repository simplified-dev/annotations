package dev.simplified.annotations;

/**
 * Java source-level access modifier choices used by the builder-generation
 * annotations in this package to describe the visibility of generated methods
 * and classes.
 */
public enum AccessLevel {

    /** {@code public} - visible everywhere. */
    PUBLIC,

    /** {@code protected} - visible in the declaring package and to subclasses. */
    PROTECTED,

    /** Package-private - no modifier keyword, visible in the declaring package only. */
    PACKAGE,

    /** {@code private} - visible only in the declaring class. */
    PRIVATE,

    /**
     * Generate nothing. Not an access level at all but the absence of a
     * member, which is why {@link #toKeyword()} throws rather than returning a
     * string: there is no modifier that means "does not exist", and the empty
     * string already means {@link #PACKAGE}. Callers gate on {@link #emits()}
     * first.
     *
     * <p>Used to subtract one field from a type-level {@link Getter} or
     * {@link Setter}. Only the accessor annotations accept it; the builder
     * surface has {@link BuilderIgnore} and {@code @ClassBuilder(exclude)} for
     * the same job.
     */
    NONE;

    /**
     * Whether a member is generated at all.
     *
     * @return {@code false} only for {@link #NONE}
     */
    public boolean emits() {
        return this != NONE;
    }

    /**
     * Returns the Java source keyword for this access level, or the empty
     * string for {@link #PACKAGE}.
     *
     * @return the modifier keyword
     * @throws IllegalStateException when called on {@link #NONE}, which has no
     *         keyword - check {@link #emits()} first
     */
    public String toKeyword() {
        return switch (this) {
            case PUBLIC -> "public";
            case PROTECTED -> "protected";
            case PACKAGE -> "";
            case PRIVATE -> "private";
            case NONE -> throw new IllegalStateException(
                "AccessLevel.NONE has no keyword - callers must check emits() first");
        };
    }

}
