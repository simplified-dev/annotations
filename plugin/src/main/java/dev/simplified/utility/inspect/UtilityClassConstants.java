package dev.simplified.utility.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Shared FQNs, attribute readers and member views for the {@code @UtilityClass}
 * PSI side.
 *
 * <p>Every attribute is read as <b>written</b> rather than as resolved: an
 * unwritten {@code members} is the {@code REQUIRE_STATIC} default, and reading
 * the declared value keeps the inspection off the annotation type's own
 * defaults. The enum constant is matched on its trailing reference name, so
 * {@code MAKE_STATIC}, {@code Members.MAKE_STATIC} and
 * {@code UtilityClass.Members.MAKE_STATIC} all read the same.
 */
public final class UtilityClassConstants {

    public static final @NotNull String UTILITY_CLASS_FQN =
        "dev.simplified.annotations.UtilityClass";
    public static final @NotNull String UTILITY_CLASS_SHORT_NAME = "UtilityClass";
    public static final @NotNull String CLASS_BUILDER_FQN =
        "dev.simplified.annotations.ClassBuilder";

    public static final @NotNull String ATTR_MAKE_FINAL = "makeFinal";
    public static final @NotNull String ATTR_MEMBERS = "members";
    public static final @NotNull String ATTR_NESTED_TYPES = "nestedTypes";
    public static final @NotNull String ATTR_CONSTRUCTOR_ACCESS = "constructorAccess";

    private UtilityClassConstants() {
    }

    /**
     * Finds an annotation written on a type.
     *
     * @param target the type to read
     * @param fqn the annotation's fully-qualified name
     * @return the annotation, or {@code null} when it is not written
     */
    public static @Nullable PsiAnnotation find(@NotNull PsiClass target, @NotNull String fqn) {
        for (PsiAnnotation annotation : target.getAnnotations()) {
            if (fqn.equals(annotation.getQualifiedName())) return annotation;
        }
        return null;
    }

    /**
     * Whether {@code members = MAKE_STATIC} is written, which is the opt-in that
     * turns every instance member static instead of reporting it.
     *
     * @param annotation the {@code @UtilityClass} annotation
     * @return whether the rewriting policy was asked for
     */
    public static boolean makeStatic(@NotNull PsiAnnotation annotation) {
        return "MAKE_STATIC".equals(enumConstant(annotation, ATTR_MEMBERS));
    }

    /**
     * Whether {@code constructorAccess = NONE} is written, which asks for a
     * class with no constructor at all.
     *
     * @param annotation the {@code @UtilityClass} annotation
     * @return whether the unexpressible access level was asked for
     */
    public static boolean constructorAccessIsNone(@NotNull PsiAnnotation annotation) {
        return "NONE".equals(enumConstant(annotation, ATTR_CONSTRUCTOR_ACCESS));
    }

    /**
     * The value written for an attribute, which is where a problem about that
     * attribute is registered.
     *
     * @param annotation the annotation to read
     * @param attribute the attribute name
     * @return the written value, or {@code null} when the attribute is defaulted
     */
    public static @Nullable PsiAnnotationMemberValue written(@NotNull PsiAnnotation annotation,
                                                             @NotNull String attribute) {
        return annotation.findDeclaredAttributeValue(attribute);
    }

    /**
     * Fields the class itself declares.
     *
     * <p>{@code getOwnFields()} rather than {@code getFields()}: the latter is
     * augment-aware and re-enters every provider registered for the class.
     *
     * @param target the class to read
     * @return the declared fields, in declaration order
     */
    public static @NotNull List<PsiField> ownFields(@NotNull PsiClass target) {
        return target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
    }

    /**
     * Methods and constructors the class itself declares.
     *
     * @param target the class to read
     * @return the declared methods, in declaration order
     */
    public static @NotNull List<PsiMethod> ownMethods(@NotNull PsiClass target) {
        return target instanceof PsiExtensibleClass ext
            ? ext.getOwnMethods()
            : List.of(target.getMethods());
    }

    private static @Nullable String enumConstant(@NotNull PsiAnnotation annotation,
                                                 @NotNull String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiReferenceExpression reference) return reference.getReferenceName();
        return null;
    }

}
