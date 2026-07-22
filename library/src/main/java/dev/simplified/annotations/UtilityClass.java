package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class that exists only to hold static members and must never be
 * instantiated.
 *
 * <p>Makes the class {@code final} and turns the constructor javac already
 * generated into a private one that throws, so the "utility class" intent is
 * enforced rather than merely documented.
 *
 * <h2>Divergence from Lombok, deliberately</h2>
 *
 * <p>Lombok's version also rewrites every member and nested type to
 * {@code static} implicitly. That is the half responsible for its sharp edges:
 * it changes the meaning of a declaration the author wrote, breaks {@code this},
 * and makes a nested type static without saying so. Here it is opt-in through
 * {@link #members()} and {@link #nestedTypes()}, and the default instead reports
 * an instance member as an error with a "Make static" fix in the IDE.
 *
 * <p>The reason is measured rather than aesthetic: across this workspace the
 * {@code final}-plus-constructor half is wanted everywhere and the implicit
 * {@code static} half has a single user, which writes it out loud.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;UtilityClass
 * public class StringUtil {
 *     public static String reverse(String value) { ... }
 * }
 * </code></pre>
 *
 * @see AccessLevel
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface UtilityClass {

    /** Whether to mark the class {@code final}. */
    boolean makeFinal() default true;

    /** How instance members are treated. */
    @NotNull Members members() default Members.REQUIRE_STATIC;

    /**
     * Whether nested types are made {@code static} too.
     *
     * <p>Off by default, and the only setting here that can change a nested
     * type's meaning: a nested class that captures its enclosing instance stops
     * compiling when it is made static, and one that does not gains an
     * unstated modifier.
     */
    boolean nestedTypes() default false;

    /**
     * Visibility of the throwing constructor.
     *
     * <p>{@link AccessLevel#PACKAGE} lets a same-package test reach the
     * constructor without hand-writing one, which under Lombok is a hard error.
     * {@link AccessLevel#NONE} is rejected - a class with no constructor at all
     * is not expressible in Java.
     */
    @NotNull AccessLevel constructorAccess() default AccessLevel.PRIVATE;

    /**
     * Message carried by the thrown exception. Empty yields
     * {@code "<SimpleName> is a utility class and cannot be instantiated"}.
     */
    @NotNull String message() default "";

    /**
     * Whether to emit {@code @XContract} on the generated constructor so IDE
     * data-flow knows it always throws.
     */
    boolean emitContracts() default true;

    /**
     * Whether to emit {@link Generated} on the generated constructor so
     * coverage tools skip it.
     */
    boolean emitGenerated() default true;

    /** How instance members of a utility class are treated. */
    enum Members {

        /**
         * Report an instance field or method as an error and offer a fix. Never
         * rewrites, so no declaration silently changes meaning.
         */
        REQUIRE_STATIC,

        /**
         * Add {@code static} to every instance field and method, which is
         * Lombok's behaviour.
         */
        MAKE_STATIC

    }

}
