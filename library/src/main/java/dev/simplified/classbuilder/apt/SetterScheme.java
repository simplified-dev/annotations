package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.NamingStyle;

/**
 * Resolved naming patterns for the setters a {@code @ClassBuilder} target
 * generates once per field, and the single place any of those names is minted.
 *
 * <p>Both pipelines resolve one of these and share it: the annotation processor
 * from annotation mirrors, the IDE from PSI. Holding the expansion here is what
 * keeps the editor's synthesised members byte-identical to what javac emits -
 * the two used to carry private copies of the naming rules and could drift.
 *
 * <p>Deliberately free of javac, PSI, and {@link FieldSpec} references so both
 * modules can depend on it.
 *
 * @param set pattern for the value-taking setter, booleans included
 * @param flag pattern for the zero-arg boolean setter and its inverse
 * @param add pattern for the single-element collection add
 * @param put pattern for the single-entry map put
 * @param compute pattern for the map put-if-absent
 * @param clear pattern for the collection or map clear
 */
public record SetterScheme(
    String set,
    String flag,
    String add,
    String put,
    String compute,
    String clear
) {

    /**
     * Resolves a scheme from a style plus the raw {@code @SetterNames}
     * attributes, in declaration order. Any argument that is unwritten takes
     * the style's pattern.
     *
     * @param style the style supplying every unwritten role
     * @param set the written {@code set} pattern, or {@code null}
     * @param flag the written {@code flag} pattern, or {@code null}
     * @param add the written {@code add} pattern, or {@code null}
     * @param put the written {@code put} pattern, or {@code null}
     * @param compute the written {@code compute} pattern, or {@code null}
     * @param clear the written {@code clear} pattern, or {@code null}
     * @return the resolved scheme
     */
    public static SetterScheme resolve(NamingStyle style, String set, String flag, String add,
                                       String put, String compute, String clear) {
        return new SetterScheme(
            NamePattern.inherit(set, style.set()),
            NamePattern.inherit(flag, style.flag()),
            NamePattern.inherit(add, style.add()),
            NamePattern.inherit(put, style.put()),
            NamePattern.inherit(compute, style.compute()),
            NamePattern.inherit(clear, style.clear())
        );
    }

    /** Resolves a scheme carrying nothing but the style's own patterns. */
    public static SetterScheme of(NamingStyle style) {
        return resolve(style, null, null, null, null, null, null);
    }

    /**
     * Resolves one slot's scheme over the target's, for a {@code @SetterNames}
     * written on a field, record component or parameter.
     *
     * <p>Unwritten roles inherit from {@code base} rather than from the style,
     * which is the composition that makes the override readable: a target
     * spelling every setter {@code with{}} and one field spelling itself
     * {@code is{}} keeps {@code with{}} for that field's other five roles.
     *
     * @param base the target's resolved scheme
     * @param set the written {@code set} pattern, or {@code null}
     * @param flag the written {@code flag} pattern, or {@code null}
     * @param add the written {@code add} pattern, or {@code null}
     * @param put the written {@code put} pattern, or {@code null}
     * @param compute the written {@code compute} pattern, or {@code null}
     * @param clear the written {@code clear} pattern, or {@code null}
     * @return the resolved scheme for that slot
     */
    public static SetterScheme override(SetterScheme base, String set, String flag, String add,
                                        String put, String compute, String clear) {
        return new SetterScheme(
            NamePattern.inherit(set, base.set()),
            NamePattern.inherit(flag, base.flag()),
            NamePattern.inherit(add, base.add()),
            NamePattern.inherit(put, base.put()),
            NamePattern.inherit(compute, base.compute()),
            NamePattern.inherit(clear, base.clear())
        );
    }

    /**
     * Whether the value-taking setter is generated. Always true for a valid
     * scheme; suppressing it leaves a field with no way to be assigned, which
     * the processor rejects.
     */
    public boolean emitsSet() {
        return NamePattern.emits(set);
    }

    /** Whether the zero-arg boolean setter and its inverse are generated. */
    public boolean emitsFlag() {
        return NamePattern.emits(flag);
    }

    /** Whether the single-element collection add is generated. */
    public boolean emitsAdd() {
        return NamePattern.emits(add);
    }

    /** Whether the single-entry map put is generated. */
    public boolean emitsPut() {
        return NamePattern.emits(put);
    }

    /** Whether the map put-if-absent is generated. */
    public boolean emitsCompute() {
        return NamePattern.emits(compute);
    }

    /** Whether the collection or map clear is generated. */
    public boolean emitsClear() {
        return NamePattern.emits(clear);
    }

    /**
     * Name of the value-taking setter for a field.
     *
     * <p>A {@code boolean} subject expands through
     * {@link NamePattern#booleanSubject}, so a field already named {@code isX}
     * under a pattern that prefixes {@code is} does not double it. That is why
     * the flag is asked for rather than inferred: the same field name on a
     * {@code String} means something else entirely, and stripping there would
     * rename an unrelated accessor.
     *
     * @param subject the field name, or the {@code @Negate} stem for the inverse
     * @param isBoolean whether the slot's declared type is {@code boolean}
     * @return the setter name
     */
    public String setName(String subject, boolean isBoolean) {
        return NamePattern.expand(set, isBoolean ? NamePattern.booleanSubject(set, subject) : subject);
    }

    /**
     * Name of the zero-arg boolean setter for a field or its negate stem.
     *
     * <p>Takes no flag: this role exists only on a {@code boolean}, so the
     * subject is always one and always strips.
     *
     * @param subject the field name, or the {@code @Negate} stem for the inverse
     * @return the setter name, or {@code null} when the role is suppressed
     */
    public String flagName(String subject) {
        return emitsFlag()
            ? NamePattern.expand(flag, NamePattern.booleanSubject(flag, subject))
            : null;
    }

    /**
     * Name of the single-element add for a collection field.
     *
     * @param subject the collector singular
     * @return the method name, or {@code null} when the role is suppressed
     */
    public String addName(String subject) {
        return emitsAdd() ? NamePattern.expand(add, subject) : null;
    }

    /**
     * Name of the single-entry put for a map field.
     *
     * @param subject the collector singular
     * @return the method name, or {@code null} when the role is suppressed
     */
    public String putName(String subject) {
        return emitsPut() ? NamePattern.expand(put, subject) : null;
    }

    /**
     * Name of the put-if-absent for a map field.
     *
     * @param subject the collector singular
     * @return the method name, or {@code null} when the role is suppressed
     */
    public String computeName(String subject) {
        return emitsCompute() ? NamePattern.expand(compute, subject) : null;
    }

    /**
     * Name of the clear for a collection or map field.
     *
     * @param subject the field name
     * @return the method name, or {@code null} when the role is suppressed
     */
    public String clearName(String subject) {
        return emitsClear() ? NamePattern.expand(clear, subject) : null;
    }

}
