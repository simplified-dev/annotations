package dev.simplified.enumlookup.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * FQNs and attribute-reading helpers for the {@code @EnumLookup} /
 * {@code @KeyField} IDE support. Mirrors the layout of
 * {@code ClassBuilderConstants} so the two annotation families stay
 * cleanly separated.
 */
public final class EnumLookupConstants {

    public static final @NotNull String ENUM_LOOKUP_FQN = "dev.simplified.annotations.EnumLookup";
    public static final @NotNull String ENUM_LOOKUP_SHORT_NAME = "EnumLookup";
    public static final @NotNull String KEY_FIELD_FQN = "dev.simplified.annotations.KeyField";
    public static final @NotNull String KEY_FIELD_SHORT_NAME = "KeyField";

    public static final @NotNull String ATTR_METHOD_NAME = "methodName";
    public static final @NotNull String ATTR_STRICT_KEYS = "strictKeys";
    public static final @NotNull String ATTR_STRICT_NULL_KEYS = "strictNullKeys";

    private EnumLookupConstants() {}

    public static @NotNull String stringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, @NotNull String fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s && !s.isEmpty()) return s;
        return fallback;
    }

    public static boolean booleanAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, boolean fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof Boolean b) return b;
        return fallback;
    }
}
