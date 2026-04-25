package dev.simplified.resourcepath;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Shared constants and helpers for the ResourcePath feature. Keeps the annotation FQN, its
 * attribute names, and a couple of pure utility functions in one place so the three classes
 * that need them don't drift.
 */
final class ResourcePathConstants {

    /** Fully-qualified name of the {@code @ResourcePath} annotation. */
    static final @NotNull String ANNOTATION_FQN = "dev.simplified.annotations.ResourcePath";

    /** Simple (unqualified) name - useful for dumb-mode fallback matching. */
    static final @NotNull String ANNOTATION_SHORT_NAME = "ResourcePath";

    /** Name of the {@code base} attribute on the annotation. */
    static final @NotNull String BASE_ATTR = "base";

    private ResourcePathConstants() {}

    /**
     * Extracts the {@code base} string from a {@code @ResourcePath} annotation, or the empty
     * string if the attribute is absent, non-literal, or the annotation is {@code null}.
     */
    static @NotNull String getBase(@Nullable PsiAnnotation annotation) {
        if (annotation == null) return "";
        PsiAnnotationMemberValue value = annotation.findAttributeValue(BASE_ATTR);
        if (value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String s) return s;
        return "";
    }

    /**
     * Converts a glob pattern to a {@link Pattern} for file-path matching.
     *
     * <p>Grammar:
     * <ul>
     *   <li>{@code **} - matches any sequence of characters including {@code /}</li>
     *   <li>{@code *}  - matches any sequence of characters except {@code /}</li>
     *   <li>{@code ?}  - matches exactly one character except {@code /}</li>
     *   <li>Everything else is matched literally.</li>
     * </ul>
     */
    static @NotNull Pattern globToRegex(@NotNull String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i += 2;
                    continue;
                }
                sb.append("[^/]*");
                i++;
                continue;
            }
            if (c == '?') {
                sb.append("[^/]");
                i++;
                continue;
            }
            sb.append(Pattern.quote(String.valueOf(c)));
            i++;
        }
        return Pattern.compile(sb.toString());
    }

}
