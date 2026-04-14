package dev.sbs.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Carries a field's declared initialiser into the generated builder as the
 * starting value for that slot.
 *
 * <p>Without this annotation the generated builder starts every field at the
 * JVM default ({@code null}, {@code 0}, {@code false}). When a field has a
 * meaningful initial state that should be preserved if the caller never sets
 * it - a randomly generated UUID, a sensible default string, a pre-populated
 * collection - mark the field with {@code @BuilderDefault} and the generated
 * builder field will use the same initialiser.
 *
 * <p>Lombok parity: {@code @Builder.Default}.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;ClassBuilder
 * public final class Button {
 *     &#64;BuilderDefault
 *     private final UUID identifier = UUID.randomUUID();
 *     private final String label;
 * }
 * </code></pre>
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface BuilderDefault {
}
