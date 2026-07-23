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
 * <p>On a {@link Lazy} field it overrides the skip; on a zero-arg
 * non-{@code void} method it adds the method's result, which is how a derived
 * value gets printed alongside the state it is derived from.
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
