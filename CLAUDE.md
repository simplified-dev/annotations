# CLAUDE.md


## Project Overview

This is an **IntelliJ IDEA plugin + Maven Central annotation library**. It ships seventeen
annotations that do work, plus the field- and member-level companions those read, and each
one comes in two halves: a javac half that emits, and an IDE half whose job is to make the
editor say exactly what javac will emit. **That pairing is the project's central invariant** -
a green editor over source javac rejects, or a red editor over source that builds, is the
failure mode nearly every design decision here is defending against.

Two families are pure inspection and generate nothing:

- `@ResourcePath` - evaluates string expressions at annotated sites and verifies the referenced resource files exist in the project's source/resource roots.
- `@XContract` - a superset of JetBrains `@Contract` with a richer grammar (relational comparisons, `&&`/`||` with grouping, field access, named-parameter references, integer/boolean constants). The plugin synthesises an equivalent `@Contract` via an `InferredAnnotationProvider` so IntelliJ's data-flow analysis works from a single annotation.

The rest generate, and **all but one generate by mutating the javac AST in place rather than
by emitting a sibling file**. The exception is load-bearing: `@ClassBuilder` on an *interface*
has no in-source mutation surface to inject into, so that one path emits `<Name>Impl.java` and
`<Name>Builder.java` as source text - which is why the interface path keeps turning out to be a
second implementation of whatever the AST path already does.

- `@ClassBuilder` - synthesises a nested `Builder` plus `builder()` / `from(T)` / `mutate()`. Classes, records and interfaces. Setter shapes cover Optional dual setters, boolean zero-arg + typed pairs with optional negation, String `@PrintFormat` overloads, `@Collector` varargs/iterable bulk overloads with opt-in single-element add/put, clear, and lazy put-if-absent for maps, and configurable method naming. Field initializers are retained as builder defaults by default (`@ClassBuilder(retainInit)`, overridable per field with `@BuilderDefault`). The `@BuildFlag` runtime validator enforces nonNull/notEmpty/group/pattern/limit in the generated `build()`. Every generated method carries a matching `@XContract` so IDE data-flow sees fresh-object and this-return shapes.
- `@Getter` / `@Setter` - read and write accessors, defaulting to bean-shaped names because a bare `@Getter` has to keep producing `getX()` or every existing call site is renamed.
- `@AllArgsConstructor` / `@RequiredArgsConstructor` / `@NoArgsConstructor` / `@BuilderArgsConstructor` - one field-selection policy with four settings, over one mutator.
- `@EqualsAndHashCode` / `@ToString` - the whole-object members, over one shared member selector and one term emitter. Records are the shape they exist for and the shape Lombok refuses.
- `@UtilityClass` - `final` plus a throwing constructor, with the implicit-`static`-on-members half deliberately opt-in behind `members = MAKE_STATIC`.
- `@Lazy` - retypes a field's storage from `T` to `Lazy<T>` and synthesises a memoizing getter, turning an initializer into the supplier body.
- `@EnumLookup` - a cached values array plus a uniform set of static lookup helpers on an enum, with `@KeyField` adding a parallel key array and `of<Name>` / `findBy<Name>` per field.
- `@Log` - one `private static final org.apache.logging.log4j.Logger` field, named through `NamePattern` and initialised from the target's raw class literal or a written `topic`. **Single-backend and always static**, which is what lets it own a processor: with no instance form there is no field the builder's collector can mistake for a property, so it has no ordering relationship with `@Lazy` or `@ClassBuilder` and needs no dispatch from `ClassBuilderProcessor`.
- `@SilentThrows` / `@Cleanup` - the two body rewrites, and the only passes here that change control flow rather than appending a member. They must run last, and after `@Lazy`.

Published to:
- JetBrains Marketplace: plugin ID `dev.simplified.simplified-annotations` (from `:plugin` module)
- Maven Central: group `io.github.simplified-dev`, artifact `annotations` (from `:library` module)

## Module layout

Two-module Gradle build. The split falls on the IntelliJ-platform boundary -
library has zero IntelliJ classpath references and ships standalone to Maven;
plugin depends on library and adds the IDE tooling.

**Packages are named per feature and mirrored across the module boundary** - a feature is
`dev.simplified.<feature>.{apt,mutate}` in library and `dev.simplified.<feature>.{editor,inspect}`
in plugin. The convention is what makes the two halves of a feature findable from either side,
and a feature missing one of its four is usually a gap rather than a decision.

