package dev.simplified.classbuilder.apt;

/**
 * Renders text as Java source literals for the emitters that write source
 * rather than mutate a tree.
 *
 * <p>A {@code pattern} regex routinely carries backslashes, and a rejection
 * message can carry quotes, so passing raw text through produces source that
 * fails to compile - or, worse, compiles to a different regex.
 */
final class SourceLiterals {

    private SourceLiterals() {}

    /**
     * Escapes a string for use inside a Java literal, without adding the
     * surrounding quotes. Everything outside printable ASCII becomes a unicode
     * escape.
     *
     * @param s the raw text
     * @return the escaped body
     */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '"') sb.append("\\\"");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c >= 0x20 && c < 0x7f) sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    /**
     * Quotes a string as a complete Java literal.
     *
     * @param s the raw text
     * @return the quoted literal
     */
    static String quote(String s) {
        return '"' + escape(s) + '"';
    }

}
