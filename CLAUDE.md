# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an **IntelliJ IDEA plugin + Maven Central annotation library** that provides three annotation families, each with companion tooling:

- `@ResourcePath` - evaluates string expressions at annotated sites and verifies the referenced resource files exist in the project's source/resource roots. Pure inspection, no code generation.
- `@XContract` - a superset of JetBrains `@Contract` with a richer grammar (relational comparisons, `&&`/`||` with grouping, field access, named-parameter references, integer/boolean constants). The plugin synthesises an equivalent `@Contract` via an `InferredAnnotationProvider` so IntelliJ's data-flow analysis works from a single annotation.
- `@ClassBuilder` - generates a sibling `<TypeName>Builder.java` via a JSR 269 annotation processor. Supports classes, records, and interfaces. Setter shapes cover Optional dual setters, boolean zero-arg + typed pairs with optional negation, String `@PrintFormat` overloads, `@Singular` collection/map add/put/varargs/clear, and configurable method naming. `@BuildFlag` runtime validator enforces nonNull/notEmpty/group/pattern/limit in the generated `build()`. Every generated method carries a matching `@XContract` so IDE data-flow sees fresh-object and this-return shapes.

Published to:
- JetBrains Marketplace: plugin ID `dev.sbs.simplified-annotations`
- Maven Central: group `dev.sbs`, artifact `simplified-annotations`

## Commands

```bash
# Build the plugin JAR
./gradlew build

# Launch a sandboxed IDE instance with the plugin loaded (for manual testing)
./gradlew runIde

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "dev.sbs.AnnotationTest"

# Build the distributable plugin zip
./gradlew buildPlugin

# Build + sign + checksum + zip for Maven Central upload
./gradlew publishAndPackage
```

## Architecture

### Annotations (`dev.sbs.annotation`)
- `ResourcePath.java` — `@Retention(CLASS)` annotation targeting fields, parameters, and methods. Has an optional `base` attribute that prefixes the resolved path.
- `XContract.java` — `@Retention(CLASS)` annotation targeting methods and constructors. Attributes: `value` (semicolon-separated clauses), `pure`, `mutates` (comma-separated `this`/`io`/`paramN`). Full grammar is on the annotation's Javadoc.
- `ClassBuilder.java` — `@Retention(CLASS)` annotation targeting types/constructors/methods. Drives the builder processor. Full Lombok `@Builder` parity (`builderName`, `builderMethodName`, `buildMethodName`, `fromMethodName`, `toBuilderMethodName`, `methodPrefix`, `access`, `validate`, `emitContracts`, `factoryMethod`, `exclude`, `generate*` toggles).
- `BuildFlag.java` — `@Retention(RUNTIME)` because the validator reflects. Ported from `dev.simplified.reflection.builder.BuildFlag`.
- `Singular.java`, `Negate.java`, `Formattable.java`, `BuilderDefault.java`, `BuilderIgnore.java`, `ObtainVia.java` — field-level `@Retention(CLASS)` companions.
- `AccessLevel.java` — enum with `toKeyword()`.

