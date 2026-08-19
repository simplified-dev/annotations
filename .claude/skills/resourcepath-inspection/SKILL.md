---
name: resourcepath-inspection
description: ResourcePath inspection internals - entry points, visitor hooks, string-expression evaluation, change service, and the method-call data flow. Use when changing @ResourcePath validation or its PSI plumbing.
---

### Inspection (`dev.simplified.inspection`)

**Entry point** â€” `ResourcePathInspection` (`LocalInspectionTool`):
- Registered in `plugin.xml` as a Java local inspection, enabled by default at ERROR level.
- Options pane: invalid-base severity dropdown, `additionalResourceRoots` string-list, `excludedFilePatterns` glob-list.
- Delegates all PSI visiting to `ResourcePathVisitor`.

**Caller-side inspection** â€” `ResourcePathUsageInspection`:
- Flags when a `@ResourcePath(base="X")` parameter is passed raw into a resource-loading call (e.g. `Class.getResourceAsStream`), or forwarded to a parameter with a different base.
- Options pane: per-check toggles (sinks vs forwarding), separate severity dropdowns for each, shared `excludedFilePatterns` glob-list.
- Quick-fix: prepend `"X/" + ` to the argument.

**Visitor** â€” `ResourcePathVisitor`:
- Three PSI visit hooks: `visitField`, `visitEnumConstant`, `visitMethodCallExpression` (no bare-literal hook â€” removed to fix the freeze it caused).
- For each annotated site, calls `StringExpressionEvaluator.evaluate()` to get the set of possible resolved string values.
- Validates the `base` directory exists first; if not, reports the problem on the annotation attribute and skips file path checking.
- Checks resolved paths against `ContentSourceRoots` plus any user-configured `additionalResourceRoots`.

**Shared utility** â€” `ResourcePathConstants`:
- Centralises the annotation FQN, short-name, and `base` attribute name.
- Pure helpers: `getBase(annotation)` and `globToRegex(glob)`.

**String evaluator** â€” `StringExpressionEvaluator`:
- Static recursive evaluator that returns a `Set<String>` of all possible path values from a UAST expression.
- Handles: `ULiteralExpression` (string literals), `UPolyadicExpression` (concatenation â€” produces a cartesian product of all branch possibilities), `USimpleNameReferenceExpression` (final fields and local variables), `UCallExpression` (recursively evaluates method bodies and binds parameters to arguments), `UQualifiedReferenceExpression` (enum field access), `UDeclarationsExpression` (UAST local variable declarations).
- Tracks visited methods to avoid infinite recursion.

**Change service** â€” `ResourcePathChangeService` (`@Service(Level.PROJECT)`):
- Narrow `PsiTreeChangeAdapter` - only reacts to `PsiAnnotation` add/remove/replace events matching the FQN or short name.
- On a match, calls `DaemonCodeAnalyzer.restart()` (no-arg) to request re-analysis. Single-file `restart(PsiFile)` was deprecated by the platform and removed in favour of the global restart.
- Limits traversal depth in `childrenChanged` to 2 levels for performance, and does an early exit if the file contains no `@ResourcePath` annotations at all.

**Startup** â€” `ResourcePathStartupActivity` (`ProjectActivity`):
- Eagerly initializes `ResourcePathChangeService` so the PSI listener is registered before any editing occurs.

### Data flow for a method-call inspection
```
visitMethodCallExpression
  â†’ ResourcePathVisitor.inspectMethod()
    â†’ resolve PsiMethod, iterate parameters
    â†’ for each parameter with @ResourcePath annotation:
        â†’ StringExpressionEvaluator.evaluate(argument)
        â†’ resolveFullPath(annotation, value)  [prepends base/]
        â†’ resourceExists(path, project)        [checks ContentSourceRoots]
        â†’ registerProblem if missing
```

