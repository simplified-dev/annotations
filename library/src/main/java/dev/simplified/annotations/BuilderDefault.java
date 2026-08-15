package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controls whether the generated builder seeds a field from its declared
 * initializer rather than the JVM default.
 *
 * <p>{@link ClassBuilder#retainInit} turns this on for every field of the type,
 * which is the default. This annotation is therefore most often written in its
 * opt-out form, {@code @BuilderDefault(false)}, to exclude one field from the
 * class-wide policy. Whatever a field declares wins over the class-level
 * setting; a field carrying no {@code @BuilderDefault} inherits it.
 *
 * <p>A field with no initializer expression is unaffected either way - there is
 * nothing to retain, so it starts at the JVM default regardless. {@link #provider}
 * is how such a field states a default anyway, and is the only way a record
 * component can: a component has no initializer to retain, so without it the
 * builder hands the canonical constructor a JVM zero. That is silent on a
 * {@code boolean}, where an unset slot and an explicit {@code false} are
 * indistinguishable at every point downstream.
 *
 * <p>The initializer is evaluated <b>fresh per builder instance</b>: a
 * {@code UUID.randomUUID()} default produces a new UUID for each builder, and a
 * {@code new ArrayList<>()} default produces a fresh mutable list. Under the
 * hood the processor injects a private static
 * {@code $default$<fieldName>()} method carrying the source expression, and the
 * generated builder's field default calls that method.
 *
 * <p>Because the expression is re-attributed inside a normal method body,
 * arbitrary Java works: method calls, constructor invocations, factory methods,
 * static field accesses, ternaries, switch expressions, anonymous classes,
 * lambdas (with or without parameters), and method or constructor references.
 *
 * <p>An initializer reading instance state - an instance field, an instance
 * method, {@code getClass()}, {@code this} - is supported too, but is applied
 * later: it cannot be evaluated when the builder is created, because no target
 * exists then, so it is computed in the generated constructor instead. The
 * observable difference is timing. A static-safe default is evaluated once per
 * builder, whereas an instance-referencing one is evaluated per
 * {@code build()}, so reusing a single builder for two builds yields two
 * values rather than a shared one.
 *
 * <p>A SuperBuilder chain behaves the same way, through the copy constructor
 * each link already carries. Every class computes its own defaults, so one
 * declared on an abstract root sees the concrete subclass under construction -
 * {@code getClass()} there names the child - and one declared on a link runs
 * after the parent's slots have been drained, so it may read them.
 *
 * <p>Every field shape whose setter simply assigns takes that path -
 * {@code boolean} (including a {@link Negate} pair), {@code Optional}, arrays,
 * {@link Formattable} strings, plain fields and {@link Lazy} ones alike. A
 * {@link Collector} container takes a merge instead, since its {@code add} /
 * {@code put} / {@code clear} setters need a real container to mutate while the
 * builder runs: the builder collects contributions into a scratch collection and
 * the constructor folds them onto the container the initializer returns. The
 * result is the same either way - the default seeds the collection,
 * single-element setters append onto it, and a wholesale replace or
 * {@code clear} discards it.
 *
 * <p>That holds for a custom container type too, whatever its shape - one with
 * no accessible constructor, or an interface, which has none at all. Nothing
 * has to construct the declared type: the built object holds exactly the
 * instance the initializer returned, subclass and all, rather than something
 * rebuilt from the declaration.
 *
 * <h2>Examples</h2>
 * <pre><code>
 * &#64;ClassBuilder
 * public class Widget {
 *
 *     // Inherits the class-wide policy - builder starts at "anonymous"
 *     String name = "anonymous";
 *
 *     // Opts out - builder starts at null despite the initializer
 *     &#64;BuilderDefault(false)
 *     String transientLabel = "ignored";
 * }
 *
 * // Class-wide opt-out, with one field opting back in
 * &#64;ClassBuilder(retainInit = false)
 * public class Sparse {
 *
 *     String untouched = "jvm default wins";
 *
 *     &#64;BuilderDefault
 *     String kept = "retained";
 * }
 *
 * // A record component, which has no initializer to retain
 * &#64;ClassBuilder
 * public record TgaWriteOptions(
 *     &#64;BuilderDefault(provider = "defaultRle") boolean rle
 * ) {
 *     private static boolean defaultRle() { return true; }
 * }
 * </code></pre>
 *
 * @see ClassBuilder#retainInit
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface BuilderDefault {

    /**
     * Whether to retain this field's declared initializer as the builder's
     * default. Defaults to {@code true} so the bare annotation reads as an
     * opt-in; pass {@code false} to opt a single field out of a class that
     * retains initializers.
     */
    boolean value() default true;

    /**
     * Names a method on the target that supplies this slot's default, for a
     * field or record component with no initializer to retain.
     *
     * <p>The method must be declared on the target, be {@code static}, take no
     * parameters, and return the slot's own type; anything else is reported at
     * this annotation, naming the method. Being a real declaration rather than a
     * source string is the point - it is type-checked where the author can see
     * it, instead of failing inside a generated body they cannot.
     *
     * <p>It is called exactly where a retained initializer's expression would
     * be, so the two behave identically downstream: evaluated fresh per builder,
     * copied before a {@link Collector} container's setters mutate it, and
     * discarded by a wholesale replace. Writing both is not a conflict - the
     * provider is the more specific statement and wins - but writing it beside
     * {@code value = false} is, since that asks for no default at all.
     */
    @NotNull String provider() default "";

}
