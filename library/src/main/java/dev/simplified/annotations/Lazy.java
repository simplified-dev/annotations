package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field-level annotation that defers a field's value computation until first access
 * and caches it thereafter.
 *
 * <p>The annotation processor rewrites the annotated field's storage type from
 * {@code T} to {@link dev.simplified.lazy.Lazy Lazy&lt;T&gt;} and
 * synthesises a public memoizing getter ({@code getFoo()} for object types,
 * {@code isFoo()} for {@code boolean}). The original initializer expression, when
 * present, becomes the supplier body so source-level reads of
 * {@code "expensive()"} now run once on the first {@code getFoo()} call rather
 * than at construction time.
 *
 * <h2>Standalone usage</h2>
 * <pre><code>
 * &#64;Lazy
 * private final Result computed = expensiveOperation();
 * </code></pre>
 * Compiled to:
 * <pre><code>
 * private final Lazy&lt;Result&gt; computed = Lazy.of(() -&gt; expensiveOperation());
 * public Result getComputed() { return computed.get(); }
 * </code></pre>
 *
 * <h2>With {@link ClassBuilder @ClassBuilder}</h2>
 * The synthesised builder receives a dual setter for the field: a value form
 * that wraps as {@code () -> value} and a {@code Supplier<T>} form that stores
 * the supplier verbatim, preserving full laziness through the builder. The
 * target's matching constructor parameter (by name) is rewritten from
 * {@code T} to {@code Supplier<T>} so the value flows from the builder to the
 * target as a deferred computation, wrapped at assignment time as
 * {@code Lazy.of(supplier)}.
 *
 * <h2>Lombok interop</h2>
 * {@code @Lazy} emits its own getter through AST mutation. When a Lombok
 * {@code @Getter} is also present, Lombok skips that field on the duplicate-
 * method check, so {@code @Lazy}'s getter wins. An IDE inspection flags the
 * combination as redundant.
 *
 * <h2>Restrictions</h2>
 * <ul>
 *   <li>Only reference types are supported. Primitives must be boxed
 *       ({@code Boolean} instead of {@code boolean}).</li>
 *   <li>Not supported on static fields, record components, or in combination
 *       with the field-only companion annotations
 *       ({@link Collector}, {@link Negate}, {@link Formattable},
 *       {@link BuildRule}).</li>
 *   <li>Standalone use (no {@code @ClassBuilder} on the enclosing class)
 *       requires a field initializer; the initializer becomes the supplier
 *       body.</li>
 * </ul>
 *
 * @see dev.simplified.lazy.Lazy
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface Lazy {

    /**
     * Access level for the synthesized getter. Defaults to {@link AccessLevel#PUBLIC}.
     * {@link AccessLevel#PACKAGE} emits no access keyword (package-private).
     */
    @NotNull AccessLevel access() default AccessLevel.PUBLIC;

}