- `:library` - Maven-publishable. `dev.simplified.annotations` holds every annotation and
  the enums they read (`AccessLevel`, `NamingStyle`, `CallSuper`). One feature package per
  family: `accessor`, `args`, `classbuilder`, `cleanup`, `enumlookup`, `equality`, `lazy`,
  `log`, `silentthrows`, `tostring`, `utility` - each with an `apt` half (annotation-model reads,
  resolved config, per-member IR) and a `mutate` half (javac AST emission).
  `dev.simplified.shared` carries what more than one feature needs: `shared.apt`
  (`MemberSelector`, `MemberPolicy`, `MemberSpec`, `MemberShape`, `SuperResolver`),
  `shared.javac` (`AstMarkers`, `MemberTerms`, `AnnotationSpelling`, `ContractAnnotations`)
  and `shared.javac.compat` (the `JavacCompat` interface, its factory, and the `v17` baseline
  every supported JDK still uses). **Two packages exist at runtime rather than at compile
  time** and that is the whole distinction: `classbuilder.validate` (`BuildFlagValidator`,
  reflected from inside the generated `build()`) and `dev.simplified.lazy` (`Lazy<T>`, which
  has to exist at runtime because it *holds* the memoized value). Plus
  `META-INF/services/javax.annotation.processing.Processor`. Source sets: `main`, `test`
  (plain JUnit), `aptTest` (compile-testing, its own task because the IntelliJ test
  framework's module layer hides `jdk.compiler`), and `showcase` - the one place the library
  is exercised against Lombok on the same processor path.
- `:plugin` - JetBrains Marketplace. The same feature names - `accessor`, `args`,
  `classbuilder`, `cleanup`, `enumlookup`, `equality`, `lazy`, `log`, `silentthrows`,
  `tostring`, `utility` - with `editor` (PSI augment
  providers, line markers, intentions) and `inspect` (inspections plus the PSI analogue of
  whatever constants the processor half reads) halves, plus three that exist only here:
  `dev.simplified.contract` (the annotation-neutral contract-DSL grammar, consumed only by
  xcontract), `dev.simplified.xcontract` and `dev.simplified.resourcepath` (both
  inspection-only features with no library half at all), and `dev.simplified.shared.psi` /
  `dev.simplified.util` for the platform helpers several features share. Also
  `META-INF/plugin.xml`, icons and inspection descriptions. Depends on `:library` via
  `implementation(project(":library"))` - which is what lets the naming trio, `ArgsSelection`
  and the other javac-free decision classes be *shared instances* rather than reimplemented,
  the single most common source of editor-versus-build drift. The library jar is bundled
  under `lib/` in the plugin distribution zip.

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
./gradlew :library:test              # plain JUnit
./gradlew :library:aptTest           # compile-testing-backed APT tests

# Cross-JDK aptTest sweep
./gradlew :library:aptTest -PaptTestJdk=17
./gradlew :library:aptTest -PaptTestJdk=25

# Plugin-only
./gradlew :plugin:test               # IntelliJ-fixture tests
./gradlew :plugin:verifyPlugin       # verifier against IC 2023.2 / 2024.3 / 2025.2
./gradlew :plugin:buildPlugin        # -> plugin/build/distributions/Simplified-Annotations-<ver>.zip

# Library publishing
./gradlew :library:publishToMavenLocal
./gradlew :library:publishReleasePublicationToCentralStagingRepository -PsignArtifacts=true
./gradlew :library:centralBundle     # -> library/build/distributions/*-bundle.zip
./gradlew :library:publishAndPackage # publishToMavenLocal + centralBundle
```

## Pass ordering (load-bearing)

Mutation passes run in one fixed order, and getting it wrong fails on a line the author never wrote:

1. Constructor family (`@AllArgsConstructor` and siblings) - before `@Lazy` retypes its parameters and before the builder decides whether to synthesise its own.
2. `processAccessors` (`@Getter` / `@Setter`) - `useAccessors` downstream has to see every accessor the pass minted.
3. `@EqualsAndHashCode` / `@ToString` - after the accessor pass, above the body rewrites.
4. `LazyFieldMutator`.
5. `@SilentThrows` and `@Cleanup` **last**, after `@Lazy`. Both relocate statements one level down, so running either earlier skips the `Lazy.of(...)` wrap and javac reports `Supplier<T>` against `Lazy<T>`. Order *between* the two is free. Pinned by `PassOrderingTest`.

`@Getter`/`@Setter`, the constructor family, and the whole-object pair are dispatched from `ClassBuilderProcessor` rather than owning processors **precisely so this ordering is a guarantee** - processor order within a round is unspecified. `@UtilityClass` and `@Log` own their processors because they have no ordering relationship with anything.

Never use a shared idempotency marker across two passes: `AstMarkers.markPass(node, pass)` is per-pass, and collapsing it back to the global `GENERATED` set makes the passes mutually exclusive with no symptom but a compile error on the author's own line.

## @ClassBuilder test strategy

APT tests run in the `aptTest` source set with a separate `aptTest` Gradle task, NOT the IntelliJ-platform-sandboxed `test` task. This is because the IntelliJ test framework's module layer hides `jdk.compiler`, which `com.google.testing.compile` requires. The `aptTest` task adds the necessary `--add-exports` JVM args. All inspection / PSI tests stay in the regular `test` source set.

## Deep dives

Detailed internals live as skills in `.claude/skills/`, loaded on demand rather than every session:

- `annotation-surface` - per-annotation reference: what each annotation emits, its attributes and legal targets, and the failure-mode argument behind each default.
- `package-layout` - package-by-package internals across the library/plugin boundary: processors, mutators, augment providers, inspections, shared components.
- `classbuilder-pipeline` - the end-to-end `@ClassBuilder` round, AST path vs interface sibling-emission path, runtime validation, editor synthesis, JDK compatibility.
- `accessor-visibility` - `GetterVisibilityInspection` triggers and `PromoteToGetterFix` matching rules.
- `resourcepath-inspection` - `@ResourcePath` inspection entry points, visitor hooks, string-expression evaluation, change service.
- `xcontract` - contract DSL lexer/parser/AST, declaration- and caller-side inspections, the JetBrains `@Contract` bridge.
