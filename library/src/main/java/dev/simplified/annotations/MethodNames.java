package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Per-role naming patterns for the methods a {@link ClassBuilder} target
 * generates. Used only as the value of {@link ClassBuilder#names()}; every
 * attribute left unwritten inherits from {@link ClassBuilder#style()}.
 *
 * <p>A pattern contains exactly one {@code {}} placeholder, which expands to the
 * name the method is built from - the field name for {@link #set} and
 * {@link #clear}, the {@link Negate} stem for the inverse {@link #flag}, and the
 * {@link Collector} singular for {@link #add}, {@link #put}, and
 * {@link #compute}. The expansion is capitalised unless the placeholder opens
 * the pattern, which is what makes {@code {}} yield {@code animated} and
 * {@code is{}} yield {@code isAnimated}. Because the placeholder may sit
 * anywhere, a pattern expresses a suffix ({@code {}Value}) or a wrapped form
 * ({@code put{}IfAbsent}) as readily as a prefix.
 *
 * <pre><code>
 * // JavaBean setters, every other role left at the style's default
 * &#64;ClassBuilder(names = &#64;MethodNames(set = "set{}"))
 *
 * // Lombok surface, but with a differently named clear
 * &#64;ClassBuilder(style = NamingStyle.LOMBOK, names = &#64;MethodNames(clear = "reset{}"))
 *
 * // Drop the zero-arg boolean convenience entirely
 * &#64;ClassBuilder(names = &#64;MethodNames(flag = MethodNames.NONE))
 * </code></pre>
 *
 * @see NamingStyle
 * @see ClassBuilder#names()
 */
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface MethodNames {

    /**
     * Pattern value meaning "take this role from {@link ClassBuilder#style()}".
     * The default of every attribute here.
     */
    String INHERIT = "";

    /**
     * Pattern value suppressing a role, so no method is generated for it. Not a
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
