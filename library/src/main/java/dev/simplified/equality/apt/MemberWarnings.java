package dev.simplified.equality.apt;

import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.shared.apt.MemberSpec;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * The two limits of generated equality that no emission can close, reported
 * against the member that runs into them.
 *
 * <p>Both are documented limitations rather than defects to design around, and
 * a compile-time message naming the field is the only honest response to
 * either.
 */
public final class MemberWarnings {

    private static final String COLLECTION_FQN = "java.util.Collection";
    private static final String OBJECT_FQN = "java.lang.Object";

    private MemberWarnings() {}

    /**
     * Reports whatever this member's type makes impossible.
     *
     * @param member the selected member
     * @param anchor the element to hang the diagnostic on when the member has none
     * @param messager the diagnostic sink
     */
    public static void report(MemberSpec member, TypeElement anchor, Messager messager) {
        Element at = member.element() != null ? member.element() : anchor;
        TypeMirror type = member.type();

        // Deliberately NOT fired on a plain array of any depth. byte[],
        // int[][] and Vector3f[] are all compared correctly by the flat and
        // deep split, and warning on them would train the reader to ignore the
        // one case that is genuinely unreachable.
        if (type.getKind() == TypeKind.DECLARED) {
            TypeMirror wrapped = arrayAmongTypeArguments((DeclaredType) type);
            if (wrapped != null) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "'" + member.name() + "' is a " + type + " - the " + wrapped
                        + " elements compare by identity, not content. The container delegates to "
                        + "its element's equals, and an array inherits Object's",
                    at);
            }
            if (isBareCollection((DeclaredType) type)) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "'" + member.name() + "' is declared java.util.Collection, which specifies no "
                        + "equals contract at all - two collections holding the same elements are "
                        + "not required to compare equal. Declare it as List or Set",
                    at);
            }
            if (overridesNoEquals((DeclaredType) type)) {
                messager.printMessage(Diagnostic.Kind.NOTE,
                    "'" + member.name() + "' is a " + type + ", which declares no equals of its "
                        + "own, so comparing it reduces to reference identity",
                    at);
            }
        }
    }

    /** The first array found among a declared type's arguments, at any nesting. */
    private static TypeMirror arrayAmongTypeArguments(DeclaredType type) {
        for (TypeMirror argument : type.getTypeArguments()) {
            if (argument.getKind() == TypeKind.ARRAY) return argument;
            if (argument.getKind() == TypeKind.DECLARED) {
                TypeMirror nested = arrayAmongTypeArguments((DeclaredType) argument);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean isBareCollection(DeclaredType type) {
        Element element = type.asElement();
        return element instanceof TypeElement declared
            && COLLECTION_FQN.contentEquals(declared.getQualifiedName());
    }

    /**
     * Whether nothing between the declared type and {@code Object} supplies an
     * {@code equals}.
     *
     * <p>A record and an enum are treated as supplying one whether or not it is
     * written: both get theirs from the language rather than from a declaration
     * this walk can see.
     */
    private static boolean overridesNoEquals(DeclaredType type) {
        if (!(type.asElement() instanceof TypeElement start)) return false;
        for (TypeElement current = start; current != null; current = superclassOf(current)) {
            ElementKind kind = current.getKind();
            if (kind == ElementKind.RECORD || kind == ElementKind.ENUM) return false;
            // A type in this same round whose pair this pipeline is about to
            // inject declares nothing yet. Reading the element model alone
            // would report the very feature that makes the member comparable.
            if (current.getAnnotation(EqualsAndHashCode.class) != null) return false;
            if (OBJECT_FQN.contentEquals(current.getQualifiedName())) return true;
            for (Element enclosed : current.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) continue;
                if (!enclosed.getSimpleName().contentEquals("equals")) continue;
                if (((ExecutableElement) enclosed).getParameters().size() == 1) return false;
            }
        }
        // An interface reaches here with no superclass to walk; whether an
        // implementation supplies one is not decidable from the declared type.
        return start.getKind() != ElementKind.INTERFACE;
    }

    private static TypeElement superclassOf(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.DECLARED) return null;
        Element element = ((DeclaredType) superclass).asElement();
        return element instanceof TypeElement declared ? declared : null;
    }

}
