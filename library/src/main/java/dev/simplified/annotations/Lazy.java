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
 * <h2>Assigned by a constructor</h2>
 * A field with no initializer takes its supplier from whatever a constructor
 * assigns it, the whole assigned expression becoming the supplier body:
 * <pre><code>
 * &#64;Lazy
 * private final Headers headers;
 *
 * Response(HttpContext context) {
 *     this.context = context;
 *     this.headers = parse(context.rawHeaders());
 * }
 * </code></pre>
 * Compiled to {@code this.headers = Lazy.of(() -> parse(context.rawHeaders()))},
 * so the parse runs on the first {@code getHeaders()} rather than during
 * construction. This is the shape for a value derived from constructor
 * arguments or from sibling fields, which has no initializer to hold the
 * expression - the alternative being to declare the field {@code Lazy<T>} by
 * hand and write the wrap inline, which is what the annotation exists to
 * replace.
 *
 * <p>Every assignment to the field is rewritten, including one inside an
 * {@code if} or a {@code try}. The field becomes {@code final}, so assigning it
 * twice, or leaving a constructor that does not assign it, is javac's ordinary
 * error about a blank final.
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
 *       {@link BuildFlag}, {@link ObtainVia}). Each assumes direct {@code T}
 *       storage, so the processor rejects the pairing rather than letting it
 *       misbehave. {@link BuilderDefault} and {@link BuilderIgnore} are
 *       permitted: they govern how the builder treats the field, not how it is
 *       stored. A {@code @BuilderIgnore}d lazy field keeps its own initializer
 *       and is never touched by the constructor, so it behaves as in the
 *       standalone case.</li>
 *   <li>Standalone use (no {@code @ClassBuilder} on the enclosing class)
 *       requires either a field initializer or a constructor assignment; that
 *       expression becomes the supplier body. Both are instance contexts, so it
 *       may reference the enclosing instance freely - instance methods, instance
 *       fields, and {@code this}. A field with neither is rejected, having
 *       nothing to defer.</li>
 *   <li>With {@code @ClassBuilder} an instance-referencing initializer is
 *       supported as well, computed in the generated constructor rather than
 *       when the builder is created. Both branches stay deferred, so laziness
 *       survives either way.</li>
 *   <li>With {@code @ClassBuilder} the builder supplies the value, so a field
 *       with no initializer must have its setter called. A deferred
 *       computation is expected to exist - building without supplying one
 *       fails at {@code build()} naming the field, rather than surfacing later
 *       as a {@link NullPointerException} inside the getter.</li>
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
