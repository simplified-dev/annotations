package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a constructor taking one parameter per instance field, in
 * declaration order.
 *
 * <p>A {@code final} field that already carries an initializer is left out - it
 * cannot be assigned twice. Everything else the type declares is a parameter,
 * including {@code transient} fields and non-final fields with initializers,
 * whose parameter overwrites the declared value.
 *
 * <p>Unlike {@link ClassBuilder}, this annotation <b>adds</b> a constructor
 * rather than backing off when the type already declares one. Stacking it with
 * {@link NoArgsConstructor} is the ordinary way to satisfy a reflective
 * framework alongside a real constructor; a genuine duplicate is reported
 * before javac sees it.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;AllArgsConstructor(access = AccessLevel.PRIVATE)
 * public final class Point {
 *     private final int x;
 *     private final int y;
 * }
 * </code></pre>
 *
 * @see RequiredArgsConstructor
 * @see NoArgsConstructor
 * @see BuilderArgsConstructor
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface AllArgsConstructor {

    /**
     * Visibility of the generated constructor.
     *
     * <p>{@link AccessLevel#NONE} is rejected - an annotation that generates
     * nothing is better deleted than written. On an {@code enum} the
     * constructor is forced {@code private} whatever is written here, since the
     * language allows nothing else.
     */
    @NotNull AccessLevel access() default AccessLevel.PUBLIC;

    /**
     * Whether to emit {@link Generated} on the generated constructor so
     * coverage tools skip it.
     */
    boolean emitGenerated() default true;

}
