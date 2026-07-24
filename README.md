# Simplified Annotations

Seventeen Java annotations with matching IntelliJ IDEA tooling - a Lombok-compatible generator set covering builders, accessors, constructors, equality, `toString`, logging, utility classes, lazy fields and enum lookups, plus two inspection-only families for resource-path validation and an extended `@Contract` grammar, and the two body rewrites for checked exceptions and resource cleanup.

Every annotation that generates has an IDE half, and that pairing is the point: a green editor over source javac rejects, or a red editor over source that builds, is the failure mode nearly every design decision here is defending against.

> [!IMPORTANT]
> The generating annotations mutate the javac AST in place and **require javac** (ecj is not supported). The processor opens `jdk.compiler` internals automatically at load time via `sun.misc.Unsafe` + `MethodHandles.Lookup.IMPL_LOOKUP` (the same technique Lombok uses), so **no `--add-exports` flags are needed** in consumer builds. `@ResourcePath` and `@XContract` generate nothing, have no compiler dependency, and work on any build.

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
  - [@Lazy](#lazy)
  - [@Getter and @Setter](#getter-and-setter)
  - [Constructors](#constructors)
  - [@EqualsAndHashCode and @ToString](#equalsandhashcode-and-tostring)
  - [@Log](#log)
  - [@UtilityClass](#utilityclass)
  - [@SilentThrows and @Cleanup](#silentthrows-and-cleanup)
  - [@EnumLookup](#enumlookup)
- [Annotation Reference](#annotation-reference)
  - [@ClassBuilder Attributes](#classbuilder-attributes)
  - [Naming](#naming)
  - [Field Annotations](#field-annotations)
  - [Field-Level Companions](#field-level-companions)
  - [Optional&lt;T&gt; fields](#optionalt-fields)
- [Documentation](#documentation)
- [License](#license)

## Features

- **`@ResourcePath`** - validates that string expressions at annotated sites resolve to files that exist in the project's source or resource roots. Supports an optional `base` directory prefix and a caller-side inspection that catches `base` mismatches across method boundaries.
- **`@XContract`** - a superset of JetBrains `@Contract` with relational comparisons, `&&`/`||` grouping, named-parameter references, `instanceof` checks, typed `throws` returns, and chained comparisons. A synthetic `@Contract` is inferred so IntelliJ's data-flow analysis works from a single annotation.
- **`@ClassBuilder`** - generates a `public static class Builder` via javac AST mutation, covering classes, records, and interfaces. Full Lombok `@Builder` parity plus richer setter shapes:
  - Boolean typed setter plus a zero-arg convenience, with `@Negate` inverse
  - Every generated name driven by a per-role pattern, with a `NamingStyle.LOMBOK` drop-in profile
  - `Optional<T>` dual setters (raw nullable + wrapped), so a maybe-null value needs no wrapping at the call site
  - `@Collector` varargs/iterable bulk overloads with opt-in single-element add/put, clear, and lazy put-if-absent
  - `@Formattable` `@PrintFormat` string overload
  - Field initializers (`UUID.randomUUID()`, `List.of(...)`, etc.) carried into the builder as defaults evaluated fresh per `build()`, with no annotation required
  - An all-args constructor synthesised when the class declares none, so a plain class needs nothing but the annotation
  - `@BuildFlag` runtime validator enforcing `nonNull` / `notEmpty` / `group` / `pattern` / `limit` in the generated `build()`
- **`@Lazy`** - field-level annotation that defers a field's value computation until first access and caches it thereafter. The processor rewrites the storage from `T` to `Lazy<T>`, wraps the initializer as `Lazy.of(() -> <init>)`, and synthesises a memoizing getter. With `@ClassBuilder` the builder gets a dual `field(T)` / `field(Supplier<T>)` setter pair so deferred computations can flow through the builder unchanged.
- **`@Getter` / `@Setter`** - read and write accessors on classes and enums. Both default to bean-shaped names, so a bare `@Getter` keeps producing `getX()` / `isX()` and renames no existing call site; `style = NamingStyle.FLUENT` mints `x()` instead, which is what replaces Lombok's `@Accessors(fluent = true)`. A field-level annotation beats the enclosing type's, and `AccessLevel.NONE` on a field opts it out of a type-level fan-out.
- **`@AllArgsConstructor` / `@RequiredArgsConstructor` / `@NoArgsConstructor` / `@BuilderArgsConstructor`** - one field-selection policy with four settings. "Required" means `final` without an initializer, since an initialized `final` is already definitely assigned. These **add** a constructor rather than backing off when one exists, so a constant-carrying enum still works, and a duplicate erasure between two of them is reported here rather than reaching javac on a line you cannot open.
- **`@EqualsAndHashCode` / `@ToString`** - the whole-object members, over one shared member selector. **Records are the shape they exist for and the shape Lombok refuses**, so a record whose implicit `equals` compares an array component by reference can finally stop hand-writing the pair. `hashCode` accumulates rather than calling `Objects.hash`, because `Objects.hash` takes `Object...` and would hash an array member by identity - inconsistently with the `equals` beside it. `identity` selects between `EXACT_CLASS`, `INSTANCE_OF` and `INSTANCE_OF_CANEQUAL`; `cacheHashCode` adds a `transient` memo.
- **`@Log`** - one `private static final org.apache.logging.log4j.Logger`, byte-for-byte what Lombok's `@Log4j2` emits, named through the same pattern grammar. Single backend by decision. The library stays zero-dependency by naming log4j2 only as strings, so a module missing `log4j-api` gets an error on the annotation naming the artifact rather than an unresolved symbol inside a field it cannot open.
- **`@UtilityClass`** - `final` plus a throwing constructor. **Deliberately splits Lombok's feature in two**: the implicit-`static`-on-members half - the one that changes the meaning of a declaration you wrote - is opt-in behind `members = MAKE_STATIC`, and the default reports an instance member with a quick fix instead of silently rewriting it.
- **`@SilentThrows` / `@Cleanup`** - the two body rewrites. `@SilentThrows` wraps the body so a checked exception leaves a method that declares none, emitting the rethrow helper rather than shipping a runtime type. `@Cleanup` splits the enclosing block and wraps the remainder in `try (name) { ... }` - it produces exactly the tree javac's parser builds for the existing-variable form, so the result *is* try-with-resources, which means a close failure is suppressed rather than masking the primary exception.
- **`@EnumLookup`** - a cached values array plus a uniform set of static lookup helpers on an enum, with `@KeyField` adding a parallel key array and `of<Name>` / `findBy<Name>` per field.

Two IDE-only inspections read *hand-written* `equals` / `hashCode` pairs on classes carrying none of these annotations: one reports a pair whose two halves compare different state, the other reports the pairs `@EqualsAndHashCode` would generate and offers to replace them. Between them a hand-written pair gets an answer either way.

## Getting Started

### Installation

<details>
<summary><b>Gradle (Kotlin DSL)</b></summary>

```kotlin
dependencies {
    implementation("io.github.simplified-dev:annotations:2.5.0")
    annotationProcessor("io.github.simplified-dev:annotations:2.5.0")
}
```

</details>

<details>
<summary><b>Gradle (Groovy DSL)</b></summary>

```groovy
dependencies {
    implementation 'io.github.simplified-dev:annotations:2.5.0'
    annotationProcessor 'io.github.simplified-dev:annotations:2.5.0'
}
```

</details>

<details>
<summary><b>Maven</b></summary>

```xml
<dependency>
    <groupId>io.github.simplified-dev</groupId>
    <artifactId>annotations</artifactId>
    <version>2.5.0</version>
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
    UUID id = UUID.randomUUID();
    @BuildFlag(nonNull = true) String name;
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
- `containsMeat(boolean)`, `vegetarian(boolean)` plus the zero-arg `isContainsMeat()` / `isVegetarian()` convenience

Plus bootstrap methods on `Pizza` itself: `static Pizza.Builder builder()`, `static Pizza.Builder from(Pizza)`, and `Pizza.Builder mutate()`.

> [!NOTE]
> Field initializers are retained as builder defaults automatically - `id` needs no annotation. Each is evaluated **fresh per builder instance**, so `UUID.randomUUID()` produces a new UUID each time and `new ArrayList<>()` a fresh list. Any expression valid in the target class's scope is supported (constructor calls, factory methods, static method invocations, ternaries, etc.). Opt a single field out with `@BuilderDefault(false)`, or the whole class with `@ClassBuilder(retainInit = false)`.

For abstract classes, `@ClassBuilder` produces a self-typed `Builder<T, B>` that concrete subclasses inherit with `class Builder extends Super.Builder<Sub, Sub.Builder>`; `self()` and `build()` are abstract on the root and overridden per subclass. This mirrors Lombok's `@SuperBuilder` with no runtime dependency.

Records, interfaces, and plain classes are all supported. For interfaces, the processor writes a sibling `<Name>Impl.java` in addition to `<Name>Builder.java` since there is no in-source mutation surface for a nested builder on an interface body. The entry points still land on the interface itself, so an interface target is used exactly like a class:

```java
@ClassBuilder
public interface Shape {
    String name();
    int sides();
}

Shape s = Shape.builder().name("tri").sides(3).build();
Shape t = Shape.from(s).sides(4).build();
Shape u = t.mutate().name("quad").build();
```

`builder()` and `from(T)` are `static` interface methods and `mutate()` is a `default`, so no runtime dependency or implementor change is involved. The usual `generate*` opt-outs and the skip-on-collision rule apply as they do on a class.

#### Generic targets

A target may declare type parameters, on any of those shapes:

```java
@ClassBuilder
public class Crate<V> {
    V item;
    List<V> spares = new ArrayList<>();
}

Crate<String> c = Crate.<String>builder().item("x").build();
```

The generated builder re-declares the target's parameters, since a nested `Builder` is `static` and an interface's sibling builder is a separate top-level class - neither can see the enclosing type's variables. Every static member that mentions one carries its own copy, so `from(T)` and the initializer providers infer them back at the call site. Bounds are preserved (`class Ranked<V extends Comparable<V>>` yields `Builder<V extends Comparable<V>>`).

On a SuperBuilder chain the parameters lead the self-typed pair, and a concrete link reproduces the arguments the target passes up:

```java
@ClassBuilder public abstract class Box<V> { V item; }
@ClassBuilder public class StringBox extends Box<String> { int n; }

// Box.Builder<V, T extends Box<V>, B extends Builder<V, T, B>>
// StringBox.Builder extends Box.Builder<String, StringBox, StringBox.Builder>
StringBox b = StringBox.builder().item("x").n(1).build();
```

> [!NOTE]
> `builder()` is a generic static method, and a chained call has nothing to infer its parameter from - so a typed chain needs the explicit witness, `Crate.<String>builder()`, the same as Lombok's generic `@Builder`. Writing `Crate.builder()` infers `Object`; the result still assigns to a `Crate<String>` local, but only under an unchecked warning. Where the parameter is already determined by an argument, as in `Crate.from(existing)`, no witness is needed.

### `@Lazy`

```java
import dev.simplified.annotations.Lazy;

public class Report {
    @Lazy
    private final List<Row> rows = expensiveQuery();
}
```

Compiled to:

```java
private final Lazy<List<Row>> rows = Lazy.of(() -> expensiveQuery());

public List<Row> getRows() {
    return rows.get();
}
```

The supplier runs once on the first `getRows()` call and the result is cached for every subsequent call (thread-safe, double-checked-locking, with a sentinel for cached `null`). Field-level annotations (`@NotNull`, `@Nullable`, `@PrintFormat`, `@Deprecated`, etc.) propagate onto the synthesised getter and its return type using each annotation's declared `@Target`.

When the enclosing class also carries `@ClassBuilder`, the generated builder receives a dual setter: `rows(List<Row>)` wraps the value as `() -> value`, and `rows(Supplier<List<Row>>)` stores the supplier verbatim, so deferred computations flow through the builder without being eagerly invoked.

> [!NOTE]
> `@Lazy` only supports reference types (use `Boolean` rather than `boolean`), is not allowed on static fields or record components, and standalone use (no `@ClassBuilder`) requires a field initializer.

### `@Getter` and `@Setter`

```java
import dev.simplified.annotations.*;

@Getter
@Setter
public class Account {
    private final String id;                          // final - read accessor only
    private String email;
    @Getter(AccessLevel.NONE) private char[] token;   // opts out of the read accessor
}
```

Generates `getId()`, `getEmail()`, `setEmail(String)` and `setToken(char[])`. A type-level annotation fans out over the type's fields and silently skips the ones it cannot serve - the `final` field above takes no setter - while a field-level annotation beats the enclosing type's and reports when it cannot be honoured, since annotating one field is a claim about that field.

Both default to `NamingStyle.SIMPLIFIED`, which is bean-shaped on purpose: a bare `@Getter` has to keep producing `getX()` or every existing call site is renamed. `@Getter(style = NamingStyle.FLUENT)` mints `id()` / `email()` instead.

### Constructors

```java
@RequiredArgsConstructor
public class Session {
    private final String id;                        // required
    private final Instant opened = Instant.now();   // initialized final - already assigned
    private String label;                           // mutable - not required
}

// Session(String id)
```

"Required" means `final` **without** an initializer. `@AllArgsConstructor` takes every instance field, `@NoArgsConstructor` takes none, and `@BuilderArgsConstructor` matches whatever the builder would pass. `access` sets the visibility; on an enum it is forced `private` whatever you write, since nothing else is legal.

> [!NOTE]
> These add a constructor rather than declining when one exists. `transient` fields are parameters - shortening the constructor of a type a reflective framework populates would bind the next same-typed argument to the wrong slot with nothing failing - but `@Lazy` fields are not, and their omission is reported rather than silent.

### `@EqualsAndHashCode` and `@ToString`

```java
@EqualsAndHashCode
@ToString
public record Chunk(int x, int z, byte[] data) { }
```

A record's implicit `equals` compares `data` by **reference**, so two chunks holding identical bytes are unequal. The generated pair compares it by content and hashes it consistently - and a record is exactly the shape Lombok refuses to touch.

```java
@EqualsAndHashCode(of = "id")
@ToString(exclude = "secret")
public class User {
    private final UUID id;
    private String displayName;
    private char[] secret;
}
```

`of` and `exclude` narrow the member set, `identity` picks between `EXACT_CLASS` (the default), `INSTANCE_OF` and `INSTANCE_OF_CANEQUAL`, and `callSuper` defaults to `AUTO`. The two annotations share one member selector, so a type whose `equals` counts an inherited field while its `toString` hides one is not a shape you can accidentally produce; the one sanctioned divergence is `transient`, which `@ToString` keeps and `@EqualsAndHashCode` drops.

### `@Log`

```java
@Log
public class Worker {
    void run() {
        log.info("started");
    }
}

// private static final Logger log = LogManager.getLogger(Worker.class);
```

`topic = "..."` passes a string literal to the factory instead of the class literal, and `name` renames the field. The IDE half contributes the field so `log.*` resolves before the first javac round; supply `org.apache.logging.log4j:log4j-api` yourself, since the library links nothing.

### `@UtilityClass`

```java
@UtilityClass
public class MathUtil {
    static int clamp(int v, int lo, int hi) { ... }
}
```

Marks the class `final` and retrofits the constructor javac already generated into one that throws. An **instance** member is reported as an error with a "Make static" quick fix rather than silently rewritten - that half is opt-in behind `@UtilityClass(members = MAKE_STATIC)`, because changing the meaning of a declaration you wrote is the source of every sharp edge in Lombok's version.

### `@SilentThrows` and `@Cleanup`

```java
@SilentThrows
public String read(Path path) {
    return Files.readString(path);   // IOException, with no throws clause
}

public void copy(Path in, Path out) throws IOException {
    @Cleanup InputStream source = Files.newInputStream(in);
    Files.copy(source, out);
}   // source closed here, by a real try-with-resources
```

`@SilentThrows` wraps the body and rethrows through a helper injected once per class, so no runtime dependency is added. `@Cleanup` splits the enclosing block at the declaration and wraps the remainder in `try (source) { ... }` - the exact tree javac's parser builds for the existing-variable form, so javac's own lowering generates the close, the null skip and the `addSuppressed` bookkeeping. A close failure is therefore **suppressed rather than masking** the primary exception, the one place this deliberately improves on Lombok.

The IDE half is subtractive: an exception handler stops the editor reporting the checked exception the wrap absorbs, and a suppressor silences the resource-leak inspections on a variable the rewrite closes.

### `@EnumLookup`

```java
@EnumLookup
public enum Status {
    OK(200, "ok"),
    NOT_FOUND(404, "not-found");

    @KeyField private final int code;
    @KeyField private final String slug;

    Status(int code, String slug) { this.code = code; this.slug = slug; }
}

Status s = Status.ofCode(404);                  // NOT_FOUND
Optional<Status> o = Status.findBySlug("ok");   // Optional[OK]
Status.forEach((i, v) -> ...);                  // i is the ordinal
```

Injects a cached `values()` array plus `size()`, `forEach`, `stream()`, `parallelStream()`, `ofName(String)`, `ofOrdinal(int)`, `findByName` and `findByOrdinal`. Each `@KeyField` adds a parallel key array - primitive-typed when the field is - and its own `of<Name>` / `findBy<Name>` pair, renameable via `@KeyField(methodName = "...")`.

## Annotation Reference

### `@ClassBuilder` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `style` | `NamingStyle` | `SIMPLIFIED` | Naming for the whole generated surface (see below) |
| `setters` | `@SetterNames` | inherit | Overrides for the members generated once per field |
| `builder` | `@BuilderNames` | inherit | Overrides for the members generated exactly once |
| `access` | `AccessLevel` | `PUBLIC` | Access level of generated bootstrap methods and builder class |
| `validate` | `boolean` | `true` | Whether `build()` validates the constructed instance against its `@BuildFlag` constraints |
| `emitContracts` | `boolean` | `true` | Whether to emit `@XContract` annotations on generated methods |
| `generateImpl` | `boolean` | `true` | Interface targets only: whether to generate `<Name>Impl` |
| `factoryMethod` | `String` | `""` | Static factory method `build()` delegates to instead of `new` |
| `exclude` | `String[]` | `{}` | Field names to exclude from the builder |

### Naming

The generated surface splits by how often a member appears, and each half has its own annotation.

**`@SetterNames` - generated once per field.** Six **roles**, each holding a **pattern** with one `{}`
placeholder. The placeholder expands to the name the setter is built from - the field name, the
`@Negate` stem, or the `@Collector` singular - capitalised unless it opens the pattern. Because the
placeholder may sit anywhere, a pattern expresses a suffix (`{}Value`) or a wrapped form
(`put{}IfAbsent`) as readily as a prefix, and it is mandatory: without it every field would generate
the same method name.

| Role | What it names | `SIMPLIFIED` | `LOMBOK` | `BEAN` |
|------|---------------|--------------|----------|--------|
| `set` | The value-taking setter, booleans included | `{}` | `{}` | `set{}` |
| `flag` | The zero-arg boolean setter and its `@Negate` inverse | `is{}` | *none* | `is{}` |
| `add` | `@Collector` single-element add | `add{}` | `{}` | `add{}` |
| `put` | `@Collector` single-entry put (maps) | `put{}` | `{}` | `put{}` |
| `compute` | `@Collector` put-if-absent (maps) | `put{}IfAbsent` | *none* | `put{}IfAbsent` |
| `clear` | `@Collector` clear | `clear{}` | `clear{}` | `clear{}` |

**`@BuilderNames` - generated exactly once.** The builder class and the methods that enter and leave
it, named by plain literals.

| Name | What it names | `SIMPLIFIED` | `LOMBOK` | `BEAN` |
|------|---------------|--------------|----------|--------|
| `type` | The generated builder class | `Builder` | `<Type>Builder` | `Builder` |
| `builder` | Static factory returning a fresh builder | `builder` | `builder` | `builder` |
| `build` | Terminal method returning the instance | `build` | `build` | `build` |
| `from` | Static copy factory | `from` | `from` | `from` |
| `toBuilder` | Instance method seeding from `this` | `mutate` | `toBuilder` | `mutate` |

`style` sets both halves at once; `setters` and `builder` override individual names.

```java
@ClassBuilder                                              // fluent: animated(boolean) + isAnimated()
@ClassBuilder(style = NamingStyle.LOMBOK)                  // drop-in for Lombok @Builder
@ClassBuilder(setters = @SetterNames(set = "set{}"))       // JavaBean setters, rest unchanged
@ClassBuilder(setters = @SetterNames(add = "append{}"))    // rename one role only
@ClassBuilder(setters = @SetterNames(flag = SetterNames.NONE))  // drop the zero-arg boolean form
@ClassBuilder(builder = @BuilderNames(build = "construct"))     // rename the terminal method
@ClassBuilder(builder = @BuilderNames(from = BuilderNames.NONE))  // suppress the copy factory
```

`NONE` suppresses a member; `INHERIT` (the default, `""`) takes it from the style. Four members
cannot be suppressed - `set`, because the field would have no way to be assigned, and `type` /
`build`, because a builder with no class to name or no way to finish is not a builder. Those, and any
malformed pattern, are rejected at the annotation by both the processor and the IDE inspection.

### Field Annotations

Each is written directly on the field - or, for `@BuildFlag` on an interface target, on the accessor
standing in for one. Only `@BuildFlag` is retained at runtime; the rest are consumed at
annotation-processing time.

| Annotation | Purpose |
|------------|---------|
| `@BuilderDefault` | Override the class-level `retainInit` policy for one field |
| `@BuilderIgnore` | Exclude this field from builder synthesis entirely |
| `@BuildFlag` | Runtime validation constraints (see below) |
| `@ObtainVia` | Override how `from(T)` / `mutate()` reads this field |

#### `@BuilderDefault` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | `boolean` | `true` | Whether to carry the field's declared initializer into the builder as a per-build default |

Since `@ClassBuilder(retainInit)` already defaults to `true`, the common use is the opt-out form
`@BuilderDefault(false)`. Writing it bare is only needed on a class that set `retainInit = false`.

An initializer that reads instance state - an instance field, an instance method, `getClass()`,
`this` - is retained too, but applied later. It cannot be evaluated when the builder is created,
since no target exists then, so it is computed in the generated constructor instead, where `this`
is available exactly as in the ordinary field initializer it came from:

```java
@ClassBuilder
public class Report {
    String kind = getClass().getSimpleName();   // computed per build()
    String header = "== " + kind;               // reads the field above
    UUID id = UUID.randomUUID();                // static-safe: per builder
}
```

The observable difference is timing: a static-safe default is evaluated once per builder, an
instance-referencing one once per `build()`. This works across a SuperBuilder chain as well, through
the copy constructor each link carries - a default on an abstract root sees the concrete subclass
being built, and a link's own default runs after `super(b)` has drained the parent's slots.

The path retypes the builder slot to `Supplier<T>` so an unset slot stays distinguishable from an
explicitly-set null. Every shape whose setter simply assigns carries that - `boolean` (including a
`@Negate` pair), `Optional`, arrays, `@Formattable` strings, plain fields and `@Lazy` ones.

`@Collector` containers take a merge rather than a `Supplier`, since their `add` / `put` / `clear`
setters need a real container to mutate while the builder runs. The slot carries only what the caller
contributed and the constructor folds it onto the computed default, so the observable behaviour
matches a static-safe default exactly:

```java
@ClassBuilder
public class Bag {
    @Collector(singular = true, clearable = true) List<String> items = seed();
    List<String> seed() { return new ArrayList<>(List.of("a")); }
}

Bag.builder().build()                  // [a]      - default seeds the collection
Bag.builder().addItem("b").build()     // [a, b]   - add appends onto it
Bag.builder().items("x").build()       // [x]      - wholesale replace discards it
Bag.builder().clearItems().build()     // []       - so does clear
```

A custom container works the same way, whatever its shape - including one with no usable
constructor, or an interface, which has none at all:

```java
@Collector(singular = true) ConcurrentList<String> items = seed();
ConcurrentList<String> seed() { return ConcurrentLists.of(List.of(prefix)); }
```

Nothing needs to construct the declared type. The builder collects contributions into a plain
`java.util` scratch, and the constructor takes the real container from the field's own initializer -
so the built object holds exactly what `seed()` returned, subclass and all, rather than something
reconstructed from the declared type. No inference is done on the initializer at any point.

#### `@BuildFlag` Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `nonNull` | `boolean` | `false` | Field must not be `null` at `build()` time |
| `notEmpty` | `boolean` | `false` | String/Collection/Map/Optional/array must not be empty |
| `pattern` | `String` | `""` | Regex the field value must match (CharSequence / Optional\<String\>) |
| `limit` | `int` | `-1` | Maximum length/size (String/Collection/Map/array/Optional) |
| `group` | `String[]` | `{}` | At-least-one-of group: all members null/empty throws |

On an **interface** target the constraint goes on the accessor, the interface having no fields of its
own. The processor copies it onto the matching `<Name>Impl` field, which is the instance `build()`
constructs and the one the validator reads, so it is enforced identically:

```java
@ClassBuilder
public interface Shape {
    @BuildFlag(nonNull = true) String name();
    int sides();
}

Shape.builder().sides(3).build();   // BuilderValidationException: Field 'name' in 'ShapeImpl' is required and is null/empty
```

`@BuildFlag` is the only companion whose target is wider than where it takes effect - written on any
other method it is silently inert, which the IDE inspection warns about.

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
| `@Negate("inverse")` | `boolean` | Emits an inverse setter pair (`inverse(boolean)` plus the zero-arg `isInverse()`) alongside the direct pair |
| `@Formattable` | `String`, `Optional<String>` | Emits a `@PrintFormat` overload (`withField(String fmt, Object... args)`) with null-safe `String.format` |
| `@Lazy` | any reference-typed field | Rewrites storage to `Lazy<T>`, wraps the initializer as a supplier, and synthesises a memoizing getter; with `@ClassBuilder` adds a dual `field(T)` / `field(Supplier<T>)` setter pair |

### `Optional<T>` fields

An `Optional<T>` field gets a **dual setter** - `x(T)` alongside `x(Optional<T>)` - so a caller
holding a maybe-null `T` hands it straight over and the wrapping stays inside the builder. This is a
deliberate divergence from Lombok, which emits only the wrapped setter and so pushes
`Optional.ofNullable(...)` onto every such call site.

The one shape it cannot serve is a bare literal `x(null)`: both parameter types accept null and
neither is more specific, so the call is ambiguous (JLS 15.12.2.5) and both javac and the IDE reject
it. Everything else resolves, including a null-valued *variable*, which carries a static type.

That is arguably the right error - on an `Optional` field, `x(null)` is ambiguous in intent too
(absent, or present-and-null?) - so the plugin ships an Alt+Enter fix rather than a way to turn the
overload off:

```java
Box.builder().label(null);              // ambiguous
Box.builder().label(Optional.empty());  // ← Alt+Enter: Replace 'null' with 'Optional.empty()'
```

## Documentation

Full attribute reference lives on the annotation Javadocs in [`library/src/main/java/dev/simplified/annotations/`](library/src/main/java/dev/simplified/annotations/). Architectural notes for contributors are in [`CLAUDE.md`](CLAUDE.md).

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE](LICENSE.md) for the full text.
