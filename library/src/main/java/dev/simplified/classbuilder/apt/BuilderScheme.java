package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.NamingStyle;

/**
 * Resolved names for the members a {@code @ClassBuilder} target generates
 * exactly once - the builder class and the methods that enter and leave it.
 * The sibling of {@link SetterScheme}, which covers the members generated once
 * per field.
 *
 * <p>Every component here is already final: inherited from the style where
 * unwritten, and empty where the author suppressed it. Downstream emitters
 * therefore test emptiness rather than carrying a second opt-out flag, which is
 * what let the {@code generateBuilder} / {@code generateFrom} /
 * {@code generateMutate} booleans retire - each duplicated its name attribute.
 *
 * <p>Deliberately free of javac, PSI, and {@link FieldSpec} references so both
 * modules can depend on it.
 *
 * @param type simple name of the generated builder class, never empty
 * @param builder name of the static factory returning a fresh builder, empty when suppressed
 * @param build name of the terminal method returning the constructed instance, never empty
 * @param from name of the static copy factory, empty when suppressed
 * @param toBuilder name of the instance seed method, empty when suppressed
 */
public record BuilderScheme(
    String type,
    String builder,
    String build,
    String from,
    String toBuilder
) {

    /**
     * Resolves a scheme from a style plus the raw {@code @BuilderNames}
     * attributes, in declaration order. Any argument that is unwritten takes
     * the style's name; one set to {@code NONE} resolves to the empty string.
     *
     * <p>{@code type} and {@code build} cannot be suppressed, so a {@code NONE}
     * on either falls back to the style rather than resolving empty. The
     * processor reports that as an error; keeping the name coherent means the
     * author sees that one diagnostic instead of a cascade from emitting a
     * member with no name.
     *
     * @param style the style supplying every unwritten name
     * @param targetSimpleName simple name of the annotated type
     * @param type the written {@code type} name, or {@code null}
     * @param builder the written {@code builder} name, or {@code null}
     * @param build the written {@code build} name, or {@code null}
     * @param from the written {@code from} name, or {@code null}
     * @param toBuilder the written {@code toBuilder} name, or {@code null}
     * @return the resolved scheme
     */
    public static BuilderScheme resolve(NamingStyle style, String targetSimpleName, String type,
                                        String builder, String build, String from, String toBuilder) {
        return new BuilderScheme(
            required(type, style.builderType(), targetSimpleName),
            optional(builder, style.builderMethod(), targetSimpleName),
            required(build, style.buildMethod(), targetSimpleName),
            optional(from, style.fromMethod(), targetSimpleName),
            optional(toBuilder, style.toBuilderMethod(), targetSimpleName)
        );
    }

    /** Resolves a scheme carrying nothing but the style's own names. */
    public static BuilderScheme of(NamingStyle style, String targetSimpleName) {
        return resolve(style, targetSimpleName, null, null, null, null, null);
    }

    private static String optional(String written, String fallback, String subject) {
        String pattern = NamePattern.inherit(written, fallback);
        return NamePattern.emits(pattern) ? NamePattern.expand(pattern, subject) : "";
    }

    private static String required(String written, String fallback, String subject) {
        String pattern = NamePattern.inherit(written, fallback);
        if (!NamePattern.emits(pattern)) pattern = fallback;
        return NamePattern.expand(pattern, subject);
    }

}
