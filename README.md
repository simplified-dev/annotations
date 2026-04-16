# Simplified Annotations

Three Java annotations with matching IntelliJ IDEA tooling:

- **`@ResourcePath`** - validates that string expressions at annotated sites
  resolve to files that exist in the project's source or resource roots.
- **`@XContract`** - a superset of JetBrains `@Contract` with relational
  comparisons, `&&`/`||` grouping, named-parameter references, `instanceof`
  checks, typed `throws` returns, and chained comparisons. A synthetic
  `@Contract` is inferred so IntelliJ's data-flow analysis works from a
  single annotation.
- **`@ClassBuilder`** - generates a `public static class Builder` via javac
  AST mutation, covering classes, records, and interfaces. Full Lombok
  `@Builder` parity plus richer setter shapes (boolean pair + `@Negate`
  inverse, `Optional` dual, `@Collector` bulk + opt-in add/put/clear, `@Formattable`
  `@PrintFormat` overload) and a runtime `@BuildRule(flag = @BuildFlag(...))` validator.

Published to:

- [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/27678-simplified-annotations) - plugin ID `dev.sbs.simplified-annotations`
- [Maven Central](https://central.sonatype.com/artifact/dev.sbs/simplified-annotations) - `dev.sbs:simplified-annotations:1.2.0`

---

## Install

### IntelliJ plugin

Settings -> Plugins -> Marketplace -> search **Simplified Annotations**.
The plugin hosts every inspection, quick-fix, gutter marker, and the
editor-side Builder synthesis; it is optional at build time but strongly
recommended while developing.

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("dev.sbs:simplified-annotations:1.2.0")
    annotationProcessor("dev.sbs:simplified-annotations:1.2.0")
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'dev.sbs:simplified-annotations:1.2.0'
    annotationProcessor 'dev.sbs:simplified-annotations:1.2.0'
}
```

### Maven

```xml
<dependency>
    <groupId>dev.sbs</groupId>
    <artifactId>simplified-annotations</artifactId>
    <version>1.2.0</version>
</dependency>
```

For annotation-processor registration on Maven, add the same coordinate
under `<annotationProcessorPaths>` in the `maven-compiler-plugin`
configuration.

---

## `@ClassBuilder` consumer setup

> **javac-only.** The AST-mutation pipeline does not run on ecj (Eclipse's
> compiler). Projects that compile with ecj cannot use `@ClassBuilder`.
> `@ResourcePath` and `@XContract` have no compiler dependency and work
> on any build.

**No `--add-exports` configuration required.** The annotation processor
opens `jdk.compiler/com.sun.tools.javac.*` automatically at load time
(same technique Lombok uses) so consumer builds work with a plain
`annotationProcessor` dependency - no JVM flags, no forked compiler.

### Supported JDKs

17, 21, 25. The processor is tested against all three on every commit.
JDK 18-20 and 22-24 inherit the JDK 17 shim and are expected to work but
are not part of the CI matrix.

---

## Quick start

### `@ResourcePath`

```java
import dev.sbs.annotation.ResourcePath;

public class Assets {
    @ResourcePath
    static final String LOGO = "images/logo.png"; // checked at edit time

    @ResourcePath(base = "shaders")
    static final String VERTEX = "sprite.vert"; // resolves to shaders/sprite.vert
}
```

The inspection reports an error if the resolved path does not exist in
any source or resource root. A caller-side inspection additionally
flags arguments passed into resource-loading sinks
(`Class.getResourceAsStream`, etc.) when the callee parameter carries
`@ResourcePath` with a mismatched `base`.

### `@XContract`

```java
import dev.sbs.annotation.XContract;

@XContract("index >= 0 && index < size -> !null; _ -> fail")
public Node get(int index) { ... }

@XContract(value = "paramName != null -> this", mutates = "this")
public Builder name(String paramName) { ... }
```

The grammar supports relational comparisons (`<`, `<=`, `==`, `!=`,
`>=`, `>`), logical `&&` / `||` with grouping, chained comparisons
(`0 < index < size`), named-parameter references, `instanceof`, and
typed `throws` returns. IntelliJ's data-flow analysis sees the
translatable subset via a synthetic `@Contract`; the richer clauses are
enforced by this plugin's own inspections.

### `@ClassBuilder`

```java
import dev.sbs.annotation.*;
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

- `withId(UUID)`, defaulting to a fresh `UUID.randomUUID()` evaluated on
  every `build()` (carried forward from the field initializer)
- `withName(String)`, chained `@BuildRule(flag = @BuildFlag(...))` enforcement at `build()` time
- `withToppings(String...)`, `withToppings(Iterable<String>)`,
  `withTopping(String)`, `clearToppings()`
- `withDescription(String)`, `withDescription(Optional<String>)`,
  `withDescription(String fmt, Object... args)` with null-safe
  `String.format`
- `isContainsMeat()`, `isContainsMeat(boolean)`,
  `isVegetarian()`, `isVegetarian(boolean)` (the inverse pair)

Plus bootstrap methods on `Pizza` itself: `static Pizza.Builder builder()`,
`static Pizza.Builder from(Pizza)`, and `Pizza.Builder mutate()`.

`@BuildRule(retainInit = true)` evaluates the field initializer fresh per
builder instance - `UUID.randomUUID()` produces a new UUID each time,
`new ArrayList<>()` produces a fresh list. Any expression valid in the
target class's scope is supported (constructor calls, factory methods,
static method invocations, ternaries, etc.).

For abstract classes, `@ClassBuilder` produces a self-typed
`Builder<T, B>` that concrete subclasses inherit with
`class Builder extends Super.Builder<Sub, Sub.Builder>`; `self()` and
`build()` are abstract on the root and overridden per subclass. This
mirrors Lombok's `@SuperBuilder` with no runtime dependency.

Records, interfaces, and plain classes are all supported. For
interfaces, the processor writes a sibling `<Name>Impl.java` in addition
to `<Name>Builder.java` since there is no in-source mutation surface
on an interface body.

---

## Documentation

Full attribute reference lives on the annotation Javadocs in
[`src/main/java/dev/sbs/annotation/`](src/main/java/dev/sbs/annotation).
Architectural notes for contributors are in [`CLAUDE.md`](CLAUDE.md).

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt).
