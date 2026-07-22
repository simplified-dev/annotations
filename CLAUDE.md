# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an **IntelliJ IDEA plugin + Maven Central annotation library** that provides three annotation families, each with companion tooling:

- `@ResourcePath` - evaluates string expressions at annotated sites and verifies the referenced resource files exist in the project's source/resource roots. Pure inspection, no code generation.
- `@XContract` - a superset of JetBrains `@Contract` with a richer grammar (relational comparisons, `&&`/`||` with grouping, field access, named-parameter references, integer/boolean constants). The plugin synthesises an equivalent `@Contract` via an `InferredAnnotationProvider` so IntelliJ's data-flow analysis works from a single annotation.
- `@ClassBuilder` - generates a sibling `<TypeName>Builder.java` via a JSR 269 annotation processor. Supports classes, records, and interfaces. Setter shapes cover Optional dual setters, boolean zero-arg + typed pairs with optional negation, String `@PrintFormat` overloads, `@Collector` varargs/iterable bulk overloads with opt-in single-element add/put, clear, and lazy put-if-absent for maps, and configurable method naming. Field initializers are retained as builder defaults by default (`@ClassBuilder(retainInit)`, overridable per field with `@BuilderDefault`). The `@BuildFlag` runtime validator enforces nonNull/notEmpty/group/pattern/limit in the generated `build()`. Every generated method carries a matching `@XContract` so IDE data-flow sees fresh-object and this-return shapes.

Published to:
- JetBrains Marketplace: plugin ID `dev.simplified.simplified-annotations` (from `:plugin` module)
- Maven Central: group `io.github.simplified-dev`, artifact `annotations` (from `:library` module)

## Module layout

Two-module Gradle build. The split falls on the IntelliJ-platform boundary -
library has zero IntelliJ classpath references and ships standalone to Maven;
plugin depends on library and adds the IDE tooling.

- `:library` - Maven-publishable. Contains `dev.simplified.annotations` (the annotations),
  `dev.simplified.classbuilder.apt` (JSR 269 processor), `dev.simplified.classbuilder.mutate`
  (javac AST mutation + compat), `dev.simplified.classbuilder.validate` (runtime validator),
  `META-INF/services/javax.annotation.processing.Processor`. The `aptTest` source
  set and the plain-JUnit tests for `validate/` live here.
- `:plugin` - JetBrains Marketplace. Contains `dev.simplified.classbuilder.editor` (PSI
  augmentation + line marker + icon provider), `dev.simplified.classbuilder.inspect`
  (field inspection + shared constants), `dev.simplified.contract` (contract DSL parser,
  only consumed by xcontract), `dev.simplified.xcontract` (@XContract inspections +
  inferred annotation provider), `dev.simplified.resourcepath` (@ResourcePath inspections),
  `META-INF/plugin.xml`, icons, inspection descriptions. Depends on `:library`
  via `implementation(project(":library"))`; the library jar is bundled under
  `lib/` in the plugin distribution zip.

Root `build.gradle.kts` is minimal - plugin versions + shared `group` / `version`
via `allprojects { }`. Everything else lives in the subprojects' own build scripts.

## Commands

```bash
# Compile both modules
./gradlew build

# Launch a sandboxed IDE instance with the plugin loaded
./gradlew :plugin:runIde

# Run all tests (library unit + aptTest + plugin fixture tests)
./gradlew test

# Library-only suites
./gradlew :library:test              # ~55 plain-JUnit tests
./gradlew :library:aptTest           # ~39 compile-testing-backed APT tests

# Cross-JDK aptTest sweep
./gradlew :library:aptTest -PaptTestJdk=17
./gradlew :library:aptTest -PaptTestJdk=25

# Plugin-only
./gradlew :plugin:test               # ~107 IntelliJ-fixture tests
./gradlew :plugin:verifyPlugin       # verifier against IC 2023.2 / 2024.3 / 2025.2
./gradlew :plugin:buildPlugin        # -> plugin/build/distributions/Simplified-Annotations-<ver>.zip

# Library publishing
./gradlew :library:publishToMavenLocal
./gradlew :library:publishReleasePublicationToCentralStagingRepository -PsignArtifacts=true
./gradlew :library:centralBundle     # -> library/build/distributions/*-bundle.zip
./gradlew :library:publishAndPackage # publishToMavenLocal + centralBundle
```

