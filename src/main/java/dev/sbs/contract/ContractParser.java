package dev.sbs.contract;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the extended {@code @XContract} grammar.
 *
 * <p>Entry point: {@link #parse(String)}.
 */
public final class ContractParser {

    private final List<ContractLexer.Token> tokens;
    private int pos;

    private ContractParser(List<ContractLexer.Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /**
     * Parses a full contract specification string into a list of clauses.
     *
     * @param spec the raw string from the annotation {@code value()} attribute
     * @return the ordered list of parsed clauses (never empty if spec is non-empty)
     * @throws ContractParseException on any lexical or syntactic error
     */
    public static @NotNull List<ContractAst.Clause> parse(@NotNull String spec) throws ContractParseException {
        ContractLexer lexer = new ContractLexer(spec);
        List<ContractLexer.Token> tokens = lexer.tokenize();
        return new ContractParser(tokens).parseSpec();
    }

    // ContractSpec := Clause (';' Clause)*
    private @NotNull List<ContractAst.Clause> parseSpec() throws ContractParseException {
        List<ContractAst.Clause> clauses = new ArrayList<>();
        clauses.add(parseClause());

        while (current().type() == ContractLexer.TType.SEMICOLON) {
            consume(ContractLexer.TType.SEMICOLON);
            clauses.add(parseClause());
        }

        if (current().type() != ContractLexer.TType.EOF) {
            ContractLexer.Token tok = current();
            throw new ContractParseException(
                "Unexpected token '" + tok.text() + "' - did you forget a ';' between clauses?",
                tok.start(), tok.length()
            );
        }

        return clauses;
    }

    // Clause := '(' OrExpr? ')' '->' Return
    private @NotNull ContractAst.Clause parseClause() throws ContractParseException {
        consume(ContractLexer.TType.LPAREN);

        ContractAst.Expr condition = null;
        if (current().type() != ContractLexer.TType.RPAREN) {
            condition = parseOr();
        }

        consume(ContractLexer.TType.RPAREN);
        consume(ContractLexer.TType.ARROW);

        ContractAst.ReturnVal returnVal = parseReturn();
        return new ContractAst.Clause(condition, returnVal);
    }

    // OrExpr := AndExpr ('||' AndExpr)*
    private @NotNull ContractAst.Expr parseOr() throws ContractParseException {
        ContractAst.Expr expr = parseAnd();

        while (current().type() == ContractLexer.TType.OR_OR) {
            consume(ContractLexer.TType.OR_OR);
            ContractAst.Expr right = parseAnd();
            expr = new ContractAst.OrExpr(expr, right);
        }

        return expr;
    }

    // AndExpr := Term ('&&' Term)*
    private @NotNull ContractAst.Expr parseAnd() throws ContractParseException {
        ContractAst.Expr expr = parseTerm();

        while (current().type() == ContractLexer.TType.AND_AND) {
            consume(ContractLexer.TType.AND_AND);
            ContractAst.Expr right = parseTerm();
            expr = new ContractAst.AndExpr(expr, right);
        }

        return expr;
    }

    // Term := '(' OrExpr ')'
    //       | '!' Value
    //       | Value 'instanceof' TypeName
    //       | Value (CompOp Value)+     (chained comparisons: a <= b < c)
    //       | Value
    private @NotNull ContractAst.Expr parseTerm() throws ContractParseException {
        if (current().type() == ContractLexer.TType.LPAREN) {
            consume(ContractLexer.TType.LPAREN);
            ContractAst.Expr inner = parseOr();
            consume(ContractLexer.TType.RPAREN);
            return inner;
        }

        if (current().type() == ContractLexer.TType.NOT) {
            consume(ContractLexer.TType.NOT);
            ContractAst.Value value = parseValue();
            return new ContractAst.NegExpr(value);
        }

        ContractAst.Value left = parseValue();

        // instanceof check
        if (current().type() == ContractLexer.TType.IDENT && "instanceof".equals(current().text())) {
            consume(ContractLexer.TType.IDENT); // 'instanceof'
            String typeName = parseTypeName();
            return new ContractAst.InstanceOfExpr(left, typeName);
        }

        ContractAst.CompOp op = tryConsumeCompOp();
        if (op == null) return new ContractAst.ValExpr(left);

        ContractAst.Value right = parseValue();
        ContractAst.Expr expr = new ContractAst.CompExpr(left, op, right);

        // Chained comparisons: a <= b < c  =>  AndExpr(CompExpr(a, <=, b), CompExpr(b, <, c))
        while (true) {
            ContractAst.CompOp nextOp = tryConsumeCompOp();
            if (nextOp == null) break;
            ContractAst.Value next = parseValue();
            expr = new ContractAst.AndExpr(expr, new ContractAst.CompExpr(right, nextOp, next));
            right = next;
        }

        return expr;
    }

    /** Parses a Java type reference: {@code Name ('.' Name)*}. */
    private @NotNull String parseTypeName() throws ContractParseException {
        ContractLexer.Token tok = current();
        if (tok.type() != ContractLexer.TType.IDENT) {
            throw new ContractParseException(
                "Expected a type name, got '" + tok.text() + "'",
                tok.start(), tok.length()
            );
        }

        StringBuilder name = new StringBuilder(tok.text());
        consume(ContractLexer.TType.IDENT);

        while (current().type() == ContractLexer.TType.DOT) {
            consume(ContractLexer.TType.DOT);
            ContractLexer.Token part = current();
            if (part.type() != ContractLexer.TType.IDENT) {
                throw new ContractParseException(
                    "Expected a qualified-name segment after '.', got '" + part.text() + "'",
                    part.start(), part.length()
                );
            }
            name.append('.').append(part.text());
            consume(ContractLexer.TType.IDENT);
        }

        return name.toString();
    }

    // Value := Constant | ParamRef | ParamNameRef | ThisRef
    private @NotNull ContractAst.Value parseValue() throws ContractParseException {
        ContractLexer.Token tok = current();

        if (tok.type() == ContractLexer.TType.INT_LIT) {
            consume(ContractLexer.TType.INT_LIT);
            return parseIntConst(tok);
        }

        if (tok.type() == ContractLexer.TType.IDENT) {
            String text = tok.text();
            return switch (text) {
                case "null"  -> { consume(ContractLexer.TType.IDENT); yield new ContractAst.NullConst(); }
                case "true"  -> { consume(ContractLexer.TType.IDENT); yield new ContractAst.BoolConst(true); }
                case "false" -> { consume(ContractLexer.TType.IDENT); yield new ContractAst.BoolConst(false); }
                case "this"  -> parseThisRef();
                default      -> text.startsWith("param") && hasParamIndex(text)
                    ? parseParamRef()
                    : parseParamNameRef();
            };
        }

        throw new ContractParseException(
            "Expected a value (null, true, false, integer, paramN, this, or parameter name) but got: '" + tok.text() + "'",
            tok.start(), tok.length()
        );
    }

    /** Distinguishes {@code paramN} from user-named identifiers that happen to start with {@code param}. */
    private static boolean hasParamIndex(@NotNull String text) {
        if (!text.startsWith("param") || text.length() == "param".length()) return false;
        String tail = text.substring("param".length());
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) return false;
        }
        return true;
    }

    // ParamRef := 'param' N ('.' SpecialField)*
    private @NotNull ContractAst.ParamRef parseParamRef() throws ContractParseException {
        ContractLexer.Token tok = current();
        String text = tok.text();
        String numPart = text.substring("param".length());

        if (numPart.isEmpty()) {
            throw new ContractParseException(
                "Expected 'param' followed by a number (e.g. 'param1'), got bare 'param'",
                tok.start(), tok.length()
            );
        }

        int index;
        try {
            index = Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            throw new ContractParseException(
                "Invalid parameter index '" + numPart + "' in '" + text + "' - must be a positive integer",
                tok.start(), tok.length()
            );
        }

        if (index < 1) {
            throw new ContractParseException(
                "Parameter index must be >= 1, got " + index + " in '" + text + "'",
                tok.start(), tok.length()
            );
        }

        consume(ContractLexer.TType.IDENT);
        List<ContractAst.SpecialField> fields = parseFieldChain();
        return new ContractAst.ParamRef(index, fields);
    }

    // ParamNameRef := Name ('.' SpecialField)*
    private @NotNull ContractAst.ParamNameRef parseParamNameRef() throws ContractParseException {
        ContractLexer.Token tok = current();
        String name = tok.text();
        consume(ContractLexer.TType.IDENT);
        List<ContractAst.SpecialField> fields = parseFieldChain();
        return new ContractAst.ParamNameRef(name, fields);
    }

    // ThisRef := 'this' ('.' SpecialField)*
    private @NotNull ContractAst.ThisRef parseThisRef() throws ContractParseException {
        consume(ContractLexer.TType.IDENT); // 'this'
        List<ContractAst.SpecialField> fields = parseFieldChain();
        return new ContractAst.ThisRef(fields);
    }

    // ('.' SpecialField)*
    // SpecialField := 'size()' | 'length()' | 'length' | 'empty()'
    private @NotNull List<ContractAst.SpecialField> parseFieldChain() throws ContractParseException {
        List<ContractAst.SpecialField> fields = new ArrayList<>();

        while (current().type() == ContractLexer.TType.DOT) {
            consume(ContractLexer.TType.DOT);

            ContractLexer.Token fieldTok = current();
            if (fieldTok.type() != ContractLexer.TType.IDENT) {
                throw new ContractParseException(
                    "Expected a field name after '.', got '" + fieldTok.text() + "'",
                    fieldTok.start(), fieldTok.length()
                );
            }

            String fieldName = fieldTok.text();
            consume(ContractLexer.TType.IDENT);

            boolean hasParens = false;
            if (current().type() == ContractLexer.TType.LPAREN) {
                consume(ContractLexer.TType.LPAREN);
                consume(ContractLexer.TType.RPAREN);
                hasParens = true;
            }

            ContractAst.SpecialField sf = switch (fieldName) {
                case "size" -> {
                    if (!hasParens) throw new ContractParseException(
                        "'size' must be called as a method: use 'size()'",
                        fieldTok.start(), fieldTok.length()
                    );
                    yield ContractAst.SpecialField.SIZE;
                }
                case "length" -> hasParens
                    ? ContractAst.SpecialField.LENGTH_METHOD
                    : ContractAst.SpecialField.LENGTH_FIELD;
                case "empty" -> {
                    if (!hasParens) throw new ContractParseException(
                        "'empty' must be called as a method: use 'empty()'",
                        fieldTok.start(), fieldTok.length()
                    );
                    yield ContractAst.SpecialField.EMPTY;
                }
                default -> throw new ContractParseException(
                    "Unknown field '" + fieldName + (hasParens ? "()" : "") +
                    "' - supported: size(), length(), length, empty()",
                    fieldTok.start(), fieldTok.length()
                );
            };

            fields.add(sf);
        }

        return fields;
    }

    // Tries to consume a comparison operator; returns null if none is present.
    private @Nullable ContractAst.CompOp tryConsumeCompOp() {
        ContractAst.CompOp op = switch (current().type()) {
            case LT  -> ContractAst.CompOp.LT;
            case GT  -> ContractAst.CompOp.GT;
            case LTE -> ContractAst.CompOp.LTE;
            case GTE -> ContractAst.CompOp.GTE;
            case EQ  -> ContractAst.CompOp.EQ;
            case NEQ -> ContractAst.CompOp.NEQ;
            default  -> null;
        };
        if (op != null) advance();
        return op;
    }

    // Return := 'true' | 'false' | 'null' | '!null' | 'fail' | 'this' | 'new'
    //         | 'param' N | integer literal
    private @NotNull ContractAst.ReturnVal parseReturn() throws ContractParseException {
        ContractLexer.Token tok = current();

        // !null - special two-token return value
        if (tok.type() == ContractLexer.TType.NOT) {
            consume(ContractLexer.TType.NOT);
            ContractLexer.Token next = current();
            if (next.type() == ContractLexer.TType.IDENT && "null".equals(next.text())) {
                consume(ContractLexer.TType.IDENT);
                return new ContractAst.NotNullRet();
            }
            throw new ContractParseException(
                "Expected 'null' after '!' in return value (write '!null')",
                next.start(), next.length()
            );
        }

        if (tok.type() == ContractLexer.TType.INT_LIT) {
            consume(ContractLexer.TType.INT_LIT);
            return new ContractAst.IntRet(parseIntConst(tok).value());
        }

        if (tok.type() == ContractLexer.TType.IDENT) {
            String text = tok.text();

            // 'throws TypeName' return value
            if ("throws".equals(text)) {
                consume(ContractLexer.TType.IDENT);
                String typeName = parseTypeName();
                return new ContractAst.ThrowsRet(typeName);
            }

            consume(ContractLexer.TType.IDENT);

            return switch (text) {
                case "true"  -> new ContractAst.TrueRet();
                case "false" -> new ContractAst.FalseRet();
                case "null"  -> new ContractAst.NullRet();
                case "fail"  -> new ContractAst.FailRet();
                case "this"  -> new ContractAst.ThisRet();
                case "new"   -> new ContractAst.NewRet();
                default -> {
                    if (text.startsWith("param") && hasParamIndex(text)) {
                        String numPart = text.substring("param".length());
                        int index = Integer.parseInt(numPart);
                        if (index < 1) throw new ContractParseException(
                            "Parameter index must be >= 1, got " + index,
                            tok.start(), tok.length()
                        );
                        yield new ContractAst.ParamRet(index);
                    }
                    // Treat any other bare identifier as a named-parameter return; inspection resolves it.
                    yield new ContractAst.ParamNameRet(text);
                }
            };
        }

        throw new ContractParseException(
            "Expected a return value but got '" + tok.text() + "'",
            tok.start(), tok.length()
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private @NotNull ContractAst.IntConst parseIntConst(@NotNull ContractLexer.Token tok) throws ContractParseException {
        try {
            return new ContractAst.IntConst(Integer.parseInt(tok.text()));
        } catch (NumberFormatException e) {
            throw new ContractParseException(
                "Integer constant out of range: '" + tok.text() + "'",
                tok.start(), tok.length()
            );
        }
    }

    private @NotNull ContractLexer.Token current() {
        return tokens.get(pos);
    }

    private void advance() {
        if (pos < tokens.size() - 1) pos++;
    }

    private @NotNull ContractLexer.Token consume(@NotNull ContractLexer.TType expected) throws ContractParseException {
        ContractLexer.Token tok = current();
        if (tok.type() != expected) {
            throw new ContractParseException(
                "Expected " + displayName(expected) + " but got '" + tok.text() + "'",
                tok.start(), tok.length()
            );
        }
        advance();
        return tok;
    }

    private static @NotNull String displayName(@NotNull ContractLexer.TType type) {
        return switch (type) {
            case LPAREN    -> "'('";
            case RPAREN    -> "')'";
            case ARROW     -> "'->'";
            case SEMICOLON -> "';'";
            case AND_AND   -> "'&&'";
            case OR_OR     -> "'||'";
            case LT        -> "'<'";
            case GT        -> "'>'";
            case LTE       -> "'<='";
            case GTE       -> "'>='";
            case EQ        -> "'=='";
            case NEQ       -> "'!='";
            case NOT       -> "'!'";
            case DOT       -> "'.'";
            case IDENT     -> "an identifier";
            case INT_LIT   -> "an integer";
            case EOF       -> "end of input";
        };
    }

}
