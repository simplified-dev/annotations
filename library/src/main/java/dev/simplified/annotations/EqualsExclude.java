package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a member from the {@code equals} and {@code hashCode}
 * {@link EqualsAndHashCode} generates.
 *
 * <p>Field-local alternative to {@code @EqualsAndHashCode(exclude = ...)},
 * sitting at the declaration it affects the way {@link BuilderIgnore} sits
 * beside {@code @ClassBuilder(exclude = ...)}. A member the selection already
 * skips - {@code static}, {@code transient}, {@link Lazy} - does not need it.
 *
 * @see EqualsInclude
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface EqualsExclude {
}
