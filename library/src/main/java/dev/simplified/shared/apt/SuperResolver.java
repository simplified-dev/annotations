package dev.simplified.shared.apt;

import dev.simplified.annotations.CallSuper;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Resolves {@link CallSuper} against what a target's superclass actually
 * provides, and reports the supertype collisions that make a generated override
 * impossible.
 *
 * <p>{@link CallSuper#AUTO} is resolved from the <b>annotation mirror first</b>
 * and only then from the superclass's declared members. Deciding it by scanning
 * members of types in the same round would be order-dependent: the round's
 * annotated elements arrive as an unordered set, so a superclass processed
 * after its subclass has not been mutated yet and the answer changes between
 * builds with nothing to see.
 *
 * <p>The member scan requires <b>every</b> named member, not any of them. A
 * superclass overriding {@code equals} without {@code hashCode} yields value
 * equality over an identity hash, which is a contract break by construction, so
 * finding exactly one is an error rather than a guess in either direction.
 */
public final class SuperResolver {

    private static final String OBJECT_FQN = "java.lang.Object";
    private static final String RECORD_FQN = "java.lang.Record";

    /**
     * A member the resolution asks the superclass about.
     *
     * @param name the member's name
     * @param arity how many parameters it takes
     */
    public record Member(String name, int arity, String parameterType) {

        /** {@code equals(Object)}. */
        public static final Member EQUALS = new Member("equals", 1, OBJECT_FQN);

        /** {@code hashCode()}. */
        public static final Member HASH_CODE = new Member("hashCode", 0, null);

        /** {@code toString()}. */
        public static final Member TO_STRING = new Member("toString", 0, null);

    }

    private SuperResolver() {}

    /**
     * Resolves whether the generated members call the superclass's own.
     *
     * @param target the annotated type
     * @param written the attribute as the author wrote it
     * @param annotationFqn this annotation, looked for on the superclass
     * @param label the annotation's own spelling, for diagnostics
     * @param members every member the superclass must supply for a positive answer
     * @param lookup the mirror attribute reader
     * @param messager sink for the resolution note and for a rejected request
     * @return whether to emit the super call
     */
    public static boolean resolve(TypeElement target, CallSuper written, String annotationFqn,
                                  String label, Member[] members, AnnotationLookup lookup,
                                  Messager messager) {
        TypeElement superElement = superclassOf(target);
        boolean rootLike = superElement == null;

        if (written == CallSuper.NO) return false;
        if (written == CallSuper.YES) {
            if (rootLike) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    label + "(callSuper = YES) on " + target.getSimpleName() + ", whose superclass "
                        + "supplies no implementation to call - the inherited one is identity-based "
                        + "and would defeat the generated member",
                    target);
                return false;
            }
            return true;
        }

        // Silent on a direct subclass of Object. The note exists because AUTO
        // can flip from NO to YES when a superclass in another artifact gains
        // the annotation, with no edit to this source - and a type with no
        // superclass to gain it can never flip, so reporting there would be a
        // message on nearly every annotated type in a codebase.
        if (rootLike) return false;
        if (lookup.hasAnnotation(superElement, annotationFqn)) {
            note(messager, target, label + " resolved callSuper = YES - "
                + superElement.getSimpleName() + " carries " + label);
            return true;
        }

        int found = 0;
        for (Member member : members) {
            if (declaresConcrete(superElement, member)) found++;
        }
        if (found == members.length) {
            note(messager, target, label + " resolved callSuper = YES - "
                + superElement.getSimpleName() + " declares its own implementation");
            return true;
        }
        if (found == 0) {
            note(messager, target, label + " resolved callSuper = NO - "
                + superElement.getSimpleName() + " declares no implementation of its own");
            return false;
        }
        messager.printMessage(Diagnostic.Kind.ERROR,
            label + " cannot resolve callSuper on " + target.getSimpleName() + " - "
                + superElement.getSimpleName() + " declares some of the pair and not the rest, "
                + "which is already inconsistent. Write callSuper explicitly to say which "
                + "behaviour you want",
            target);
        return false;
    }

    /**
     * The nearest supertype declaring {@code member} as {@code final}, or null.
     *
     * <p>A generated override of one cannot compile, and the diagnostic should
     * name the supertype rather than leaving javac pointing at a member the
     * author cannot see.
     *
     * @param target the annotated type
     * @param member the member about to be generated
     * @return the offending supertype, or null when there is none
     */
    public static TypeElement finalSupertypeMember(TypeElement target, Member member) {
        for (TypeElement current = superclassOf(target);
             current != null;
             current = superclassOf(current)) {
            for (Element enclosed : current.getEnclosedElements()) {
                if (!matches(enclosed, member)) continue;
                if (enclosed.getModifiers().contains(Modifier.FINAL)) return current;
            }
        }
        return null;
    }

    /**
     * The direct superclass, or null when there is none worth calling.
     *
     * <p>{@code java.lang.Record} counts as none: it declares the equality pair
     * and {@code toString} abstract, so a {@code super} call to any of them is
     * rejected outright.
     *
     * @param type the type to walk up from
     * @return the superclass a generated member could call, or null
     */
    public static TypeElement callableSuperclass(TypeElement type) {
        return superclassOf(type);
    }

    private static TypeElement superclassOf(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.DECLARED) return null;
        Element element = ((DeclaredType) superclass).asElement();
        if (!(element instanceof TypeElement superElement)) return null;
        String fqn = superElement.getQualifiedName().toString();
        if (OBJECT_FQN.equals(fqn) || RECORD_FQN.equals(fqn)) return null;
        return superElement;
    }

    private static boolean declaresConcrete(TypeElement start, Member member) {
        for (TypeElement current = start; current != null; current = superclassOf(current)) {
            for (Element enclosed : current.getEnclosedElements()) {
                if (!matches(enclosed, member)) continue;
                return !enclosed.getModifiers().contains(Modifier.ABSTRACT);
            }
        }
        return false;
    }

    /**
     * Whether an element is the named member rather than an overload of it.
     *
     * <p>The parameter type is checked, not only the count. A supertype
     * declaring {@code equals(Sup)} - a typed convenience method that overrides
     * nothing - would otherwise be read as supplying half the pair, and the
     * subclass's {@code callSuper = AUTO} would fail the build with a message
     * about an inconsistency the author never wrote.
     *
     * <p>That check reads the parameter's name through {@link TypeNames} rather
     * than off {@code asType().toString()}, which renders a type annotation
     * inline. A supertype declaring {@code equals(@NotNull Object)} - the way an
     * annotated codebase writes it - otherwise matched nothing, and the two
     * callers failed in opposite directions: {@code AUTO} reported the
     * superclass as declaring half the pair when it declares both, and a
     * {@code final} one went undetected, so the generated override reached javac
     * as "overridden method is final" on a line the author never wrote.
     */
    private static boolean matches(Element element, Member member) {
        if (element.getKind() != ElementKind.METHOD) return false;
        if (!element.getSimpleName().contentEquals(member.name())) return false;
        ExecutableElement method = (ExecutableElement) element;
        if (method.getParameters().size() != member.arity()) return false;
        return member.parameterType() == null
            || TypeNames.is(method.getParameters().get(0).asType(), member.parameterType());
    }

    private static void note(Messager messager, TypeElement target, String message) {
        messager.printMessage(Diagnostic.Kind.NOTE, message, target);
    }

}
