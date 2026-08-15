package dev.simplified.classbuilder.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiReferenceExpression;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.BuilderScheme;
import dev.simplified.classbuilder.apt.SetterScheme;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Shared FQNs and attribute-reading helpers for the {@code @ClassBuilder}
 * IDE support. Mirrors the layout of {@code ResourcePathConstants}.
 *
 * <p>Every reader here asks for the <b>declared</b> attribute value and supplies
 * the annotation's own default itself, which is what the {@code fallback}
 * argument on each of them is. That is not a shortcut: reading the value the
 * platform fills in resolves the annotation type, and a resolve started from an
 * annotation written inside a class body walks that class's nested types, which
 * is augment-aware and re-enters the provider that asked. The defaults are
 * stated once at each call site instead.
 */
public final class ClassBuilderConstants {

    public static final @NotNull String ANNOTATION_FQN = "dev.simplified.annotations.ClassBuilder";
    public static final @NotNull String ANNOTATION_SHORT_NAME = "ClassBuilder";
    public static final @NotNull String XCONTRACT_FQN = "dev.simplified.annotations.XContract";

    public static final @NotNull String BUILDER_DEFAULT_FQN = "dev.simplified.annotations.BuilderDefault";
    public static final @NotNull String BUILDER_IGNORE_FQN = "dev.simplified.annotations.BuilderIgnore";
    public static final @NotNull String BUILDER_SEED_FQN = "dev.simplified.annotations.BuilderSeed";
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
        BUILDER_DEFAULT_FQN,
        BUILDER_IGNORE_FQN,
        BUILDER_SEED_FQN,
        BUILD_FLAG_FQN,
        OBTAIN_VIA_FQN,
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
        "BuilderDefault",
        "BuilderIgnore",
        "BuilderSeed",
        "BuildFlag",
        "ObtainVia",
        "Collector",
        "Negate",
        "Formattable",
        "Lazy"
    );

    public static final @NotNull String ATTR_STYLE = "style";
    public static final @NotNull String ATTR_SETTERS = "setters";
    public static final @NotNull String ATTR_BUILDER = "builder";
    public static final @NotNull String ATTR_EMIT_CONTRACTS = "emitContracts";
    public static final @NotNull String ATTR_ACCESS = "access";
    public static final @NotNull String ATTR_CONSTRUCTOR_ACCESS = "constructorAccess";
    public static final @NotNull String ATTR_BUILDER_CONSTRUCTOR_ACCESS = "builderConstructorAccess";
    public static final @NotNull String ATTR_FACTORY_METHOD = "factoryMethod";

    /** Attribute names of {@code @SetterNames}, in declaration order. */
    public static final @NotNull String[] SETTER_ROLES = {"set", "flag", "add", "put", "compute", "clear"};

    /** Attribute names of {@code @BuilderNames}, in declaration order. */
    public static final @NotNull String[] BUILDER_ROLES = {"type", "builder", "build", "from", "toBuilder"};

    private ClassBuilderConstants() {}

    public static @NotNull String stringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, @NotNull String fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s && !s.isEmpty()) return s;
        return fallback;
    }

    /**
     * Reads a string attribute only when it is written at the annotation,
     * mirroring the processor's {@code getElementValues()} view rather than
     * {@code findAttributeValue}'s defaults-included one. Returning
     * {@code null} for an unwritten attribute is what lets the schemes tell
     * "inherit from the style" from an explicit value, empty ones included.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param attr the attribute name
     * @return the written value, or {@code null}
     */
    public static @Nullable String writtenStringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr) {
        if (annotation == null) return null;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s) return s;
        return null;
    }

    /** Reads the {@code style} attribute, defaulting to {@link NamingStyle#SIMPLIFIED}. */
    public static @NotNull NamingStyle namingStyle(@Nullable PsiAnnotation annotation) {
        if (annotation == null) return NamingStyle.SIMPLIFIED;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(ATTR_STYLE);
        if (value instanceof PsiReferenceExpression ref) {
            String name = ref.getReferenceName();
            if (name != null) {
                try {
                    return NamingStyle.valueOf(name);
                } catch (IllegalArgumentException ignored) {
                    // Unresolvable or mid-typing reference - fall through.
                }
            }
        }
        return NamingStyle.SIMPLIFIED;
    }

    /**
     * Resolves the per-field setter patterns from the nested {@code setters}
     * attribute over the given style. An unwritten attribute leaves every role
     * inheriting.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param style the style supplying every unwritten role
     * @return the resolved scheme
     */
    public static @NotNull SetterScheme setterScheme(@Nullable PsiAnnotation annotation,
                                                     @NotNull NamingStyle style) {
        PsiAnnotation setters = nestedAnnotation(annotation, ATTR_SETTERS);
        if (setters == null) return SetterScheme.of(style);
        return SetterScheme.resolve(style,
            writtenStringAttr(setters, "set"),
            writtenStringAttr(setters, "flag"),
            writtenStringAttr(setters, "add"),
            writtenStringAttr(setters, "put"),
            writtenStringAttr(setters, "compute"),
            writtenStringAttr(setters, "clear"));
    }

    /**
     * Resolves the once-per-target names from the nested {@code builder}
     * attribute over the given style.
     *
     * @param annotation the annotation to read, or {@code null}
     * @param style the style supplying every unwritten name
     * @param targetSimpleName simple name of the annotated type
     * @return the resolved scheme
     */
    public static @NotNull BuilderScheme builderScheme(@Nullable PsiAnnotation annotation,
                                                       @NotNull NamingStyle style,
                                                       @NotNull String targetSimpleName) {
        PsiAnnotation names = nestedAnnotation(annotation, ATTR_BUILDER);
        if (names == null) return BuilderScheme.of(style, targetSimpleName);
        return BuilderScheme.resolve(style, targetSimpleName,
            writtenStringAttr(names, "type"),
            writtenStringAttr(names, "builder"),
            writtenStringAttr(names, "build"),
            writtenStringAttr(names, "from"),
            writtenStringAttr(names, "toBuilder"));
    }

    /**
     * Reads a written nested-annotation attribute.
     *
     * @param annotation the enclosing annotation, or {@code null}
     * @param attr the attribute name
     * @return the nested annotation, or {@code null} when it is not written
     */
    public static @Nullable PsiAnnotation nestedAnnotation(@Nullable PsiAnnotation annotation,
                                                           @NotNull String attr) {
        if (annotation == null) return null;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        return value instanceof PsiAnnotation nested ? nested : null;
    }

    public static boolean booleanAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, boolean fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
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
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
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
