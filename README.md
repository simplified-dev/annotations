# Simplified Annotations

Three Java annotations with matching IntelliJ IDEA tooling - covering static resource-path validation, an extended `@Contract` grammar, and a full-featured builder generator with runtime validation.

> [!IMPORTANT]
> `@ClassBuilder` uses javac AST mutation and **requires javac** (ecj is not supported). The processor opens `jdk.compiler` internals automatically at load time via `sun.misc.Unsafe` + `MethodHandles.Lookup.IMPL_LOOKUP` (same technique Lombok uses), so **no `--add-exports` flags are needed** in consumer builds. `@ResourcePath` and `@XContract` have no compiler dependency and work on any build.

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
  - [Installation](#installation)
  - [IntelliJ Plugin](#intellij-plugin)
  - [Supported JDKs](#supported-jdks)
- [Quick Start](#quick-start)
  - [@ResourcePath](#resourcepath)
  - [@XContract](#xcontract)
  - [@ClassBuilder](#classbuilder)
- [Annotation Reference](#annotation-reference)
  - [@ClassBuilder Attributes](#classbuilder-attributes)
  - [@BuildRule Attributes](#buildrule-attributes)
  - [Field-Level Companions](#field-level-companions)
- [Documentation](#documentation)
- [License](#license)

## Features

- **`@ResourcePath`** - validates that string expressions at annotated sites resolve to files that exist in the project's source or resource roots. Supports an optional `base` directory prefix and a caller-side inspection that catches `base` mismatches across method boundaries.
- **`@XContract`** - a superset of JetBrains `@Contract` with relational comparisons, `&&`/`||` grouping, named-parameter references, `instanceof` checks, typed `throws` returns, and chained comparisons. A synthetic `@Contract` is inferred so IntelliJ's data-flow analysis works from a single annotation.
- **`@ClassBuilder`** - generates a `public static class Builder` via javac AST mutation, covering classes, records, and interfaces. Full Lombok `@Builder` parity plus richer setter shapes:
  - Boolean zero-arg + typed pair with `@Negate` inverse
  - `Optional<T>` dual setters (raw nullable + wrapped)
  - `@Collector` varargs/iterable bulk overloads with opt-in single-element add/put, clear, and lazy put-if-absent
  - `@Formattable` `@PrintFormat` string overload
  - `@BuildRule(retainInit = true)` carries field initializers (`UUID.randomUUID()`, `List.of(...)`, etc.) into the builder as defaults evaluated fresh per `build()`
  - `@BuildRule(flag = @BuildFlag(...))` runtime validator enforcing `nonNull` / `notEmpty` / `group` / `pattern` / `limit` in the generated `build()`

## Getting Started

### Installation

<details>
<summary><b>Gradle (Kotlin DSL)</b></summary>

```kotlin
dependencies {
    implementation("io.github.simplified-dev:annotations:2.0.0")
    annotationProcessor("io.github.simplified-dev:annotations:2.0.0")
}
```

</details>

<details>
<summary><b>Gradle (Groovy DSL)</b></summary>

```groovy
dependencies {
    implementation 'io.github.simplified-dev:annotations:2.0.0'
    annotationProcessor 'io.github.simplified-dev:annotations:2.0.0'
}
```

</details>

<details>
<summary><b>Maven</b></summary>

```xml
<dependency>
    <groupId>io.github.simplified-dev</groupId>
    <artifactId>annotations</artifactId>
    <version>2.0.0</version>
</dependency>
```

For annotation-processor registration on Maven, add the same coordinate under `<annotationProcessorPaths>` in the `maven-compiler-plugin` configuration.

</details>

> [!NOTE]
> Published to Maven Central as `io.github.simplified-dev:annotations` and to JetBrains Marketplace as plugin ID `dev.simplified.simplified-annotations`.

### IntelliJ Plugin

**Settings > Plugins > Marketplace** > search **Simplified Annotations**.

The plugin hosts every inspection, quick-fix, gutter marker, and the editor-side Builder synthesis. It is optional at build time but strongly recommended while developing - autocompletion, goto-symbol, and type resolution for generated builder methods all work before the first javac round.

### Supported JDKs

| JDK | Status |
|-----|--------|
| 17, 21, 25 | Tested on every commit |
| 18-20, 22-24 | Inherit the JDK 17 shim; expected to work but not in the CI matrix |

## Quick Start

### `@ResourcePath`

```java
import dev.simplified.annotations.ResourcePath;

public class Assets {
    @ResourcePath
    static final String LOGO = "images/logo.png"; // checked at edit time

    @ResourcePath(base = "shaders")
    static final String VERTEX = "sprite.vert"; // resolves to shaders/sprite.vert
}
```

The inspection reports an error if the resolved path does not exist in any source or resource root. A caller-side inspection additionally flags arguments passed into resource-loading sinks (`Class.getResourceAsStream`, etc.) when the callee parameter carries `@ResourcePath` with a mismatched `base`.

### `@XContract`

```java
import dev.simplified.annotations.XContract;

@XContract("index >= 0 && index < size -> !null; _ -> fail")
public Node get(int index) { ... }

@XContract(value = "paramName != null -> this", mutates = "this")
public Builder name(String paramName) { ... }
```

The grammar supports relational comparisons (`<`, `<=`, `==`, `!=`, `>=`, `>`), logical `&&` / `||` with grouping, chained comparisons (`0 < index < size`), named-parameter references, `instanceof`, and typed `throws` returns. IntelliJ's data-flow analysis sees the translatable subset via a synthetic `@Contract`; the richer clauses are enforced by this plugin's own inspections.

### `@ClassBuilder`

```java
import dev.simplified.annotations.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ClassBuilder
public class Pizza {
    @BuildRule(retainInit = true) UUID id = UUID.randomUUID();
    @BuildRule(flag = @BuildFlag(nonNull = true)) String name;
    @Collector(singular = true, clearable = true) List<String> toppings;
    @Formattable Optional<String> description;
    @Negate("vegetarian") boolean containsMeat;
}
```

Generates a `Pizza.Builder` with:

- `id(UUID)` - defaults to a fresh `UUID.randomUUID()` evaluated on every `build()` (carried forward from the field initializer)
- `name(String)` - chained `@BuildFlag` enforcement at `build()` time
- `toppings(String...)`, `toppings(Iterable<String>)`, `addTopping(String)`, `clearToppings()`
- `description(String)`, `description(Optional<String>)`, `description(String fmt, Object... args)` with null-safe `String.format`
- `isContainsMeat()`, `isContainsMeat(boolean)`, `isVegetarian()`, `isVegetarian(boolean)` (booleans always use `is` prefix)

Plus bootstrap methods on `Pizza` itself: `static Pizza.Builder builder()`, `static Pizza.Builder from(Pizza)`, and `Pizza.Builder mutate()`.

> [!NOTE]
> `@BuildRule(retainInit = true)` evaluates the field initializer **fresh per builder instance** - `UUID.randomUUID()` produces a new UUID each time, `new ArrayList<>()` produces a fresh list. Any expression valid in the target class's scope is supported (constructor calls, factory methods, static method invocations, ternaries, etc.).

For abstract classes, `@ClassBuilder` produces a self-typed `Builder<T, B>` that concrete subclasses inherit with `class Builder extends Super.Builder<Sub, Sub.Builder>`; `self()` and `build()` are abstract on the root and overridden per subclass. This mirrors Lombok's `@SuperBuilder` with no runtime dependency.

Records, interfaces, and plain classes are all supported. For interfaces, the processor writes a sibling `<Name>Impl.java` in addition to `<Name>Builder.java` since there is no in-source mutation surface on an interface body.

## Annotation Reference

### `@ClassBuilder` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `builderName` | `String` | `"Builder"` | Simple name of the generated builder class |
| `builderMethodName` | `String` | `"builder"` | Static factory method returning a fresh builder |
| `buildMethodName` | `String` | `"build"` | Terminal method on the builder |
| `fromMethodName` | `String` | `"from"` | Static copy-factory seeding a builder from an existing instance |
| `toBuilderMethodName` | `String` | `"mutate"` | Instance method returning a pre-seeded builder |
| `methodPrefix` | `String` | `""` | Setter method prefix (booleans always use `is`) |
| `access` | `AccessLevel` | `PUBLIC` | Access level of generated bootstrap methods and builder class |
| `validate` | `boolean` | `true` | Whether `build()` calls `BuildFlagValidator.validate(target)` |
| `emitContracts` | `boolean` | `true` | Whether to emit `@XContract` annotations on generated methods |
| `generateBuilder` | `boolean` | `true` | Whether to emit the static `builder()` factory |
| `generateFrom` | `boolean` | `true` | Whether to emit the static copy factory |
| `generateMutate` | `boolean` | `true` | Whether to emit the instance `mutate()` method |
| `generateImpl` | `boolean` | `true` | Interface targets only: whether to generate `<Name>Impl` |
| `factoryMethod` | `String` | `""` | Static factory method `build()` delegates to instead of `new` |
| `exclude` | `String[]` | `{}` | Field names to exclude from the builder |

### `@BuildRule` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `retainInit` | `boolean` | `false` | Carry the field's declared initializer into the builder as a per-build default |
| `ignore` | `boolean` | `false` | Exclude this field from builder synthesis entirely |
| `flag` | `@BuildFlag` | `@BuildFlag` | Runtime validation constraints (see below) |
| `obtainVia` | `@ObtainVia` | `@ObtainVia` | Override how `from(T)` / `mutate()` reads this field |

#### `@BuildFlag` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `nonNull` | `boolean` | `false` | Field must not be `null` at `build()` time |
| `notEmpty` | `boolean` | `false` | String/Collection/Map/Optional/array must not be empty |
| `pattern` | `String` | `""` | Regex the field value must match (CharSequence / Optional\<String\>) |
| `limit` | `int` | `-1` | Maximum length/size (String/Collection/Map/array/Optional) |
| `group` | `String[]` | `{}` | At-least-one-of group: all members null/empty throws |

#### `@ObtainVia` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `method` | `String` | `""` | Instance method to call instead of the standard getter |
| `field` | `String` | `""` | Alternate field name to read |
| `isStatic` | `boolean` | `false` | Whether `method` is a `static Type.method(instance)` helper |

### Field-Level Companions

| Annotation | Target | Description |
|------------|--------|-------------|
| `@Collector` | `Collection`, `List`, `Set`, `Map` | Emits varargs + `Iterable` bulk setters; opt-in `singular`, `clearable`, `compute` (maps: `putIfAbsent(K, Supplier<V>)`) |
| `@Negate("inverse")` | `boolean` | Emits an inverse setter pair (`isInverse()` / `isInverse(boolean)`) alongside the direct pair |
| `@Formattable` | `String`, `Optional<String>` | Emits a `@PrintFormat` overload (`withField(String fmt, Object... args)`) with null-safe `String.format` |

## Documentation

Full attribute reference lives on the annotation Javadocs in [`library/src/main/java/dev/simplified/annotations/`](library/src/main/java/dev/simplified/annotations/). Architectural notes for contributors are in [`CLAUDE.md`](CLAUDE.md).

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE](LICENSE.md) for the full text.
