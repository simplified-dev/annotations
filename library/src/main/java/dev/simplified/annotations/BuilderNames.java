package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names for the members a {@link ClassBuilder} target generates exactly once,
 * however many fields it has - the builder class itself and the methods that
 * enter and leave it. Used only as the value of {@link ClassBuilder#builder()};
 * every attribute left unwritten inherits from {@link ClassBuilder#style()}.
 *
 * <p>Setting one of {@link #builder}, {@link #from}, or {@link #toBuilder} to
 * {@link #NONE} is how that entry point is suppressed. {@link #type} and
 * {@link #build} cannot be: a builder with no class to name, or no way to
 * finish, is not a builder.
 *
 * <p>Contrast {@link SetterNames}, whose members are generated once per field
 * and are therefore named by a pattern rather than a literal.
 *
 * <pre><code>
 * // Rename the terminal method, leave everything else at the style's default
 * &#64;ClassBuilder(builder = &#64;BuilderNames(build = "construct"))
 *
 * // Lombok surface, minus the static copy factory
 * &#64;ClassBuilder(style = NamingStyle.LOMBOK, builder = &#64;BuilderNames(from = BuilderNames.NONE))
 * </code></pre>
 *
 * @see NamingStyle
 * @see SetterNames
 * @see ClassBuilder#builder()
 */
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface BuilderNames {

    /**
     * Value meaning "take this name from {@link ClassBuilder#style()}". The
     * default of every attribute here.
     */
    String INHERIT = SetterNames.INHERIT;

    /**
     * Value suppressing a member, so nothing is generated for it. Not a valid
     * Java identifier, so it can never collide with a real name. Rejected on
     * {@link #type} and {@link #build}.
     */
    String NONE = SetterNames.NONE;

    /**
     * Simple name of the generated builder class. Lombok parity:
     * {@code builderClassName}.
     */
    @NotNull String type() default INHERIT;

    /**
     * The static factory on the target returning a fresh builder. Lombok
     * parity: {@code builderMethodName}.
     */
    @NotNull String builder() default INHERIT;

    /**
     * The terminal method on the builder returning the constructed instance.
     * Lombok parity: {@code buildMethodName}.
     */
    @NotNull String build() default INHERIT;

    /**
     * The static copy factory on the target seeding a builder from an existing
     * instance. Has no Lombok equivalent, so {@link NamingStyle#LOMBOK} keeps
     * it rather than suppressing it; pass {@link #NONE} to drop it.
     */
    @NotNull String from() default INHERIT;

    /**
     * The instance method on the target returning a builder seeded from
     * {@code this}. Lombok parity: {@code toBuilder}, renamed to
     * {@code mutate} by project convention outside {@link NamingStyle#LOMBOK}.
     */
    @NotNull String toBuilder() default INHERIT;

}
