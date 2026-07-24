package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

/**
 * Named set of naming patterns for everything a {@link ClassBuilder} target
 * generates. Selecting a style sets the default for every generated name at
 * once; individual names are overridden with {@link SetterNames} and
 * {@link BuilderNames}, and the explicitly written attributes of
 * {@code @ClassBuilder} always win.
 *
 * <p>A style covers two groups. The per-field setters carry patterns whose
 * {@code {}} placeholder expands to the name the setter is built from - the
 * field name, the {@link Negate} stem, or the {@link Collector} singular -
 * capitalised unless it opens the pattern, so {@code {}} yields
 * {@code animated} while {@code is{}} yields {@code isAnimated}. The builder's
 * own members exist exactly once per target and carry plain names.
 *
 * @see SetterNames
 * @see BuilderNames
 * @see ClassBuilder#style()
 */
public enum NamingStyle {

    /**
     * Fluent setters with an {@code isX()} zero-arg convenience on booleans, and
     * this project's {@code Type.Builder} / {@code mutate()} builder naming.
     */
    SIMPLIFIED(
        new Setters("{}", "is{}", "add{}", "put{}", "put{}IfAbsent", "clear{}"),
        new Builder("Builder", "builder", "build", "from", "mutate"),
        Shared.BEAN_ACCESSORS
    ),

    /**
     * The exact surface Lombok {@code @Builder} and {@code @Singular} emit -
     * {@code Type.TypeBuilder}, {@code toBuilder()}, bare-name setters, bare
     * singular add/put, and no zero-arg boolean form. Lombok has no
     * put-if-absent analogue, so that role is suppressed; a target wanting it
     * back writes {@code @SetterNames(compute = "put{}IfAbsent")}.
     */
    LOMBOK(
        new Setters("{}", SetterNames.NONE, "{}", "{}", SetterNames.NONE, "clear{}"),
        new Builder("{}Builder", "builder", "build", "from", "toBuilder"),
        Shared.BEAN_ACCESSORS
    ),

    /**
     * JavaBean-style {@code setX} setters, otherwise identical to
     * {@link #SIMPLIFIED}. For a codebase whose convention is that setters
     * read as commands rather than as field names.
     */
    BEAN(
        new Setters("set{}", "is{}", "add{}", "put{}", "put{}IfAbsent", "clear{}"),
        new Builder("Builder", "builder", "build", "from", "mutate"),
        Shared.BEAN_ACCESSORS
    ),

    /**
     * Accessors named for the field alone - {@code label()} rather than
     * {@code getLabel()} - and otherwise identical to {@link #SIMPLIFIED}.
     * Replaces Lombok's {@code @Accessors(fluent = true)}, which is the only
     * form of that annotation this workspace has ever written.
     *
     * <p>It is a separate constant rather than a change to {@link #SIMPLIFIED}
     * because a bare {@link Getter} has to keep minting {@code getLabel()}: the
     * accessor surface and the builder-setter surface are independent choices,
     * and a codebase pairing fluent builder setters with bean accessors is the
     * common case here rather than a contradiction.
     */
    FLUENT(
        new Setters("{}", "is{}", "add{}", "put{}", "put{}IfAbsent", "clear{}"),
        new Builder("Builder", "builder", "build", "from", "mutate"),
        new Accessors("{}", "{}", "{}")
    );

    /** The six patterns generated once per field. */
    private record Setters(String set, String flag, String add, String put, String compute, String clear) { }

    /** The five names generated exactly once per target. */
    private record Builder(String type, String builder, String build, String from, String toBuilder) { }

    /** The three patterns {@link Getter} and {@link Setter} generate once per field. */
    private record Accessors(String get, String is, String set) { }

    /**
     * Holder for values shared between constants. A plain {@code static final}
     * field cannot be read from a constant's argument list - enum constants are
     * initialised first - so the shared value lives in a nested class, which is
     * initialised on demand.
     */
    private static final class Shared {

        /**
         * Every style but {@link NamingStyle#FLUENT}. Lombok's accessor surface
         * is bean-shaped whatever its builder surface does, so the three styles
         * that predate accessor support agree here rather than each picking.
         */
        private static final Accessors BEAN_ACCESSORS = new Accessors("get{}", "is{}", "set{}");

        private Shared() {
        }

    }

    private final Setters setters;
    private final Builder builder;
    private final Accessors accessors;

    NamingStyle(Setters setters, Builder builder, Accessors accessors) {
        this.setters = setters;
        this.builder = builder;
        this.accessors = accessors;
    }

    /** Pattern for the value-taking setter every field kind emits, booleans included. */
    public @NotNull String set() {
        return setters.set();
    }

    /** Pattern for the zero-arg boolean convenience setter and its {@link Negate} inverse. */
    public @NotNull String flag() {
        return setters.flag();
    }

    /** Pattern for the {@link Collector} single-element add on a collection field. */
    public @NotNull String add() {
        return setters.add();
    }

    /** Pattern for the {@link Collector} single-entry put on a map field. */
    public @NotNull String put() {
        return setters.put();
    }

    /** Pattern for the {@link Collector} put-if-absent on a map field. */
    public @NotNull String compute() {
        return setters.compute();
    }

    /** Pattern for the {@link Collector} clear on a collection or map field. */
    public @NotNull String clear() {
        return setters.clear();
    }

    /** Name of the generated builder class. */
    public @NotNull String builderType() {
        return builder.type();
    }

    /** Name of the static factory on the target returning a fresh builder. */
    public @NotNull String builderMethod() {
        return builder.builder();
    }

    /** Name of the terminal method on the builder returning the constructed instance. */
    public @NotNull String buildMethod() {
        return builder.build();
    }

    /** Name of the static copy factory seeding a builder from an existing instance. */
    public @NotNull String fromMethod() {
        return builder.from();
    }

    /** Name of the instance method returning a builder seeded from {@code this}. */
    public @NotNull String toBuilderMethod() {
        return builder.toBuilder();
    }

    /**
     * Pattern for a {@link Getter} on a non-boolean field.
     *
     * <p>Prefixed {@code accessor} to keep it clear of {@link #set()}, which is
     * the builder's setter role and a different surface entirely.
     */
    public @NotNull String accessorGet() {
        return accessors.get();
    }

    /** Pattern for a {@link Getter} on a {@code boolean} field. */
    public @NotNull String accessorIs() {
        return accessors.is();
    }

    /** Pattern for a {@link Setter} on any field. */
    public @NotNull String accessorSet() {
        return accessors.set();
    }

}
