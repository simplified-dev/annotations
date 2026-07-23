package dev.simplified.shared.apt;

import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * The emission row a member's value is compared, hashed and printed through.
 *
 * <p>Chosen from the type of the <b>read expression</b> rather than the
 * declared field, so a member read through an accessor dispatches on what that
 * accessor actually returns. A member whose declared type is a collection but
 * whose accessor returns an array would otherwise be compared by identity.
 *
 * <p>Array depth is taken off the {@link TypeMirror} rather than a printed
 * type, which makes it immune to type-use annotations - {@code float @NotNull []}
 * is a one-dimensional primitive array whatever it looks like in source.
 */
public enum MemberShape {

    /** {@code boolean}. */
    BOOLEAN,

    /** {@code byte}, {@code short}, {@code char} or {@code int} - already an {@code int} hash. */
    INTEGRAL,

    /** {@code long}, folded to an {@code int} by exclusive-or of its halves. */
    LONG,

    /**
     * {@code float}, compared with {@code Float.compare} and hashed through
     * {@code floatToIntBits}.
     *
     * <p>{@code ==} is not an equivalence relation on a float: it is
     * non-reflexive for {@code NaN} and conflates {@code -0.0} with
     * {@code 0.0}. {@code compare} and {@code floatToIntBits} induce the same
     * partition, so the pair stays consistent.
     */
    FLOAT,

    /** {@code double}, on the same reasoning as {@link #FLOAT}. */
    DOUBLE,

    /** A one-dimensional array of primitives - {@code Arrays.equals} and friends. */
    PRIMITIVE_ARRAY,

    /** An array of references or of arrays - the {@code deep} family. */
    DEEP_ARRAY,

    /** Anything else, delegating to the value's own {@code equals} and {@code hashCode}. */
    REFERENCE;

    /**
     * Classifies a type.
     *
     * @param type the type of the read expression
     * @return the row that type is emitted through
     */
    public static MemberShape of(TypeMirror type) {
        switch (type.getKind()) {
            case BOOLEAN: return BOOLEAN;
            case BYTE:
            case SHORT:
            case CHAR:
            case INT: return INTEGRAL;
            case LONG: return LONG;
            case FLOAT: return FLOAT;
            case DOUBLE: return DOUBLE;
            case ARRAY: return arrayOf(((ArrayType) type).getComponentType());
            default: return REFERENCE;
        }
    }

    /**
     * Whether a shape is one of the two array rows, which is what decides
     * between the flat and the {@code deep} spelling rather than the rank.
     */
    public boolean array() {
        return this == PRIMITIVE_ARRAY || this == DEEP_ARRAY;
    }

    private static MemberShape arrayOf(TypeMirror component) {
        TypeKind kind = component.getKind();
        // A type variable erases to its bound, so T[] is an Object[] at
        // runtime and the deep form is the one that reads its elements.
        boolean reference = kind == TypeKind.ARRAY
            || kind == TypeKind.DECLARED
            || kind == TypeKind.TYPEVAR
            || kind == TypeKind.WILDCARD;
        return reference ? DEEP_ARRAY : PRIMITIVE_ARRAY;
    }

}