## Architecture

### Annotations (`dev.simplified.annotations`)
- `ResourcePath.java` — `@Retention(CLASS)` annotation targeting fields, parameters, and methods. Has an optional `base` attribute that prefixes the resolved path.
- `XContract.java` — `@Retention(CLASS)` annotation targeting methods and constructors. Attributes: `value` (semicolon-separated clauses), `pure`, `mutates` (comma-separated `this`/`io`/`paramN`). Full grammar is on the annotation's Javadoc.
- `ClassBuilder.java` — `@Retention(CLASS)` annotation targeting types/constructors/methods. Drives the builder processor. Full Lombok `@Builder` parity (`style`, `setters`, `builder`, `access`, `constructorAccess`, `validate`, `emitContracts`, `emitGenerated`, `factoryMethod`, `exclude`, `retainInit`, `generateImpl`, `generateCopyConstructor`). `constructorAccess` sets the visibility of the synthesised all-args ctor and defaults to package-private, matching Lombok; `access` governs the builder class and bootstrap methods and stays `PUBLIC`.
- `NamingStyle.java` / `SetterNames.java` / `BuilderNames.java` — the naming surface, replacing the retired `methodPrefix` and the five flat `*Name` attributes in 2.5.0. **The split is by cardinality, not by "field vs class"**: `@SetterNames` names the six roles generated *once per field* (`set`, `flag`, `add`, `put`, `compute`, `clear`), `@BuilderNames` names the five members generated *exactly once* (`type`, `builder`, `build`, `from`, `toBuilder`). That difference is the whole reason for two annotations - a per-field name **must** carry the `{}` placeholder or every field collides, while a once-per-target name defaults to a plain literal. `style` sets both halves; a written name always wins.
  - `{}` expands to the field name, `@Negate` stem, or `@Collector` singular for setters, and to the target's simple name for builder names (undocumented there deliberately - it works, but the alternatives to allowing it were worse and nobody should use it). Capitalised unless it opens the pattern.
  - `NONE` (`"-"`, not a legal identifier) suppresses; `INHERIT` (`""`) takes the style's. Four members refuse suppression: `set`, `type`, `build` in the annotations, enforced by the processor and the inspection.
  - **The boolean special case is gone**: the typed setter is the ordinary `set` role (`animated(boolean)`), and the zero-arg convenience is the separate `flag` role (`isAnimated()`). Before 2.5.0 both were hardcoded `is`-prefixed and unreachable by any attribute.
  - **`generateBuilder` / `generateFrom` / `generateMutate` retired with them.** Each duplicated its name attribute - four emission sites read `generateX() && !nameX().isEmpty()`. `BuilderScheme` resolves a `NONE` to the empty string, so emptiness is now the single opt-out signal. `generateImpl` and `generateCopyConstructor` stay: they switch synthesis strategy rather than suppressing a named member.
