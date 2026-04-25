# Simplified Annotations Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Versions 1.0.0 through 1.0.5 were published under the legacy plugin ID
`dev.sbs.simplified-annotations` and Maven coordinate `dev.sbs:simplified-annotations`.
Versions 2.0.0 onward are published under `dev.simplified.simplified-annotations` /
`io.github.simplified-dev:annotations`. See the 2.0.0 entry for the rename details.

## [2.0.0]

### Changed

- **Namespace migration** - the `dev.sbs.*` Java packages and the `dev.sbs:simplified-annotations` Maven coordinate are retired. New Maven coordinate is `io.github.simplified-dev:annotations`; the public-API package is now `dev.simplified.annotations.*` (plural). The JetBrains plugin is re-published under ID `dev.simplified.simplified-annotations` with vendor "Simplified Dev". This is source-breaking for Maven consumers - update imports from `dev.sbs.annotation.*` to `dev.simplified.annotations.*`. The legacy `dev.sbs:simplified-annotations` 1.x line on Maven Central and the JetBrains plugin `dev.sbs.simplified-annotations` will receive no further updates; existing installs of the legacy plugin see a one-time redirect notice (1.0.5).

## [1.4.0]

### Added

- **Automatic `--add-exports` module opening** - the annotation processor opens `jdk.compiler/com.sun.tools.javac.*` packages at load time via a `sun.misc.Unsafe` + `MethodHandles.Lookup.IMPL_LOOKUP` bootstrap (same technique Lombok uses). Consumers no longer need to pass `--add-exports` flags in their build configuration. Version-gated via `JavacAccess` / `JavacAccessFactory` / `JavacAccessV17` mirroring the existing `JavacCompat` design so future JDK hardening is absorbed by a new shim subclass + one gate.
- **End-to-end `@ClassBuilder` runtime showcase** - new `library/src/showcase/` source set packaged as a standalone runnable jar that exercises every runtime-observable configuration of `@ClassBuilder` + companions (`@BuildRule`, `@BuildFlag`, `@ObtainVia`, `@Collector`, `@Negate`, `@Formattable`) against real APT-generated builders. A new parameterised JUnit test in `:library:test` execs the jar in a fresh JVM and surfaces one Gradle test row per CASE (43 rows), with a sibling coverage-drift assertion. The showcase artifact lives in `build/showcase/` and is deliberately excluded from the Maven Central publication.

### Changed

- **Merged generic field-level annotations into `@BuildRule`** - `@BuilderDefault`, `@BuilderIgnore`, `@BuildFlag`, and `@ObtainVia` collapse into a single parent `@BuildRule(retainInit = ..., ignore = ..., flag = @BuildFlag(...), obtainVia = @ObtainVia(...))`. `@BuildFlag` and `@ObtainVia` become nested-only (`@Target({})`); `@BuildRule` is `@Retention(RUNTIME)` so `BuildFlagValidator` continues to reflectively read the nested `@BuildFlag`. Type-specific companions (`@Negate`, `@Formattable`, `@Collector`) stay standalone. Pre-release, no deprecation aliases - consumers migrate via find-and-replace.

### Fixed

- **`@BuildRule(flag = @BuildFlag(...))` runtime validation now actually fires on AST-mutated targets.** The generated `build()` method previously called `BuildFlagValidator.validate(this)` against the Builder, whose synthesised fields carry no annotations - so every `nonNull` / `notEmpty` / `pattern` / `limit` / `group` constraint silently no-op'd on classes and records. `build()` now constructs the target first, validates it, then returns. Same fix mirrored in the sibling-emitter path used for interface targets. Surfaced by the new showcase harness.
- **`@BuildRule(retainInit = true)` is now honoured on the AST-mutation path** (classes and records). Previously only the sibling-emitter path used for interface targets supported it. Implemented via a private static `$default$<fieldName>()` provider injected onto the target class - the provider holds a deep-cloned copy of the original initializer expression with `sym` / `type` / `pos` pointers reset so javac re-attributes it inside the method body's scope, and the generated Builder's field default becomes a static call to that provider. Supports arbitrary expressions (`UUID.randomUUID()`, `new ArrayList<>()`, `List.of(...)`, ternaries, field accesses, etc.) and evaluates them fresh on every `build()` invocation.

