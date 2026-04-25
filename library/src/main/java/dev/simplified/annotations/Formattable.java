package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a {@code @PrintFormat} string overload to a {@link String} or
 * {@link java.util.Optional Optional}{@code <String>} field on a
 * {@link ClassBuilder}-annotated type.
 *
 * <p>The emitted overload accepts a format template and a varargs array,
 * running the template through {@link String#format(String, Object...)}.
 * The nullability of the overload mirrors the field's declared nullability:
 * <ul>
 *   <li>{@code @NotNull String} - non-null template, calls {@code String.format}</li>
 *   <li>{@code @Nullable String} - nullable template, calls {@code Strings.formatNullable}
 *       and unwraps the result (null template leaves the field unchanged at {@code null})</li>
 *   <li>{@code Optional<String>} - nullable template, assigns {@code Strings.formatNullable(...)}
 *       to the field directly (Optional fields are always null-tolerant on the
 *       template side)</li>
 * </ul>
 *
 * <p>For fields that need null-tolerant templates, annotate the field itself
 * with {@code @Nullable}; the formattable overload will follow. There's no
 * need for a separate {@code nullable} attribute on this annotation.
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface Formattable {
}
