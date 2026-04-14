package dev.sbs.classbuilder.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shared FQNs and attribute-reading helpers for the {@code @ClassBuilder}
 * IDE support. Mirrors the layout of {@code ResourcePathConstants}.
 */
public final class ClassBuilderConstants {

    public static final @NotNull String ANNOTATION_FQN = "dev.sbs.annotation.ClassBuilder";
    public static final @NotNull String ANNOTATION_SHORT_NAME = "ClassBuilder";
    public static final @NotNull String XCONTRACT_FQN = "dev.sbs.annotation.XContract";

    public static final @NotNull String ATTR_BUILDER_NAME = "builderName";
    public static final @NotNull String ATTR_BUILDER_METHOD_NAME = "builderMethodName";
    public static final @NotNull String ATTR_FROM_METHOD_NAME = "fromMethodName";
    public static final @NotNull String ATTR_TO_BUILDER_METHOD_NAME = "toBuilderMethodName";
    public static final @NotNull String ATTR_METHOD_PREFIX = "methodPrefix";
    public static final @NotNull String ATTR_GENERATE_BUILDER = "generateBuilder";
    public static final @NotNull String ATTR_GENERATE_FROM = "generateFrom";
    public static final @NotNull String ATTR_GENERATE_MUTATE = "generateMutate";
    public static final @NotNull String ATTR_ACCESS = "access";

    public static final @NotNull String DEFAULT_BUILDER_NAME = "Builder";
    public static final @NotNull String DEFAULT_BUILDER_METHOD = "builder";
    public static final @NotNull String DEFAULT_FROM_METHOD = "from";
    public static final @NotNull String DEFAULT_TO_BUILDER_METHOD = "mutate";
    public static final @NotNull String DEFAULT_METHOD_PREFIX = "with";

    private ClassBuilderConstants() {}

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

    /**
     * Reads a {@code AccessLevel.X} enum reference from an annotation attribute, or returns the fallback.
     */
    public static @NotNull String accessKeyword(@Nullable PsiAnnotation annotation) {
        if (annotation == null) return "public";
        PsiAnnotationMemberValue value = annotation.findAttributeValue(ATTR_ACCESS);
        if (value instanceof PsiReferenceExpression ref) {
            String name = ref.getReferenceName();
            if (name != null) {
                return switch (name) {
                    case "PROTECTED" -> "protected";
                    case "PACKAGE" -> "";
                    case "PRIVATE" -> "private";
                    default -> "public";
                };
            }
        }
        return "public";
    }

}
