package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a read accessor for a field, or for every field of a type.
 *
 * <p>Written on a type it fans out over the instance fields that type declares;
 * written on a field it governs that field alone and overrides whatever the
 * enclosing type said. {@link AccessLevel#NONE} is how one field opts out of a
 * type-level annotation.
 *
 * <p>The fan-out passes over static fields, which hold the class's own state
 * rather than any instance's - otherwise every constant in an annotated class
 * publishes an accessor as a side effect of annotating the class. Writing the
 * annotation on a static field is how a static accessor is asked for, and that
 * route mints one.
 *
 * <p>Naming comes from {@link NamingStyle}, so the accessor and the builder
 * setter for the same field are minted by one mechanism rather than two that
 * have to agree. A bare {@code @Getter} produces {@code getLabel()} and
 * {@code isAnimated()}; {@link NamingStyle#FLUENT} produces {@code label()} and
 * {@code animated()}; {@link #name()} substitutes a different pattern for one
 * field. It is a pattern and not a literal, so the field's name is always part
 * of the accessor's.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;Getter
 * public class BlockOptions {
 *     private final String blockId;              // getBlockId()
 *     private final boolean animated;            // isAnimated()
 *     &#64;Getter(AccessLevel.NONE) private int cache;   // nothing
 * }
 * </code></pre>
 *
 * <p>A field the author already has an accessor for is left alone: collision is
 * decided on name and argument count, so a hand-written accessor always wins
 * and a build never breaks by adopting this annotation.
 *
 * @see Setter
 * @see NamingStyle
 * @see AccessLevel
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Getter {

    /**
     * Visibility of the generated accessor, or {@link AccessLevel#NONE} to
     * generate none.
     */
    @NotNull AccessLevel value() default AccessLevel.PUBLIC;

    /** Naming style supplying the accessor pattern. */
    @NotNull NamingStyle style() default NamingStyle.SIMPLIFIED;

    /**
     * Name pattern overriding the style's, where {@code {}} expands to the
     * field name. Empty inherits from {@link #style()}.
     *
     * <p>Overrides the boolean and non-boolean patterns together.
     *
     * <p>The placeholder is mandatory and a value without one is rejected: the
     * pattern is applied once per field, so a literal would give every field
     * under a type-level annotation the same method name. An accessor spelled
     * differently from its field is therefore not expressible here and stays
     * hand-written.
     */
    @NotNull String name() default "";

    /**
     * Field names to skip. Type level only - a field-level
     * {@code @Getter(AccessLevel.NONE)} is the per-field opt-out and reads
     * better at the declaration it affects.
     */
    @NotNull String[] exclude() default {};

    /** Whether to emit {@code @XContract} on the generated accessor. */
    boolean emitContracts() default true;

    /** Whether to emit {@link Generated} on the generated accessor. */
    boolean emitGenerated() default true;

}
