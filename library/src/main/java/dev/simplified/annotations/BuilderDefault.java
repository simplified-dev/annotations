package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls whether the generated builder seeds a field from its declared
 * initializer rather than the JVM default.
 *
 * <p>{@link ClassBuilder#retainInit} turns this on for every field of the type,
 * which is the default. This annotation is therefore most often written in its
 * opt-out form, {@code @BuilderDefault(false)}, to exclude one field from the
 * class-wide policy. Whatever a field declares wins over the class-level
 * setting; a field carrying no {@code @BuilderDefault} inherits it.
 *
 * <p>A field with no initializer expression is unaffected either way - there is
 * nothing to retain, so it starts at the JVM default regardless.
 *
 * <p>The initializer is evaluated <b>fresh per builder instance</b>: a
 * {@code UUID.randomUUID()} default produces a new UUID for each builder, and a
 * {@code new ArrayList<>()} default produces a fresh mutable list. Under the
 * hood the processor injects a package-private static
 * {@code $default$<fieldName>()} method carrying the source expression, and the
 * generated builder's field default calls that method.
 *
 * <p>Any Java expression that compiles in the target class's scope works -
 * method calls, constructor invocations, factory methods, field accesses - as
 * long as every identifier it references would resolve from the original field
 * declaration.
 *
 * <h2>Examples</h2>
 * <pre><code>
 * &#64;ClassBuilder
 * public class Widget {
 *
 *     // Inherits the class-wide policy - builder starts at "anonymous"
 *     String name = "anonymous";
 *
 *     // Opts out - builder starts at null despite the initializer
 *     &#64;BuilderDefault(false)
 *     String transientLabel = "ignored";
 * }
 *
 * // Class-wide opt-out, with one field opting back in
 * &#64;ClassBuilder(retainInit = false)
 * public class Sparse {
 *
 *     String untouched = "jvm default wins";
 *
 *     &#64;BuilderDefault
 *     String kept = "retained";
 * }
 * </code></pre>
 *
 * @see ClassBuilder#retainInit
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface BuilderDefault {

    /**
     * Whether to retain this field's declared initializer as the builder's
     * default. Defaults to {@code true} so the bare annotation reads as an
     * opt-in; pass {@code false} to opt a single field out of a class that
     * retains initializers.
     */
    boolean value() default true;

}
