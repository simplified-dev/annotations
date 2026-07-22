package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates the constructor a {@link ClassBuilder}-generated {@code build()}
 * invokes: one parameter per builder-visible field, in declaration order.
 *
 * <p>Rarely written. A {@code @ClassBuilder} target infers it - or
 * {@link AllArgsConstructor}, when the two field sets happen to coincide - and
 * the IDE renders the inferred annotation and its resolved signature rather
 * than writing either into the source. Write it explicitly only to pin the
 * visibility independently of {@code @ClassBuilder(constructorAccess)}.
 *
 * <p>The field set differs from {@link AllArgsConstructor} in both directions,
 * which is why it is a separate annotation rather than a special case.
 * {@link BuilderIgnore}, {@code @ClassBuilder(exclude)} and {@code transient}
 * remove fields the all-args form keeps; a {@code final} field <b>with</b> an
 * initializer is kept here and dropped there, because
 * {@code @ClassBuilder(retainInit)} turns that initializer into a builder
 * default and the field is stripped to a blank final for the constructor to
 * assign.
 *
 * @see ClassBuilder#constructorAccess()
 * @see AllArgsConstructor
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface BuilderArgsConstructor {

    /**
     * Visibility of the generated constructor.
     *
     * <p>Package-private by default, matching
     * {@link ClassBuilder#constructorAccess()} - the generated builder is a
     * nested class of the target, so nothing wider is needed to construct it.
     * A value written here wins over {@code constructorAccess}.
     */
    @NotNull AccessLevel access() default AccessLevel.PACKAGE;

    /**
     * Whether to emit {@link Generated} on the generated constructor so
     * coverage tools skip it.
     */
    boolean emitGenerated() default true;

}
