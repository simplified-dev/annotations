package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a member from the {@code toString} {@link ToString} generates.
 *
 * <p>Field-local alternative to {@code @ToString(exclude = ...)}. The case it
 * exists for is a member whose value is large rather than secret - a buffer, an
 * encoded image, a collection that scales with input - where inlining it turns
 * one log line into several kilobytes.
 *
 * @see ToStringInclude
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ToStringExclude {
}
