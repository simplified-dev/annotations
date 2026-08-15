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

    /**
     * The subject a {@code @Collector}'s single-element members are named from -
     * the field's own name with its plural inflection removed, so a
     * {@code List<String> tags} contributes {@code addTag}.
     *
     * <p>Three rules in order, and the middle one is the one that earns its
     * keep: an {@code -es} plural gives up both letters only where the stem ends
     * in a sibilant or an {@code o}, because that is the only place English put
     * the {@code e} there. Everywhere else the {@code e} belongs to the word, so
     * {@code frames} yields {@code frame} rather than {@code fram} and
     * {@code sizes} yields {@code size}, while {@code boxes}, {@code classes},
     * {@code matches} and {@code heroes} still give up theirs.
     *
     * <p>A name ending in {@code ss} or {@code us} is left whole: it is not a
     * plural at all, and taking a letter off {@code address} or {@code status}
     * would name a method after nothing.
     *
     * <p>No rule covers English, so {@code @Collector(singularMethodName)} is
     * the answer for a word this misses - and the name it mints is what both the
     * processor and the editor use, so a miss is at least the same miss in both.
     *
     * @param field the field name
     * @return the singular to expand the add and put patterns against
     */
    public static String singularSubject(String field) {
        if (field == null || field.length() < 2) return field;
        // Two letters of stem before -ies, so `ties` falls through to the plain
        // -s rule and comes out `tie` rather than `ty`.
        if (field.endsWith("ies") && field.length() > 4) {
            return field.substring(0, field.length() - 3) + "y";
        }
        // The sibilant has to be doubled to have taken the -es: `classes` is
        // `class` and `buzzes` is `buzz`, while a single one before it is the
        // word's own final letter - `houses`, `sizes`, `phases`.
        if (endsWithAny(field, "sses", "zzes", "xes", "ches", "shes", "oes")) {
            return field.substring(0, field.length() - 2);
        }
        if (field.endsWith("ss") || field.endsWith("us")) return field;
        return field.endsWith("s") ? field.substring(0, field.length() - 1) : field;
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) return true;
        }
        return false;
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
