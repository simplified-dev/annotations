package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.NamingStyle;

/**
 * Resolved naming patterns for the accessors {@code @Getter} and
 * {@code @Setter} generate once per field, and the single place any of those
 * names is minted.
 *
 * <p>Third member of the naming trio beside {@link SetterScheme} and
 * {@link BuilderScheme}, with the same {@code resolve} / {@code of} factory
 * pair. Its one non-mechanical member is {@link #readName(String, boolean)},
 * which picks {@code is} over {@code get} in one place - that selection is the
 * whole reason a boolean needs its own pattern, and centralising it is what
 * stops the processor and the editor disagreeing about it.
 *
 * <p>Deliberately free of javac, PSI, and {@link FieldSpec} references so both
 * modules can depend on it.
 *
 * @param get pattern for the read accessor on a non-boolean field
 * @param is pattern for the read accessor on a boolean field
 * @param set pattern for the write accessor
 */
public record AccessorScheme(
    String get,
    String is,
    String set
) {

    /**
     * Resolves a scheme from a style plus a written name override.
     *
     * <p>A written {@code name} collapses the get-and-is choice, overriding
     * both read patterns at once. No site has ever wanted {@code get{}} for
     * objects and something other than {@code is{}} for booleans
     * independently, and two attributes would only invite the mismatch.
     *
     * @param style the style supplying every unwritten pattern
     * @param name the written name pattern, or {@code null}
     * @return the resolved scheme
     */
    public static AccessorScheme resolve(NamingStyle style, String name) {
        return new AccessorScheme(
            NamePattern.inherit(name, style.accessorGet()),
            NamePattern.inherit(name, style.accessorIs()),
            NamePattern.inherit(name, style.accessorSet())
        );
    }

    /** Resolves a scheme carrying nothing but the style's own patterns. */
    public static AccessorScheme of(NamingStyle style) {
        return resolve(style, null);
    }

    /**
     * Name of the read accessor for a field.
     *
     * @param field the field name
     * @param isBoolean whether the field's declared type is {@code boolean}
     * @return the accessor name
     */
    public String readName(String field, boolean isBoolean) {
        return NamePattern.expand(isBoolean ? is : get, field);
    }

    /**
     * Name of the write accessor for a field.
     *
     * @param field the field name
     * @return the accessor name
     */
    public String writeName(String field) {
        return NamePattern.expand(set, field);
    }

    /**
     * Every spelling a read accessor could carry for this field, most specific
     * first, for probing what the author already declared.
     *
     * <p>Read by the {@code from(T)} / {@code mutate()} seeding ladder, which
     * has to find a hand-written accessor whatever convention the target uses
     * rather than only the one this scheme would mint.
     *
     * @param field the field name
     * @param isBoolean whether the field's declared type is {@code boolean}
     * @return candidate accessor names, without duplicates
     */
    public java.util.List<String> readCandidates(String field, boolean isBoolean) {
        java.util.List<String> out = new java.util.ArrayList<>(3);
        out.add(readName(field, isBoolean));
        if (isBoolean) addIfAbsent(out, NamePattern.expand("get{}", field));
        addIfAbsent(out, field);
        return out;
    }

    private static void addIfAbsent(java.util.List<String> out, String candidate) {
        if (!out.contains(candidate)) out.add(candidate);
    }

}
