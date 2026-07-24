package dev.simplified.args.apt;

/**
 * The field-selection rule, expressed over plain booleans.
 *
 * <p>Free of javac, {@code javax.lang.model} and PSI on purpose. The processor
 * decides from a {@code JCVariableDecl} and the IDE from a {@code PsiField},
 * and the two have to agree exactly or the signature in the gutter contradicts
 * the one javac emits. Sharing the extracted facts is not possible; sharing the
 * decision is, and this is it.
 */
public final class ArgsSelection {

    private ArgsSelection() {
    }

    /**
     * Whether a field becomes a constructor parameter under the given mode.
     *
     * <p>Callers filter {@code static}, {@code $}-prefixed and enum-constant
     * fields out beforehand; none of the modes ever selects one.
     *
     * @param mode the selection policy
     * @param isFinal whether the field is {@code final}
     * @param hasInitializer whether the field carries an initializer
     * @param isTransient whether the field is {@code transient}
     * @param ignored whether the field carries {@code @BuilderIgnore}
     * @param excluded whether {@code @ClassBuilder(exclude)} names the field
     * @return whether the field is a parameter
     */
    public static boolean selects(ArgsMode mode, boolean isFinal, boolean hasInitializer,
                                  boolean isTransient, boolean ignored, boolean excluded) {
        return switch (mode) {
            // A final field with an initializer is already definitely assigned,
            // so a parameter for it would be a second assignment.
            case ALL -> !(isFinal && hasInitializer);
            case REQUIRED -> isFinal && !hasInitializer;
            case NONE -> false;
            // Keeps final-with-initializer, which the builder holds as a default
            // and the AST pass strips to a blank final for this constructor to
            // assign - the axis on which BUILDER runs wider than ALL rather
            // than narrower.
            case BUILDER -> !isTransient && !ignored && !excluded;
        };
    }

}
