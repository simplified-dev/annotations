package dev.simplified.annotations;

import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Declares runtime-enforced constraints on a builder field, or on the abstract
 * accessor standing in for one on an interface target, verified by
 * {@code BuildFlagValidator.validate($result)} inside the builder's generated
 * {@code build()} method.
 *
 * <p>Each attribute is independent and may be combined. Every one of them states
 * something about the value a single field holds; a rule spanning two fields
 * belongs in the target's own constructor, which {@link ClassBuilder} takes as a
 * target, or in the {@link ClassBuilder#factoryMethod()} that has to run before
 * construction rather than after it.
 *
 * <p>When {@link #group()} is
 * empty, {@link #nonNull()} / {@link #notEmpty()} enforce the field
 * individually (fail-fast if the field is null/empty at {@code build()} time).
 * When {@link #group()} names one or more groups, the field joins each group
 * and the group is satisfied so long as <b>at least one</b> of its members is
 * valid - the "A or B" pattern used by Discord buttons requiring an emoji or a
 * label.
 *
 * <p>The validator is provided by this plugin's runtime support and has no
 * external dependencies. Fields are scanned once per class and cached - the
 * scan walking the superclass chain, so an inherited constraint is enforced on
 * the subclass being built.
 *
 * <p>On an interface {@link ClassBuilder} target the constraint goes on the
 * abstract accessor instead, an interface declaring no fields of its own. The
 * processor copies it onto the matching field of the generated
 * {@code <Name>Impl} - the instance {@code build()} actually constructs, and
 * the one the validator reads - so an accessor constraint is enforced exactly
 * as a field constraint is. That is the only place a method target is read:
 * written on any other method it has no effect.
 *
 * <h2>Examples</h2>
 * <pre><code>
 * // Required, must be non-empty, at most 256 characters
 * &#64;BuildFlag(nonNull = true, notEmpty = true, limit = 256)
 * private String name;
 *
 * // At least one of emoji or label must be set
 * &#64;BuildFlag(nonNull = true, group = "face") private Emoji emoji;
 * &#64;BuildFlag(nonNull = true, group = "face") private String label;
 *
 * // Must match a regex
 * &#64;BuildFlag(nonNull = true, pattern = "[a-z0-9_]+")
 * private String identifier;
 *
 * // Limit applied to a collection
 * &#64;BuildFlag(limit = 25)
 * private List&lt;Field&gt; fields;
 *
 * // Numeric range, either end on its own or both together
 * &#64;BuildFlag(min = 0, max = 100)
 * private int nearLossless;
 *
 * &#64;BuildFlag(min = -1)
 * private int forceKeyframeEvery;
 *
 * // On an interface target, the accessor carries it
 * &#64;ClassBuilder
 * public interface Shape {
 *     &#64;BuildFlag(nonNull = true) String name();
 * }
 * </code></pre>
 *
 * @see ClassBuilder#validate
 */
@Target({ElementType.FIELD, ElementType.METHOD})
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

    /**
     * Smallest value a numeric field may hold at {@code build()} time, inclusive.
     * Applies to any primitive number, its boxed form, and an {@link Optional} of
     * one - an empty {@code Optional} holding nothing to bound.
     * {@link Double#NEGATIVE_INFINITY} (the default) disables the check.
     *
     * <p>Declared as a {@code double} so one attribute bounds every numeric width,
     * and written as an ordinary literal either way - {@code min = 0} on an
     * {@code int} field is the same widening every assignment does.
     *
     * <p>A bound rejects; it does not clamp. A field that should quietly take the
     * nearest legal value wants {@link AssignVia} on the setter instead, which
     * runs where the caller passed the value rather than after the object exists.
     */
    double min() default Double.NEGATIVE_INFINITY;

    /**
     * Largest value a numeric field may hold at {@code build()} time, inclusive.
     * {@link Double#POSITIVE_INFINITY} (the default) disables the check.
     *
     * @see #min
     */
    double max() default Double.POSITIVE_INFINITY;

}
