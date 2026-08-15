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

    /**
     * The subject a pattern expands against for a {@code boolean} field, which
     * is the field's own name minus a leading {@code is} when keeping it would
     * double the prefix the pattern is about to add.
     *
     * <p>A {@code boolean} field named {@code isPermaLink} under {@code is{}}
     * would otherwise mint {@code isIsPermaLink()}. The condition is on the
     * pattern rather than on the style, so it holds for a written
     * {@code name = "is{}"} as readily as for a style's, and it is skipped
     * exactly when the pattern opens with the placeholder - a fluent
     * {@code "{}"} adds no prefix to double, and its accessor is the field's own
     * name.
     *
     * <p>The trailing character test accepts anything that is not lower case, so
     * {@code isPermaLink} strips while a field genuinely named {@code island}
     * does not.
     *
     * @param pattern the pattern the subject will be expanded into
     * @param field the field name
     * @return the name to expand, stripped or unchanged
     */
    public static String booleanSubject(String pattern, String field) {
        if (pattern == null || field == null) return field;
        if (pattern.indexOf(PLACEHOLDER) == 0) return field;
        if (!field.startsWith("is") || field.length() <= 2) return field;
        return Character.isLowerCase(field.charAt(2)) ? field : field.substring(2);
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