- `BuilderDefault.java` — `@Retention(CLASS)`, `@Target(FIELD)`. `boolean value() default true`. Overrides the class-level `@ClassBuilder(retainInit)` policy for one field: bare form retains the declared initializer, `@BuilderDefault(false)` opts out. A field with neither inherits the class setting.
- `BuilderIgnore.java` — `@Retention(CLASS)`, `@Target(FIELD)`. Marker; excludes the field from builder synthesis. Field-local alternative to `@ClassBuilder(exclude = ...)`.
- `BuildFlag.java` — `@Retention(RUNTIME)`, `@Target({FIELD, METHOD})`. Runtime validation (nonNull/notEmpty/group/pattern/limit). The only field companion needing RUNTIME retention, since `BuildFlagValidator` reflects it inside the generated `build()`. **`METHOD` exists solely for interface targets**, which declare no fields to carry a constraint: `FieldSpec.fromInterfaceAccessor` keeps the raw mirror and `InterfaceImplEmitter` copies it onto the `<Name>Impl` field, which is what the validator actually reads. Emission needed no change - `build()` already validated the constructed instance, whose runtime class is the `Impl`. It is the one companion whose `@Target` is wider than the place it takes effect, so the IDE inspection warns on a `@BuildFlag` written on any other method.
- `ObtainVia.java` — `@Retention(CLASS)`, `@Target(FIELD)`. Redirects how `from(T)` / `mutate()` read the field, overriding the default seeding ladder (below).
- `Generated.java` — `@Retention(CLASS)`, `@Target({TYPE, CONSTRUCTOR, METHOD, FIELD})`, no attributes. Marks every member the pipeline synthesises so coverage tools skip them. **Two properties are load bearing and neither is style**: the simple name must stay `Generated` because JaCoCo matches on it, and retention must stay `CLASS` - `SOURCE` never reaches a class file and an AST-injected member has no source to read instead. Gated by `@ClassBuilder(emitGenerated)`, default `true`, independent of `emitContracts`. It lands on synthesised members and synthesised types, **never on the annotated target** - marking the target would discard coverage for the author's own methods. Attachment is centralised in `AstMarkers.markGenerated(node, GeneratedAnnotations)` rather than at each `make.Modifiers(..)` site, which makes the invariant structural: a node the pipeline marks is a node whose class file carries the marker. The one-arg `markGenerated(node)` overload survives for nodes that cannot hold an annotation (a static initialiser block) and for `LazyFieldMutator`'s in-place rewrite of the author's own field declaration, which is marked for collision detection but must not be claimed as generated.

These four replaced a single `@BuildRule` parent in 2.5.0. Bundling them forced `RUNTIME` retention on all four to serve `flag` alone; splitting confines it to `@BuildFlag` and makes the field-level surface uniform with `@Collector` / `@Negate` / `@Formattable` / `@Lazy` / `@KeyField`.
- `Collector.java`, `Negate.java`, `Formattable.java` — field-level `@Retention(CLASS)` type-specific companions (collection/map, boolean, String).
- `AccessLevel.java` — enum with `toKeyword()`.

