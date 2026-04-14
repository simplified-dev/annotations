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
final class ClassBuilderConstants {

    static final @NotNull String ANNOTATION_FQN = "dev.sbs.annotation.ClassBuilder";
    static final @NotNull String ANNOTATION_SHORT_NAME = "ClassBuilder";
    static final @NotNull String XCONTRACT_FQN = "dev.sbs.annotation.XContract";

    static final @NotNull String ATTR_BUILDER_NAME = "builderName";
    static final @NotNull String ATTR_BUILDER_METHOD_NAME = "builderMethodName";
    static final @NotNull String ATTR_FROM_METHOD_NAME = "fromMethodName";
    static final @NotNull String ATTR_TO_BUILDER_METHOD_NAME = "toBuilderMethodName";
    static final @NotNull String ATTR_GENERATE_BUILDER = "generateBuilder";
    static final @NotNull String ATTR_GENERATE_FROM = "generateFrom";
    static final @NotNull String ATTR_GENERATE_MUTATE = "generateMutate";
    static final @NotNull String ATTR_ACCESS = "access";

    static final @NotNull String DEFAULT_BUILDER_NAME = "Builder";
    static final @NotNull String DEFAULT_BUILDER_METHOD = "builder";
    static final @NotNull String DEFAULT_FROM_METHOD = "from";
    static final @NotNull String DEFAULT_TO_BUILDER_METHOD = "mutate";

    private ClassBuilderConstants() {}

    static @NotNull String stringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, @NotNull String fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s && !s.isEmpty()) return s;
        return fallback;
    }

    static boolean booleanAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, boolean fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof Boolean b) return b;
        return fallback;
    }

    /**
     * Reads a {@code AccessLevel.X} enum reference from an annotation attribute, or returns the fallback.
     */
    static @NotNull String accessKeyword(@Nullable PsiAnnotation annotation) {
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
