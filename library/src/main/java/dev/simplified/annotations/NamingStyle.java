package dev.simplified.annotations;

import org.jetbrains.annotations.NotNull;

/**
 * Named set of naming patterns for the methods a {@link ClassBuilder} target
 * generates. Selecting a style sets the default for every generated name at
 * once; individual roles are overridden with {@link MethodNames}, and the
 * explicitly written attributes of {@code @ClassBuilder} always win.
 *
 * <p>Each pattern contains at most one {@code {}} placeholder, which expands to
 * the name the method is built from - the field name, the {@link Negate} stem,
 * or the {@link Collector} singular. The placeholder's expansion is capitalised
 * unless it opens the pattern, so {@code {}} yields {@code animated} while
 * {@code is{}} yields {@code isAnimated}.
 *
 * @see MethodNames
 * @see ClassBuilder#style()
 */
public enum NamingStyle {

    /**
     * Fluent setters with an {@code isX()} zero-arg convenience on booleans, and
     * this project's {@code Type.Builder} / {@code mutate()} bootstrap naming.
     */
    SIMPLIFIED("{}", "is{}", "add{}", "put{}", "put{}IfAbsent", "clear{}", "Builder", "mutate"),

    /**
     * The exact surface Lombok {@code @Builder} and {@code @Singular} emit -
     * {@code Type.TypeBuilder}, {@code toBuilder()}, bare-name setters, bare
     * singular add/put, and no zero-arg boolean form. Lombok has no
     * put-if-absent analogue, so that role is suppressed; a target wanting it
     * back writes {@code @MethodNames(compute = "put{}IfAbsent")}.
     */
    LOMBOK("{}", MethodNames.NONE, "{}", "{}", MethodNames.NONE, "clear{}", "{}Builder", "toBuilder"),

    /**
     * JavaBean-style {@code setX} setters, otherwise identical to
     * {@link #SIMPLIFIED}. The migration target for the retired
     * {@code methodPrefix = "set"}.
     */
    BEAN("set{}", "is{}", "add{}", "put{}", "put{}IfAbsent", "clear{}", "Builder", "mutate");

    private final String set;
    private final String flag;
    private final String add;
    private final String put;
    private final String compute;
    private final String clear;
    private final String builderName;
    private final String toBuilderMethodName;

    NamingStyle(String set, String flag, String add, String put, String compute, String clear,
                String builderName, String toBuilderMethodName) {
        this.set = set;
        this.flag = flag;
        this.add = add;
        this.put = put;
        this.compute = compute;
        this.clear = clear;
        this.builderName = builderName;
        this.toBuilderMethodName = toBuilderMethodName;
    }

    /** Pattern for the value-taking setter every field kind emits, booleans included. */
    public @NotNull String set() {
        return set;
    }

    /** Pattern for the zero-arg boolean convenience setter and its {@link Negate} inverse. */
    public @NotNull String flag() {
        return flag;
    }

    /** Pattern for the {@link Collector} single-element add on a collection field. */
    public @NotNull String add() {
        return add;
    }

    /** Pattern for the {@link Collector} single-entry put on a map field. */
    public @NotNull String put() {
        return put;
    }

    /** Pattern for the {@link Collector} put-if-absent on a map field. */
    public @NotNull String compute() {
        return compute;
    }

    /** Pattern for the {@link Collector} clear on a collection or map field. */
    public @NotNull String clear() {
        return clear;
    }

    /** Pattern for the generated builder class, whose placeholder is the target's simple name. */
    public @NotNull String builderName() {
        return builderName;
    }

    /** Default name of the instance method seeding a builder from {@code this}. */
    public @NotNull String toBuilderMethodName() {
        return toBuilderMethodName;
    }

}
