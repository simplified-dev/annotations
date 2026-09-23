package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
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
 * @param superType the builder type the extends clause has to name, qualified by the ancestor's simple name, or null when the role requires none
 * @param buildReturnType the erased type the role's generated build method returns
 * @param superTypeArguments the erased simple names the extends clause has to pass, in order, empty when none are required
 * @param buildReturnTypes every erased type a declared build method may return and still stand in for the generated one, {@code buildReturnType} first
 * @param typeParameterBounds the bounds the target writes on each of its own type parameters, which lead {@code typeParameterNames}, null where none is written, empty when the bounds are not compared
 */
public record RoleExpectation(List<String> typeParameterNames,
                              @Nullable String superType,
                              String buildReturnType,
                              List<String> superTypeArguments,
                              List<String> buildReturnTypes,
                              List<@Nullable String> typeParameterBounds) {

    /**
     * Defensive copies of lists the caller still owns. The bounds go through
     * {@link Collections#unmodifiableList}, an unbounded parameter being a null
     * entry.
     */
    public RoleExpectation {
        typeParameterNames = List.copyOf(typeParameterNames);
        superTypeArguments = List.copyOf(superTypeArguments);
        buildReturnTypes = List.copyOf(buildReturnTypes);
        typeParameterBounds = Collections.unmodifiableList(new ArrayList<>(typeParameterBounds));
    }

    /**
     * An expectation that leaves the target's bounds uncompared.
     *
     * @param typeParameterNames the parameter names the declared builder has to re-declare, in order
     * @param superType the builder type the extends clause has to name, or null when the role requires none
     * @param buildReturnType the erased type the build method has to return
     * @param superTypeArguments the erased simple names the extends clause has to pass, in order
     * @param buildReturnTypes every erased type a declared build method may return, {@code buildReturnType} first
     */
    public RoleExpectation(List<String> typeParameterNames, @Nullable String superType,
                           String buildReturnType, List<String> superTypeArguments,
                           List<String> buildReturnTypes) {
        this(typeParameterNames, superType, buildReturnType, superTypeArguments, buildReturnTypes, List.of());
    }

    /**
     * An expectation whose build method may return only the type the role builds.
     *
     * @param typeParameterNames the parameter names the declared builder has to re-declare, in order
     * @param superType the builder type the extends clause has to name, or null when the role requires none
     * @param buildReturnType the erased type the build method has to return
     * @param superTypeArguments the erased simple names the extends clause has to pass, in order
     */
    public RoleExpectation(List<String> typeParameterNames, @Nullable String superType,
                           String buildReturnType, List<String> superTypeArguments) {
        this(typeParameterNames, superType, buildReturnType, superTypeArguments, List.of(buildReturnType));
    }

    /**
     * An expectation requiring no extends-clause arguments.
     *
     * @param typeParameterNames the parameter names the declared builder has to re-declare, in order
     * @param superType the builder type the extends clause has to name, or null when the role requires none
     * @param buildReturnType the erased type the build method has to return
     */
    public RoleExpectation(List<String> typeParameterNames, @Nullable String superType,
                           String buildReturnType) {
        this(typeParameterNames, superType, buildReturnType, List.of());
    }

}
