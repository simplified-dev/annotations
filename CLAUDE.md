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

- `@ClassBuilder` - synthesises a nested `Builder` plus `builder()` / `from(T)` / `mutate()`. Classes, records and interfaces. Setter shapes cover Optional dual setters, boolean zero-arg + typed pairs with optional negation, String `@PrintFormat` overloads, `@Collector` varargs/iterable bulk overloads with opt-in single-element add/put, clear, and lazy put-if-absent for maps, and configurable method naming. Field initializers are retained as builder defaults by default (`@ClassBuilder(retainInit)`, overridable per field with `@BuilderDefault`). `@BuildFlag` constraints are resolved at processing time and enforced by nonNull/notEmpty/group/pattern/limit checks emitted into the generated `build()`. Every generated method carries a matching `@XContract` so IDE data-flow sees fresh-object and this-return shapes.
- `@Getter` / `@Setter` - read and write accessors, defaulting to bean-shaped names because a bare `@Getter` has to keep producing `getX()` or every existing call site is renamed.
- `@AllArgsConstructor` / `@RequiredArgsConstructor` / `@NoArgsConstructor` / `@BuilderArgsConstructor` - one field-selection policy with four settings, over one mutator.
- `@EqualsAndHashCode` / `@ToString` - the whole-object members, over one shared member selector and one term emitter. Records are the shape they exist for and the shape Lombok refuses.
- `@UtilityClass` - `final` plus a throwing constructor, with the implicit-`static`-on-members half deliberately opt-in behind `members = MAKE_STATIC`.
- `@Lazy` - retypes a field's storage from `T` to `AtomicReference<Supplier<T>>`, adds a `$value$<name>` sibling for the memoized result, and synthesises a memoizing getter, turning an initializer into the supplier body. Primitives are supported: the value slot stays primitive and only the supplier's type argument is boxed. The getter is named through its own `style` / `name` off the shared `AccessorScheme`, so it is spelled the way `@Getter` would spell it - including `isX()` on a boolean - while staying independent of any `@Getter` on the same field or type, which is what lets the accessor pass keep stepping over a lazy field.
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
  every supported JDK still uses). **Nothing here is needed at runtime** - every feature
  emits the code it needs into the target, so a consumer scopes the artifact `compileOnly`
  plus `annotationProcessor` whatever they use. `BootstrapMethodInjectionTest` pins that by
  asserting generated classes name no `dev/simplified/` type but the annotations. Plus
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
```

## Publishing - use the `publish` group, never the module tasks

Both artifacts of a release are built, checked and shipped through five root tasks.
Reach for these rather than `:library:centralBundle`, `:plugin:buildPlugin` or a
hand-passed `-PsignArtifacts`.

```bash
./gradlew publishBuild        # both artifacts, the bundle signed
./gradlew publishValidate     # publishBuild, then check what was built
./gradlew publishLocal        # install the library into ~/.m2 (unsigned, no GPG key needed)
./gradlew publishCentral      # publishValidate, confirm, then upload the bundle
./gradlew publishMarketplace  # publishValidate, confirm, then upload the plugin
```

- **Do not pass `-PsignArtifacts=true`.** Requesting any of `publishBuild`,
  `publishValidate`, `publishCentral` or `publishMarketplace` turns signing on by
  itself, read off the invocation in `library/build.gradle.kts`. The flag still works
  and is what a module-level task needs, but nothing in this group does.
  `publishLocal` is deliberately outside the set - it exists to try the library from a
  real consumer, which should not require a GPG key.
- **`publishValidate` reads both archives in place** - no unzipping to a temp
  directory, no `gpg --verify` round trip. It asserts the bundle carries one version
  matching the build, that every jar / pom / module has an `.asc` beside it, and that
  each `.asc` is armored PGP. On the plugin zip it asserts the packaged
  `plugin-<ver>.jar` and `library-<ver>.jar`, which is what catches a zip left behind
  by an earlier build and reported up to date.
- **What it does not prove:** presence and armor, not cryptographic validity. To
  check a signature for real, see the GPG recipe below.
- **The plugin zip is unsigned and that is expected.** JetBrains plugin signing needs
  a certificate chain and private key this project does not hold; `publishValidate`
  says so on its own line rather than passing in silence.
- **Uploads confirm before sending.** Interactive by default; pass `-Pupload=yes` or
  `-Pupload=no` when there is no console, which there is not in an agent session.
  `no` reports where the artifact sits and succeeds; anything but yes/no is rejected.
  On the Marketplace the confirmation is an `onlyIf` on `:plugin:publishPlugin`, not a
  check in `publishMarketplace` - a dependency runs *before* the task declaring it, so
  a confirmation asked there would be asked after the upload it authorises.
- **Both uploads are real, and the two are not equally recoverable.** A Marketplace
  upload is queued for a human moderator, so the release is not live when the task
  succeeds and a mistake is still catchable. `publishCentral` posts with
  `publishingType=AUTOMATIC`, which has no such gate: once validation passes the
  version is on Maven Central, and Central coordinates can never be withdrawn. Treat
  `publishCentral` as the sharper of the two even though they read alike.
- **`publishCentral` stops at `PUBLISHING`, on purpose.** That state means validation
  passed and the publish is under way, which is the last thing the build can usefully
  learn - `PUBLISHING` to `PUBLISHED` has been observed to take 15-20 minutes, and
  appearing on `repo1.maven.org` takes longer still. Waiting for either would block a
  build on something no longer in doubt. The five-minute timeout therefore only ever
  applies to `PENDING` / `VALIDATING` / `VALIDATED`, and if it fires the message says to
  check the Portal rather than re-upload - a second upload of a version Central already
  accepted is rejected, and there is no way to take the first one back.
- **Neither upload can be rehearsed.** There is no dry run against either service, and
  a version cannot be published twice. Anything to be checked has to be checked before
  the confirmation, which is what `publishValidate` is for.
- **Central is a hand-written call, by choice.** Sonatype ships no official Gradle
  plugin and marks every community one unsupported, so `publishCentral` posts the
  bundle to `/api/v1/publisher/upload` itself and polls `/api/v1/publisher/status`
  until `PUBLISHING`, `PUBLISHED` or `FAILED`. The `maven-publish` tasks
  (`publish`, `publishAllPublicationsTo…`, `publishReleasePublicationTo…`) cannot do
  this - the only repository declared is `centralStaging`, a local directory.
- **Never set `channels` on the plugin.** Its default is `default`, which *is* the
  stable channel. Writing `listOf("stable")` creates a custom channel of that name,
  which nobody sees without adding a repository URL by hand.

### Tokens

Both are read as an environment variable first, falling back to a Gradle property, so
either location works and neither is in the repo:

| Service | Environment variable | Gradle property |
|---|---|---|
| Maven Central | `MAVEN_CENTRAL_TOKEN` | `mavenCentralToken` |
| JetBrains Marketplace | `JETBRAINS_MARKETPLACE_TOKEN` | `jetbrainsMarketplaceToken` |

The Gradle property belongs in `~/.gradle/gradle.properties`
(`C:\Users\<user>\.gradle\gradle.properties`), which is outside the repo and is the
usual home for these. **A token in the project's own `gradle.properties` would be
committed** - that file is tracked.

`providers.gradleProperty` and `providers.environmentVariable` are different sources:
a value in `gradle.properties` is *not* visible to the environment lookup, which is why
both are wired. Do not `cat` either location to check a value - test for emptiness
instead (`[ -z "${MAVEN_CENTRAL_TOKEN:-}" ]`).

Central's token is a *pair*: the Portal generates a username and password, and the API
wants `Bearer <base64 of user:pass>`. Either form can be given - a value containing a
colon is encoded before sending, and base64 never contains one - so pasting
`username:password` straight in works.

Verifying a signature for real, which `publishValidate` deliberately does not:

```bash
GPG="/c/Program Files (x86)/GnuPG/bin/gpg.exe"
GNUPGHOME="$APPDATA/gnupg" "$GPG" --verify <file>.asc <file>
```

`$APPDATA/gnupg` is not optional - git-bash ships its own `gpg` pointing at an empty
keyring that prints nothing and reports no error.

## Pass ordering (load-bearing)

Mutation passes run in one fixed order, and getting it wrong fails on a line the author never wrote:

1. Constructor family (`@AllArgsConstructor` and siblings) - before `@Lazy` retypes its parameters and before the builder decides whether to synthesise its own.
2. `processAccessors` (`@Getter` / `@Setter`) - `useAccessors` downstream has to see every accessor the pass minted.
3. `@EqualsAndHashCode` / `@ToString` - after the accessor pass, above the body rewrites.
4. `LazyFieldMutator`.
5. `@SilentThrows` and `@Cleanup` **last**, after `@Lazy`. Both relocate statements one level down, so running either earlier skips the holder wrap and javac reports `Supplier<T>` against the field's storage type. Order *between* the two is free. Pinned by `PassOrderingTest`.

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
