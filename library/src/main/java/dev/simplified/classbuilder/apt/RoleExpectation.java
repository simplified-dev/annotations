package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What a role requires of the builder declared for it.
 *
 * <p>Derived from the role and the target rather than from the declaration, so
 * the comparison has a fixed side: whatever the author wrote is measured against
 * this, and the same values render the operands of whichever rejection comes
 * back.
 *
 * @param typeParameterNames the parameter names the declared builder has to re-declare, in order
 * @param superType the erased builder type the extends clause has to name, or null when the role requires none
 * @param buildReturnType the erased type the build method has to return
 */
public record RoleExpectation(List<String> typeParameterNames,
                              @Nullable String superType,
                              String buildReturnType) {

    /** Defensive copy of a list the caller still owns. */
    public RoleExpectation {
        typeParameterNames = List.copyOf(typeParameterNames);
    }

}
