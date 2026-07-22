package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.MethodNames;
import dev.simplified.annotations.NamingStyle;

/**
 * Resolved naming patterns for the methods a {@code @ClassBuilder} target
 * generates, and the single place any generated method name is minted.
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
public record NamingScheme(
    String set,
    String flag,
    String add,
    String put,
    String compute,
    String clear
) {

    /** The token a pattern expands the method's subject into. */
    public static final String PLACEHOLDER = "{}";

    /**
     * Resolves a scheme from a style plus the raw {@code @MethodNames}
     * attributes, in declaration order. Any argument equal to
     * {@link MethodNames#INHERIT} or {@code null} takes the style's pattern.
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
    public static NamingScheme resolve(NamingStyle style, String set, String flag, String add,
                                       String put, String compute, String clear) {
        return new NamingScheme(
            inherit(set, style.set()),
            inherit(flag, style.flag()),
            inherit(add, style.add()),
            inherit(put, style.put()),
            inherit(compute, style.compute()),
            inherit(clear, style.clear())
        );
    }

    /** Resolves a scheme carrying nothing but the style's own patterns. */
    public static NamingScheme of(NamingStyle style) {
        return resolve(style, null, null, null, null, null, null);
    }

    private static String inherit(String written, String fallback) {
        return written == null || MethodNames.INHERIT.equals(written) ? fallback : written;
    }

    /**
     * Expands a pattern against the name the method is built from. The subject
     * is capitalised unless the placeholder opens the pattern, so {@code "{}"}
     * yields {@code animated} while {@code "is{}"} yields {@code isAnimated}. A
     * pattern with no placeholder is a literal and returns unchanged, which is
     * how a plain {@code builderName = "Builder"} still works.
     *
     * @param pattern the pattern to expand
     * @param subject the field name, negate stem, singular, or type simple name
     * @return the generated name
     */
    public static String expand(String pattern, String subject) {
        int at = pattern.indexOf(PLACEHOLDER);
        if (at < 0) return pattern;
        String value = at == 0 ? subject : capitalise(subject);
        return pattern.substring(0, at) + value + pattern.substring(at + PLACEHOLDER.length());
    }

    /**
     * Validates a pattern independently of any subject, checking placeholder
     * count and that the literal text around it can occupy its position in a
     * Java identifier.
     *
     * @param pattern the pattern to check
     * @param placeholderRequired whether omitting the placeholder is an error,
     *                            true for the six method roles and false for
     *                            {@code builderName}, whose default is a literal
     * @return a message describing the defect, or {@code null} when valid
     */
    public static String patternError(String pattern, boolean placeholderRequired) {
        if (pattern == null || MethodNames.NONE.equals(pattern)) return null;
        if (pattern.isEmpty()) return "must not be empty";
        int at = pattern.indexOf(PLACEHOLDER);
        if (at < 0) {
            if (placeholderRequired) {
                return "must contain the '" + PLACEHOLDER + "' placeholder, otherwise every field "
                    + "generates the same method name";
            }
        } else if (pattern.indexOf(PLACEHOLDER, at + PLACEHOLDER.length()) >= 0) {
            return "must contain the '" + PLACEHOLDER + "' placeholder at most once";
        }
        String literal = at < 0 ? pattern : pattern.substring(0, at) + pattern.substring(at + PLACEHOLDER.length());
        boolean leadsWithSubject = at == 0;
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            boolean ok = i == 0 && !leadsWithSubject
                ? Character.isJavaIdentifierStart(c)
                : Character.isJavaIdentifierPart(c);
            if (!ok) return "expands to an invalid Java identifier - '" + c + "' cannot appear there";
        }
        return null;
    }

    /**
     * Whether the value-taking setter is generated. Always true for a valid
     * scheme; suppressing it leaves a field with no way to be assigned, which
     * the processor rejects.
     */
    public boolean emitsSet() {
        return emits(set);
    }

    /** Whether the zero-arg boolean setter and its inverse are generated. */
    public boolean emitsFlag() {
        return emits(flag);
    }

    /** Whether the single-element collection add is generated. */
    public boolean emitsAdd() {
        return emits(add);
    }

    /** Whether the single-entry map put is generated. */
    public boolean emitsPut() {
        return emits(put);
    }

    /** Whether the map put-if-absent is generated. */
    public boolean emitsCompute() {
        return emits(compute);
    }

    /** Whether the collection or map clear is generated. */
    public boolean emitsClear() {
        return emits(clear);
    }

    private static boolean emits(String pattern) {
        return !MethodNames.NONE.equals(pattern);
    }

    /**
     * Name of the value-taking setter for a field.
     *
     * @param subject the field name
     * @return the setter name
     */
    public String setName(String subject) {
        return expand(set, subject);
    }

    /**
     * Name of the zero-arg boolean setter for a field or its negate stem.
     *
     * @param subject the field name, or the {@code @Negate} stem for the inverse
     * @return the setter name, or {@code null} when the role is suppressed
     */
    public String flagName(String subject) {
        return emitsFlag() ? expand(flag, subject) : null;
    }

    /**
     * Name of the single-element add for a collection field.
     *
     * @param subject the collector singular
     * @return the method name, or {@code null} when the role is suppressed
     */
    public String addName(String subject) {
        return emitsAdd() ? expand(add, subject) : null;
    }

    /**
     * Name of the single-entry put for a map field.
     *
     * @param subject the collector singular
     * @return the method name, or {@code null} when the role is suppressed
     */
    public String putName(String subject) {
        return emitsPut() ? expand(put, subject) : null;
    }

    /**
     * Name of the put-if-absent for a map field.
     *
     * @param subject the collector singular
     * @return the method name, or {@code null} when the role is suppressed
     */
    public String computeName(String subject) {
        return emitsCompute() ? expand(compute, subject) : null;
    }

    /**
     * Name of the clear for a collection or map field.
     *
     * @param subject the field name
     * @return the method name, or {@code null} when the role is suppressed
     */
    public String clearName(String subject) {
        return emitsClear() ? expand(clear, subject) : null;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