### Removed

- **`@BuilderDefault` and `@BuilderIgnore`** - replaced by `@BuildRule(retainInit = true)` and `@BuildRule(ignore = true)` respectively.

## [1.3.0]

### Added

- **Auto-generated bootstrap methods** - `builder()`, `from(T)`, and `mutate()` are now injected on the annotated type automatically. Hand-written methods with the same name and arity win (skip-on-collision + `Kind.NOTE`). The bootstrap-methods inspection is retired.
- **SuperBuilder for abstract classes** - `@ClassBuilder` on an abstract class produces a self-typed `Builder<T extends Target, B extends Builder<T, B>>` with abstract `build()` and `self()`. Concrete subclasses carrying `@ClassBuilder` automatically inherit the parent's builder (`class Builder extends Super.Builder<ThisType, ThisType.Builder>`), override `self()` and `build()`, and get a protected copy constructor. Opt out with `generateCopyConstructor = false`.
- **IDE augmentation** - a new `PsiAugmentProvider` surfaces the injected bootstrap methods to the PSI layer, so autocompletion, goto-symbol, and type resolution all work before the first javac round. A gutter icon (replaceable SVG at `/icons/classbuilder_generated.svg`) marks every `@ClassBuilder` annotation.
- **Multi-JDK support** - versioned compat layer under `dev.simplified.classbuilder.mutate.compat` with a single `v17` baseline dispatched by `Runtime.version().feature()`. aptTest matrix covers JDK 17, 21, and 25.

### Changed

- **AST-mutation pivot** - `@ClassBuilder` now injects a `public static class Builder` directly into the annotated class via javac AST mutation rather than emitting a sibling `<TypeName>Builder.java`. Interface targets still emit sibling `<Name>Impl.java` + `<Name>Builder.java` since there is no in-source mutation surface on an interface body.
- **Consumer requirement** - javac-only (ecj not supported). Consumers' builds need `--add-exports=jdk.compiler/com.sun.tools.javac.*=ALL-UNNAMED` on compile; see `build.gradle.kts` for the full list.
- **Gradle 9.4.1** - wrapper bumped so JDK 25 is natively supported without toolchain workarounds; unused Kotlin JVM plugin dropped.

## [1.2.0]

### Added

- **New @ClassBuilder annotation** - generates a sibling `<TypeName>Builder.java` via a JSR 269 annotation processor. Supports classes, records, and interfaces (interfaces also get a matching `<Name>Impl`). Full Lombok `@Builder` parity plus opinionated extras: configurable method prefix, `builderName`/`builderMethodName`/`buildMethodName`/`fromMethodName`/`toBuilderMethodName`, generated `from(T)` + `mutate()`, and emitted `@XContract` on every setter so IDE data-flow understands fresh-object and this-return shapes.
- **New field-level companions** - `@Collector` (collection/map varargs + iterable bulk overloads, with opt-in single-element add/put, clear, and lazy put-if-absent for maps), `@Negate` (paired inverse boolean setters), `@Formattable` (`@PrintFormat` overloads with null-tolerant variants for `Optional<String>` fields), `@BuilderDefault`, `@BuilderIgnore`, `@ObtainVia`.
- **@BuilderDefault source-initializer copying** - the generated builder now reproduces the field's declared initializer verbatim, with type references (e.g. `UUID.randomUUID()`, `List.of(...)`) auto-imported. Reports a compile error if `@BuilderDefault` is applied to a field with no initializer.
- **@ObtainVia accessor redirection** - the generated `from(T)` honours `method`, `field`, and `isStatic`, so builders can reconstruct from types that don't expose a standard getter.
- **New @BuildFlag runtime validator** - enforces `nonNull`, `notEmpty`, `group` mutual-requirement, regex `pattern`, and length/size `limit` constraints in the generated `build()`. Zero external dependencies.
- **New ClassBuilder bootstrap inspection** - ERROR-severity check that the three bootstrap methods (`builder()` / `from(T)` / `mutate()`) are materialised on the annotated class, with a quick-fix that inserts them all at once with matching `@XContract` annotations.
- **New ClassBuilder field inspection** - flags misuse of the companion annotations (e.g. `@Formattable` on a non-String field) at source-edit time.