### Package layout
- `dev.sbs.resourcepath` — ResourcePath inspection + visitor + evaluator + change listener + startup activity.
- `dev.sbs.contract` — **annotation-neutral** contract-DSL grammar infrastructure: `ContractAst`, `ContractLexer`, `ContractParser`, `ContractParseException`. Reusable for any annotation that carries the same contract DSL.
- `dev.sbs.xcontract` — **specific to the `@XContract` annotation**: `XContractInspection` (declaration-side), `XContractCallInspection` (caller-side), `XContractInferredAnnotationProvider` (bridge to JetBrains `@Contract`), `XContractTranslator` (AST → `@Contract` string).
- `dev.sbs.classbuilder.apt` — JSR 269 annotation processor: `ClassBuilderProcessor` (entry, registered via `META-INF/services/javax.annotation.processing.Processor`, dispatches class/record targets to the mutator and interface targets to the sibling emitter), `FieldSpec` (per-field IR), `SourceIntrospector` (Trees-API bridge for reading declared initialisers), `AnnotationLookup` (mirror attribute reader), `BuilderConfig` (resolved annotation attributes), `BuilderEmitter` (legacy source generator; now only handles interface targets since classes/records use AST mutation), `InterfaceImplEmitter` (emits the `<Name>Impl` concrete class for interface targets).
- `dev.sbs.classbuilder.mutate` — javac AST mutation pipeline: `JavacBridge` (reflective gateway to `JavacProcessingEnvironment` internals), `MutationContext`, `BuilderMutator` (concrete/record orchestrator), `SuperBuilderMutator` (abstract-target and concrete-subclass orchestrator with self-typed generics), `NestedBuilderFactory`, `BootstrapMethodFactory`, `CopyConstructorFactory`, `FieldMutators`, `SelfTypedSetters`, `JavacTypeFactory`, `AstMarkers`. `compat/` subpackage carries `JavacCompat` interface + `JavacCompatFactory` version-gated static dispatch + `v17`/`v21`/`v23`/`v25` shims (only `v17` has real logic day-one; higher versions inherit unchanged).
- `dev.sbs.classbuilder.editor` — IntelliJ editor-side synthesis: `ClassBuilderAugmentProvider` (surfaces bootstrap methods to the PSI layer so autocompletion works before the first javac round), `ClassBuilderLineMarkerProvider` (gutter icon on `@ClassBuilder` annotations), `GeneratedMemberFactory` + `GeneratedMemberMarker` (synthesis helpers and provenance key), `PsiFieldShape` + `PsiFieldShapeExtractor` (PSI analogue of `FieldSpec`).
- `dev.sbs.classbuilder.validate` — runtime: `BuildFlagValidator` (reflective, per-class cached), `BuilderValidationException`, `Strings.formatNullable` helper.
- `dev.sbs.classbuilder.inspect` — IDE-side: `ClassBuilderFieldInspection` (flags misuse of companion annotations at source-edit time), `ClassBuilderConstants` (shared FQNs + attribute readers). The bootstrap-methods inspection was retired in 1.2.0 since the methods are now auto-injected.

### Inspection (`dev.sbs.inspection`)

**Entry point** — `ResourcePathInspection` (`LocalInspectionTool`):
- Registered in `plugin.xml` as a Java local inspection, enabled by default at ERROR level.
- Options pane: invalid-base severity dropdown, `additionalResourceRoots` string-list, `excludedFilePatterns` glob-list.
- Delegates all PSI visiting to `ResourcePathVisitor`.

**Caller-side inspection** — `ResourcePathUsageInspection`:
- Flags when a `@ResourcePath(base="X")` parameter is passed raw into a resource-loading call (e.g. `Class.getResourceAsStream`), or forwarded to a parameter with a different base.
- Options pane: per-check toggles (sinks vs forwarding), separate severity dropdowns for each, shared `excludedFilePatterns` glob-list.
- Quick-fix: prepend `"X/" + ` to the argument.

**Visitor** — `ResourcePathVisitor`:
- Three PSI visit hooks: `visitField`, `visitEnumConstant`, `visitMethodCallExpression` (no bare-literal hook — removed to fix the freeze it caused).
- For each annotated site, calls `StringExpressionEvaluator.evaluate()` to get the set of possible resolved string values.
- Validates the `base` directory exists first; if not, reports the problem on the annotation attribute and skips file path checking.
- Checks resolved paths against `ContentSourceRoots` plus any user-configured `additionalResourceRoots`.

**Shared utility** — `ResourcePathConstants`:
- Centralises the annotation FQN, short-name, and `base` attribute name.
- Pure helpers: `getBase(annotation)` and `globToRegex(glob)`.

