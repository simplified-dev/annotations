package dev.simplified.utility.apt;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.UtilityClass;

/**
 * Resolved {@code @UtilityClass} configuration for a single target type.
 *
 * @param makeFinal whether the class is marked {@code final}
 * @param members how instance members are treated
 * @param nestedTypes whether nested types are made {@code static}
 * @param constructorAccess visibility of the throwing constructor
 * @param message text carried by the thrown exception, already defaulted
 * @param emitContracts whether the constructor carries {@code @XContract}
 * @param emitGenerated whether the constructor carries {@code @Generated}
 */
public record UtilityConfig(
    boolean makeFinal,
    UtilityClass.Members members,
    boolean nestedTypes,
    AccessLevel constructorAccess,
    String message,
    boolean emitContracts,
    boolean emitGenerated
) {

    /**
     * Reads the annotation off a target, filling {@link #message()} with the
     * default text when it is unwritten.
     *
     * @param annotation the annotation as written
     * @param simpleName the target's simple name, for the default message
     * @return the resolved configuration
     */
    public static UtilityConfig from(UtilityClass annotation, String simpleName) {
        String written = annotation.message();
        return new UtilityConfig(
            annotation.makeFinal(),
            annotation.members(),
            annotation.nestedTypes(),
            annotation.constructorAccess(),
            written.isEmpty()
                ? simpleName + " is a utility class and cannot be instantiated"
                : written,
            annotation.emitContracts(),
            annotation.emitGenerated()
        );
    }

}
