package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a constructor taking one parameter per field that must be assigned,
 * in declaration order.
 *
 * <p>"Required" means <b>{@code final} without an initializer</b>, which is
 * exactly the set the compiler would otherwise reject as unassigned. A
 * {@code final} field that carries an initializer is already definitely
 * assigned and takes no parameter, so adding one initializer to a type shortens
 * its constructor - the resolved signature is rendered in the gutter for that
 * reason.
 *
 * <p>A nullness annotation on a mutable field does <b>not</b> make it required.
 * Nullness describes what the field may hold, not whether the constructor has
 * to be told; reading it the other way would silently lengthen the constructor
 * of every type that annotates its fields.
 *
 * <p>Selecting nothing is legal and yields a no-argument constructor, which is
 * the common way a type with only initialized state seals itself behind a
 * factory.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;RequiredArgsConstructor(access = AccessLevel.PRIVATE)
 * public final class PackStack {
 *     private final String id;                       // parameter
 *     private final List&lt;Layer&gt; layers = List.of();  // not a parameter
 * }
 * </code></pre>
 *
 * @see AllArgsConstructor
 * @see NoArgsConstructor
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface RequiredArgsConstructor {

    /**
     * Visibility of the generated constructor.
     *
     * <p>{@link AccessLevel#NONE} is rejected. On an {@code enum} the
     * constructor is forced {@code private} whatever is written here.
     */
    @NotNull AccessLevel access() default AccessLevel.PUBLIC;

    /**
     * Whether to emit {@link Generated} on the generated constructor so
     * coverage tools skip it.
     */
    boolean emitGenerated() default true;

}
