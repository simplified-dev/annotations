---
name: xcontract
description: Extended contract DSL internals - lexer, recursive-descent parser, AST shape, declaration- and caller-side inspections, and the bridge that synthesises a JetBrains @Contract. Use when changing @XContract grammar or inference.
---

### Extended contract (`dev.simplified.contract` + `dev.simplified.xcontract`)

**Parser pipeline** â€” `ContractLexer` -> `ContractParser` -> `ContractAst`:
- `ContractLexer` tokenises the contract string and surfaces precise error positions.
- `ContractParser` is a recursive-descent parser with OR/AND precedence, grouping parens, chained comparisons, `instanceof`, and typed `throws` returns: `parseOr` -> `parseAnd` -> `parseTerm` -> `parseValue`.
- `ContractAst` is a sealed record hierarchy (`Expr`: `OrExpr`/`AndExpr`/`CompExpr`/`NegExpr`/`ValExpr`/`InstanceOfExpr`; `Value`: `NullConst`/`BoolConst`/`IntConst`/`ParamRef`/`ParamNameRef`/`ThisRef`; `ReturnVal`: `TrueRet`/`FalseRet`/`NullRet`/`NotNullRet`/`FailRet`/`ThisRet`/`NewRet`/`ParamRet`/`ParamNameRet`/`IntRet`/`ThrowsRet`).
- `ContractParseException` carries position + token length for IDE range highlighting.

**Declaration-side inspection** â€” `XContractInspection`:
- Parses `value` and reports syntax errors at the exact offending token.
- Validates `paramN` indices, named-parameter refs, `instanceof` type names, and `throws` type names against the project's classpath.
- Validates the `mutates` attribute token-by-token.
- Flags overrides that weaken a super's contract: `pure=true` -> `pure=false` downgrade and `mutates` superset violations.

**Caller-side inspection** â€” `XContractCallInspection`:
- Visits `PsiMethodCallExpression` + `PsiNewExpression`; resolves target method, looks up `@XContract` (incl. inherited).
- Uses `PsiConstantEvaluationHelper` to evaluate each literal argument, then three-valued (`TRUE`/`FALSE`/`UNKNOWN`) logic to check `fail`/`throws` clauses.
- Warns only when a clause's condition is proven TRUE by the call's arguments - `UNKNOWN` stays silent to avoid false positives.

**Bridge to JetBrains `@Contract`** â€” `XContractInferredAnnotationProvider` + `XContractTranslator`:
- Registered as `<inferredAnnotationProvider>` in `plugin.xml`.
- Translator parses the `value` and emits the `@Contract`-expressible subset (null/!null/true/false constraints, AND of single-param constraints, standard return values). `throws` collapses to `fail`. Other clauses (relational comparisons, `OR`, grouping, `this`, field access, named refs, `instanceof`, integer returns) are dropped from the synthesised annotation but retain their validation and caller-side checks in this plugin.
- Result: IDE data-flow analysis treats `@XContract` methods like `@Contract` methods for the translatable subset; extended features are enforced by this plugin's own inspections.

