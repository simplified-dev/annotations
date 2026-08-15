package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Routes a builder setter's argument through a static method on the way into
 * the slot, so the stored value is a clamp, a normalisation, a parse or an
 * adaptation of what the caller passed rather than the argument verbatim.
 *
 * <p>The write-direction twin of {@link ObtainVia}, which redirects how
 * {@code from(T)} and {@code mutate()} read a value back off an instance.
 *
 * <h2>What the setter looks like</h2>
 * The named method takes exactly one parameter and returns something that can
 * supply the slot, and <b>its parameter type is the setter's parameter type</b>.
 * That one rule decides the shape:
 * <ul>
 *   <li><b>The slot's own type</b> - there is still one setter, and it assigns
 *       the method's result. A clamp or a normalisation is written this way, and
 *       every route into the slot goes through it, including the zero-argument
 *       {@code boolean} form, the {@link Negate} inverse and the
 *       {@link Formattable} overload.</li>
 *   <li><b>Any other type</b> - the ordinary setter stays, and this adds an
 *       overload beside it taking what the method accepts. A parse or an
 *       adaptation is written this way.</li>
 * </ul>
 *
 * <p>Repeatable, so several overloads can reach one slot. Two of them may not
 * take the same parameter type, and none may take a type the slot's own matrix
 * already emits - an {@code Optional<T>} slot, for instance, already has a
 * {@code T} setter.
 *
 * <p>{@code from(T)} and {@code mutate()} seed a slot by calling its setter, so
 * a direct transform runs on the value they read back as well. Write one that
 * gives the same answer applied twice - a clamp, a mask, an extension appended
 * only when absent - or the round trip moves the value.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;ClassBuilder
 * public final class WebPWriteOptions {
 *
 *     &#64;AssignVia(method = "clampQuality")
 *     private float quality;
 *
 *     &#64;AssignVia(method = "parseCidr")
 *     private IPv6Prefix sourcePrefix;
 *
 *     static float clampQuality(float quality) {
 *         return Math.clamp(quality, 0.0f, 1.0f);
 *     }
 *
 *     static IPv6Prefix parseCidr(String cidr) {
 *         return IPv6Prefix.parse(cidr);
 *     }
 * }
 *
 * // withQuality(float)      - clamped
 * // sourcePrefix(IPv6Prefix) - assigned
 * // sourcePrefix(String)     - parsed
 * </code></pre>
 *
 * <h2>Where it does not apply</h2>
 * A {@link Collector} slot rejects it: those setters copy element by element
 * into the container rather than assigning it, so there is no single value to
 * route. A {@link Lazy} slot rejects it too, that slot holding a
 * {@code Supplier<T>} rather than a {@code T}. So does a {@link BuilderSeed}
 * parameter, which emits no setter at all.
 *
 * @see ObtainVia
 * @see ClassBuilder
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Repeatable(AssignVia.List.class)
public @interface AssignVia {

    /**
     * The name of a {@code static} single-argument method on the target type
     * whose result is assigned to the slot. Exactly one method of that name may
     * take a single argument, its return type has to supply the slot, and its
     * parameter type is what the generated setter takes.
     */
    @NotNull String method();

    /**
     * Holder for several {@link AssignVia} declarations on one slot, filled in
     * by the compiler.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target({ ElementType.FIELD, ElementType.PARAMETER })
    @interface List {

        /**
         * The declared transforms, in source order.
         */
        @NotNull AssignVia[] value();

    }

}
