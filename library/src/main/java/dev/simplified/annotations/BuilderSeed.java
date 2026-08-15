package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Moves a parameter of a {@link ClassBuilder}-annotated constructor or static
 * factory onto the entry point, so the value is supplied when the builder is
 * obtained rather than through a setter.
 *
 * <p>A seeded parameter becomes a parameter of {@code builder(...)} and of the
 * generated builder's own constructor, is held in a {@code final} slot, and
 * emits <b>no setter</b>. Every other parameter keeps the setter matrix it
 * would have had. Seeds appear on {@code builder(...)} in declaration order,
 * and {@code build()} passes every slot - seeded or not - in the order the
 * annotated member declares them.
 *
 * <p>This is the shape a required, pre-validated or type-bearing value asks
 * for: without it, a value the caller must supply is indistinguishable from one
 * they may, and nothing marks it required at the point of entry.
 *
 * <h2>Example</h2>
 * <pre><code>
 * public final class Action {
 *
 *     &#64;ClassBuilder
 *     Action(&#64;BuilderSeed String key, boolean enabled) { ... }
 * }
 *
 * // Action.builder(String) - key is required, and has no setter
 * Action a = Action.builder("open").isEnabled().build();
 * </code></pre>
 *
 * <p>The companion annotations that shape a setter - {@link Collector},
 * {@link Negate} and {@link Formattable} - have nothing to shape on a seed and
 * are rejected beside it rather than silently ignored.
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface BuilderSeed {
}