**String evaluator** — `StringExpressionEvaluator`:
- Static recursive evaluator that returns a `Set<String>` of all possible path values from a UAST expression.
- Handles: `ULiteralExpression` (string literals), `UPolyadicExpression` (concatenation — produces a cartesian product of all branch possibilities), `USimpleNameReferenceExpression` (final fields and local variables), `UCallExpression` (recursively evaluates method bodies and binds parameters to arguments), `UQualifiedReferenceExpression` (enum field access), `UDeclarationsExpression` (UAST local variable declarations).
- Tracks visited methods to avoid infinite recursion.

**Change service** — `ResourcePathChangeService` (`@Service(Level.PROJECT)`):
- Narrow `PsiTreeChangeAdapter` - only reacts to `PsiAnnotation` add/remove/replace events matching the FQN or short name.
- On a match, calls `DaemonCodeAnalyzer.restart()` (no-arg) to request re-analysis. Single-file `restart(PsiFile)` was deprecated by the platform and removed in favour of the global restart.
- Limits traversal depth in `childrenChanged` to 2 levels for performance, and does an early exit if the file contains no `@ResourcePath` annotations at all.

**Startup** — `ResourcePathStartupActivity` (`ProjectActivity`):
- Eagerly initializes `ResourcePathChangeService` so the PSI listener is registered before any editing occurs.

### Extended contract (`dev.sbs.contract` + `dev.sbs.xcontract`)

**Parser pipeline** — `ContractLexer` -> `ContractParser` -> `ContractAst`:
- `ContractLexer` tokenises the contract string and surfaces precise error positions.
- `ContractParser` is a recursive-descent parser with OR/AND precedence, grouping parens, chained comparisons, `instanceof`, and typed `throws` returns: `parseOr` -> `parseAnd` -> `parseTerm` -> `parseValue`.
- `ContractAst` is a sealed record hierarchy (`Expr`: `OrExpr`/`AndExpr`/`CompExpr`/`NegExpr`/`ValExpr`/`InstanceOfExpr`; `Value`: `NullConst`/`BoolConst`/`IntConst`/`ParamRef`/`ParamNameRef`/`ThisRef`; `ReturnVal`: `TrueRet`/`FalseRet`/`NullRet`/`NotNullRet`/`FailRet`/`ThisRet`/`NewRet`/`ParamRet`/`ParamNameRet`/`IntRet`/`ThrowsRet`).
- `ContractParseException` carries position + token length for IDE range highlighting.

**Declaration-side inspection** — `XContractInspection`:
- Parses `value` and reports syntax errors at the exact offending token.
- Validates `paramN` indices, named-parameter refs, `instanceof` type names, and `throws` type names against the project's classpath.
- Validates the `mutates` attribute token-by-token.
- Flags overrides that weaken a super's contract: `pure=true` -> `pure=false` downgrade and `mutates` superset violations.

**Caller-side inspection** — `XContractCallInspection`:
- Visits `PsiMethodCallExpression` + `PsiNewExpression`; resolves target method, looks up `@XContract` (incl. inherited).
- Uses `PsiConstantEvaluationHelper` to evaluate each literal argument, then three-valued (`TRUE`/`FALSE`/`UNKNOWN`) logic to check `fail`/`throws` clauses.
- Warns only when a clause's condition is proven TRUE by the call's arguments - `UNKNOWN` stays silent to avoid false positives.

**Bridge to JetBrains `@Contract`** — `XContractInferredAnnotationProvider` + `XContractTranslator`:
- Registered as `<inferredAnnotationProvider>` in `plugin.xml`.
- Translator parses the `value` and emits the `@Contract`-expressible subset (null/!null/true/false constraints, AND of single-param constraints, standard return values). `throws` collapses to `fail`. Other clauses (relational comparisons, `OR`, grouping, `this`, field access, named refs, `instanceof`, integer returns) are dropped from the synthesised annotation but retain their validation and caller-side checks in this plugin.
- Result: IDE data-flow analysis treats `@XContract` methods like `@Contract` methods for the translatable subset; extended features are enforced by this plugin's own inspections.

