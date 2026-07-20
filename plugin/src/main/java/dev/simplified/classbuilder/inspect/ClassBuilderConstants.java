package dev.simplified.classbuilder.inspect;

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

    public static final @NotNull String ANNOTATION_FQN = "dev.simplified.annotations.ClassBuilder";
    public static final @NotNull String ANNOTATION_SHORT_NAME = "ClassBuilder";
    public static final @NotNull String XCONTRACT_FQN = "dev.simplified.annotations.XContract";

    public static final @NotNull String BUILD_RULE_FQN = "dev.simplified.annotations.BuildRule";
    public static final @NotNull String BUILD_RULE_SHORT_NAME = "BuildRule";
    public static final @NotNull String BUILD_FLAG_FQN = "dev.simplified.annotations.BuildFlag";
    public static final @NotNull String OBTAIN_VIA_FQN = "dev.simplified.annotations.ObtainVia";
    public static final @NotNull String COLLECTOR_FQN = "dev.simplified.annotations.Collector";
    public static final @NotNull String NEGATE_FQN = "dev.simplified.annotations.Negate";
    public static final @NotNull String FORMATTABLE_FQN = "dev.simplified.annotations.Formattable";
    public static final @NotNull String LAZY_FQN = "dev.simplified.annotations.Lazy";

    /**
     * FQNs of every annotation whose PSI changes should invalidate the editor-
     * side synthesis. Consumed by {@code ClassBuilderChangeService} to decide
     * whether a tree-change event warrants a {@code DaemonCodeAnalyzer.restart()}.
     */
    public static final @NotNull Set<String> TRACKED_ANNOTATION_FQNS = Set.of(
        ANNOTATION_FQN,
        BUILD_RULE_FQN,
        COLLECTOR_FQN,
        NEGATE_FQN,
        FORMATTABLE_FQN,
        LAZY_FQN
    );

    /**
     * Short-name fallback for the tracked set - used when the PSI is in dumb
     * mode and {@link PsiAnnotation#getQualifiedName()} returns the unqualified
     * name. A short-name false positive here just triggers a harmless extra
     * daemon restart.
     */
    public static final @NotNull Set<String> TRACKED_ANNOTATION_SHORT_NAMES = Set.of(
        ANNOTATION_SHORT_NAME,
        BUILD_RULE_SHORT_NAME,
        "Collector",
        "Negate",
        "Formattable",
        "Lazy"
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
    public static final @NotNull String ATTR_CONSTRUCTOR_ACCESS = "constructorAccess";
    public static final @NotNull String ATTR_FACTORY_METHOD = "factoryMethod";

    public static final @NotNull String DEFAULT_BUILDER_NAME = "Builder";
    public static final @NotNull String DEFAULT_BUILDER_METHOD = "builder";
    public static final @NotNull String DEFAULT_BUILD_METHOD = "build";
    public static final @NotNull String DEFAULT_FROM_METHOD = "from";
    public static final @NotNull String DEFAULT_TO_BUILDER_METHOD = "mutate";
    public static final @NotNull String DEFAULT_METHOD_PREFIX = "";

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
     * Reads the {@code access} attribute as a Java modifier keyword, defaulting
     * to {@code public}.
     */
    public static @NotNull String accessKeyword(@Nullable PsiAnnotation annotation) {
        return accessKeyword(annotation, ATTR_ACCESS, "public");
    }

    /**
     * Reads an {@code AccessLevel.X} enum reference from an annotation attribute
     * as a Java modifier keyword. {@code PACKAGE} maps to the empty string,
     * which is how package-private is spelled in source.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param attr the attribute name holding the {@code AccessLevel}
     * @param fallback keyword to return when the attribute is absent or unreadable
     * @return the modifier keyword, or the empty string for package-private
     */
    public static @NotNull String accessKeyword(@Nullable PsiAnnotation annotation,
                                                @NotNull String attr,
                                                @NotNull String fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attr);
        if (value instanceof PsiReferenceExpression ref) {
            String name = ref.getReferenceName();
            if (name != null) {
                return switch (name) {
                    case "PUBLIC" -> "public";
                    case "PROTECTED" -> "protected";
                    case "PACKAGE" -> "";
                    case "PRIVATE" -> "private";
                    default -> fallback;
                };
            }
        }
        return fallback;
    }

}
