package dev.sbs.contract;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a raw contract string into a flat list of {@link Token}s.
 * Package-private – callers should use {@link ContractParser} directly.
 */
final class ContractLexer {

    enum TType {
        LPAREN, RPAREN,
        ARROW,          // ->
        SEMICOLON,      // ;
        AND_AND,        // &&
        OR_OR,          // ||
        LT, GT, LTE, GTE, EQ, NEQ,
        NOT,            // !
        DOT,            // .
        IDENT,          // identifier or keyword
        INT_LIT,        // integer (possibly negative)
        EOF
    }

    record Token(@NotNull TType type, @NotNull String text, int start) {
        int length() { return text.length(); }
    }

    private final String input;
    private int pos;

    ContractLexer(@NotNull String input) {
        this.input = input;
    }

    @NotNull List<Token> tokenize() throws ContractParseException {
        List<Token> tokens = new ArrayList<>();
        pos = 0;

        while (pos < input.length()) {
            skipWhitespace();
            if (pos >= input.length()) break;

            int start = pos;
            char c = input.charAt(pos);

            switch (c) {
                case '(' -> { tokens.add(tok(TType.LPAREN, "(", start)); pos++; }
                case ')' -> { tokens.add(tok(TType.RPAREN, ")", start)); pos++; }
                case ';' -> { tokens.add(tok(TType.SEMICOLON, ";", start)); pos++; }
                case '.' -> { tokens.add(tok(TType.DOT, ".", start)); pos++; }
                case '>' -> {
                    if (peek(1) == '=') { tokens.add(tok(TType.GTE, ">=", start)); pos += 2; }
                    else                { tokens.add(tok(TType.GT,  ">",  start)); pos++; }
                }
                case '<' -> {
                    if (peek(1) == '=') { tokens.add(tok(TType.LTE, "<=", start)); pos += 2; }
                    else                { tokens.add(tok(TType.LT,  "<",  start)); pos++; }
                }
                case '=' -> {
                    if (peek(1) == '=') { tokens.add(tok(TType.EQ, "==", start)); pos += 2; }
                    else throw new ContractParseException("Use '==' for equality comparison, not '='", start);
                }
                case '!' -> {
                    if (peek(1) == '=') { tokens.add(tok(TType.NEQ, "!=", start)); pos += 2; }
                    else                { tokens.add(tok(TType.NOT, "!",  start)); pos++; }
                }
                case '&' -> {
                    if (peek(1) == '&') { tokens.add(tok(TType.AND_AND, "&&", start)); pos += 2; }
                    else throw new ContractParseException("Use '&&' for logical AND, not '&'", start);
                }
                case '|' -> {
                    if (peek(1) == '|') { tokens.add(tok(TType.OR_OR, "||", start)); pos += 2; }
                    else throw new ContractParseException("Use '||' for logical OR, not '|'", start);
                }
                case '-' -> {
                    if (peek(1) == '>') {
                        tokens.add(tok(TType.ARROW, "->", start));
                        pos += 2;
                    } else if (peek(1) != 0 && Character.isDigit(peek(1))) {
                        tokens.add(readInt(start));
                    } else {
                        throw new ContractParseException("Unexpected '-'; did you mean '->'?", start);
                    }
                }
                default -> {
                    if (Character.isDigit(c))                    { tokens.add(readInt(start)); }
                    else if (Character.isLetter(c) || c == '_') { tokens.add(readIdent(start)); }
                    else throw new ContractParseException("Unexpected character: '" + c + "'", start);
                }
            }
        }

        tokens.add(new Token(TType.EOF, "", input.length()));
        return tokens;
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private char peek(int offset) {
        int idx = pos + offset;
        return idx < input.length() ? input.charAt(idx) : 0;
    }

    private @NotNull Token readInt(int start) {
        pos = start;
        if (input.charAt(pos) == '-') pos++; // consume leading minus
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        return new Token(TType.INT_LIT, input.substring(start, pos), start);
    }

    private @NotNull Token readIdent(int start) {
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) pos++;
        return new Token(TType.IDENT, input.substring(start, pos), start);
    }

    private static @NotNull Token tok(@NotNull TType type, @NotNull String text, int start) {
        return new Token(type, text, start);
    }

}