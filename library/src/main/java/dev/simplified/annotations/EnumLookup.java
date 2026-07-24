package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Type-level marker that drives synthesis of a cached values array plus a
 * uniform set of static lookup helpers on the annotated enum.
 *
 * <p>The annotation processor injects, via javac AST mutation, a
 * {@code private static final E[] CACHED_VALUES} initialised from
 * {@code values()} and the following {@code public static} methods:
 * <ul>
 *   <li>{@code int size()}</li>
 *   <li>{@code void forEach(Consumer<? super E>)}</li>
 *   <li>{@code void forEach(BiConsumer<Integer, ? super E>)} - second argument is the ordinal</li>
 *   <li>{@code Stream<E> stream()}</li>
 *   <li>{@code Stream<E> parallelStream()}</li>
 *   <li>{@code E ofName(String)} - case-insensitive lookup by {@code name()}, or {@code null}</li>
 *   <li>{@code E ofOrdinal(int)} - bounds-checked lookup by ordinal, or {@code null}</li>
 *   <li>{@code Optional<E> findByName(String)}</li>
 *   <li>{@code Optional<E> findByOrdinal(int)}</li>
 * </ul>
 *
 * <p>Annotating one or more instance fields with {@link KeyField} adds, per
 * field, a parallel {@code CACHED_KEYS_<fieldName>} array (primitive-typed when
 * the field is primitive) and the matching {@code of<Name>(T)} +
 * {@code findBy<Name>(T)} overloads, where {@code <Name>} defaults to the
 * capitalised field name and is overridable via {@link KeyField#methodName()}.
 *
 * <p>All generated methods carry an {@link XContract} annotation so IntelliJ
 * data-flow analysis understands their null / non-null return shapes.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;EnumLookup
 * public enum Status {
 *     OK(200, "ok"),
 *     NOT_FOUND(404, "not-found"),
 *     ERROR(500, "error");
 *
 *     &#64;KeyField private final int code;
 *     &#64;KeyField private final String slug;
 *
 *     Status(int code, String slug) { this.code = code; this.slug = slug; }
 * }
 *
 * // call sites:
 * Status s = Status.ofCode(404);                 // NOT_FOUND
 * Optional&lt;Status&gt; o = Status.findBySlug("ok"); // Optional[OK]
 * Status.forEach((i, v) -&gt; System.out.println(i + " -&gt; " + v));
 * </code></pre>
 *
 * <h2>Restrictions</h2>
 * <ul>
 *   <li>Targets {@code enum} declarations only - other type kinds are ignored
 *       with a compiler warning.</li>
 *   <li>Requires javac for AST mutation. Builds running under ecj fail with a
 *       compiler error.</li>
 * </ul>
 *
 * @see KeyField
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface EnumLookup {
}
