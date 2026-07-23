package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Includes a member in the {@code equals} and {@code hashCode}
 * {@link EqualsAndHashCode} generates that the selection would otherwise skip.
 *
 * <p>On a {@code transient} or {@link Lazy} field it overrides the skip; on a
 * zero-arg non-{@code void} method it adds the method's result as a compared
 * term, which is how a derived value becomes part of the relation.
 *
 * <p>There is deliberately no {@code rank} attribute. Rank would make the
 * emitted hash depend on an ordering rule invisible in the source, where the
 * same attribute on {@link ToStringInclude} only changes print order.
 *
 * @see EqualsExclude
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface EqualsInclude {
}
