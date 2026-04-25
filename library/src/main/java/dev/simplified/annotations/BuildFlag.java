package dev.simplified.annotations;

import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Declares runtime-enforced constraints on a builder field that are verified by
 * {@code BuildFlagValidator.validate(this)} inside the builder's generated
 * {@code build()} method.
 *
 * <p>Used only as the {@link BuildRule#flag} attribute of
 * {@link BuildRule} - not a field-level annotation in its own right.
 * {@link Target @Target} is empty so direct field usage
 * ({@code @BuildFlag String x;}) is a compile-time error.
 *
 * <p>Each attribute is independent and may be combined. When {@link #group()} is
 * empty, {@link #nonNull()} / {@link #notEmpty()} enforce the field
 * individually (fail-fast if the field is null/empty at {@code build()} time).
 * When {@link #group()} names one or more groups, the field joins each group
 * and the group is satisfied so long as <b>at least one</b> of its members is
 * valid - the "A or B" pattern used by Discord buttons requiring an emoji or a
 * label.
 *
 * <p>The validator is provided by this plugin's runtime support and has no
 * external dependencies. Fields are scanned once per class and cached.
 *
 * <h2>Examples</h2>
 * <pre><code>
 * // Required, must be non-empty, at most 256 characters
 * &#64;BuildRule(flag = &#64;BuildFlag(nonNull = true, notEmpty = true, limit = 256))
 * private String name;
 *
 * // At least one of emoji or label must be set
 * &#64;BuildRule(flag = &#64;BuildFlag(nonNull = true, group = "face")) private Emoji emoji;
 * &#64;BuildRule(flag = &#64;BuildFlag(nonNull = true, group = "face")) private String label;
 *
 * // Must match a regex
 * &#64;BuildRule(flag = &#64;BuildFlag(nonNull = true, pattern = "[a-z0-9_]+"))
 * private String identifier;
 *
 * // Limit applied to a collection
 * &#64;BuildRule(flag = &#64;BuildFlag(limit = 25))
 * private List&lt;Field&gt; fields;
 * </code></pre>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface BuildFlag {

    /**
     * Whether the field must be non-null at {@code build()} time.
     */
    boolean nonNull() default false;

    /**
     * Whether the field must be non-empty at {@code build()} time. Emptiness is
     * defined per type:
     * <ul>
     *   <li>{@link CharSequence} - {@code length() == 0}</li>
     *   <li>{@link Optional} - {@link Optional#isEmpty()}</li>
     *   <li>{@link Collection} - {@link Collection#isEmpty()}</li>
     *   <li>{@link Map} - {@link Map#isEmpty()}</li>
     *   <li>Object array - {@code length == 0}</li>
     * </ul>
     */
    boolean notEmpty() default false;

    /**
     * The groups this field participates in. When empty, the field is validated
     * individually (fail-fast on null/empty). When non-empty, the field is
     * required only so long as no other member of each named group is valid.
     */
    @NotNull String[] group() default { };

    /**
     * Regular expression the field's value must match. Only applied to
     * {@link CharSequence} fields and {@link Optional} of {@link String}. Empty
     * string disables the check.
     */
    @Language("RegExp")
    @NotNull String pattern() default "";

    /**
     * Maximum length or size. Applied as follows:
     * <ul>
     *   <li>{@link CharSequence} / {@link String} - character length</li>
     *   <li>{@link Collection} - size</li>
     *   <li>{@link Optional} of {@link String} - character length of the value (or 0 when empty)</li>
     *   <li>{@link Optional} of {@link Number} - integer value of the number (or 0 when empty)</li>
     * </ul>
     * {@code -1} (the default) disables the check.
     */
    int limit() default -1;

}
