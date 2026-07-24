package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a write accessor for a field, or for every field of a type.
 *
 * <p>Mirrors {@link Getter} in placement, precedence, naming and collision
 * handling. Three things differ, each because a write is not a read:
 *
 * <ul>
 *   <li>A {@code final} field is <b>skipped</b> under a type-level annotation
 *       and is an <b>error</b> under a field-level one. Reversing that rejects
 *       every mixed final and non-final class, which is the normal shape once
 *       identity columns are made final.</li>
 *   <li>Nullness annotations propagate onto the <b>parameter</b> rather than
 *       the return type.</li>
 *   <li>{@link Lazy} is incompatible: its storage is rewritten to a wrapper, so
 *       a plain assignment stops type-checking.</li>
 * </ul>
 *
 * <p>Setters return {@code void}. A chaining form returning {@code this} is
 * deliberately not offered - nothing in this workspace wants one, and adding it
 * later is a return-type swap plus a {@code return this}.
 *
 * @see Getter
 * @see NamingStyle
 * @see AccessLevel
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Setter {

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
     */
    @NotNull String name() default "";

    /** Field names to skip. Type level only. */
    @NotNull String[] exclude() default {};

    /** Whether to emit {@code @XContract} on the generated accessor. */
    boolean emitContracts() default true;

    /** Whether to emit {@link Generated} on the generated accessor. */
    boolean emitGenerated() default true;

}