### Package layout
- `dev.simplified.resourcepath` — ResourcePath inspection + visitor + evaluator + change listener + startup activity.
- `dev.simplified.contract` — **annotation-neutral** contract-DSL grammar infrastructure: `ContractAst`, `ContractLexer`, `ContractParser`, `ContractParseException`. Reusable for any annotation that carries the same contract DSL.
- `dev.simplified.xcontract` — **specific to the `@XContract` annotation**: `XContractInspection` (declaration-side), `XContractCallInspection` (caller-side), `XContractInferredAnnotationProvider` (bridge to JetBrains `@Contract`), `XContractTranslator` (AST → `@Contract` string).
- `dev.simplified.classbuilder.apt` — JSR 269 annotation processor: `ClassBuilderProcessor` (entry, registered via `META-INF/services/javax.annotation.processing.Processor`, dispatches class/record targets to the mutator and interface targets to the sibling emitter), `FieldSpec` (per-field IR), `SourceIntrospector` (Trees-API bridge for reading declared initialisers), `AnnotationLookup` (mirror attribute reader), `BuilderConfig` (resolved annotation attributes), `NamePattern` + `SetterScheme` + `BuilderScheme` (the naming trio, below), `BuilderEmitter` (legacy source generator; now only handles interface targets since classes/records use AST mutation), `InterfaceImplEmitter` (emits the `<Name>Impl` concrete class for interface targets, copying each accessor's `@BuildFlag` onto the field it synthesises - re-escaping string attributes, since a `pattern` regex routinely carries backslashes).
  - **The naming trio is the single place any generated name is minted.** `NamePattern` holds the `{}` expander and the validator (`patternError(pattern, placeholderRequired)` - that boolean *is* the per-field / once-per-target distinction). `SetterScheme` resolves the six per-field patterns and mints their names; `BuilderScheme` resolves the five once-per-target names, already expanded and already emptied where suppressed. All three are deliberately free of javac, PSI, and `FieldSpec` references so `:plugin` shares the instances, and the five naming sites (`FieldMutators`, `SelfTypedSetters`, `BootstrapMethodFactory`'s seed-setter lookup, `BuilderEmitter`, and the editor's `GeneratedMemberFactory`) all route through them. Each used to carry a private `methodName(String, boolean forceBoolean)` plus inline `"is"` / `"add"` / `"put"` / `"clear"` literals that had to agree byte-for-byte or the editor's autocompletion diverged from javac's output.
  - `BuilderConfig` keeps flat `builderName()` / `builderMethodName()` / … accessors delegating to its `BuilderScheme`, so the ~30 emitter call sites did not move when the annotation surface regrouped.
- `dev.simplified.classbuilder.mutate` — javac AST mutation pipeline: `JavacBridge` (reflective gateway to `JavacProcessingEnvironment` internals), `MutationContext`, `BuilderMutator` (concrete/record orchestrator), `SuperBuilderMutator` (abstract-target and concrete-subclass orchestrator with self-typed generics), `AnnotatedSuper` (the annotated direct super plus the type arguments the target passes it), `NestedBuilderFactory`, `BootstrapMethodFactory`, `CopyConstructorFactory`, `AllArgsConstructorFactory` (synthesises the all-args ctor `build()` calls, on plain classes that declare none), `FieldMutators`, `SelfTypedSetters`, `JavacTypeFactory`, `AstMarkers`.
  - **`from(T)` / `mutate()` seeding is a ladder, not a bean-accessor assumption.** `BootstrapMethodFactory.readFrom` resolves each field in order: `@ObtainVia`, then a record component's canonical accessor, then a `@Lazy` field's synthesised getter (pinned, since storage is `Lazy<T>` and a field read would yield the wrapper), then an author-declared zero-arg accessor in any spelling a getter generator produces (`getX` / `isX` / bare `x`), then a direct field read, then the bean call with a `NOTE`. A declared accessor deliberately outranks the field read: when the author wrote the accessor, a normalising or defensive-copying body is the behaviour they asked for. The direct read is legal for anything the target declares - `from(T)` and `mutate()` are members of the target - and for an inherited field only when `public`/`protected`/same-package. This is what closed F1: before it, the emitted `instance.getX()` named a method nobody had written, so a `@ClassBuilder` target had to carry Lombok `@Getter` whether or not it wanted public accessors. The last rung stays a `NOTE` rather than an error because an accessor generated later in the same round is invisible to any scan of the model, so erroring would regress a build that works.
  - `MutationContext.declaresAccessor(name)` backs rung four. The snapshot is taken **in the constructor, before any mutator appends** - the only moment "declared by the author" and "present on the tree" are the same set, since `LazyFieldMutator` synthesises a getter onto the target immediately afterwards. It unions the target's own `defs` with `Elements.getAllMembers` (which is the only view of an inherited accessor, and matters for a SuperBuilder subclass seeding its parent's fields), minus `java.lang.Object`'s own methods.
  - **Generic targets**: a nested `Builder` is `static` and cannot see the enclosing type's variables, so `MutationContext.typeParams()` / `typeArgs()` / `targetType()` / `builderType()` mint fresh declaration and reference nodes (javac trees cannot be shared between parents, so every call returns new ones). Every emitter goes through those rather than `make.Ident(builderName)` — returning the bare name from a setter erases the builder to a raw type and silently drops the parameter for the rest of the chain. Static members (`builder()`, `from(T)`, non-instance `$default$` providers) declare their own copies. `MutationContext.selfTypeName()` / `selfBuilderName()` pick the SuperBuilder `T` / `B` names, dodging a collision when the target declares parameters by those names; both `SuperBuilderMutator` (which declares them) and `SelfTypedSetters` (which returns them) read from there so they cannot drift. `compat/` subpackage carries `JavacCompat` interface + `JavacCompatFactory` (single entry point, ready to version-gate) + `v17/JavacCompatV17` baseline. Every supported JDK (17 through 25) uses the baseline today; a future divergence is absorbed by adding a new `v<N>/JavacCompatV<N>` subclass plus one gate in the factory.
