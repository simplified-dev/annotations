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

    /**
     * Name of the generated array holding {@code values()} once.
     *
     * <p>Here rather than privately on each reader so the augment provider that
     * synthesises it and the inspection that refuses a clash with it cannot
     * disagree about its spelling. The processor holds its own copy across the
     * module boundary.
     */
    public static final @NotNull String CACHED_VALUES = "CACHED_VALUES";

    /** Prefix of the generated per-{@code @KeyField} parallel key array. */
    public static final @NotNull String CACHED_KEYS_PREFIX = "CACHED_KEYS_";
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
