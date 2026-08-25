package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.BuildFlag;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the {@code @BuildFlag} constraints a target carries, so the
 * generated {@code build()} can enforce them without reading annotations at
 * runtime.
 *
 * <p>The walk climbs the superclass chain, because a constraint written on an
 * unannotated parent is still a constraint on the child being built, and
 * stopping at the declared type would turn an inherited requirement into an
 * unenforced one.
 */
public final class BuildFlags {

    private BuildFlags() {}

    /**
     * Collects every enforceable constraint on a target, in declaration order.
     *
     * <p>An interface target carries its flags on the accessors it declares -
     * there are no fields to write them on - so those are read instead, and the
     * generated check calls the accessor rather than reading a field.
     *
     * @param target the annotated type
     * @return the constrained members, empty when there are none
     */
    public static List<FlaggedMember> of(TypeElement target) {
        return target.getKind() == ElementKind.INTERFACE
            ? fromAccessors(target)
            : fromFields(target);
    }

    private static List<FlaggedMember> fromAccessors(TypeElement target) {
        List<FlaggedMember> out = new ArrayList<>();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (!method.getParameters().isEmpty()) continue;
            BuildFlag flag = method.getAnnotation(BuildFlag.class);
            if (flag == null || !FlaggedMember.enforceable(flag)) continue;
            out.add(new FlaggedMember(
                method.getSimpleName().toString(),
                true,
                method.getReturnType().getKind().isPrimitive(),
                flag
            ));
        }
        return out;
    }

    /**
     * Walks the target and its superclasses.
     *
     * <p>A private field on a <em>parent</em> is skipped: the builder is nested
     * in the target, so the target's own private fields are reachable but a
     * parent's are not, and emitting a read of one would fail on a line the
     * author never wrote. Names already seen win, so a child field shadowing a
     * parent's is checked once, against the declaration the constructed object
     * actually holds.
     */
    private static List<FlaggedMember> fromFields(TypeElement target) {
        List<FlaggedMember> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TypeElement type = target; type != null; type = superclassOf(type)) {
            boolean inherited = type != target;
            for (Element enclosed : type.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) continue;
                if (enclosed.getModifiers().contains(Modifier.STATIC)) continue;
                String name = enclosed.getSimpleName().toString();
                if (!seen.add(name)) continue;
                BuildFlag flag = enclosed.getAnnotation(BuildFlag.class);
                if (flag == null || !FlaggedMember.enforceable(flag)) continue;
                if (inherited && enclosed.getModifiers().contains(Modifier.PRIVATE)) continue;
                out.add(new FlaggedMember(
                    name,
                    false,
                    enclosed.asType().getKind().isPrimitive(),
                    flag
                ));
            }
        }
        return out;
    }

    private static TypeElement superclassOf(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass == null || superclass.getKind() != TypeKind.DECLARED) return null;
        Element element = ((DeclaredType) superclass).asElement();
        if (!(element instanceof TypeElement parent)) return null;
        return "java.lang.Object".contentEquals(parent.getQualifiedName()) ? null : parent;
    }

}
