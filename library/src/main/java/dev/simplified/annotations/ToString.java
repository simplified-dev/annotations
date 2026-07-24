package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates {@code toString} over a type's instance state.
 *
 * <p>Mirrors {@link EqualsAndHashCode} in member selection, in
 * {@link #callSuper()}, {@link #useAccessors()}, {@link #of()} /
 * {@link #exclude()} and in the flat marker pair - one component resolves both,
 * so the two annotations cannot disagree about which members a type is made of.
 * Three things differ, each because a diagnostic is not a contract:
 *
 * <ul>
 *   <li><b>{@code transient} members are kept.</b> They are still state worth
 *       seeing in a debugger, where {@link EqualsAndHashCode} drops them
 *       because they are by definition not part of the value.</li>
 *   <li><b>A type that already declares {@code toString} is left alone</b> with
 *       a note, rather than reported as an error. Nothing depends on this
 *       member the way a collection depends on the equality pair.</li>
 *   <li><b>{@link ToStringInclude} carries {@link ToStringInclude#name()} and
 *       {@link ToStringInclude#rank()}</b>, which rename and reorder what is
 *       printed. Both are visible in the output; the same attributes on the
 *       equality pair would hide a rule the emitted hash depended on.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;ToString
 * public class ServerPing {
 *     private String motd;
 *     private int players;
 *     &#64;ToStringExclude private String favicon;   // a multi-kilobyte data URI
 * }
 * // ServerPing[motd=Hello, players=12]
 * </code></pre>
 *
 * <p>A member need not be a field. A zero-arg non-{@code void} instance method
 * carrying {@link ToStringInclude} joins the selection as a printed term, which
 * is how a <b>derived</b> value - one with no backing field to select - appears
 * beside the state it is derived from.
 *
 * <p>A class and a record admit that. An interface whose implementation
 * {@link ClassBuilder} emits does not, and reports the marker instead: that
 * class holds a field only for an abstract zero-arg accessor, so there is no
 * member for a derived method to become.
 *
 * <p>An array member prints through {@code java.util.Arrays.toString} or
 * {@code deepToString} rather than as an identity hash. There is no cycle
 * detection: a bidirectional graph recurses until the stack is exhausted, the
 * same as every generator of this shape.
 *
 * @see EqualsAndHashCode
 * @see ToStringExclude
 * @see ToStringInclude
 * @see CallSuper
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ToString {

    /** Whether the generated member folds in the superclass's own. */
    @NotNull CallSuper callSuper() default CallSuper.AUTO;

    /** Whether each value is prefixed with its member name. */
    boolean includeFieldNames() default true;

    /** The rendered shape of the output. */
    @NotNull Style style() default Style.SIMPLIFIED;

    /**
     * Member names to print, to the exclusion of every other. Mutually
     * exclusive with {@link #exclude()}, and an error on a name that matches
     * nothing.
     *
     * <p>A member name is a field name, or the name of a method already
     * carrying {@link ToStringInclude}. The marker is what makes a method a
     * member, so neither attribute can reach one without it.
     */
    @NotNull String[] of() default {};

    /**
     * Member names to skip. An error on a name that matches nothing.
     *
     * <p>Names a field or a {@link ToStringInclude}-carrying method, the same
     * as {@link #of()}.
     */
    @NotNull String[] exclude() default {};

    /**
     * Whether members are read through an accessor the target declares rather
     * than directly.
     *
     * <p>Only an accessor declared by the target itself and returning the
     * member's own type is used; anything else falls back to the direct read.
     */
    boolean useAccessors() default false;

    /** Whether to emit {@code @XContract} on the generated member. */
    boolean emitContracts() default true;

    /** Whether to emit {@link Generated} on the generated member. */
    boolean emitGenerated() default true;

    /** The rendered shape of a generated {@code toString}. */
    enum Style {

        /**
         * {@code Name[a=1, b=2]} - the default, and the shape a record and an
         * interface's generated implementation already print, so one library
         * does not ship two.
         */
        SIMPLIFIED,

        /** {@code Name(a=1, b=2)} - Lombok's shape, for an output a consumer already parses. */
        LOMBOK

    }

}
