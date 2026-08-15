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
 * <p>An interface target declares {@link BuildFlag} constraints on the accessor
 * rather than on a field, having none of its own. The generated
 * {@code <Name>Impl} carries the annotation onto the field it synthesises, so
 * the validator finds it on the instance {@code build()} returns.
 *
 * <h2>Constructor and factory targets</h2>
 * On a constructor or a {@code static} factory method the slots are that
 * member's parameters, and {@code build()} calls it. The builder still nests in
 * the enclosing type and is still entered through it - {@code Range.builder()},
 * not {@code new RangeBuilder()} - so a caller sees the same shape either way.
 * An instance method is rejected, having no receiver at {@code builder()} time,
 * as is a {@code void} one, having nothing for {@code build()} to return.
 *
 * <p>One type carries one builder, so the annotation belongs either on the type
 * or on exactly one of its members; both, or two members, is a compile error
 * rather than two colliding {@code Builder} classes.
 *
 * <p>What a slot is changes what applies to it:
 * <ul>
 *   <li>{@link Collector}, {@link Negate}, {@link Formattable} and
 *       {@link AssignVia} shape a parameter's setters exactly as they shape a
 *       field's.</li>
 *   <li>{@link BuilderSeed} moves a parameter onto {@code builder(...)} and
 *       drops its setter, which is how a required value is asked for at the
 *       entry point.</li>
 *   <li>{@link BuildFlag} stays on the built type's <em>fields</em>. The
 *       validator resolves the flagged fields of the instance {@code build()}
 *       produced, so a constraint written there is enforced whichever member
 *       constructed it.</li>
 *   <li>{@link BuilderDefault}, {@link BuilderIgnore} and {@link ObtainVia} do
 *       not apply: a parameter carries no initializer to retain, the annotated
 *       member requires every one of its parameters, and there is no instance to
 *       read a slot back off.</li>
 * </ul>
 *
 * <p>For that last reason the copy factory and {@code mutate()} are not
 * generated either - both seed every slot by reading a built object, and no
 * slot-to-accessor mapping exists when the slots are parameters. {@link #exclude}
 * and {@link #factoryMethod} are rejected on this form: the first names fields,
 * and the second redirects what {@code build()} calls, which the annotated
 * member already decides. {@link #retainInit} and {@link #generateCopyConstructor}
 * have nothing to act on and are ignored.
 *
 * <p>A constructor runs under its enclosing type's parameters and a
 * {@code static} factory under its own, which is also what {@code build()}
 * returns - the factory's declared return type rather than the enclosing type.
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
 *   <li>{@link AssignVia} - route a setter's argument through a static method
 *       on the way into the slot, either shaping the setter the field already
 *       has or adding an overload taking what that method accepts</li>
 *   <li>{@link Collector} - emit varargs / {@code Iterable} bulk setters on
 *       collection and map fields, with opt-in single-element add/put,
 *       {@code clearX}, and lazy {@code putXIfAbsent} overloads</li>
 *   <li>{@link Negate} - emit an inverse boolean setter on a {@code boolean} field</li>
 *   <li>{@link Formattable} - emit a {@code @PrintFormat} string overload</li>
 *   <li>{@link BuilderSeed} - on a constructor or factory parameter, move it
 *       onto {@code builder(...)} and emit no setter for it</li>
 * </ul>
 *
 * <h2>Naming</h2>
 * The generated surface splits by how often a member appears. The setters are
 * generated once per field and are named by a pattern whose {@code {}}
 * placeholder expands to the field name, {@link Negate} stem, or
 * {@link Collector} singular - six roles covering the value-taking setter, the
 * zero-arg boolean setter, and the collector's add, put, put-if-absent, and
 * clear. The builder class and the methods that enter and leave it are
 * generated exactly once and carry plain names.
 *
 * <p>{@link #style()} sets the whole surface at once; {@link #setters()} and
 * {@link #builder()} override individual names.
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
 * &#64;ClassBuilder
 * public interface Shape {
 *     &#64;BuildFlag(nonNull = true) String name();
 * }
 *
 * // Builder on a static factory method - slots are min and max
 * public final class Range {
 *     &#64;ClassBuilder(builder = &#64;BuilderNames(type = "RangeBuilder"))
 *     public static Range of(int min, int max) { ... }
 * }
 *
 * // Builder on a constructor, with a seeded entry point: Action.builder(String)
 * public final class Action {
 *     &#64;ClassBuilder
 *     Action(&#64;BuilderSeed String key, boolean enabled) { ... }
 * }
 *
 * // Custom naming
 * &#64;ClassBuilder(
 *     builder = &#64;BuilderNames(type = "MyBuilder", builder = "newBuilder", toBuilder = "toBuilder"),
 *     setters = &#64;SetterNames(set = "set{}")
 * )
 * public final class Config { ... }
 *
 * // A drop-in for Lombok &#64;Builder: ConfigBuilder, toBuilder(), bare-name setters
 * &#64;ClassBuilder(style = NamingStyle.LOMBOK)
 * public final class Config { ... }
 *
 * // Suppress the static copy factory
 * &#64;ClassBuilder(builder = &#64;BuilderNames(from = BuilderNames.NONE))
 * public final class Config { ... }
 * </code></pre>
 *
 * @see NamingStyle
 * @see SetterNames
 * @see BuilderNames
 * @see BuilderDefault
 * @see BuilderIgnore
 * @see BuilderSeed
 * @see BuildFlag
 * @see Collector
 * @see Negate
 * @see Formattable
 * @see ObtainVia
 * @see AssignVia
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD })
public @interface ClassBuilder {

    /**
     * The naming style every generated member takes its name from. Selecting a
     * style sets the default for the whole surface at once - the six per-field
     * setter roles, the builder class, and the methods that enter and leave it -
     * which is what makes {@code style = NamingStyle.LOMBOK} a complete drop-in
     * for Lombok {@code @Builder} naming rather than a set of overrides repeated
     * per type.
     *
     * <p>Anything written in {@link #setters()} or {@link #builder()} wins over
     * the style.
     *
     * @see NamingStyle
     */
    @NotNull NamingStyle style() default NamingStyle.SIMPLIFIED;

    /**
     * Overrides of the setter patterns {@link #style()} supplies, for the
     * members generated once per field. Every unwritten role inherits, so only
     * the roles that differ need naming.
     *
     * @see SetterNames
     */
    @NotNull SetterNames setters() default @SetterNames;

    /**
     * Overrides of the names {@link #style()} supplies for the members
     * generated exactly once - the builder class and the methods that enter and
     * leave it. Every unwritten name inherits, and
     * {@link BuilderNames#NONE} suppresses an entry point.
     *
     * @see BuilderNames
     */
    @NotNull BuilderNames builder() default @BuilderNames;

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
     * The access level of the generated builder's own no-arg constructor.
     * Defaults to package-private for the reason {@link #constructorAccess}
     * does one level down - it routes callers through the entry point rather
     * than past it, so {@code builder()} is the one way to obtain a builder and
     * Lombok's shape is matched.
     *
     * <p>Separate from {@link #access()}, which governs the builder class and
     * would otherwise decide this too: a builder class has to be visible to be
     * useful as a type, and that is a different question from whether
     * {@code new Target.Builder()} is an entry point. Widen it only to publish
     * that second way in deliberately.
     */
    @NotNull AccessLevel builderConstructorAccess() default AccessLevel.PACKAGE;

    /**
     * Whether the generated builder seeds each field from its declared
     * initializer rather than the JVM default. On by default, since a field
     * written as {@code String name = "anonymous"} almost always means that
     * value to survive into the builder.
     *
     * <p>Applies to every field of the type. An individual field overrides it
     * with {@link BuilderDefault}, whose setting always wins; fields carrying no
     * {@code @BuilderDefault} inherit this one. Fields without an initializer
     * are unaffected either way. A constructor or factory target ignores it,
     * a parameter having no initializer to retain.
     *
     * @see BuilderDefault
     */
    boolean retainInit() default true;

    /**
     * Whether the annotation processor should inject a protected copy
     * constructor ({@code protected T(Builder<?, ?> b)}) used by the injected
     * self-typed builder hierarchy on abstract targets. Concrete targets
     * outside a SuperBuilder chain ignore this attribute, as does a constructor
     * or factory target, which is never in such a chain. Set to {@code false}
     * when hand-writing a copy constructor with custom coercion logic.
     */
    boolean generateCopyConstructor() default true;

    /**
     * Whether the generated {@code build()} method should validate the
     * constructed instance against its {@link BuildFlag} constraints.
     *
     * <p>A target that no {@code @BuildFlag} reaches costs only a cached no-op
     * call - the validator resolves the flagged fields of the instance's
     * runtime class once and returns immediately when there are none.
     */
    boolean validate() default true;

    /**
     * Whether to emit {@code @XContract} annotations on generated methods so
     * IntelliJ data-flow analysis understands their null-return and
     * this-return shapes.
     */
    boolean emitContracts() default true;

    /**
     * Whether to emit {@link Generated} on the members and types this
     * annotation synthesises, so coverage tools exclude them from their
     * reports.
     *
     * <p>Independent of {@link #emitContracts()} - the two answer different
     * questions, and dropping {@code @XContract} from decompiled output is no
     * reason to lose coverage filtering.
     */
    boolean emitGenerated() default true;

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
     *
     * <p>A compile error on a constructor or factory target, where the annotated
     * member is already what {@code build()} calls.
     */
    @NotNull String factoryMethod() default "";

    /**
     * Field names to exclude from the builder, in addition to the fields
     * always excluded ({@code static}, {@code transient}, and fields marked
     * with {@link BuilderIgnore}).
     *
     * <p>A compile error on a constructor or factory target, whose slots are
     * parameters the annotated member requires - there is nothing a builder
     * could leave out.
     */
    @NotNull String[] exclude() default { };

}
