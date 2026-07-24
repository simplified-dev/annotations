package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Includes a member in the {@code equals} and {@code hashCode}
 * {@link EqualsAndHashCode} generates that the selection would otherwise skip.
 *
 * <p>On a {@code transient} field it overrides the skip, and that is the whole
 * answer for a value the type exposes through a plain accessor over transient
 * storage - no method form is needed there.
 *
 * <p>On a zero-arg non-{@code void} method it adds the method's result as a
 * compared term, which is how a <b>derived</b> value - one with no backing
 * field to select - becomes part of the relation. That holds on a class and a
 * record; on an interface whose implementation {@link ClassBuilder} emits it is
 * reported, since the generated class holds a field only for an abstract
 * accessor.
 *
 * <p>On a {@link Lazy} field it is an error rather than an override. By the
 * time the pass runs that field's storage is a {@code Lazy} wrapper, so neither
 * the slot nor a forced read means what the marker asks for; the method form
 * says the same thing where the forcing is visible.
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
