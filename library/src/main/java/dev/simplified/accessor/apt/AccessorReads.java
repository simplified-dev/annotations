package dev.simplified.accessor.apt;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.AccessorScheme;
import dev.simplified.shared.apt.MemberSpec;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Resolves how one member is read when an annotation asks to go through an
 * accessor rather than to touch the field.
 *
 * <p>Deliberately its own resolver rather than a reuse of the builder's
 * {@code from(T)} seeding ladder. That ladder matches on <b>name only</b>,
 * with no return-type and no owner check, and unions the inherited members -
 * which is right for seeding a builder, where a wrong guess produces a compile
 * error at the assignment. Here a wrong guess produces a member of the wrong
 * type silently dispatched down the wrong emission row, and the first symptom
 * is a hash table losing entries.
 *
 * <p>So the accepted accessor must be declared by the target itself, take no
 * parameters, and return something assignable to the member's own type.
 * Anything else falls back to the direct read, and the resolved type is what
 * the emission row is chosen from.
 */
public final class AccessorReads {

    private AccessorReads() {}

    /**
     * How a member is read, and the type that read produces.
     *
     * @param name the field or accessor name to select off a receiver
     * @param method whether the read is a zero-arg call
     * @param type the type of the read expression
     */
    public record Read(String name, boolean method, TypeMirror type) {
    }

    /**
     * Resolves the read for one member.
     *
     * @param target the annotated type
     * @param member the selected member
     * @param useAccessors whether the annotation asked for the accessor route
     * @param typeUtils the model's type operations
     * @param label the asking annotation's spelling, for the fallback note
     * @param messager sink for the note recording a fallback
     * @return the resolved read
     */
    public static Read resolve(TypeElement target, MemberSpec member, boolean useAccessors,
                               Types typeUtils, String label, Messager messager) {
        Read direct = new Read(member.name(), member.method(), member.type());
        if (!useAccessors || member.method()) return direct;

        Element element = member.element();
        Getter onField = element == null ? null : element.getAnnotation(Getter.class);
        if (onField != null && onField.value() == AccessLevel.NONE) return direct;

        NamingStyle style = onField != null ? onField.style() : styleOf(target);
        String written = onField != null ? onField.name() : nameOf(target);
        AccessorScheme scheme = AccessorScheme.resolve(style, written);
        boolean isBoolean = member.type().getKind() == TypeKind.BOOLEAN;

        for (String candidate : scheme.readCandidates(member.name(), isBoolean)) {
            ExecutableElement accessor = declaredReader(target, candidate, member.type(), typeUtils);
            if (accessor != null) return new Read(candidate, true, accessor.getReturnType());
        }

        messager.printMessage(Diagnostic.Kind.NOTE,
            label + "(useAccessors) found no declared accessor for '" + member.name()
                + "' returning its own type - reading the field directly",
            element != null ? element : target);
        return direct;
    }

    private static NamingStyle styleOf(TypeElement target) {
        Getter onType = target.getAnnotation(Getter.class);
        return onType != null ? onType.style() : NamingStyle.SIMPLIFIED;
    }

    private static String nameOf(TypeElement target) {
        Getter onType = target.getAnnotation(Getter.class);
        return onType != null ? onType.name() : null;
    }

    private static ExecutableElement declaredReader(TypeElement target, String name,
                                                    TypeMirror memberType, Types typeUtils) {
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            if (!enclosed.getSimpleName().contentEquals(name)) continue;
            if (enclosed.getModifiers().contains(Modifier.STATIC)) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (!method.getParameters().isEmpty()) continue;
            TypeMirror returned = method.getReturnType();
            if (returned.getKind() == TypeKind.VOID) continue;
            if (typeUtils.isAssignable(returned, memberType)) return method;
        }
        return null;
    }

}
