package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Expands the setter matrix on a {@link java.util.Collection Collection} or
 * {@link java.util.Map Map} field in a {@link ClassBuilder}-annotated type.
 *
 * <p>Without this annotation, a {@code List<T>} / {@code Set<T>} /
 * {@code Map<K,V>} field gets a single whole-collection replace setter
 * ({@code withEntries(List<T>)}, etc). With this annotation, the builder
 * additionally emits bulk-style overloads and - opt-in via the attributes
 * below - single-element mutators.
 *
 * <h2>Always generated when the annotation is present</h2>
 * <ul>
 *   <li><b>List / Set / Iterable</b>:
 *     {@code withEntries(T... entries)},
 *     {@code withEntries(Iterable<T> entries)}</li>
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
 * </ul>
 *
 * <p>Use {@link #singularMethodName} to override the inflected single-element
 * name (default: field name minus trailing plural inflection - {@code entries}
 * becomes {@code entry}, {@code boxes} becomes {@code box}, {@code tags}
 * becomes {@code tag}).
 *
 * <h2>Examples</h2>
 * <pre><code>
 * // Bulk-only: withEntries(T...) + withEntries(Iterable&lt;T&gt;)
 * &#64;Collector List&lt;String&gt; entries;
 *
 * // Bulk + single-element add
 * &#64;Collector(singular = true) List&lt;String&gt; tags;
 * // withTags(String...), withTags(Iterable&lt;String&gt;), addTag(String)
 *
 * // Bulk + clear + custom singular name
 * &#64;Collector(clearable = true, singularMethodName = "flavor") List&lt;String&gt; flavors;
 * // withFlavors(String...), withFlavors(Iterable&lt;String&gt;), clearFlavors()
 *
 * // Map: opt-in put + lazy compute
 * &#64;Collector(singular = true, compute = true) Map&lt;String, Integer&gt; counts;
 * // withCounts(Map), putCount(String, Integer), putCountIfAbsent(String, Supplier&lt;Integer&gt;)
 * </code></pre>
 *
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
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

}
