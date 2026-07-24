package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a constructor taking no parameters.
 *
 * <p>The dominant use is sealing a type so a static factory or a builder is the
 * only way in - {@code @NoArgsConstructor(access = AccessLevel.PRIVATE)}
 * replaces the public default javac would otherwise supply. The second use is
 * handing a reflective framework an entry point it can call.
 *
 * <p>A {@code final} field with no initializer is a compile error here, because
 * the constructor would leave it unassigned. {@link #force()} resolves that by
 * assigning the JVM zero value instead.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;NoArgsConstructor(access = AccessLevel.PRIVATE)
 * public final class Registry {
 *     public static Registry of() { ... }
 * }
 * </code></pre>
 *
 * @see AllArgsConstructor
 * @see RequiredArgsConstructor
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface NoArgsConstructor {

    /**
     * Visibility of the generated constructor.
     *
     * <p>{@link AccessLevel#NONE} is rejected. On an {@code enum} the
     * constructor is forced {@code private} whatever is written here.
     */
    @NotNull AccessLevel access() default AccessLevel.PUBLIC;

    /**
     * Whether to assign the JVM zero value - {@code null}, {@code 0},
     * {@code false} - to every {@code final} field with no initializer, instead
     * of rejecting the type.
     *
     * <p>Deliberately violates whatever the field's own nullness annotation
     * claims, which is the point: a JSON or persistence layer populates the
     * fields immediately afterwards, and the alternative is giving up
     * {@code final}. Nothing else is suppressed, so the type is briefly in a
     * state its own contract forbids.
     */
    boolean force() default false;

    /**
     * Whether to emit {@link Generated} on the generated constructor so
     * coverage tools skip it.
     */
    boolean emitGenerated() default true;

}
