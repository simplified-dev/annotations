package dev.sbs.annotation;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds an inverse setter to a {@code boolean} field on a
 * {@link ClassBuilder}-annotated type.
 *
 * <p>Given {@code @Negate("enabled") private boolean disabled} the plugin
 * generates the usual {@code isDisabled()} / {@code isDisabled(boolean)}
 * setters AND their inverse {@code isEnabled()} / {@code isEnabled(boolean)}
 * setters that assign {@code disabled = !value}. This matches the common
 * hand-written pattern of paired enable/disable setters over a single
 * underlying field.
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface Negate {

    /**
     * The inverse method name stem. {@code "enabled"} produces {@code isEnabled()}
     * and {@code isEnabled(boolean)}.
     */
    @NotNull String value();

}
