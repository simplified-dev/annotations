package dev.simplified.shared.apt;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * The identity of a type, taken from its element rather than from how it
 * renders.
 *
 * <p>This exists because {@link TypeMirror#toString()} is a <b>display</b> form
 * and was repeatedly used as an identity. It renders type annotations inline, so
 * a field written the way most real code writes one - {@code @NotNull
 * List<String>} - stringifies as
 * {@code java.util.@org.jetbrains.annotations.NotNull List<java.lang.String>}
 * and matches no expected name. Every such comparison fails silently and only
 * on annotated input, which is why the resulting defects reached tests that
 * passed: the fixtures were unannotated.
 *
 * <p>Nothing here erases type arguments beyond what the element's own qualified
 * name carries, and nothing here is a substitute for {@code Types.erasure} when
 * a genuine erasure is wanted - a type variable answers null rather than its
 * bound.
 */
public final class TypeNames {

    private TypeNames() {}

    /**
     * The fully qualified name of a declared type.
     *
     * @param type the type to name
     * @return the qualified name, or null when {@code type} is not a declared
     *     type - a primitive, an array, a type variable or a wildcard
     */
    public static String fqn(TypeMirror type) {
        if (type == null || type.getKind() != TypeKind.DECLARED) return null;
        Element element = ((DeclaredType) type).asElement();
        return element instanceof TypeElement declared ? declared.getQualifiedName().toString() : null;
    }

    /**
     * Whether a type is the named class.
     *
     * @param type the type to test
     * @param fqn the fully qualified name to match
     * @return true when {@code type} is a declared type with that name
     */
    public static boolean is(TypeMirror type, String fqn) {
        return fqn.equals(fqn(type));
    }

    /**
     * Whether a type is, or is parameterised by, a type variable.
     *
     * <p>What it is for is knowing when a comparison between two types has to
     * drop to erasures. A {@code static} member of a generic type declares its
     * own parameters, being unable to name the type's, so a method relating to a
     * slot of that type mentions a variable no assignability or sameness test
     * relates to the slot's - while the call javac ends up attributing infers
     * one from the other perfectly happily.
     *
     * @param type the type to test
     * @return whether a type variable appears anywhere in it
     */
    public static boolean mentionsTypeVariable(TypeMirror type) {
        if (type == null) return false;
        return switch (type.getKind()) {
            case TYPEVAR -> true;
            case ARRAY -> mentionsTypeVariable(((javax.lang.model.type.ArrayType) type).getComponentType());
            case WILDCARD -> {
                var wildcard = (javax.lang.model.type.WildcardType) type;
                TypeMirror bound = wildcard.getExtendsBound() != null
                    ? wildcard.getExtendsBound() : wildcard.getSuperBound();
                yield mentionsTypeVariable(bound);
            }
            case DECLARED -> {
                for (TypeMirror argument : ((DeclaredType) type).getTypeArguments()) {
                    if (mentionsTypeVariable(argument)) yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

}
