package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Expands the setter matrix on a {@link Collection} or
 * {@link Map} field in a {@link ClassBuilder}-annotated type.
 *
 * <p>Without this annotation, a {@code List<T>} / {@code Set<T>} /
 * {@code Map<K,V>} field gets a single whole-collection replace setter
 * ({@code entries(List<T>)}, etc). With this annotation, the builder
 * additionally emits bulk-style overloads and - opt-in via the attributes
 * below - single-element mutators.
 *
 * <p>Every name here is the one {@link NamingStyle#SIMPLIFIED} emits, where the
 * replace and bulk role is the bare field name. No style prefixes it with
 * {@code with}: a target wanting {@code withEntries(...)} writes
 * {@code @ClassBuilder(setters = @SetterNames(set = "with{}"))}.
 *
 * <h2>Always generated when the annotation is present</h2>
 * <ul>
 *   <li><b>List / Set / Iterable</b>:
 *     {@code entries(T... entries)},
 *     {@code entries(Iterable<T> entries)}</li>
 *   <li><b>Map</b>: the whole-{@code Map} replace setter (same as without
 *     the annotation, no extra bulk overloads since maps have no varargs form)</li>
 * </ul>
 *
 * <h2>Opt-in extras</h2>
 * <ul>
 *   <li>{@link #singular} - single-element add/put:
 *     {@code addEntry(T)} for collections, {@code putEntry(K, V)} for maps.</li>
 *   <li>{@link #clearable} - {@code clearEntries()} that empties the
 *     underlying collection or map.</li>
 *   <li>{@link #compute} - (maps only) {@code putEntryIfAbsent(K, Supplier<V>)}
 *     that lazily computes a value when the key is missing.</li>
 *   <li>{@link #append} - makes the bulk setters add to the container instead
 *     of replacing it, so repeated calls accumulate.</li>
 * </ul>
 *
 * <p>The put-if-absent form takes a {@link Supplier} rather than a value, which is
 * the whole point of it and the one signature a call site is likely to guess wrong:
 *
 * <pre>{@code
 * builder.putMetaIfAbsent("region", () -> resolveRegion());   // supplier
 * builder.putMetaIfAbsent("region", resolveRegion());         // does not compile
 * }</pre>
 *
 * <p>Use {@link #singularMethodName} to override the inflected single-element
 * name (default: field name minus trailing plural inflection - {@code entries}
 * becomes {@code entry}, {@code boxes} becomes {@code box}, {@code tags}
 * becomes {@code tag}).
 *
 * <h2>Interaction with the field's initializer</h2>
 * A declared initializer seeds the collection (see {@link BuilderDefault}), and
 * the setters compose with it as their names suggest:
 *
 * <pre>{@code
 * @Collector(singular = true, clearable = true) List<String> items = List.of("a");
 *
 * builder().build()                 // [a]     - the default seeds it
 * builder().addItem("b").build()    // [a, b]  - a single-element add appends
 * builder().items("x").build()      // [x]     - a wholesale replace discards it
 * builder().clearItems().build()    // []      - so does clear
 * }</pre>
 *
 * <p>Under {@link #append} the third line reads {@code [a, x]} instead, because
 * nothing replaces the container the initializer seeded.
 *
 * <p>The default is copied per builder before any of this, so an immutable one
 * such as {@code List.of(...)} is safe to add to and a default that returns
 * shared state cannot be mutated through the builder.
 *
 * <p>This holds for a custom container type as well - one recognised by
 * implementing {@link Collection} or {@link Map} rather than by being a
 * {@code java.util} type - including one with no accessible constructor, or an
 * interface. The built field always holds the instance the initializer
 * returned; nothing is reconstructed from the declared type.
 *
 * <h2>Examples</h2>
 * <pre><code>
 * // Bulk-only: entries(T...) + entries(Iterable&lt;T&gt;)
 * &#64;Collector List&lt;String&gt; entries;
 *
 * // Bulk + single-element add
 * &#64;Collector(singular = true) List&lt;String&gt; tags;
 * // tags(String...), tags(Iterable&lt;String&gt;), addTag(String)
 *
 * // Bulk + clear + custom singular name
 * &#64;Collector(clearable = true, singularMethodName = "flavor") List&lt;String&gt; flavors;
 * // flavors(String...), flavors(Iterable&lt;String&gt;), clearFlavors()
 *
 * // Map: opt-in put + lazy compute
 * &#64;Collector(singular = true, compute = true) Map&lt;String, Integer&gt; counts;
 * // counts(Map), putCount(String, Integer), putCountIfAbsent(String, Supplier&lt;Integer&gt;)
 * </code></pre>
 *
 * <h2>On a constructor or factory parameter</h2>
 * A {@link ClassBuilder} written on a constructor or static factory derives its
 * slots from that member's parameters, and this annotation reaches them the same
 * way it reaches a field - the bulk overloads, the single-element add or put,
 * the clear, and the lazy put-if-absent are all emitted against the parameter's
 * own type.
 *
 * <p>A parameter carries no initializer, so the slot starts empty and there is
 * no default for a wholesale replace to discard. {@link #append} still decides
 * what a <em>second</em> bulk call does - {@code tags("a").tags("b")} yields
 * {@code [a, b]} under it and {@code [b]} without.
 *
 * <p>One shape is out of reach there, and it is the initializer's absence that
 * puts it there rather than a decision: a container recognised by implementing
 * {@link Collection} or {@link Map} rather than by being a {@code java.util}
 * type has no expression the builder could use to make a fresh instance of it,
 * so such a parameter gets a plain replace setter and a compiler note.
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
public @interface Collector {

    /**
     * Overrides the single-element method name. When empty, the plugin derives
     * it from the field name by stripping a trailing plural inflection
     * ({@code entries} -> {@code entry}).
     */
    @NotNull String singularMethodName() default "";

    /**
     * Adds a single-element add setter: {@code addEntry(T)} for collections,
     * {@code putEntry(K, V)} for maps. Method name derives from
     * {@link #singularMethodName} (or the defaulted singular form).
     */
    boolean singular() default false;

    /**
     * Adds a {@code clearEntries()} method that empties the underlying
     * collection or map.
     */
    boolean clearable() default false;

    /**
     * (Maps only) Adds a {@code putEntryIfAbsent(K, Supplier<V>)} method that
     * lazily computes a value when the key is missing. No effect on non-map
     * fields.
     */
    boolean compute() default false;

    /**
     * Makes the whole-collection setters add to the container rather than
     * replace it, so repeated calls accumulate:
     * {@code tags("a").tags("b")} yields {@code [a, b]} instead of
     * {@code [b]}.
     *
     * <p>Covers every bulk shape - the varargs and {@link Iterable} forms on a
     * collection, and the whole-{@link Map} form on a map, which then puts every
     * entry instead of starting a new map. The single-element
     * {@link #singular} add always appended and is unaffected, as is
     * {@link #clearable}, which is how an accumulating builder empties the
     * container deliberately.
     *
     * <p>A declared initializer therefore survives a bulk call rather than being
     * discarded by it, since nothing replaces the container it seeded.
     *
     * <p>Off by default because replace is what a setter normally means and
     * what the built object's own field would hold. Turn it on when converting a
     * hand-written builder whose bulk setter was written as
     * {@code entries.forEach(this.entries::add)} - the two shapes compile
     * identically and differ only in what the second call does.
     */
    boolean append() default false;

}
