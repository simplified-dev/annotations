package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.SetterNames;

/**
 * Expansion and validation of the naming patterns {@code @ClassBuilder} takes
 * its generated names from. Shared by {@link SetterScheme} and
 * {@link BuilderScheme}, which differ only in whether the placeholder is
 * mandatory and in what it expands against.
 *
 * <p>Deliberately free of javac, PSI, and {@link FieldSpec} references so both
 * modules can depend on it.
 */
public final class NamePattern {

    /** The token a pattern expands the name's subject into. */
    public static final String PLACEHOLDER = "{}";

    private NamePattern() {
    }

    /**
     * Expands a pattern against the name the member is built from. The subject
     * is capitalised unless the placeholder opens the pattern, so {@code "{}"}
     * yields {@code animated} while {@code "is{}"} yields {@code isAnimated}. A
     * pattern with no placeholder is a literal and returns unchanged, which is
     * how a plain {@code build} still works.
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

    /** Whether a pattern asks for its member to be generated at all. */
    public static boolean emits(String pattern) {
        return !SetterNames.NONE.equals(pattern);
    }

    /**
     * Resolves a written pattern against the style's, treating an unwritten or
     * {@link SetterNames#INHERIT} value as "take the style's".
     *
     * @param written the pattern written on the annotation, or {@code null}
     * @param fallback the style's pattern
     * @return the resolved pattern
     */
    public static String inherit(String written, String fallback) {
        return written == null || SetterNames.INHERIT.equals(written) ? fallback : written;
    }

    /**
     * Validates a pattern independently of any subject, checking placeholder
     * count and that the literal text around it can occupy its position in a
     * Java identifier.
     *
     * @param pattern the pattern to check
     * @param placeholderRequired whether omitting the placeholder is an error,
     *                            true for the per-field setters and false for
     *                            the builder's own once-per-target members
     * @return a message describing the defect, or {@code null} when valid
     */
    public static String patternError(String pattern, boolean placeholderRequired) {
        if (pattern == null || !emits(pattern)) return null;
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

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
