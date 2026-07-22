package dev.simplified.args.apt;

/**
 * Which fields a generated constructor takes as parameters.
 *
 * <p>The four modes are one field-selection policy with four settings rather
 * than four annotations with four implementations, which is how Lombok models
 * the same feature and why the emitter is shared.
 */
public enum ArgsMode {

    /**
     * Every instance field except a {@code final} one that already carries an
     * initializer. {@code transient} fields are included.
     */
    ALL("@AllArgsConstructor"),

    /**
     * Only {@code final} fields with no initializer - the set the compiler
     * would otherwise reject as unassigned.
     */
    REQUIRED("@RequiredArgsConstructor"),

    /** No fields at all. */
    NONE("@NoArgsConstructor"),

    /**
     * The builder-visible field set: {@link #ALL} minus {@code transient},
     * minus {@code @BuilderIgnore}, minus {@code @ClassBuilder(exclude)}, but
     * keeping a {@code final} field with an initializer, whose value the
     * builder holds as a default instead.
     */
    BUILDER("@BuilderArgsConstructor");

    private final String annotationName;

    ArgsMode(String annotationName) {
        this.annotationName = annotationName;
    }

    /**
     * The annotation this mode is spelled as, for diagnostics and for the
     * inferred-annotation surface.
     *
     * @return the annotation's source spelling, including the {@code @}
     */
    public String annotationName() {
        return annotationName;
    }

}
