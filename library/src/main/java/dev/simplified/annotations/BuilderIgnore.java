package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes a field from builder synthesis entirely - no setter is emitted, no
 * parameter in the generated constructor or {@code build()}, and no entry in
 * {@code from(T)} / {@code mutate()}.
 *
 * <p>Field-local alternative to naming the field in {@link ClassBuilder#exclude},
 * preferable when the reason to skip the field is evident at its declaration.
 *
 * <p>An ignored field keeps its declared initializer as ordinary Java field
 * initialization; {@link BuilderDefault} has no bearing on it, since the builder
 * never touches the field at all.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;ClassBuilder
 * public class Session {
 *
 *     String userId;
 *
 *     &#64;BuilderIgnore
 *     transient Cache cache = new Cache();
 * }
 * </code></pre>
 *
 * @see ClassBuilder#exclude
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface BuilderIgnore {
}
