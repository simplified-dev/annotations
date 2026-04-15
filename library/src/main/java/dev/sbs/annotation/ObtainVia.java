package dev.sbs.annotation;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides how the generated {@code from(T)} and {@code mutate()} methods
 * read a field's value off an existing instance.
 *
 * <p>By default the plugin reads {@code instance.field} directly (or
 * {@code instance.getField()} when a matching getter exists). When the value
 * lives behind a differently named accessor, a different field, or a static
 * helper, this annotation redirects the read.
 *
 * <p>Lombok parity: {@code @Builder.ObtainVia}. Exactly one of {@link #method}
 * or {@link #field} should be non-empty.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;ClassBuilder
 * public final class Parameter {
 *     &#64;ObtainVia(method = "getSizeLimit")
 *     private final Range&lt;Double&gt; sizeLimit;
 * }
 * </code></pre>
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface ObtainVia {

    /**
     * The instance method on the target type to call instead of reading the
     * field directly. Mutually exclusive with {@link #field}.
     */
    @NotNull String method() default "";

    /**
     * The name of a different field on the target type to read. Mutually
     * exclusive with {@link #method}.
     */
    @NotNull String field() default "";

    /**
     * When {@code true} and {@link #method} is set, invokes
     * {@code TargetType.method(instance)} rather than
     * {@code instance.method()}.
     */
    boolean isStatic() default false;

}