- `dev.simplified.classbuilder.editor` — IntelliJ editor-side synthesis: `ClassBuilderAugmentProvider` (surfaces bootstrap methods to the PSI layer so autocompletion works before the first javac round), `ClassBuilderLineMarkerProvider` (gutter icon on `@ClassBuilder` annotations), `GeneratedMemberFactory` + `GeneratedMemberMarker` (synthesis helpers and provenance key), `PsiFieldShape` + `PsiFieldShapeExtractor` (PSI analogue of `FieldSpec`), `OptionalSetterNullIntention` (Alt+Enter fix for the Optional dual setter's one ambiguous call).
  - **`OptionalSetterNullIntention`** rewrites a bare `x(null)` to `x(Optional.empty())`. An `Optional<T>` field generates `x(T)` + `x(Optional<T>)` deliberately - the wrapping belongs inside the builder, not at every call site - and the price is that a *literal* null is ambiguous per JLS 15.12.2.5. Only `Optional.empty()` is offered: the raw overload wraps with `ofNullable`, so `x((T) null)` stores the identical value while saying less. Keyed on the **candidate shape** (one `Optional<T>` param, one `T` param, same name) rather than on `GeneratedMemberMarker`, because a builder read from a compiled dependency carries no marker and the fix is equally right there. See notes C8 for why the single-setter mode was closed instead.
  - **Generic targets**: `GeneratedBuilderClass` re-declares the target's type parameters (bounds included) because it is `static`. Those copies are *distinct* `PsiTypeParameter`s from the target's, so anything declared inside the Builder must be expressed in them — `GeneratedMemberFactory.remap(...)` builds the target→Builder substitutor and `PsiFieldShapeExtractor.fromClass/fromRecord` apply it before classification, which is what makes a `Builder<String>` receiver actually substitute the setter parameter. Leaving a setter holding the target's parameter compiles fine and looks right in the PSI, but the editor then rejects every call with "cannot be applied to". Static bootstraps (`builder()`, `from(T)`) declare their own method-level copies via `DocProxyingLightMethodBuilder.withTypeParameters(...)` — `LightMethodBuilder` has a `getTypeParameterList()` but no setter, so the list is held in the subclass and surfaced through three overrides. `mutate()` is an instance method and uses the target's directly.
  - **SuperBuilder chains**: `ChainRole` classifies a target the same way `BuilderMutator.mutate` branches (abstract? annotated direct super?) so the editor's model matches what javac emits. An `ABSTRACT_ROOT`/`CHAINED_ABSTRACT` Builder is `abstract` and self-typed `<T extends Target, B extends Builder<T,B>>` with setters returning `B` and abstract `self()`/`build()`; a `CONCRETE_LINK` sets `extends Parent.Builder<superArgs…, Target, Builder>` via `GeneratedBuilderClass.setSuperType(...)`. Two things are load-bearing and easy to get wrong: (1) without the `extends`, the platform's inherited-member lookup has nothing to walk and every setter declared further up reads as unresolved; (2) without the self-typed `B`, an inherited setter returns the *parent's* Builder, so `.parentSetter(...).childSetter(...)` resolves in one call order and not the other. The parent's synth Builder is resolved through the augment-aware `getInnerClasses()` rather than by name, so the reference points at the same instance the platform hands out; re-entering for the parent is safe because `IN_PROGRESS` is keyed per target and a root has no super to walk to. Inherited setters are *not* copied onto the link — they arrive through the supertype, which is why `findMethodsByName("t", false)` is empty there while `(…, true)` finds it.
- `dev.simplified.classbuilder.validate` — runtime: `BuildFlagValidator` (reflective, per-class cached), `BuilderValidationException`, `Strings.formatNullable` helper.
- `dev.simplified.classbuilder.inspect` — IDE-side: `ClassBuilderFieldInspection` (flags misuse of companion annotations at source-edit time, plus malformed `@SetterNames`/`@BuilderNames` patterns and the four members that refuse `NONE`), `ClassBuilderConstants` (shared FQNs + attribute readers, including `namingStyle`/`setterScheme`/`builderScheme` and the `writtenStringAttr` reader that mirrors the processor's `getElementValues()` written-vs-defaulted view — `stringAttr` uses `findAttributeValue` and folds empty into the fallback, so it cannot tell an explicit value from an unwritten attribute). The bootstrap-methods inspection was retired in 1.3.0 since the methods are now auto-injected.

### Inspection (`dev.simplified.inspection`)

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

### Extended contract (`dev.simplified.contract` + `dev.simplified.xcontract`)

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
      AllArgsConstructorFactory emits the ctor build() calls, ahead of the
        @Lazy rewrite so lazy params/assignments are retyped with it. Skipped
        for records, SuperBuilder targets, a set factoryMethod, a fieldless
        target, or any author-declared ctor (Lombok @Builder's rule).
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
Runtime: generated `build()` calls `BuildFlagValidator.validate($result)` when `validate=true`, enforcing `@BuildFlag` constraints via a per-class cached field list. No classpath entries beyond the plugin jar. **The "nothing to validate" decision lives entirely in the validator** - it keys on `$result.getClass()`, the actual runtime type, and returns on an empty cached list. The processor makes no attempt to prove the call is a no-op: a `factoryMethod` can return a subtype whose flags it cannot see, and the validator's superclass walk reaches `@BuilderIgnore`d, excluded, static, and inherited fields the builder never models.

Editor: `ClassBuilderAugmentProvider` surfaces the bootstrap methods AND the nested `Builder` class to the PSI layer so autocompletion, goto-symbol, and type resolution all work before the first javac round. The synthesised Builder mirrors `FieldMutators.setters` in full - boolean zero-arg/typed pair plus `@Negate` inverse, `Optional` nullable-raw/wrapped plus `@Formattable` overload, `@Collector` varargs/iterable bulk overloads with opt-in single-element add/put/clear and (map) put-if-absent, array varargs, String `@Formattable` overload. Parameter-level annotations (`@PrintFormat`, `@Nullable`, `@NotNull`) propagate live from field annotations via `buildParam` + type-use annotations. `ClassBuilderLineMarkerProvider` shows a gutter icon (`/icons/classbuilder_generated.svg`) on every `@ClassBuilder` annotation.

JDK compatibility: `mutate/compat/` carries the `JavacCompat` interface plus the `JavacCompatV17` baseline. Every currently supported JDK (17 through 25) uses the baseline because every javac internal the pipeline touches has been stable across those versions. `JavacCompatFactory.forRuntime()` stays wired up as the single entry point so a future divergence is a new subclass + one gate - no caller change required.

Consumer requirements: javac-only (no ecj). Consumers must configure the same `--add-exports=jdk.compiler/com.sun.tools.javac.*=ALL-UNNAMED` flags the plugin's own build uses (see `build.gradle.kts`), since the mutator reaches into internal javac APIs.

### @ClassBuilder test strategy

APT tests run in the `aptTest` source set with a separate `aptTest` Gradle task, NOT the IntelliJ-platform-sandboxed `test` task. This is because the IntelliJ test framework's module layer hides `jdk.compiler`, which `com.google.testing.compile` requires. The `aptTest` task adds the necessary `--add-exports` JVM args. All inspection / PSI tests stay in the regular `test` source set.

## Key Configuration

- **Platform**: IntelliJ IDEA Community (IC) 2023.2, `sinceBuild = "232"`
- **Java**: source/target compatibility 17
- **Gradle IntelliJ Platform Plugin**: 2.6.0
- **Bundled plugin dependency**: `com.intellij.java` (for PSI/UAST Java support)
- `buildSearchableOptions = false` (speeds up builds during development)