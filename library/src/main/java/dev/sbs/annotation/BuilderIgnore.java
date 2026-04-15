package dev.sbs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a single field from the generated builder.
 *
 * <p>Equivalent to listing the field name in
 * {@link ClassBuilder#exclude() @ClassBuilder(exclude = ...)} but cleaner for
 * one-off exclusions that live with the field rather than on the type.
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface BuilderIgnore {
}
