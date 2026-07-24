package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Includes a member in the {@code toString} {@link ToString} generates that the
 * selection would otherwise skip, and optionally renames or reorders it.
 *
 * <p>On a zero-arg non-{@code void} method it adds the method's result, which
 * is how a <b>derived</b> value - one with no backing field to select - gets
 * printed alongside the state it is derived from. That holds on a class and a
 * record; on an interface whose implementation {@link ClassBuilder} emits it is
 * reported, since the generated class holds a field only for an abstract
 * accessor. A {@code transient} field needs no marker to be printed, since
 * {@link ToString} keeps them.
 *
 * <p>On a {@link Lazy} field it is an error rather than an override. By the
 * time the pass runs that field's storage is a {@code Lazy} wrapper, so neither
 * the slot nor a forced read means what the marker asks for; the method form
 * says the same thing where the forcing is visible.
 *
 * @see ToStringExclude
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ToStringInclude {

    /** Label printed instead of the member's own name. Empty keeps the name. */
    @NotNull String name() default "";

    /**
     * Sort key, higher first, with declaration order inside one rank.
     *
     * <p>Safe here because it changes only what the output looks like. The same
     * attribute on {@link EqualsInclude} would make an emitted hash depend on
     * an ordering rule nothing in the source shows.
     */
    int rank() default 0;

}