### Data flow for a method-call inspection
```
visitMethodCallExpression
  → ResourcePathVisitor.inspectMethod()
    → resolve PsiMethod, iterate parameters
    → for each parameter with @ResourcePath annotation:
        → StringExpressionEvaluator.evaluate(argument)
        → resolveFullPath(annotation, value)  [prepends base/]
        → resourceExists(path, project)        [checks ContentSourceRoots]
        → registerProblem if missing
```

### @ClassBuilder data flow

Consumer's `javac` → `META-INF/services` registers `ClassBuilderProcessor` → `process()`:
```
for each element annotated with @ClassBuilder (CLASS | RECORD | INTERFACE):
  extractConfig(target)                [reads all annotation attributes]
  collectFields(target, config)        [enclosed FIELDS for CLASS/RECORD;
                                        abstract zero-arg methods for INTERFACE]
    FieldSpec.from(variableElement)    [classifies: primitive/boolean/String/Optional
                                        /list/set/map/array and pulls companion annos]
  for CLASS / RECORD:
    BuilderMutator.mutate()            [injects nested Builder + builder()/from()/mutate() via AST]
      NestedBuilderFactory + FieldMutators build the JCClassDecl
      BootstrapMethodFactory appends builder()/from()/mutate() onto target.defs
      SuperBuilderMutator (abstract or annotated-super path) produces
        self-typed <T, B> generics, abstract self()/build() on the root,
        CopyConstructorFactory emits protected Target(Builder<?,?> b)
    If JavacProcessingEnvironment cannot be unwrapped (ecj, unknown wrapper),
      the processor ERRORs - consumers must use javac.
  for INTERFACE:
    InterfaceImplEmitter.emit(...)     [writes <Name>Impl.java via Filer]
    BuilderEmitter.emit(INTERFACE)     [writes <Name>Builder.java via Filer]
    - interfaces stay on the sibling-emission path because there is no in-source
      mutation surface to inject into.
```
Runtime: generated `build()` calls `BuildFlagValidator.validate(this)` when `validate=true`, enforcing `@BuildFlag` constraints via a per-class cached field list. No classpath entries beyond the plugin jar.

Editor: `ClassBuilderAugmentProvider` surfaces the bootstrap methods to the PSI layer so autocompletion, goto-symbol, and type resolution all work before the first javac round. `ClassBuilderLineMarkerProvider` shows a gutter icon (placeholder `/icons/classbuilder_generated.svg`) on every `@ClassBuilder` annotation. The nested `Builder` class itself is NOT yet synthesised at editor time - `Target.Builder` references remain unresolved until a build runs, at which point the compiled class file makes the injected nested class visible.

JDK compatibility: `mutate/compat/` carries per-JDK shims behind `JavacCompat`. Day-one all sweep JDKs (17, 21, 25) share the `v17` baseline; the `v21`/`v23`/`v25` subclasses are placeholders for future divergence. Adding a new JDK = one new subclass + one conditional line in `JavacCompatFactory`.

Consumer requirements: javac-only (no ecj). Consumers must configure the same `--add-exports=jdk.compiler/com.sun.tools.javac.*=ALL-UNNAMED` flags the plugin's own build uses (see `build.gradle.kts`), since the mutator reaches into internal javac APIs.

### @ClassBuilder test strategy

APT tests run in the `aptTest` source set with a separate `aptTest` Gradle task, NOT the IntelliJ-platform-sandboxed `test` task. This is because the IntelliJ test framework's module layer hides `jdk.compiler`, which `com.google.testing.compile` requires. The `aptTest` task adds the necessary `--add-exports` JVM args. All inspection / PSI tests stay in the regular `test` source set.

## Key Configuration

- **Platform**: IntelliJ IDEA Community (IC) 2023.2, `sinceBuild = "232"`
- **Java**: source/target compatibility 17
- **Gradle IntelliJ Platform Plugin**: 2.6.0
- **Bundled plugin dependency**: `com.intellij.java` (for PSI/UAST Java support)
- `buildSearchableOptions = false` (speeds up builds during development)