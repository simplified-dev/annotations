package dev.simplified.annotations;

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
 * <p>The companion annotation processor in this plugin injects a
 * {@code public static class Builder} directly into the annotated type via
 * javac AST mutation, along with three bootstrap methods that wire the
 * generated builder into the public API:
 * <ul>
 *   <li>a static factory returning a fresh builder - default name {@code builder}</li>
 *   <li>a static copy factory seeding a builder from an existing instance - default name {@code from}</li>
 *   <li>an instance method returning a builder seeded from {@code this} - default name {@code mutate}</li>
 * </ul>
 * If the target already declares any of those methods by name and arity,
 * injection is skipped for that name and the user-supplied version wins -
 * a compiler {@code NOTE} is emitted for visibility.
 *
 * <p>An interface target gets its builder as a sibling
 * {@code <Name>Builder.java} (plus {@code <Name>Impl.java}), there being no
 * in-source surface for a nested class on an interface body. The bootstrap
 * methods still land on the interface itself, so it is entered exactly like a
 * class - {@code Shape.builder()} rather than {@code new ShapeBuilder<>()}.
 * {@code builder} and {@code from} are {@code static} interface methods and
 * {@code mutate} is a {@code default}, both legal since Java 8, so implementors
 * need no change.
 *
 * <h2>Generic targets</h2>
 * A target may declare type parameters, on any supported shape. The generated
 * builder re-declares them, since a nested {@code Builder} is {@code static}
 * and an interface's sibling builder is a separate top-level class - neither
 * can see the enclosing type's variables. Bounds carry over, and every static
 * member mentioning a parameter takes its own copy so the call site infers it
 * back. On a SuperBuilder chain the target's parameters lead the self-typed
 * pair ({@code Builder<V, T extends Box<V>, B extends Builder<V, T, B>>}) and a
 * concrete link reproduces the arguments it passes up.
 *
 * <p>{@code builder()} is a generic static method, so a chained call has
 * nothing to infer from and needs the explicit witness -
 * {@code Crate.<String>builder().item("x").build()}. The bare form infers
 * {@code Object}, which still assigns to a parameterised local but only under
 * an unchecked warning. {@code from(T)} infers from its argument and needs no
 * witness.
 *
 * <h2>Per-field customisation</h2>
 * <ul>
 *   <li>{@link BuilderDefault} - opt a single field in or out of carrying its
 *       declared initialiser into the builder, overriding {@link #retainInit()}</li>
 *   <li>{@link BuilderIgnore} - exclude a single field from the builder</li>
 *   <li>{@link BuildFlag} - runtime constraints enforced in the generated
 *       {@code build()}</li>
 *   <li>{@link ObtainVia} - override how {@code from}/{@code mutate} reads the
 *       field off an existing instance</li>
 *   <li>{@link Collector} - emit varargs / {@code Iterable} bulk setters on
 *       collection and map fields, with opt-in single-element add/put,
 *       {@code clearX}, and lazy {@code putXIfAbsent} overloads</li>
 *   <li>{@link Negate} - emit an inverse boolean setter on a {@code boolean} field</li>
 *   <li>{@link Formattable} - emit a {@code @PrintFormat} string overload</li>
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
 * @see BuilderDefault
 * @see BuilderIgnore
 * @see BuildFlag
 * @see Collector
 * @see Negate
 * @see Formattable
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
    @NotNull String methodPrefix() default "";

    /**
     * The access level of the generated bootstrap methods and the generated
     * builder class.
     */
    @NotNull AccessLevel access() default AccessLevel.PUBLIC;

    /**
     * The access level of the synthesised all-args constructor. Defaults to
     * package-private, matching the implicit constructor Lombok {@code @Builder}
     * supplies, so callers are routed through {@code build()} and its
     * {@code @BuildFlag} validation rather than instantiating the type directly.
     * Independent of {@link #access()}, which governs the builder class and the
     * bootstrap methods.
     */
    @NotNull AccessLevel constructorAccess() default AccessLevel.PACKAGE;

    /**
     * Whether the generated builder seeds each field from its declared
     * initializer rather than the JVM default. On by default, since a field
     * written as {@code String name = "anonymous"} almost always means that
     * value to survive into the builder.
     *
     * <p>Applies to every field of the type. An individual field overrides it
     * with {@link BuilderDefault}, whose setting always wins; fields carrying no
     * {@code @BuilderDefault} inherit this one. Fields without an initializer
     * are unaffected either way.
     *
     * @see BuilderDefault
     */
    boolean retainInit() default true;

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
     * Whether the annotation processor should inject a protected copy
     * constructor ({@code protected T(Builder<?, ?> b)}) used by the injected
     * self-typed builder hierarchy on abstract targets. Concrete targets
     * outside a SuperBuilder chain ignore this attribute. Set to {@code false}
     * when hand-writing a copy constructor with custom coercion logic.
     */
    boolean generateCopyConstructor() default true;

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
     * For {@code interface} targets only: whether to generate a concrete
     * {@code <TypeName>Impl} that the builder's {@code build()} returns.
     * Ignored for concrete class, record, and abstract-class targets.
     *
     * <p>When set to {@code false}, {@link #factoryMethod()} must also be
     * set; {@code build()} then delegates to that static factory instead of
     * {@code new <TypeName>Impl(...)}. The factory's return type must be the
     * interface type (compiler-enforced at the call site, not at annotation-
     * processing time). Setting both to empty on an interface target is a
     * compile error.
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
