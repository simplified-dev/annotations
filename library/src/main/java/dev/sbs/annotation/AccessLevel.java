package dev.sbs.annotation;

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
    PRIVATE;

    /**
     * Returns the Java source keyword for this access level, or the empty
     * string for {@link #PACKAGE}.
     */
    public String toKeyword() {
        return switch (this) {
            case PUBLIC -> "public";
            case PROTECTED -> "protected";
            case PACKAGE -> "";
            case PRIVATE -> "private";
        };
    }

}
