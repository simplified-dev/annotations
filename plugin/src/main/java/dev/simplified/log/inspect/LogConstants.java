package dev.simplified.log.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import dev.simplified.classbuilder.apt.NamePattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * FQNs and attribute-reading helpers for the {@code @Log} IDE support. Mirrors
 * the layout of {@code EnumLookupConstants} so each annotation family keeps its
 * own constants.
 *
 * <p>Field-name resolution routes through {@link NamePattern}, the same class
 * the processor uses, so the name the editor synthesises and the name javac
 * emits cannot drift.
 */
public final class LogConstants {

    public static final @NotNull String LOG_FQN = "dev.simplified.annotations.Log";
    public static final @NotNull String LOG_SHORT_NAME = "Log";

    public static final @NotNull String ATTR_TOPIC = "topic";
    public static final @NotNull String ATTR_NAME = "name";
    public static final @NotNull String ATTR_EMIT_GENERATED = "emitGenerated";

    /** Type of the generated field, referenced by name because the library does not depend on log4j2. */
    public static final @NotNull String LOGGER_FQN = "org.apache.logging.log4j.Logger";

    /** Name of the generated field when {@code name} is unwritten. */
    public static final @NotNull String DEFAULT_FIELD_NAME = "log";

    private LogConstants() {}

    public static @NotNull String stringAttr(@Nullable PsiAnnotation annotation, @NotNull String attr, @NotNull String fallback) {
        if (annotation == null) return fallback;
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attr);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s && !s.isEmpty()) return s;
        return fallback;
    }

    /**
     * Finds the {@code @Log} annotation on a type.
     *
     * @param target the type to read
     * @return the annotation, or {@code null} when the type does not carry it
     */
    public static @Nullable PsiAnnotation findLog(@NotNull PsiClass target) {
        for (PsiAnnotation a : target.getAnnotations()) {
            if (LOG_FQN.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

    /**
     * Resolves the written {@code name} against the default, leaving the
     * suppression sentinel and any malformed pattern intact for the caller to
     * report on.
     *
     * @param annotation the {@code @Log} annotation, or {@code null}
     * @return the resolved pattern, before expansion
     */
    public static @NotNull String namePattern(@Nullable PsiAnnotation annotation) {
        return NamePattern.inherit(stringAttr(annotation, ATTR_NAME, ""), DEFAULT_FIELD_NAME);
    }

    /**
     * Resolves the name of the generated field, applying the same rules the
     * processor applies. The placeholder is optional here - the field is
     * generated once per target, so a plain literal cannot collide with itself.
     *
     * @param annotation the {@code @Log} annotation, or {@code null}
     * @param target the annotated type, whose simple name the placeholder expands to
     * @return the field name, or {@code null} when the pattern suppresses the
     *         field or cannot expand to a legal identifier
     */
    public static @Nullable String resolvedFieldName(@Nullable PsiAnnotation annotation, @NotNull PsiClass target) {
        String pattern = namePattern(annotation);
        if (!NamePattern.emits(pattern)) return null;
        if (NamePattern.patternError(pattern, false) != null) return null;
        String simpleName = target.getName();
        if (simpleName == null) return null;
        return NamePattern.expand(pattern, simpleName);
    }

    /**
     * Whether {@code @Log} can generate its field into the given type. An
     * annotation type reports {@link PsiClass#isInterface()} as well, so it is
     * rejected on its own test rather than folded into the interface one.
     *
     * @param target the annotated type
     * @return whether the type is a plain class or an enum
     */
    public static boolean isLegalTarget(@NotNull PsiClass target) {
        if (target.isAnnotationType()) return false;
        if (target.isInterface()) return false;
        return !target.isRecord();
    }
}
