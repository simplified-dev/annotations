package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Naming patterns for the setters a {@link ClassBuilder} target generates once
 * per field. Written as the value of {@link ClassBuilder#setters()} it sets the
 * whole target's; written on a single field, record component or constructor
 * parameter it overrides the target's for that one slot. Every attribute left
 * unwritten inherits - from {@link ClassBuilder#style()} at the target, and from
 * the target's resolved patterns at a slot.
 *
 * <p>The per-slot form is what lets one type pair setters that do not share a
 * shape: a {@code boolean lossless} spelled {@code isLossless(boolean)} beside a
 * {@code float quality} spelled {@code withQuality(float)}. Without it the
 * target's single pattern has to fit every field it has, and a type whose fields
 * disagree keeps its whole builder hand-written.
 *
 * <p>A pattern contains exactly one {@code {}} placeholder, which expands to the
 * name the setter is built from - the field name for {@link #set} and
 * {@link #clear}, the {@link Negate} stem for the inverse {@link #flag}, and the
 * {@link Collector} singular for {@link #add}, {@link #put}, and
 * {@link #compute}. The expansion is capitalised unless the placeholder opens
 * the pattern, which is what makes {@code {}} yield {@code animated} and
 * {@code is{}} yield {@code isAnimated}. Because the placeholder may sit
 * anywhere, a pattern expresses a suffix ({@code {}Value}) or a wrapped form
 * ({@code put{}IfAbsent}) as readily as a prefix.
 *
 * <p>The placeholder is mandatory on the target, where the pattern fans out over
 * every field and one without it would give them all the same method name. On a
 * single slot it expands exactly once, so a placeholder-free literal is simply
 * that setter's name - and the only way to spell one that does not contain its
 * field's name at all. Contrast {@link BuilderNames}, whose members exist
 * exactly once per target and never require it.
 *
 * <pre><code>
 * // JavaBean setters, every other role left at the style's default
 * &#64;ClassBuilder(setters = &#64;SetterNames(set = "set{}"))
 *
 * // Lombok surface, but with a differently named clear
 * &#64;ClassBuilder(style = NamingStyle.LOMBOK, setters = &#64;SetterNames(clear = "reset{}"))
 *
 * // Drop the zero-arg boolean convenience entirely
 * &#64;ClassBuilder(setters = &#64;SetterNames(flag = SetterNames.NONE))
 *
 * // One field spelled differently from the rest of its type
 * &#64;ClassBuilder(setters = &#64;SetterNames(set = "with{}"))
 * public final class WebPWriteOptions {
 *
 *     float quality;                        // withQuality(float)
 *
 *     &#64;SetterNames(set = "is{}")
 *     boolean lossless;                     // isLossless(boolean)
 * }
 * </code></pre>
 *
 * @see NamingStyle
 * @see BuilderNames
 * @see ClassBuilder#setters()
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
public @interface SetterNames {

    /**
     * Pattern value meaning "take this role from whatever encloses it" - the
     * target's {@link ClassBuilder#style()} on the target, and the target's
     * resolved pattern on a single slot. The default of every attribute here.
     */
    String INHERIT = "";

    /**
     * Pattern value suppressing a role, so no setter is generated for it. Not a
     * valid Java identifier, so it can never collide with a real pattern.
     * Rejected on {@link #set}, which has no other way to assign the field.
     */
    String NONE = "-";

    /**
     * The value-taking setter every field kind emits, booleans included. A
     * {@code boolean animated} field yields {@code animated(boolean)} under
     * {@code "{}"} and {@code isAnimated(boolean)} under {@code "is{}"}.
     */
    @NotNull String set() default INHERIT;

    /**
     * The zero-arg boolean convenience setter and, on a {@link Negate} field,
     * its inverse. Suppressed rather than renamed when set to {@link #NONE}.
     */
    @NotNull String flag() default INHERIT;

    /** The {@link Collector} single-element add on a collection field. */
    @NotNull String add() default INHERIT;

    /** The {@link Collector} single-entry put on a map field. */
    @NotNull String put() default INHERIT;

    /** The {@link Collector} put-if-absent on a map field. */
    @NotNull String compute() default INHERIT;

    /** The {@link Collector} clear on a collection or map field. */
    @NotNull String clear() default INHERIT;

}