## [1.1.0]

### Added

- **@XContract annotation** - superset of JetBrains `@Contract` with relational comparisons, `&&`/`||` grouping, named-parameter references, `instanceof` checks, typed `throws` returns, chained comparisons, and full `pure`/`mutates` support. A synthetic `@Contract` is inferred so IntelliJ data-flow works from a single annotation.
- **XContract Call-Site inspection** - flags calls whose literal arguments deterministically trigger a `fail` or `throws` clause.
- **ResourcePath Base-Prefix Usage inspection** - warns when a `@ResourcePath(base="X")` parameter is passed raw into a resource-loading call, with a quick-fix that prepends `X/`. Also flags base mismatches across call boundaries.
- **Settings** - additional resource-root paths, glob-based file exclusions, split severity dropdowns, inheritance and mutates checks.

### Changed

- **Modernised PSI listener** - narrowed to annotation events only; replaced deprecated `DaemonCodeAnalyzer.restart(PsiFile)`.

### Fixed

- **ResourcePath freeze fix** - removed the project-wide `ReferencesSearch` that locked up the IDE on large utility files.

## [1.0.5]

### Changed

- **Plugin moved** - final release of the legacy `dev.sbs.simplified-annotations` plugin. Description and change-notes replaced with a redirect notice pointing users at *Simplified Annotations* by *Simplified Dev* on the JetBrains Marketplace. No functionality changes; this listing receives no further updates. New installs should use the new plugin ID `dev.simplified.simplified-annotations` (see 2.0.0 above).

## [1.0.4]

### Fixed

- **Heavy lag in 800+ line files** - removed an inspection traversal hot path that locked up the IDE on large utility files.

## [1.0.3]

### Added

- **Inspection settings** - persisted highlight-level and enabled-by-default toggles surfaced through `plugin.xml` defaults; settings now round-trip across IDE restarts.
- **Startup indexing safety net** - secondary check during startup so literal-string analysis no longer races the indexing phase on cold-open projects.

### Changed

- Inspection enabled by default at ERROR severity.
- Method-call inspection code consolidated for readability.

## [1.0.2]

### Fixed

- **`IndexNotReadyException` on IDE startup** - inspection no longer attempts PSI resolution before the project index is ready.

## [1.0.1]

### Changed

- **Publishing pipeline cleanup** - publish directory empties on build to prevent stale-artifact hangs; documentation links added.

## [1.0.0]

### Added

- **Initial release** - `@ResourcePath` annotation for fields, parameters, and methods with an optional `base` directory prefix.
- **Resource Path inspection** - validates that the resolved string expression at every annotated site refers to a file that exists in the project's source/resource roots. Reports a problem at edit time when the file is missing.
- **String expression evaluator** - resolves literal string values across literals (`ULiteralExpression`), concatenation (`UPolyadicExpression`), final/static/enum field references, local variable declarations, recursive method-call return values, and UAST local variables. Bounded recursion guards against cycles.
- **Change-tracking listener** - narrow `PsiTreeChangeAdapter` keyed on `@ResourcePath` annotations, requesting `DaemonCodeAnalyzer` re-analysis on add/remove/replace events.
- **Maven Central + JetBrains Marketplace publication** - dual-target build producing both a publishable jar (`dev.sbs:simplified-annotations`) and a sandboxed plugin distribution (`dev.sbs.simplified-annotations`). Sources jar, javadoc jar, and signed POM included.
