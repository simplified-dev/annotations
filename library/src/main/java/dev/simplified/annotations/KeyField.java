package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;

/**
 * Marks an enum's instance field as a lookup key for use with
 * {@link EnumLookup}.
 *
 * <p>For each {@code @KeyField} on a field of an {@code @EnumLookup}-annotated
 * enum, the processor generates:
 * <ul>
 *   <li>a parallel cached array {@code CACHED_KEYS_<fieldName>} (primitive-typed when
 *       the field is primitive),</li>
 *   <li>a {@code public static E of<Name>(T)} method returning the matching constant
 *       or {@code null},</li>
 *   <li>a {@code public static Optional<E> findBy<Name>(T)} method.</li>
 * </ul>
 *
 * <p>{@code <Name>} defaults to the field name capitalised; override via
 * {@link #methodName()}.
 *
 * <p>Lookup is a zero-allocation linear scan over the parallel array. First
 * match wins on duplicate keys. For typical enum sizes (&lt; 32 constants)
 * the scan is faster than a {@link HashMap} due to cache locality
 * and zero hash computation.
 *
 * <h2>Strict-validation toggles</h2>
 * Both strict toggles are IDE-time validations driven by inspections - they
 * produce editor highlighting and never inject runtime checks. The runtime
 * populate loop stays branch-free regardless of these flags.
 *
 * @see EnumLookup
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface KeyField {

    /**
     * Overrides the suffix used when deriving the generated method names.
     * The generator emits {@code of<methodName>(T)} and
     * {@code findBy<methodName>(T)}. When empty (the default), the suffix
     * is derived by capitalising the annotated field's name.
     *
     * <p>Example: {@code @KeyField(methodName = "Id") private final int identifier}
     * yields {@code ofId(int)} and {@code findById(int)} instead of
     * {@code ofIdentifier} / {@code findByIdentifier}.
     */
    @NotNull String methodName() default "";

    /**
     * Opts into the IDE inspection that highlights any two enum constants
     * sharing a value for this key. Default {@code false}. Edit-time only -
     * the generated runtime never throws. Severity is configurable in the
     * inspection settings (default {@code ERROR}).
     */
    boolean strictKeys() default false;

    /**
     * Opts into the IDE inspection that highlights any enum constant
     * assigning {@code null} to this key. Default {@code false}. No-op for
     * primitive-typed fields (flagged by the inspection when set anyway).
     * Edit-time only - the generated runtime never throws. Severity is
     * configurable in the inspection settings (default {@code ERROR}).
     */
    boolean strictNullKeys() default false;

}
