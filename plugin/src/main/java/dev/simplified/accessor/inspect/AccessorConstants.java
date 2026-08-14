package dev.simplified.accessor.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReferenceExpression;
import dev.simplified.annotations.NamingStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared FQNs and attribute readers for the {@code @Getter} / {@code @Setter}
 * PSI side, so the augment provider and the inspection read one annotation the
 * same way.
 */
public final class AccessorConstants {

    public static final String GETTER_FQN = "dev.simplified.annotations.Getter";
    public static final String SETTER_FQN = "dev.simplified.annotations.Setter";
    public static final String LAZY_FQN = "dev.simplified.annotations.Lazy";
    public static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";
    public static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";

    private AccessorConstants() {
    }

    /**
     * The access modifier keyword the annotation asks for.
     *
     * @param annotation the resolved {@code @Getter} or {@code @Setter}
     * @return the keyword, the empty string for package-private, or
     *         {@code null} for {@code AccessLevel.NONE} - which means generate
     *         nothing, and is why this is not simply a string
     */
    public static String accessKeyword(PsiAnnotation annotation) {
        String name = enumConstant(annotation, "value");
        if (name == null) return PsiModifier.PUBLIC;
        return switch (name) {
            case "PROTECTED" -> PsiModifier.PROTECTED;
            case "PRIVATE" -> PsiModifier.PRIVATE;
            case "PACKAGE" -> "";
            case "NONE" -> null;
            default -> PsiModifier.PUBLIC;
        };
    }

    /**
     * Whether the annotation generates a member at all.
     *
     * @param annotation the resolved {@code @Getter} or {@code @Setter}
     * @return {@code false} for {@code AccessLevel.NONE}, which is how one
     *         field opts out of a type-level fan-out
     */
    public static boolean generates(PsiAnnotation annotation) {
        return accessKeyword(annotation) != null;
    }

    /** The naming style the annotation asks for, defaulting to the annotation's own default. */
    public static NamingStyle style(PsiAnnotation annotation) {
        String name = enumConstant(annotation, "style");
        if (name == null) return NamingStyle.SIMPLIFIED;
        try {
            return NamingStyle.valueOf(name);
        } catch (IllegalArgumentException e) {
            // A style the user mistyped, or one from a newer library version
            // than the plugin. The default keeps autocompletion working rather
            // than dropping every accessor on the class.
            return NamingStyle.SIMPLIFIED;
        }
    }

    /** The written {@code name} pattern, or {@code null} when unwritten. */
    public static String name(PsiAnnotation annotation) {
        String written = stringAttr(annotation, "name");
        return written == null || written.isEmpty() ? null : written;
    }

    /**
     * The {@code @Getter} governing a field's read accessor.
     *
     * <p>A field-level annotation beats the enclosing type's outright, exclude
     * list included - a field that writes one has said what it wants.
     *
     * @param typeLevel the type's own {@code @Getter}, or {@code null}
     * @param field the field to resolve
     * @return the governing annotation, or {@code null} when no accessor is
     *         generated for the field at all
     */
    public static PsiAnnotation effectiveGetter(PsiAnnotation typeLevel, PsiField field) {
        PsiAnnotation fieldLevel = field.getAnnotation(GETTER_FQN);
        if (fieldLevel != null) return fieldLevel;
        if (typeLevel == null) return null;
        if (!reachedByTypeLevel(field)) return null;
        return excludes(typeLevel, field.getName()) ? null : typeLevel;
    }

    /**
     * Whether a type-level annotation fans out over the field.
     *
     * <p>A static field holds the class's own state rather than any instance's,
     * so a blanket request written across the class passes it by - otherwise
     * every constant in the class grows a public accessor nobody asked for.
     * Writing the annotation on the field is how a static accessor is asked
     * for, and it still mints one.
     *
     * @param field the field a type-level annotation would reach
     * @return whether the fan-out includes it
     */
    public static boolean reachedByTypeLevel(PsiField field) {
        return !field.hasModifierProperty(PsiModifier.STATIC);
    }

    /** Whether a type-level annotation excludes this field by name. */
    public static boolean excludes(PsiAnnotation annotation, String fieldName) {
        if (annotation == null || fieldName == null) return false;
        return stringArrayAttr(annotation, "exclude").contains(fieldName);
    }

    private static String enumConstant(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiReferenceExpression ref) return ref.getReferenceName();
        return null;
    }

    private static String stringAttr(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiLiteralExpression literal
            && literal.getValue() instanceof String s) return s;
        return null;
    }

    private static List<String> stringArrayAttr(PsiAnnotation annotation, String attribute) {
        List<String> out = new ArrayList<>();
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiLiteralExpression literal
            && literal.getValue() instanceof String s) {
            out.add(s);
        } else if (value instanceof PsiArrayInitializerMemberValue array) {
            for (PsiAnnotationMemberValue entry : array.getInitializers()) {
                if (entry instanceof PsiLiteralExpression literal
                    && literal.getValue() instanceof String s) out.add(s);
            }
        }
        return out;
    }

}
