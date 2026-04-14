package dev.sbs.annotation;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a fluent builder for the annotated type with opinionated defaults
 * and fine-grained overrides.
 *
 * <p>Applied to a {@link ElementType#TYPE type} (class, record, interface, or
 * abstract class) the builder is derived from the type's fields (or record
 * components or abstract accessors). Applied to a {@link ElementType#CONSTRUCTOR
 * constructor} or static {@link ElementType#METHOD factory method} the builder
 * is derived from the parameters of that member.
 *
 * <p>The companion annotation processor in this plugin emits a sibling
 * {@code <TypeName>Builder.java} file. Three bootstrap methods are expected on
 * the annotated type to wire the generated builder into the public API:
 * <ul>
 *   <li>a static factory returning a fresh builder - default name {@code builder}</li>
 *   <li>a static copy factory seeding a builder from an existing instance - default name {@code from}</li>
 *   <li>an instance method returning a builder seeded from {@code this} - default name {@code mutate}</li>
 * </ul>
 * The {@code ClassBuilderBootstrapInspection} flags any that are missing and
 * offers a quick-fix to insert all three.
 *
 * <h2>Per-field customisation</h2>
 * <ul>
 *   <li>{@link BuildFlag} - declare runtime constraints ({@code nonNull}, {@code notEmpty}, groups, regex, length)</li>
 *   <li>{@link Singular} - emit {@code addX} / {@code putX} / varargs / {@code Iterable} / {@code clearX} setters for collections</li>
 *   <li>{@link Negate} - emit an inverse boolean setter on a {@code boolean} field</li>
 *   <li>{@link Formattable} - emit a {@code @PrintFormat} string overload</li>
 *   <li>{@link BuilderDefault} - carry the field's initialiser into the builder as its starting value</li>
 *   <li>{@link BuilderIgnore} - exclude a single field from the builder</li>
 *   <li>{@link ObtainVia} - override how {@code from}/{@code mutate} reads the field from an existing instance</li>
 * </ul>
 *
 * <h2>Examples</h2>
 * <pre><code>
 * // Simplest case
 * &#64;ClassBuilder
 * public final class Shape {
 *     private final String name;
 *     private final int sides;
 * }
 *
 * // Record with a required field
 * &#64;ClassBuilder
 * public record User(&#64;BuildFlag(nonNull = true, notEmpty = true) String name, int age) { }
 *
 * // Interface - plugin generates ShapeImpl + ShapeBuilder
 * &#64;ClassBuilder(generateImpl = true)
 * public interface Shape {
 *     &#64;BuildFlag(nonNull = true) String name();
 * }
 *
 * // Builder on a static factory method
 * public final class Range {
 *     &#64;ClassBuilder(builderName = "RangeBuilder")
 *     public static Range of(int min, int max) { ... }
 * }
 *
 * // Custom naming
 * &#64;ClassBuilder(
 *     builderName = "MyBuilder",
 *     builderMethodName = "newBuilder",
 *     toBuilderMethodName = "toBuilder",
 *     methodPrefix = "set"
 * )
 * public final class Config { ... }
 * </code></pre>
 *
 * @see BuildFlag
 * @see Singular
 * @see Negate
 * @see Formattable
 * @see BuilderDefault
 * @see BuilderIgnore
 * @see ObtainVia
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD })
public @interface ClassBuilder {

    /**
     * The simple name of the generated builder class. Lombok parity:
     * {@code builderClassName}.
     */
    @NotNull String builderName() default "Builder";

    /**
     * The name of the static factory method returning a fresh builder. Lombok
     * parity: {@code builderMethodName}.
     */
    @NotNull String builderMethodName() default "builder";

    /**
     * The name of the {@code build} method on the generated builder. Lombok
     * parity: {@code buildMethodName}.
     */
    @NotNull String buildMethodName() default "build";

    /**
     * The name of the static copy factory seeding a builder from an existing
     * instance. Empty string suppresses the factory.
     */
    @NotNull String fromMethodName() default "from";

    /**
     * The name of the instance method returning a builder seeded from
     * {@code this}. Lombok parity: {@code toBuilder} (renamed to {@code mutate}
     * by default per project convention). Empty string suppresses the method.
     */
    @NotNull String toBuilderMethodName() default "mutate";

    /**
     * The setter method prefix. Booleans always use {@code "is"} unless this
     * attribute is set to a non-default value.
     */
    @NotNull String methodPrefix() default "with";

    /**
     * The access level of the generated bootstrap methods and the generated
     * builder class.
     */
    @NotNull AccessLevel access() default AccessLevel.PUBLIC;

    /**
     * Whether to generate the static {@code builder()} factory on the annotated
     * type.
     */
    boolean generateBuilder() default true;

    /**
     * Whether to generate the static copy factory on the annotated type.
     */
    boolean generateFrom() default true;

    /**
     * Whether to generate the instance {@code mutate()} method on the annotated
     * type.
     */
    boolean generateMutate() default true;

    /**
     * Whether the generated {@code build()} method should call
     * {@code BuildFlagValidator.validate(this)} before invoking the
     * constructor or factory.
     */
    boolean validate() default true;

    /**
     * Whether to emit {@code @XContract} annotations on generated methods so
     * IntelliJ data-flow analysis understands their null-return and
     * this-return shapes.
     */
    boolean emitContracts() default true;

    /**
     * For {@code interface} and {@code abstract class} targets only: whether
     * to generate a concrete {@code <TypeName>Impl} that the builder's
     * {@code build()} returns. Ignored for concrete class and record targets.
     */
    boolean generateImpl() default true;

    /**
     * The name of a static factory method on the annotated type that
     * {@code build()} should invoke instead of the constructor directly.
     * Useful for types that need build-time caching or extra validation.
     * Empty string (the default) invokes the constructor.
     */
    @NotNull String factoryMethod() default "";

    /**
     * Field names to exclude from the builder, in addition to the fields
     * always excluded ({@code static}, {@code transient}, and fields marked
     * with {@link BuilderIgnore}).
     */
    @NotNull String[] exclude() default { };

}
