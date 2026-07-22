package dev.simplified.accessor.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
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
