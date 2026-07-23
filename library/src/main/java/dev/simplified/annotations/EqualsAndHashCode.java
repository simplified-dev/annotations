package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates {@code equals} and {@code hashCode} over a type's instance state.
 *
 * <p>Both members are generated together or not at all - a type with value
 * equality and an identity hash is broken by construction, so there is no
 * attribute that produces one without the other.
 *
 * <p>Members are taken in declaration order: instance fields for a class, the
 * components for a record. {@code static}, {@code transient}, {@code $}-named
 * and {@link Lazy} members are skipped, {@link EqualsExclude} removes one and
 * {@link EqualsInclude} adds one back.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;EqualsAndHashCode
 * public final class Palette {
 *     private final String name;
 *     private final byte[] swatches;             // compared by content
 *     &#64;EqualsExclude private final long loadedAt;
 * }
 * </code></pre>
 *
 * <h2>Arrays</h2>
 *
 * <p>An array field is the reason most of the hand-written pairs this replaces
 * exist. A one-dimensional primitive array is compared with
 * {@code java.util.Arrays.equals}; an array of references or an array of arrays
 * is compared with {@code java.util.Arrays.deepEquals}. The choice is made from
 * the member's own type, so it is right at any depth.
 *
 * <p>An array reached through a type argument - {@code List<byte[]>},
 * {@code Optional<byte[]>}, {@code Map<K, int[]>} - cannot be. The container
 * delegates to its element's {@code equals} and an array inherits identity
 * equality, so those members compare by reference and the compile reports a
 * warning naming the field. A plain array field of any depth is handled
 * correctly and is never warned about.
 *
 * <h2>Divergence from Lombok, deliberately</h2>
 *
 * <ul>
 *   <li><b>Records and enums.</b> Lombok refuses both. Records are supported
 *       here, and are the shape that needs this most - a record's implicit
 *       {@code equals} compares an array component by reference. An enum is
 *       rejected, because {@code Enum.equals} is {@code final} and already
 *       identity-based.</li>
 *   <li><b>{@link #identity()} defaults to {@link Identity#EXACT_CLASS}</b>
 *       rather than Lombok's unconditional {@code instanceof}.</li>
 *   <li><b>{@link #callSuper()} defaults to {@link CallSuper#AUTO}</b> rather
 *       than to dropping inherited state with a warning.</li>
 *   <li><b>{@link #useAccessors()} defaults to {@code false}</b>, inverting
 *       Lombok. A direct field read cannot be intercepted by an overridden
 *       accessor, and it keeps the emitted member independent of whether an
 *       accessor generator ran first.</li>
 * </ul>
 *
 * <p>A type that already declares either member is an error rather than a
 * silent skip. An annotation asking for an equality relation that then produces
 * none would leave the type with a relation nobody wrote down.
 *
 * @see ToString
 * @see EqualsExclude
 * @see EqualsInclude
 * @see CallSuper
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface EqualsAndHashCode {

    /** The relation deciding whether two objects are even candidates for equality. */
    @NotNull Identity identity() default Identity.EXACT_CLASS;

    /** Whether the generated members fold in the superclass's own. */
    @NotNull CallSuper callSuper() default CallSuper.AUTO;

    /**
     * Member names to compare, to the exclusion of every other. Mutually
     * exclusive with {@link #exclude()}, and an error on a name that matches
     * nothing.
     */
    @NotNull String[] of() default {};

    /**
     * Member names to skip. An error on a name that matches nothing, which is
     * the check that catches a rename leaving the attribute behind.
     */
    @NotNull String[] exclude() default {};

    /**
     * Whether {@code hashCode} memoizes its result in a {@code transient} field.
     *
     * <p>Only sound while every compared member is immutable, so a non-final one
     * is reported. Not available on a record, whose body cannot declare an
     * instance field to hold the memo.
     */
    boolean cacheHashCode() default false;

    /**
     * Whether members are read through an accessor the target declares rather
     * than directly.
     *
     * <p>Only an accessor the target <b>declares</b> and that returns the
     * member's own type is used; anything else falls back to the direct read
     * and says so.
     *
     * <p>Worth setting when a persistence or proxying framework subclasses the
     * type, since a field read on an uninitialised proxy sees the subclass's
     * own unpopulated slot rather than the loaded state. Worth knowing that it
     * puts every such accessor on a path reachable from {@code equals}, so an
     * accessor that initialises on read can make a comparison throw.
     */
    boolean useAccessors() default false;

    /** Whether to emit {@code @XContract} on the generated members. */
    boolean emitContracts() default true;

    /** Whether to emit {@link Generated} on the generated members. */
    boolean emitGenerated() default true;

    /**
     * The relation deciding whether two objects are candidates for equality.
     *
     * <p>All three ship because they fail differently and no single one serves
     * every shape.
     */
    enum Identity {

        /**
         * {@code getClass() != o.getClass()} - the default.
         *
         * <p>Always a valid equivalence relation and needs no cooperation from
         * any other class, so it is right wherever the other two are and right
         * in the places they are not. Its cost is narrow rather than wrong: a
         * proxy or a subclass that adds nothing compares unequal to the
         * instance it stands for.
         */
        EXACT_CLASS,

        /**
         * Bare {@code o instanceof Target}, with no cooperation hook.
         *
         * <p>Asymmetric the moment a subclass adds a value component, so it is
         * never a safe default - but it is the only relation that lets two
         * different implementations of one abstraction compare equal, which is
         * a thing some hierarchies deliberately want.
         */
        INSTANCE_OF,

        /**
         * {@code instanceof} plus a {@code protected boolean canEqual(Object)}
         * hook, which a state-adding subclass overrides to restore symmetry.
         *
         * <p>A cooperation protocol rather than a defence: it holds only while
         * every participant overrides the hook alongside {@code equals}, and a
         * subclass that overrides one without the other reinstates the
         * asymmetry silently. Two subclasses of an annotated base that neither
         * override compare <b>equal to each other</b>, which is the failure
         * {@link #EXACT_CLASS} does not have.
         *
         * <p>The hook is emitted only where a subclass can exist, and it is a
         * {@code protected} member on the type's public surface - a
         * compatibility commitment for a published artifact. A hook the type
         * declares itself is <b>reused</b> rather than replaced: writing one is
         * how a hierarchy opts into the protocol, so the generated
         * {@code equals} calls it and nothing is emitted beside it.
         */
        INSTANCE_OF_CANEQUAL

    }

}
