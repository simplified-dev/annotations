package dev.sbs.classbuilder.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Shared FQNs and attribute-reading helpers for the {@code @ClassBuilder}
 * IDE support. Mirrors the layout of {@code ResourcePathConstants}.
 */
public final class ClassBuilderConstants {

    public static final @NotNull String ANNOTATION_FQN = "dev.sbs.annotation.ClassBuilder";
    public static final @NotNull String ANNOTATION_SHORT_NAME = "ClassBuilder";
    public static final @NotNull String XCONTRACT_FQN = "dev.sbs.annotation.XContract";

    public static final @NotNull String BUILD_FLAG_FQN = "dev.sbs.annotation.BuildFlag";
    public static final @NotNull String BUILD_FLAG_SHORT_NAME = "BuildFlag";
    public static final @NotNull String SINGULAR_FQN = "dev.sbs.annotation.Singular";
    public static final @NotNull String NEGATE_FQN = "dev.sbs.annotation.Negate";
    public static final @NotNull String FORMATTABLE_FQN = "dev.sbs.annotation.Formattable";
    public static final @NotNull String BUILDER_DEFAULT_FQN = "dev.sbs.annotation.BuilderDefault";
    public static final @NotNull String BUILDER_IGNORE_FQN = "dev.sbs.annotation.BuilderIgnore";
    public static final @NotNull String OBTAIN_VIA_FQN = "dev.sbs.annotation.ObtainVia";

    /**
     * FQNs of every annotation whose PSI changes should invalidate the editor-
     * side synthesis. Consumed by {@code ClassBuilderChangeService} to decide
     * whether a tree-change event warrants a {@code DaemonCodeAnalyzer.restart()}.
     */
    public static final @NotNull Set<String> TRACKED_ANNOTATION_FQNS = Set.of(
        ANNOTATION_FQN,
        BUILD_FLAG_FQN,
        SINGULAR_FQN,
        NEGATE_FQN,
        FORMATTABLE_FQN,
        BUILDER_DEFAULT_FQN,
        BUILDER_IGNORE_FQN,
        OBTAIN_VIA_FQN
    );

    /**
     * Short-name fallback for the tracked set - used when the PSI is in dumb
     * mode and {@link PsiAnnotation#getQualifiedName()} returns the unqualified
     * name. A short-name false positive here just triggers a harmless extra
     * daemon restart.
     */
    public static final @NotNull Set<String> TRACKED_ANNOTATION_SHORT_NAMES = Set.of(
        ANNOTATION_SHORT_NAME,
        BUILD_FLAG_SHORT_NAME,
        "Singular",
        "Negate",
        "Formattable",
        "BuilderDefault",
        "BuilderIgnore",
        "ObtainVia"
    );

    public static final @NotNull String ATTR_BUILDER_NAME = "builderName";
    public static final @NotNull String ATTR_BUILDER_METHOD_NAME = "builderMethodName";
    public static final @NotNull String ATTR_BUILD_METHOD_NAME = "buildMethodName";
    public static final @NotNull String ATTR_FROM_METHOD_NAME = "fromMethodName";
    public static final @NotNull String ATTR_TO_BUILDER_METHOD_NAME = "toBuilderMethodName";
    public static final @NotNull String ATTR_METHOD_PREFIX = "methodPrefix";
    public static final @NotNull String ATTR_GENERATE_BUILDER = "generateBuilder";
    public static final @NotNull String ATTR_GENERATE_FROM = "generateFrom";
    public static final @NotNull String ATTR_GENERATE_MUTATE = "generateMutate";
    public static final @NotNull String ATTR_EMIT_CONTRACTS = "emitContracts";
    public static final @NotNull String ATTR_ACCESS = "access";

    public static final @NotNull String DEFAULT_BUILDER_NAME = "Builder";
    public static final @NotNull String DEFAULT_BUILDER_METHOD = "builder";
    public static final @NotNull String DEFAULT_BUILD_METHOD = "build";
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
