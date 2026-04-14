package dev.sbs.annotation;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.util.Collection Collection} or {@link java.util.Map Map}
 * field on a {@link ClassBuilder}-annotated type so the generated builder
 * emits single-element add/put setters alongside the default whole-collection
 * replace setter.
 *
 * <p>For a {@code List<T>} or {@code Set<T>} field named {@code entries} the
 * plugin generates:
 * <ul>
 *   <li>{@code withEntries(T...)} - varargs replace</li>
 *   <li>{@code withEntries(Iterable<T>)} - iterable replace</li>
 *   <li>{@code addEntry(T)} - append one element</li>
 *   <li>{@code clearEntries()} - reset</li>
 * </ul>
 * For a {@code Map<K, V>} field the singular setter is {@code putEntry(K, V)}
 * and the collection-taking overload accepts a {@code Map}.
 *
 * <p>The singular method name defaults to the field name with any trailing
 * {@code "s"} stripped ({@code entries} -> {@code entry}); set {@link #value}
 * to override.
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface Singular {

    /**
     * Overrides the singular method name. Defaults to the field name with a
     * trailing {@code "s"} stripped when empty.
     */
    @NotNull String value() default "";

}
