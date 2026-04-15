package dev.sbs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Single entry point for type-agnostic field-level modifiers on a
 * {@link ClassBuilder}-annotated type. Collapses what used to be
 * {@code @BuilderDefault}, {@code @BuilderIgnore}, {@code @BuildFlag}, and
 * {@code @ObtainVia} into one annotation with four attributes.
 *
 * <p>Type-specific annotations - {@link Negate} (boolean fields),
 * {@link Formattable} (String fields), {@link Collector} (collection / map
 * fields) - stay separate because their semantics are tied to the field's
 * type.
 *
 * <h2>Examples</h2>
 * <pre><code>
 * // Preserve the field's declared initializer as the builder's default
 * &#64;BuildRule(retainInit = true)
 * String name = "anonymous";
 *
 * // Skip a field entirely
 * &#64;BuildRule(ignore = true)
 * transient Cache cache;
 *
 * // Runtime validation constraints
 * &#64;BuildRule(flag = &#64;BuildFlag(nonNull = true, notEmpty = true, limit = 256))
 * String label;
 *
 * // Override how from(instance) reads the field
 * &#64;BuildRule(obtainVia = &#64;ObtainVia(method = "getCustomAccess"))
 * String custom;
 *
 * // Combine any subset
 * &#64;BuildRule(
 *     retainInit = true,
 *     flag = &#64;BuildFlag(nonNull = true),
 *     obtainVia = &#64;ObtainVia(method = "getName")
 * )
 * String required = "";
 * </code></pre>
 *
 * <h2>Runtime retention</h2>
 *
 * This annotation is {@link RetentionPolicy#RUNTIME} so
 * {@code BuildFlagValidator} can reflectively read the nested
 * {@link #flag} at build-time validation. Class-retention on the parent
 * would strip the nested annotation's runtime visibility regardless of
 * {@link BuildFlag}'s own retention.
 *
 * @see ClassBuilder
 * @see BuildFlag
 * @see ObtainVia
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface BuildRule {

    /**
     * When {@code true}, the generated builder starts this field at its
     * declared initializer value (e.g. {@code UUID.randomUUID()},
     * {@code List.of(...)}) rather than the JVM default. The field must have
     * an initializer expression; validating otherwise produces an APT error.
     */
    boolean retainInit() default false;

    /**
     * When {@code true}, the field is excluded from builder synthesis - no
     * setter is emitted, no parameter in {@code build()}, no entry in
     * {@code from(T)} / {@code mutate()}. Cleaner alternative to listing the
     * field name in {@link ClassBuilder#exclude}.
     */
    boolean ignore() default false;

    /**
     * Runtime validation constraints enforced by {@code BuildFlagValidator}
     * inside the generated {@code build()}. Default is the no-op
     * {@code @BuildFlag} - every attribute at its default, validator skips
     * the field.
     */
    BuildFlag flag() default @BuildFlag;

    /**
     * Overrides how {@code from(T)} and {@code mutate()} read this field off
     * an existing instance. Default is the no-op {@code @ObtainVia} - all
     * attributes empty, extractor uses the standard getter or direct field
     * access.
     */
    ObtainVia obtainVia() default @ObtainVia;

}
