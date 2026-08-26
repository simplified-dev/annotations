# Simplified Annotations Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Versions 1.0.0 through 1.0.5 were published under the legacy plugin ID
`dev.sbs.simplified-annotations` and Maven coordinate `dev.sbs:simplified-annotations`.
Versions 2.0.0 onward are published under `dev.simplified.simplified-annotations` /
`io.github.simplified-dev:annotations`. See the 2.0.0 entry for the rename details.

## [2.6.3]

### Added

- **Find Usages on a field reports the calls made through the members generated from it.** A
  `@Getter` field, a `@Lazy` field and a `@ClassBuilder` slot are all called through a member that
  exists in no source file, so the field itself is written nowhere and the usage view came back
  empty on a property called from one end of a project to the other. The platform gathers a field's
  accessors and then drops the ones whose backing field it cannot recover by reading the method
  body, which is every generated one. Provenance answers what a body read cannot, so the search now
  runs over the field and everything minted from it - accessors on the class, and the setters
  `@ClassBuilder` hangs off the nested `Builder`. A call written against a supertype is included
  under the same "search for base accessors" option the platform already puts a hand-written
  accessor behind.
- **A call that assigns a slot reads as a write of it.** The platform identifies a setter by a
  `set` prefix and a one-argument signature, which is the one shape a generated setter is free not
  to have: a fluent `@Setter` mints `label(String)`, a `name` attribute mints whatever it was given,
  and every `@ClassBuilder` setter is named after its slot - so a whole builder chain was filed
  under read access and coloured as one in the editor. The provider that minted the member records
  which it was, so the classification is a marker read rather than a guess at the name.
- **A Find Usages narrowed to write access keeps the generated setter calls.** Read and write are
  filtered one usage at a time, by asking whether that expression is assigned to - which a call is
  not, whatever the method does with its argument, so narrowing to writes dropped every call the
  search was narrowed to find. A generated accessor needs no per-usage question: every call to it
  does the same thing to the slot, so the answer is a property of the method and is settled once.
- **Renaming a field renames the members generated from it.** A generated member is spelled from the
  slot behind it, so a rename that touched only what it could see in source left every call site in
  the project naming a method that would not exist after the next build - and said nothing about it.
  An accessor's new name comes from the naming scheme that spelled the old one; a builder setter's
  comes from asking the setter dispatch for the same slot under the new name, so a name a companion
  annotation pins - a `@Negate` flag, a `@Collector(singularMethodName)` - stays where it is while
  the singular name a collector derives follows the slot. Renaming a generated accessor renames the
  field it stands for. A generated member that stands for no field at all - the nested `Builder`,
  the bootstrap methods, the whole-object trio - is refused up front rather than partway through.

### Changed

- **`@Lazy` names its getter through the same scheme `@Getter` reads.** It minted `get` plus a
  capitalised field name for every type, which put the one accessor on a lazy field in different
  naming territory from every other generated accessor on the same class - and left a class that
  spells its accessors fluently with one that does not. It now carries `style` and `name` with
  `@Getter`'s defaults and resolves them through the shared `AccessorScheme`. **This renames the
  getter on a `boolean` lazy field**, which now reads through `is` like every other boolean
  accessor: `@Lazy boolean active` generates `isActive()` where it generated `getActive()`. Naming
  stays this annotation's own rather than deferring to a `@Getter` on the same field or type - the
  accessor pass steps over a lazy field precisely so there is one getter, which leaves the naming of
  it here to answer.

### Fixed

- **A generated builder setter on a record navigates to the component it fills** rather than to the
  record. Ctrl-Q still shows the record's own prose, since that is where a component is documented.

## [2.6.2]

### Changed

- **The library is a compile-time dependency again.** Three features generated code that reached
  back into the jar at runtime - `@Lazy` stored its value in a library holder, `@BuildFlag`
  validation called a reflective validator, and a `@Formattable` setter on a nullable or
  `Optional<String>` field routed through a string helper. A consumer using any of them had to put
  the artifact on their runtime classpath, and the failure mode for getting that wrong is a green
  `compileJava` followed by a `NoClassDefFoundError`, which is not a failure a build can catch. All
  three now emit the equivalent code inline, so `compileOnly` plus `annotationProcessor` is correct
  whatever a consumer uses.
- **`@Lazy` stores its supplier in an `AtomicReference` and memoizes into a sibling field.** The
  reference doubles as the state token and as the monitor the getter locks on, so a memoized `null`
  needs no sentinel and the supplier - with everything its lambda captured - becomes collectable
  once the value exists. The initializer still runs at most once, still retries if it throws, and
  still runs exactly once under contention. A private `$resolve$<name>()` carries the memoizing
  read, which is what an author who writes their own getter should call.
- **`@BuildFlag` constraints are resolved where the builder is generated and checked by code emitted
  into it.** Regexes compile once into a constant instead of on every `build()`, the annotation no
  longer needs to survive to runtime, and the requirement that a consumer's module `opens` its
  package for reflection is gone. Rejections now throw `IllegalStateException`; the messages are
  unchanged.

### Added

- **`@Lazy` accepts primitive fields.** The restriction existed because the old holder could not be
  parameterised with one. The value slot stays primitive and only the supplier's type argument is
  boxed, so the single boxing happens when the value is computed and never on a read.
- **The editor reports a `@Lazy` field as the storage javac rewrites it to.** Previously it showed
  the written type, so reading the field directly was green in the editor and rejected by the build.
- **A target that hands construction to a factory is warned when `validate` is on.** Constraints are
  resolved against the declared type, so a `@BuildFlag` on a subtype the factory returns is not
  enforced - which had to stop being something that happened in silence.

### Removed

- `dev.simplified.lazy.Lazy`, `dev.simplified.classbuilder.validate.BuildFlagValidator`,
  `BuilderValidationException` and `Strings`. All four existed only to be called from generated
  code, and nothing generates a call to them any more.

## [2.6.1]

### Added

- **A javadoc run can now see the members the processor generates.** The javadoc tool runs no
  annotation processors and cannot be made to, so every member the mutation passes inject is absent
  from the model the doclet resolves against: `{@link #getName()}` over a `@Getter` field is
  `reference not found`, and a generated constructor leaves the doclet reporting the implicit one it
  can see instead. Setting `-Adev.simplified.expandTo=<dir>` writes each compilation unit out again
  with its generated members spliced in, for a javadoc task to read in place of the source tree;
  `gradle/expand-javadoc.init.gradle.kts` wires both halves. The option is the only switch, so an
  ordinary build neither reads nor writes anything. Each generated accessor carries the
  documentation of the field it reads, which is what makes the accessor link worth resolving rather
  than worth rewriting - it renders as a real hyperlink, where a link to the backing field renders
  as code text whenever that field is private. Measured over one consumer's 275 sources at private
  visibility: 124 `reference not found` errors to none, and 128 `use of default constructor`
  warnings to sixteen on classes carrying no annotation of this set, with every other warning
  category byte-identical - so the expansion introduces none of its own and needs no `-Xdoclint`
  relaxation.

### Fixed

- **The processor no longer calls JDK methods its oldest supported compiler does not have.** Five
  `java.util.List.getFirst()` calls sat in live processor paths, resolving a `@Collector` key, an
  `@AssignVia` transform, an `Optional` slot's type argument and a copy factory's parameter. It is
  a `SequencedCollection` method added in 21 and this module targets 17, so a consumer building on
  17 got `NoSuchMethodError` naming a JDK class, which reads like their bug rather than this one.
  Nothing about building the module could have caught it: `sourceCompatibility` settles the
  language level and not the API surface, and `options.release`, which settles both, cannot be used
  here because `--release` hides the `com.sun.tools.javac.*` packages every mutator is built on.
  The processor suite now runs on the floor as part of `check`, because running it there is the
  only thing that catches an API ceiling the compiler cannot be asked to enforce.
- **The editor no longer reports a container a generated member fills as one nothing fills.**
  IntelliJ's contents-of-container inspections ask who reads or writes what a field holds, and
  reference search cannot see a member an augment provider contributes, so a class whose
  collections, arrays and string builders are filled by a generated constructor or handed out
  through a generated accessor drew one warning per field. `MismatchedQueryAndUpdateOfCollection`,
  `MismatchedReadAndWriteOfArray` and `MismatchedQueryAndUpdateOfStringBuilder` now join the unread
  and over-scoped families `GeneratedMemberSuppressor` already answers, and are answered by the
  same per-field question rather than blanket per class, so an unannotated field on an annotated
  class keeps every report. Each is spelled by its suppression id, which is what the platform hands
  a suppressor and is not the short name for any of the three. A generated constructor is not the
  only answer to them either: a class carrying nothing but `@Getter` reports a list it fills,
  because the only reader is an accessor holding no reference into the source tree.
- **`@ClassBuilder`'s editor synthesis no longer throws away the resolve that reached it.** Asking a
  `@ClassBuilder` class for its nested classes built the all-args constructor beside them, and
  building that constructor classifies every field, which resolves the type each one declares. A
  reference to a type name is one of the things that asks a class for its nested classes, so the
  platform could already be resolving the very reference the classification went on to ask about.
  It answers a cycle like that by refusing to cache the outer resolve, which costs every later pass
  the same walk and leaves the classification reading a type that resolved to nothing. The
  constructor is built on the first read of it now, and a type name resolves through the nested
  classes alone.
- **The synthesised-member icon's wand shaft carries on dark themes.** The shaft was a near-black
  `#2C2C2C` against the New UI's dark gutter, about 1.2:1, so the one element tying the star and the
  two sparkles together into a wand vanished and the icon read as three unrelated floating shapes.
  A `generated_dark.svg` companion now lifts it to `#9DA0A8`, and the light base settles at
  `#6C707E` rather than near-black. Both sit below the amber star in luminance, so the wand tip
  stays the focal point instead of the shaft outshining it.

### Changed

- **The icon resource is `icons/generated.svg`.** Three features render it - the `@ClassBuilder`
  gutter marker, the `@EqualsAndHashCode` / `@ToString` marker, and the element icon every
  augment-synthesised member wears in the Structure window, completion popup and breadcrumbs - so a
  name carrying only the first was describing one caller rather than the thing itself. Call sites
  name the light file alone; IntelliJ resolves the dark companion from the filename.

## [2.6.0]

The first release driven by adoption rather than by parity. Nineteen in-house modules moved off
Lombok onto this set, and every capability below is one those modules asked for by leaving code
hand-written - each was scoped against its real sites before it was designed, which changed four of
them and killed two outright. Grouped by feature rather than by change type, since most features
landed additions, fixes and breaking changes together; breaking changes are flagged inline.

### `@ClassBuilder` on a constructor or static factory

- **`@ClassBuilder` works on the targets it has always advertised.** `@Target` has permitted
  `CONSTRUCTOR` and `METHOD` since the annotation shipped and the javadoc has described both with a
  worked example, but the processor emitted `@ClassBuilder on <kind> targets is not yet supported -
  skipping` at `WARNING` and moved on, so an author following the documentation got no builder and a
  diagnostic easy to lose in a large build. Both forms are now processed. The slots are the annotated
  member's **parameters** rather than the enclosing type's fields, which is what unblocks a builder
  whose state is not the built type's shape: builder-only values, a `build()` that constructs a
  different field set, and a target with no settable fields at all.
- **It is a third emission path, and shares rather than copies.** `FieldSpec` is the slot IR either
  way, the naming trio resolves the names, `FieldMutators` emits every setter shape, and
  `NestedBuilderFactory` builds everything but the instantiation - so a slot derived from a parameter
  cannot come out with a different setter matrix from a field of the same shape. Five things the
  field path does are absent here because a parameter cannot carry them: all-args constructor
  synthesis, the `@Lazy` storage rewrite, the SuperBuilder chain, `retainInit`, and `from(T)` /
  `mutate()`. The last pair is suppressed rather than guessed at - both seed slots by reading the
  built object, and there is no slot-to-accessor mapping when the slots are parameters.
- **A constructor runs under the enclosing type's parameters and a `static` factory under its own.**
  That is what `new Target<V>(..)` and a static member unable to name the class's parameters
  respectively produce, and `builtType()` follows the factory's declared return type rather than the
  enclosing type. Measured on emitted bytecode: `public static <T> Boxed$Builder<T> builder()` beside
  `public Boxed<T> build()`, off a `static <T> Boxed<T> of(T)`.
- **`@BuilderSeed` (new, `@Target(PARAMETER)`) moves a parameter onto `builder(...)` and emits no
  setter for it.** A seeded parameter becomes a parameter of `builder(...)` and of the builder's own
  constructor, and is held in a `final` slot - which is what makes it a capability rather than a
  convention, since there is no setter to write over the value and the only thing that can assign a
  final field is the constructor that takes it. With that constructor `PACKAGE` by default,
  `builder(seed)` really is the one way in. Several seeds are allowed and reach `builder(...)` in
  declaration order; `build()` passes every parameter in the order the annotated member declares.
  `@Collector`, `@Negate` and `@Formattable` are rejected beside a seed rather than ignored, each
  shaping a setter that a seed does not emit.
- **`@Collector`, `@Negate` and `@Formattable` widen to `@Target({FIELD, PARAMETER})`** so a
  parameter slot keeps the bulk setters, the negated flag and the format overload a field of the same
  type would have. Widening a `@Target` is source-compatible. One shape does not reach a parameter: a
  custom container recognised by implementing `Collection` or `Map` needs an expression that produces
  its declared type, and a field's initializer is the only one there is, so such a parameter degrades
  to a plain replace setter and the processor emits a `NOTE` naming the slot rather than leaving it
  silent.
- **`@BuildFlag` deliberately does not widen.** `BuildFlagValidator` resolves the flagged fields of
  the instance `build()` produced, so a flag on a parameter would be inert whatever `@Target` said,
  and one written on the built type's own fields is found regardless of which member constructed it.
- Rejections are reported at the declaration that causes them rather than inside generated code: an
  instance method, a `void` method, the annotation on both a type and a member, two annotated members
  on one type, a `@Lazy` field on the enclosing type, a written `exclude`, and a written
  `factoryMethod`.

### Builder naming: a pattern per slot

- **`@SetterNames` widens to `@Target({FIELD, PARAMETER})`, so one slot can spell its setters
  differently from the rest of its target.** Its `@Target` was `{}` - only ever an attribute value -
  which made the naming scheme a property of the whole type, so a builder could not pair
  `isLossless(boolean)` with `withQuality(float)` and any type needing one exception stayed entirely
  hand-written. The same six roles are now writable on a slot to override the target's for that slot
  alone, with no new vocabulary. Being usable as `@ClassBuilder(setters = ...)` costs nothing:
  `@Target` restricts *declaration* sites, and an annotation used as another's element value is not
  one.
- **Unwritten roles inherit from the target's resolved scheme rather than from the style.** That is
  the composition the sites need - a type spelling everything `with{}` and one field spelling itself
  `is{}` keeps `with{}` for that field's other five roles - where inheriting from the style would
  silently undo the target's own override for every role the slot did not name.
- **The `{}` placeholder is optional on a slot, and mandatory on a target.** They are two statements
  of one rule: a pattern written where it fans out needs the placeholder or every field generates the
  same method name, and a pattern written on a single member expands exactly once, so
  `@SetterNames(set = "withLabel")` is simply that setter's name. It is also the only way to spell a
  setter that does not contain its field's name at all. The same asymmetry now applies to
  `@Getter(name)` and `@Setter(name)`, where a placeholder-free literal on a field-level annotation
  is accepted and a type-level one is still rejected - so an accessor spelled differently from its
  field (`gateway()` over a `gatewayClient`) no longer has to stay hand-written.
- Every emitter reads the scheme **off the slot**: `FieldMutators`, `SelfTypedSetters`,
  `BuilderEmitter`, `BootstrapMethodFactory` and the plugin's `GeneratedMemberFactory`. Reaching past
  it to the target's config is what would mint the wrong name for an overridden slot, and `from(T)` /
  `mutate()` are where that stops being cosmetic - they call the setters by name, so seeding through
  the wrong one emits a call to a method the builder does not have.

### `@AssignVia` - a setter that transforms its argument

- **`@AssignVia(method = "...")` (new, repeatable, `@Target({FIELD, PARAMETER})`) names a static
  method the generated setter routes its argument through.** The write-direction twin of
  `@ObtainVia`, for the setters that clamp, mask, append a suffix or adapt a second functional
  interface and were therefore left hand-written. **One rule decides the shape and no attribute
  states it: the named method's parameter type is the setter's parameter type.** The slot's own type
  means there is still one setter and it assigns the method's result; any other type means the
  ordinary setter stays and this adds an overload beside it. Repeatable, so a ladder of coercions
  reaches one slot.
- **The direct form goes in at one choke point, and that is the argument for it.** Every shape that
  hands a value to a slot assigns through the same path, so one written transform covers the ordinary
  setter, the zero-argument boolean form, the `@Negate` inverse and the `@Formattable` overload
  together - a clamp reaching only some of those would be a hole no call site can see. `from(T)`
  seeds through the setter too, so the transform runs again on a round trip, which is why the javadoc
  asks for one that gives the same answer applied twice.
- **`@Collector` and `@Lazy` are rejected rather than ignored**, along with `@BuilderSeed`. A
  collector's setters copy element by element into the container rather than assigning it, so there
  is no single value to route; a `@Lazy` slot holds a `Supplier<T>` rather than a `T`; and a seed
  emits no setter. Both mutators refuse to emit against a collector slot as well, so a rejected
  pairing cannot leave a stray method behind.
- Seven more rejections at the annotation: a name matching no single-argument method, a name matching
  several, an instance method, a return type that cannot supply the slot, a direct transform whose
  parameter cannot accept the slot's own type, two transforms of one parameter type, and a transform
  colliding with a signature the slot's matrix already emits - an `Optional<T>` slot already having a
  `T` setter. The last two matter most: each would be a duplicate method in generated code, which
  javac reports on a line nobody wrote.
- Measured on emitted bytecode: `@AssignVia(method = "clampQuality") float quality` yields **one**
  `quality(float)` whose body is `invokestatic clampQuality:(F)F` before the `putfield`, and a
  `String id` carrying two transforms yields `id(String)` beside `id(long)` and `id(char)`, each
  calling its own.

### `@BuilderDefault(provider)` - a default with no initializer to retain

- **`@BuilderDefault(provider = "...")` names a static no-argument method whose return seeds the
  slot, which is what gives a record component a builder default.** `retainInit` reads a field's
  initializer, and a record component has none - so a component default was dropped and the built
  object silently got the JVM zero value. The reference-typed cases were recoverable by
  null-coalescing in a compact constructor; the primitive ones were not, an unset `boolean` being
  indistinguishable from an explicit `false`. A record carrying a non-zero component default is now
  convertible.
- **It routes through the same `$default$<name>()` the retained-initializer path uses** - the
  generated method calls the author's instead of carrying a cloned expression - so the builder slot,
  the `@Collector` merge helper, the empty-container factory and the collected defaults all still
  call one method and nothing downstream had to learn a second shape.
- Chosen over an `expression = "true"` source string on two grounds. It is **type-checked where the
  author can see it**: a missing, non-static or wrongly-typed provider is reported at the annotation,
  naming the method, instead of failing inside a generated body on a line that does not exist in the
  source. And it needs no expression parser - the initializer path never needed one because javac had
  already parsed the field's own tree.
- Four rejections at the annotation: a name matching no no-argument method, an instance method (the
  default is read when the builder is created, before any target exists), a return type that cannot
  supply the slot, and `value = false` beside a provider. The type check drops to erasures where
  either side mentions a type variable, because a generic target's provider declares its own -
  `static <T> List<T> none()` seeding a `List<V>` component is two distinct variables that no
  assignability test relates and that javac infers between perfectly happily.
- A written provider **wins over an initializer beside it**, being the more specific statement, and
  it silences the warning a bare `@BuilderDefault` earns on an initializer-free field - only because
  it supplies the default the annotation was asking for.

### `@Collector` - append, remove, and a key derived from the value

- **`@Collector(append = true)` makes the bulk setter add to the container instead of replacing it.**
  `withOptions(a).withOptions(b)` yielded `[b]` rather than `[a, b]`, with no compile error, no
  warning and no failing test - just a lost element - which is what a hand-rolled accumulating
  builder silently becomes when it is converted. It covers every bulk shape: the varargs and
  `Iterable` forms on a collection, and the whole-`Map` form, which then `putAll`s rather than
  starting a new map. The single-element `singular` add already appended and is untouched;
  `clearable` is how an accumulating builder empties the container deliberately. A declared
  initializer now survives a bulk call, so the replaced-mark is not set either - that mark is what
  tells `retainInit` the default was discarded, and under `append` it was not. **Off by default**,
  and a test pins that: replace is what a setter normally means and it is what the built object's own
  field would hold.
- **`@Collector(removable = true)` adds a remove for one element**, symmetric with `clearable`.
  `SetterNames` gains a seventh `remove` role and each `NamingStyle` a pattern for it; `LOMBOK`
  suppresses it, Lombok having no remove, exactly as it suppresses `compute`. The name comes off the
  collector singular like the add and the put, so `typeAdapters` yields `removeTypeAdapter`. **The
  collection form casts to `Object`, and that is the whole reason it is safe** - a `List<Integer>`
  would otherwise bind `List.remove(int)` and take out the element *at that index*, which compiles
  and quietly removes the wrong one. Measured on emitted bytecode: `removeSize(20)` over
  `[10, 20, 30]` calls `List.remove:(Ljava/lang/Object;)Z` and yields `[10, 30]`. A remove does not
  discard a declared default, unlike `clear` - it takes one thing out of what the builder collected,
  where saying the default is gone is what `clear` is for.
- **`@Collector(key = "...")` derives a map entry's key from the value**, so the generated put drops
  its key parameter and takes the value alone. It names a no-argument method on the map's value type.
  Measured: `function(Named)` calls `Named.name()` and then `Map.put`. This is the one collector
  opt-in that moves a signature, so the editor follows it rather than merely recording it. Four
  rejections, mirrored in the IDE inspection because a bad `key` would otherwise leave the editor
  offering a put no build emits: not a map, no `singular` to reshape, beside `compute` (a key read
  off a value the put-if-absent has not created yet is nothing to generate), and a name matching no
  no-argument method on the value type.

### `@BuildFlag(min, max)`

- **A build flag can state the range a number has to be in.** Both attributes are `double`, so one
  pair bounds every numeric width - `min = 0` on an `int` field is the same widening any assignment
  does - and both default to an infinity, which is the natural spelling of "no bound" and leaves
  every finite value inside the range until one end is written. The bound reads a `Number` or what an
  `Optional` holds, so an empty `Optional` and an unset reference are simply not bounded. A `char` is
  deliberately out: it boxes to `Character`, which is not a `Number`.
- **A bound rejects, it does not clamp**, and that is the seam with `@AssignVia`: a setter that
  clamps into a range is a transform, and a value the build must refuse is a flag.
- The one line that would have made this silently useless is in the validator's own scan, which
  elides a flag whose every attribute is at its default - a bound that did not count as a constraint
  would be dropped there and never enforced, leaving a field that looks checked and is not. It
  counts, and a test pins it by validating a class whose only flag is a bound. Measured and then run:
  a target whose only `@BuildFlag` is a range yields a builder holding **one** `BuildFlagValidator`
  reference where a flag-free one holds **zero**, and `nearLossless(101).build()` throws
  `Field 'nearLossless' in 'Bounded' is 101, above the maximum of 100`.
- **A second validation entry point was considered and declined.** The cross-field cases that asked
  for one all check builder state mapping one-for-one onto a constructed field, so they fit a
  constructor body - a first-class `@ClassBuilder` target as of this release - and the one that must
  act *before* construction fits the `factoryMethod` that already documents itself as being for
  build-time caching or extra validation. New surface on a published annotation set, for cases the
  constructor already reaches, earns nothing.

### `@ClassBuilder(mergeDeclaredBuilder)` - keep one hand-written member, generate the rest

- **`mergeDeclaredBuilder = true` appends the generated members to an author-declared nested
  `Builder` instead of standing down.** A hand-written `Builder` and a generated one could not
  coexist, so a builder needing one custom setter had to stay entirely hand-written. **Off by
  default**, because a declared builder normally means the author wrote the whole thing and the
  existing skip is the right answer for that; the note the skip prints now names the attribute, so
  the way out is discoverable from the build that hits it.
- **The author wins member for member** - a field by name, a method by name and parameter count. That
  arity rule is deliberately looser than the bootstrap collision rule and for the opposite reason: an
  unrelated `from(String)` on a *target* is a parser sharing a name, while a same-named same-arity
  method on the author's *own builder* is the setter they wrote instead of the generated one, which
  is the entire point. Both paths emit from one producer, so a merged builder cannot come out with a
  different member for a slot than an unmerged one.
- **The declared builder's constructor is the author's throughout**, and that is a limitation rather
  than an oversight: javac enters a default constructor into the tree before the round begins, so
  there is no second no-arg form to add, and retyping the one that exists does not take - its symbol
  was entered with the class's own access and that is what every later reference reads. Measured,
  then reverted. `builderConstructorAccess` therefore does not reach a merged builder, and the
  javadoc says to declare one. The all-args constructor on the *target* does move: a declared builder
  no longer suppresses it under merge, since the generated `build()` still calls `new Target(..)`.
- Three rejections at the target: an inner (non-`static`) builder, which no static entry point can
  create; a generic target whose declared builder does not re-declare its type parameters, which
  every generated member names; and a declared slot field whose type is not the slot's, reported at
  the declaration rather than left to fail on a generated setter the author never wrote.

### `@Lazy` over a field its own initializer does not assign

- **An initializer-free `@Lazy` field is legal when a constructor assigns it, and the whole assigned
  expression becomes the supplier body.** `@Lazy` turned a field's own initializer into the supplier,
  so a field assigned anywhere else had nothing to synthesise from and the annotation could not
  express it at all. Now `this.headers = parse(this.raw)` compiles to
  `this.headers = Lazy.of(() -> parse(this.raw))`, deferring the parse to the first read. That covers
  a value taken from a constructor parameter, one computed from a **sibling field** - which has
  neither an initializer to hold the expression nor a parameter to take it from - and the
  supplier-passthrough case, where `this.body = this.decoder.get()` defers the `get()`, which is
  `Lazy.of(decoder)` by another spelling and needs the annotation to know nothing about `Supplier`.
- **Every assignment is rewritten, including one inside an `if` or a `try`.** A field assigned in
  only one arm is still a field this has to rewrite, and missing an arm leaves a type error about
  `Lazy<T>` on a constructor line the author wrote and did not change. Assigning twice, or a
  constructor that does not assign it, is javac's ordinary blank-final error, the field being `final`
  after the rewrite.
- **A constructor this pipeline generated takes only the pass-through.** The all-args factory already
  emits a complete `Lazy<T>` right-hand side for a `@Lazy` instance default, and wrapping that again
  nests a `Lazy` inside a `Lazy`; the discriminator is the generated-member marker on the
  constructor.
- `dev.simplified.lazy.Lazy<T>` keeps its "not part of the public API" javadoc and gains no
  commitments. Nothing about this makes a consumer name the type: the synthesised getter returns `T`,
  so no signature leaks it. The class is still needed on the **runtime** classpath, since it is what
  the field stores.

### `@KeyField(ignoreCase)`

- **`@KeyField(ignoreCase = true)` matches a `String` key without regard to case.** A hand-rolled
  case-insensitive lookup replaced by an exact generated one compiles fine and stops matching, which
  is why two real enums kept their hand-written scans rather than adopting `@EnumLookup`'s generated
  members. It swaps `Objects.equals` for `String.equalsIgnoreCase`, keeping that method's null
  tolerance exactly - a null stored key matches a null argument and nothing else, since calling the
  method on a null element would throw inside generated code the author cannot see. `findBy<Key>`
  delegates to `of<Key>`, so it folds case with it. Exact matching stays the default and a test pins
  that; the attribute is inert on any other type, which the IDE inspection now warns about.

### Generated surface that changes

Four fixes below rename or narrow a member the previous release generated. Each is a source break
for a caller compiled against 2.5.1, and each is done now rather than later because the emitted name
was wrong and every additional adopter makes it more expensive to correct.

- **BREAKING: `@Getter` on a `boolean` field already named `isX` mints `isX()`, not `isIsX()`.**
  Lombok strips the leading `is` and emits `isX()`; every non-`FLUENT` `NamingStyle` here applied
  `is{}` unconditionally, so a bare `lombok.Getter` -> `dev.simplified.annotations.Getter` import
  swap **renamed a public accessor** with `compileJava` and the whole suite staying green - the only
  thing that could see it was a consumer outside the module or a bytecode diff. The rule is on the
  pattern rather than on the style: a leading `is` is stripped from the name a pattern expands
  against, and skipped exactly when the pattern opens with the placeholder, so `is{}` and `set{}`
  strip while a fluent `{}` does not. That is Lombok's own condition expressed without naming a
  style. **The setter half was part of the same defect**: Lombok sets the base name once and both
  accessors use it, so `boolean isPermaLink` is `isPermaLink()` and `setPermaLink(boolean)`, where
  this was minting `setIsPermaLink` too. A `String` field named `isPermaLink` means something else
  entirely and is left whole, as are `island` and a bare `is`.
- **BREAKING: the builder's zero-argument boolean setter no longer doubles the same prefix.** The
  same defect one surface over: the `flag` role is `is{}` under every style but `LOMBOK`, expanded
  against a `boolean` field's name or a `@Negate` stem, so a field named `isPermaLink` got a builder
  setter `isIsPermaLink()` and `@Negate("isDisabled")` got `isIsDisabled()`. The typed form had the
  same shape one level down, so `NamingStyle.BEAN` minted `setIsPermaLink(boolean)`. Measured under
  `BEAN`, where the worst of it was: `boolean isPermaLink` now emits `isPermaLink()` and
  `setPermaLink(boolean)`. SuperBuilder chains mint the same names, and so does the editor.
- **BREAKING: `@Collector(singular = true)` no longer eats the `e` off a field whose name ends in
  `es`.** A `List<String> frames` generated `addFram`. The rule stripped two letters from **any**
  `-es` ending, so `frames`, `names`, `types`, `values`, `phases` and `responses` all lost a letter
  belonging to the word rather than to the plural - and the builder compiled, so the wrong name was
  simply the name. An `-es` ending now gives up both letters only after a sibilant or an `o`
  (`boxes`, `classes`, `addresses`, `matches`, `dishes`, `heroes`), which is where English put the
  `e`; everywhere else the plain `-s` rule applies. A name ending `ss` or `us` is left whole, so
  `address` and `status` are not mistaken for plurals, and `-ies` needs two letters of stem so `ties`
  comes out `tie` rather than `ty`. The rule now exists once and both the processor and the editor
  call it.
- **BREAKING: the generated `Builder`'s own no-arg constructor defaults to `PACKAGE`, via a new
  `builderConstructorAccess` attribute.** `access()` governs the builder class and the bootstrap
  methods, and the constructor followed it, so the default `PUBLIC` published `new X.Builder()`
  beside `X.builder()` - a second entry point the author did not write, on every converted type. The
  neighbouring `constructorAccess` javadoc already argues the case one level up ("matching the
  implicit constructor Lombok `@Builder` supplies, so callers are routed through `build()`"), and
  Lombok's own builder constructor is package-private for exactly that reason. A separate attribute
  rather than a widening of `constructorAccess`, because the two govern different constructors, and
  separate from `access()` because a builder class has to be visible to be useful as a type - a
  different question from whether `new Target.Builder()` is an entry point. The constructor is now
  **declared** rather than left implicit, an implicit one taking the class's access with no other way
  to narrow it. Measured: a bare `@ClassBuilder` emits `Probe$Builder();` where it used to emit
  `public Probe$Builder();`.

### The runtime dependency `@ClassBuilder` no longer forces

- **The generated `build()` calls `BuildFlagValidator` only when something would answer it.**
  `validate()` defaults to `true`, so the call was emitted unconditionally and a module whose only
  library annotation was `@ClassBuilder` could not use `compileOnly` - the failure mode being a green
  `compileJava` followed by `NoClassDefFoundError` at runtime, which no compile check and no test
  over a module without builder coverage can see. Measured rather than theorised: three modules were
  forced onto `implementation` for this, one of them for eight builders not one of which declared a
  `@BuildFlag`. A bare `@ClassBuilder` now yields a builder whose constant pool holds **zero**
  references to the validator.
- **Two conditions the naive fix would have got wrong.** "Something" is decided by walking the
  **superclass chain**, because the validator's own scan climbs to `Object` - asking only about
  declared fields would turn an inherited requirement into a silently unenforced one. And a target
  with a `factoryMethod` keeps the call **unconditionally**, since the validator reads
  `target.getClass()` and so sees the flags of whatever the factory returned, which may be a subtype
  declaring constraints this type has never heard of. A `static` factory target keeps it for the same
  reason.
- **The build-file consequence is now the opposite one.** A module whose surviving annotations are a
  `@ClassBuilder` with no reachable `@BuildFlag` needs `compileOnly`, not `implementation`, and so do
  its consumers - which takes this jar off their runtime classpath entirely. `@Lazy` and `@BuildFlag`
  are still runtime triggers.

### The editor half

Every capability above moved in the same change as its processor half, because a green editor over
source javac rejects - or a red one over source that builds - is the failure this plugin exists to
prevent. Six defects were found by writing the parity test rather than by using the plugin.

- **The editor synthesised a second bootstrap beside a hand-written one.** `builder()`, `from(T)` and
  `mutate()` were added unconditionally with no collision check at all, so a class declaring its own
  `public static Builder builder()` showed **two** in the PSI where javac emits one - a
  duplicate-method error in the editor over source javac compiles cleanly, and reachable by following
  the ordinary advice to leave a hand-rolled builder in place when it cannot be expressed. The
  processor's own rule now decides both, over own methods rather than all methods, since the latter
  includes augmented members and a provider asking it while running would see what it contributed
  last.
- **`from(T)` was suppressed by an unrelated `from(X)`.** The check declining to overwrite an
  author's method matched on name and arity alone, so a `from(String)` parser silently cost the type
  its copy factory. `from(T)` now additionally requires its parameter to denote the target, asked of
  the element model rather than of the tree - a tree parameter's declared type is an unattributed
  expression no type test applies to, while every author-written method is resolved before the round
  begins. Comparing the parameter's element to the target element is exact and needs no name
  matching, so a generic `Widget<T>` parameter answers the same as a raw `Widget`.
- **`@EnumLookup` reported a cache-field collision in the build and skipped it silently in the
  editor.** An enum already declaring `CACHED_VALUES` - or a `CACHED_KEYS_<field>` - got a
  definite-assignment error rather than a diagnostic naming the collision, and the augment provider
  passed over it without a word, so the IDE was *greener* than the build. The mutator now reports one
  error per colliding name, naming the annotation, the field and the fix, and emits nothing rather
  than assigning over the author's `final`; refusing the enum outright is right here rather than
  degrading, since the hand-rolled cache is what the annotation is adopted to delete. Both halves
  read the two names from one place so they cannot disagree about the spelling.
- **`@Lazy` on a constructor-assigned field was flagged as an error in the editor.** The inspection
  reported `@Lazy on a field without @ClassBuilder requires an initializer expression` at error
  severity, so accepting the shape in the processor would have painted every such field red over
  source that compiles. It now asks the same question the processor does. There was no test for this
  inspection at all, which is how it would have gone unnoticed.
- **The editor claimed an `@XContract` on the builder's own constructor that javac never emits.** The
  inferred-annotation provider treated any other method inside the synthesised `Builder` as a setter
  and shaped it by arity, and the constructor went through that arm - so the IDE reported
  `@XContract("-> this", mutates = "this")` where the build attaches nothing at all. A constructor
  does not return `this` and cannot be reasoned about as if it did, so the claim was wrong on its own
  terms as well as absent from the build.
- **The attribute readers resolved the annotation type, which re-enters the augment provider when the
  annotation is on a member.** Reading an annotation's qualified name resolves its name reference,
  and on one written **inside a class body** the name lookup consults the enclosing class's nested
  types first - a lookup that is augment-aware, and so comes straight back into the provider that
  asked. The platform kills the nesting and logs the provider as the culprit: a hard failure under
  the test fixture, and in a live IDE a logged error plus a builder that intermittently does not
  appear. Every such read now takes the *declared* value and states the annotation's default at the
  call site, which each reader already did in its fallback argument, so nothing changed but the
  resolve. Matching the annotation by name had the same problem, so that is now decided from the
  reference's own shape and the file's import list.
- **The re-entry guard is a set, so a nested guard for one target cleared the outer one.** It did a
  bare add then a `finally` remove, correct only if it never nests - and reading a builder's
  declaration site to answer where the annotation lives made it nest. The inner remove cleared it and
  the next re-entry recursed for real. The guard is now released only by the call that took it, and
  the one hand-rolled add/remove pair goes through the same helper.
- **A synthesis pass resolved annotations from inside the augment chain, and the platform can enter
  that chain mid-resolve.** The platform's own folding builder resolves a field-level annotation,
  that resolve walks the class's nested types, and whatever the provider then resolves for itself
  nests inside a resolve already in flight. Fixing it closed a second defect rather than forcing the
  expected trade: the nullness read asked a manager that answers to every configured flavour, while
  the processor reads exactly `org.jetbrains.annotations.NotNull` / `Nullable` - so a field carrying
  `javax.annotation.Nonnull` got `@NotNull` on its setter in the editor and nothing in the class
  file, a claim the build does not make. Narrowing to the two names is parity, not a concession.

### Documentation

- **`@Collector`'s javadoc named bulk setters the default style never emits.** Seven examples spelled
  the whole-collection replace and its overloads `withEntries(...)` / `withTags(...)`, where the
  `set` role under `NamingStyle.SIMPLIFIED` is `"{}"` and the emitted names are `entries(...)` /
  `tags(...)`. The `add` / `put` / `clear` names in the same examples were correct, which is what made
  the wrong ones believable - and it cost one real conversion plan, whose author concluded a
  `with`-prefixed hand-rolled builder would come out byte-for-byte from a bare `@ClassBuilder`.
  Anyone converting such a builder on the strength of that javadoc silently renamed every setter. The
  seven now carry the emitted spelling, and one line says that a `with` prefix needs
  `@SetterNames(set = "with{}")`.
- **`@Getter(name)` documented a free rename it never permitted.** It said the attribute "overrides
  the pattern outright for one field", where the processor rejected any value without the `{}`
  placeholder. Both `name()` attributes now state that the placeholder is mandatory at type level and
  optional on a single member, which is the rule as it now stands.
- **`putXIfAbsent` takes a `Supplier<V>`, not a `V`.** Correct and deliberate - it is the lazy
  put-if-absent - but it is the one generated signature that reads wrong at a call site, and it cost
  a compile error in the first probe written against this library. A two-line worked call shape now
  sits under the opt-in list, the compiling form beside the one that does not.
- **The processors no longer warn on every module built at `-source 21`.** Each of the six declared
  `RELEASE_17`, so a clean compile printed six copies of `warning: Supported source version
  'RELEASE_17' from annotation processor ... less than -source '21'`, taking one module's warning
  count from 1 to 7 and burying the real one. The v17 javac baseline is deliberate; advertising it as
  the *maximum* supported source was not. Each processor now reports `latestSupported()`, which
  changes nothing about which compat layer is chosen. It is an override rather than an annotation,
  an annotation value having to be a constant expression.

### Adopting the set: what the first consumers saw

Nineteen modules moved off Lombok onto this set during this release cycle. Four surface changes came
out of that and are recorded here rather than in those modules alone, because each is what a generated
member does differently from the hand-written one it replaced, and none of them was visible to a
compiler or to a passing test. All four were found by building each module at its pre-migration
commit and diffing the emitted member list against the migrated one - which is the only check that
finds them, and is worth running on any adoption of this set.

- **A type-level `@Getter` stops at instance fields, and a class full of constants notices.** The
  2.5.1 change removing the static fan-out (Lombok parity - Lombok's type-level `@Getter` only ever
  covered instance fields) cost one consumer 22 public static accessors, none of which had a caller
  anywhere: `DiscordCommand.getNO_EXAMPLES()`; `Parameter.getNOOP_HANDLER()`, `getNOOP_COMPLETE()`,
  `getEMOJI_PATTERN()`, `getMENTIONABLE_PATTERN()`, `getMENTIONABLE_CHANNEL_PATTERN()`,
  `getMENTIONABLE_ROLE_PATTERN()`, `getMENTIONABLE_USER_PATTERN()`; `Button.getNOOP_HANDLER()`;
  `SelectMenu.Option.getMAX_ALLOWED()` and `getNOOP_HANDLER()`; `TextInput.getNOOP_HANDLER()`;
  `PipelineBuilderSession.SAVE_LABEL()`, `SAVE_SHORT_ID()`, `SAVE_VISIBILITY()`, `SHORT_ID_RE()`;
  `EmojiHandler.getEMOJI_NAME_LENGTH()`; `Emoji.getNOOP_HANDLER()`; `Embed.getFOOTER_TIME_FORMAT()`;
  `Field.getMAX_ALLOWED()`; `FieldSet.getMAX_MODAL_COMPONENTS()`; `Page.getSINGLETON_KEY()`. A
  `static final` constant is reachable by name and never needed an accessor, so the fan-out was the
  drift rather than its removal. Writing the annotation on the field still mints one.
- **A converted builder's setter is named from the field, not from the method it replaced.** A
  hand-written `withLogger(Logger)` over a field named `log` comes back as `withLog(Logger)` under
  `@SetterNames(set = "with{}")`, and every other setter on that type is unchanged - so the rename
  reads as arbitrary until you notice it is the only one that was not already field-shaped. Renaming
  the field to recover the old spelling would put the tail before the dog. Two shape changes rode
  along in the same conversion and are the ones to expect generally: the builder is no longer `final`,
  and a whole-container replace setter appears for any field that has one - here `withBag(Map)`, which
  the hand-written builder did not expose.
- **A converted builder does not carry the `toString()` Lombok emitted on its own builder.** Eight
  builders on one consumer lost it. Nothing prints a builder, and `@ClassBuilder` emitting one would
  be new surface rather than restored surface, so it is not being added. Every setter name on all
  eight is identical either side, which is the part that matters for their call sites.
- **`@Negate` generates the true inverse, which can silently repair a hand-written flag that was
  wrong.** One consumer's `isSpringdocDisabled()` had the body `return this.isSpringdocEnabled(true)`,
  so the disable form turned the flag **on**, while its two siblings were correct. Replacing it with
  `@Negate` gives the inverse the name promises - verified against the built classes:
  `builder().isSpringdocDisabled().build().isSpringdocEnabled()` is now `false`. A caller relying on
  the broken form changes behaviour with no compile error, from a diff that reads as a pure annotation
  swap. Worth diffing the *behaviour* of any hand-written negated flag before replacing it, not just
  its name.

### Fixed

- **A Central upload bundle no longer carries the release before it.** The staging repository the
  bundle is zipped from is an ordinary directory under `build/`, and nothing in an ordinary build
  removes it, so publishing into one that still held the last release produced a bundle containing
  both - and an upload that tried to publish a version Central already has. Nothing about it
  surfaced in the build log; the bundle's size was the only tell. Clearing it is now wired into
  the task graph ahead of the publish rather than left as a step to remember.

## [2.5.1]

### Added

- **A field's `@ApiStatus` markings travel onto the accessors generated from it.** The field is
  private, so it was never the member a consumer could reach - the generated accessor is, and it
  carried nothing, so an author could mark a value internal or experimental while every reader,
  and every tool that reads a marking, still saw ordinary public surface. The whole family travels
  rather than the one member that names it, and setters travel with getters, since both are
  reachable and both are surface. Written trees are copied rather than rebuilt so an argument
  survives: `@ApiStatus.AvailableSince` declares no default for its value, and a copy that dropped
  arguments would emit a member javac rejects rather than merely losing information. A marking
  written without its enclosing name does not travel - the family is nested, so it can be imported
  directly and written bare, and settling that case would mean resolving every annotation on every
  field; the processor and the editor decline it alike, so the two agree on what a generated
  member carries.

### Changed

- **A type-level `@Getter` / `@Setter` no longer fans out over static fields** - *breaking*.
  Annotating a class asked for an accessor on every field it declared, static ones included, so
  each constant in the class published a static accessor as a side effect of annotating the class.
  The first consumer swept for it carried a dozen of them and read none anywhere. A static field
  holds the class's own state rather than any instance's - the rule Lombok already applies - so a
  blanket request written across the class now passes it by. Writing the annotation on the field
  is how a static accessor is asked for, and that route still mints one, so nothing that wants the
  shape loses it. The rejection is silent, matching the rule already governing a `final` field
  under a type-level `@Setter`: fanning out over a class is a blanket request rather than a claim
  about any one field.
- **The accessor-width report is bounded to types whose readers the project holds.** The reader
  search runs over the project, so on a type visible outside one it cannot tell an accessor
  nothing reads from an accessor something depends on - and every narrowing it offers, to the
  package, to subclasses, or away entirely, removes reachability a consumer elsewhere may be
  using. The report now stays quiet on a type reachable from outside: public the whole way out, or
  protected inside one that is, since a subclass in another artifact reaches a protected nested
  type. Two things bring such a type back into range - `@ApiStatus.Internal` written on it, on a
  type enclosing it, or on the field itself, and a *Report accessors on types visible outside
  their project* option for a project that publishes nothing. The promotion trigger is left
  unbounded on purpose: it replaces hand-written accessors with generated ones of the same name
  and width, so it removes no reachability and needs no bound.
- **The annotation-misuse inspections moved into a Misuse subgroup.** Nineteen inspections sat in
  one flat list and eleven of them answered the same question - whether an annotation is written
  somewhere it cannot work - so reaching the ones that report on code rather than on annotation
  placement meant reading past all eleven. Those now sit under *Simplified Annotations > Misuse*,
  each having dropped *misuse* from its own name, since the heading already says what kind of
  check it is. What stays at the top level is what is not about placement: the two resource-path
  checks, the two contract checks, the duplicate-key check, the accessor-width check and the two
  hand-written-equality checks. Per-inspection severity and enablement carry over untouched, since
  those key on the short name rather than on the group.

### Fixed

- **The plugin no longer calls a JDK 21 method on IDEs that run 17.** `ReplaceableEqualityInspection`
  built its `exclude` attribute through `List.getFirst()`, which arrived with `SequencedCollection`
  well after the oldest IDE this plugin supports, so the call resolved when compiled and would have
  thrown `NoSuchMethodError` in an editor. Indexed access replaces it. `sourceCompatibility` and
  `targetCompatibility` settle the language level and the bytecode version and neither looks at
  which API is being called, so compiling on a newer JDK still linked against that JDK's class
  library and nothing objected; the plugin module now compiles with `release`, which links against
  the 17 API itself and turns the same mistake into a compile error rather than something an
  IDE-compatibility check finds later.
- **The accessor-width search no longer races itself across files.** The search deciding how far
  an accessor is read hands the platform a processor, and the platform runs it over several files
  at once; it collected into a plain list and tested its overflow ceiling by re-reading that
  list's size. Only one of the two failure directions was visible - a corrupt append threw out of
  the search and aborted the inspection for whichever file was being analysed, which at least left
  a stack trace, while a lost update did not throw at all: it dropped a reader, and the reach came
  back narrower than the truth, so the report offered to cut a member something really does read.
  The symptom of the silent half is that asking the same file twice gives different answers.
- **A synthesis pass no longer resolves every annotation it walks past.** Reading an annotation's
  qualified name resolves its name reference, and a resolve started inside an augment or
  inferred-annotation provider runs while the platform already holds the class being augmented -
  the name lookup consults that class's nested types before its imports, the nested-type lookup is
  augment-aware, and so the resolve can re-enter the provider that started it. Every such read now
  passes a written-simple-name test first, which costs no resolution and rejects nothing the
  qualified-name test would have accepted, since a qualified name ends with the reference's own
  name either way.
- **The editor no longer reports a generated constructor's fields as uninitialized.** A
  `@RequiredArgsConstructor` / `@AllArgsConstructor` / `@BuilderArgsConstructor` /
  `@ClassBuilder` target marked every `final` field with no initializer
  `Field 'x' might not have been initialized`, and every `@NotNull` one
  `Not-null fields must be initialized` - on source javac compiles. An augment provider makes a
  generated constructor **resolve**, which is what call sites need, but definite-assignment
  analysis walks written constructors and an augmented one is not among them. Because both
  reports come from the Java highlighter rather than from an inspection, no severity setting and
  no `InspectionSuppressor` could reach the first of them; `GeneratedMemberHighlightFilter`
  registers the `daemon.highlightInfoFilter` extension point that can. The first real consumer
  migration off Lombok hit this on 64 files and 328 fields, and the plugin's own demo fixture had
  hidden it by hand-writing the constructor it was meant to be generating.
- **A field whose only reader is a generated accessor is no longer offered up as a local
  variable.** `@Getter` fields drew `Field can be converted to a local variable`, because
  reference search sees no reader: the generated getter is a light method holding no reference
  into the source tree. This one shipped a quick fix, so the warning was not merely wrong -
  taking its offer deleted the field the accessor reads. `GeneratedMemberSuppressor` answers it
  along with the unused-field reports that share the cause, per field rather than per class, so
  an unannotated field on an annotated target keeps every report it should.
- **`@ClassBuilder`'s lifted blank finals no longer read as double assignment.** `retainInit`
  keeps a `final` field's initializer as a builder default and strips it from the field, so a
  constructor assigning that field is correct in the class javac emits and
  `Cannot assign a value to final variable` in the source the platform reads. Handled through the
  same filter, gated on the builder's own field selection rather than on a second reading of the
  rule, so a field `exclude` or `@BuilderIgnore` drops keeps the error it has earned.

Both new extension points route every selection question through `ArgsSelection`, the decision
class the processor itself calls, so a suppression cannot come to cover a field javac still
rejects. `ArgsInference.excluded` becomes public to serve them.

## [2.5.0]

The lombok-parity release. Grouped by feature rather than by change type, since
several features landed additions, fixes, and breaking changes together; breaking
changes are flagged inline.

### `@Log` - in-house replacement for Lombok's `@Log4j2`

- **`@Log` on a class or enum injects `private static final org.apache.logging.log4j.Logger log = LogManager.getLogger(Foo.class)`,** byte-for-byte what `@Log4j2` emits. `topic` carries over from Lombok: written, the factory takes the string literal in place of the class literal. `name` sets the field name through the same `NamePattern` expander the builder uses and defaults to `log`. **Single backend by decision** - `@Log` means log4j2, with no `LogBackend` enum - because a second backend can be added later as a defaulted `value()` without breaking any existing site. The field is always `private static final`, which keeps it clear of the builder's field collector and lets `@Log` run as its own processor with no ordering relationship to `@Lazy` or `@ClassBuilder`. The library stays zero-dependency: log4j2 is named only as fully-qualified strings and never linked, so the consumer supplies `org.apache.logging.log4j:log4j-api` themselves, and a module missing it gets an error on the annotation naming the artifact rather than an unresolved symbol inside a generated field. The class literal is emitted raw so a generic target still compiles, a field already named `log` is a warn-and-skip (an inherited one is not a collision), and interfaces, records and annotation types are rejected. `LogAugmentProvider` and `LogInspection` surface the field to the editor so its `log.*` call sites resolve before the first javac round.

### Accessors: `@Getter` and `@Setter`

- **`@Getter` and `@Setter` - read and write accessors on classes and enums.** `@Retention(CLASS)`, `@Target({TYPE, FIELD})`. A type-level annotation fans out over the type's fields; a field-level one beats the enclosing type's, and `AccessLevel.NONE` on a field is how one field opts out of a type-level fan-out. `value` sets the access level (default `PUBLIC`), `style` the naming (`NamingStyle`, default `SIMPLIFIED`), `name` a per-field `NamePattern`, and `exclude` (type level only) drops named fields; `emitContracts` and `emitGenerated` round out the surface. Both default to bean-shaped names - a bare `@Getter` produces `getX()` / `isX()` - so it is a drop-in for Lombok's `@Getter` / `@Setter` that renames no existing call site. Records and interfaces are rejected; classes and enums are supported. Emission is dispatched from the `@ClassBuilder` processor rather than a processor of its own, so its ordering against `@Lazy` is a guarantee - a `@Getter` racing `@Lazy` would otherwise emit a duplicate `getX()`.
- **`NamingStyle.FLUENT` replaces Lombok's `@Accessors(fluent = true)`.** `@Getter(style = FLUENT)` mints `label()` rather than `getLabel()` - `{}` for all three accessor roles - which is the single accessor shape every workspace site uses. No `@Accessors` clone ships: the existing `NamePattern` grammar already expresses it, and `AccessorScheme` joins the naming trio to resolve the three per-field accessor patterns (`accessorGet` / `accessorIs` / `accessorSet`).
- **`GetterVisibilityInspection` (IDE) flags accessors wider than their use and promotes hand-written ones.** A `PUBLIC` read accessor whose references fall entirely inside its declaring class, subclasses, or package is offered a narrower `AccessLevel` - a `WEAK_WARNING`, since `:library` publishes to Maven Central and narrowing a public accessor is a downstream break, so it is a review aid rather than a batch-apply cleanup. Separately, two or more hand-written zero-arg field-returning methods under the names the resolved `AccessorScheme` would mint are offered promotion to a type-level `@Getter`. The body test is exact - a single `return field` of matching type, disqualified by a null check, cast, defensive copy, ternary, widening, `synchronized`, or supertype declaration - so promotion never deletes a method that was doing real work.

### Constructor annotations: `@AllArgsConstructor`, `@RequiredArgsConstructor`, `@NoArgsConstructor`, `@BuilderArgsConstructor`

- **One field-selection policy with four settings.** `@Retention(CLASS)`, `@Target(TYPE)`, each carrying one `access` attribute (default `PUBLIC`) plus `emitGenerated`; `@NoArgsConstructor` adds `force`. `@RequiredArgsConstructor` selects `final` fields with no initializer - an initialized `final` is already definitely assigned, so it is not a parameter, and a nullness annotation on a mutable field does not make it required, since nullness says what a field may hold rather than whether the constructor must be told. `transient` fields are parameters; `@Lazy` fields are not - the field installs its own `Lazy<T>` wrapper and only the builder's constructor knows how to hand it a supplier - and their omission from an "all args" constructor is reported. Enums are supported: the constructor is forced `private` whatever `access` says, and the constant list binds positionally to the generated arity.
- **These annotations add a constructor rather than backing off when one exists.** `@ClassBuilder` declines to synthesise when the author wrote a constructor; copying that guard here would silently do nothing on every constant-carrying enum, which declares a constructor by necessity. A duplicate erasure between two of these annotations on one type is reported naming both, rather than reaching javac as a duplicate-constructor error on a line the author cannot see. `staticName`, `onConstructor_` and `AccessLevel.NONE` are not carried over - `NONE` on an annotation whose only job is to generate a member leaves it with nothing to do, so it is an error.
- **The IDE contributes the constructor in both directions.** `ArgsAugmentProvider` surfaces a synthesised constructor so a target's call sites resolve, and contributes the private default that `@NoArgsConstructor(access = PRIVATE)` implies so an outside `new Target()` reports the error javac will give. The inferred annotation is surfaced through `ArgsInferredAnnotationProvider` and the line-marker tooltip - which carries the resolved signature, since one `@BuilderIgnore` silently shortens the constructor - rather than written into the author's source.

### Whole-object members: `@EqualsAndHashCode` and `@ToString`

- **`@EqualsAndHashCode` - `equals` / `hashCode` over a type's instance state, including the shape Lombok refuses.** `@Retention(CLASS)`, `@Target(TYPE)`. Works on records, where Lombok declines and every implicit `equals` comparing an array component by reference has had to be hand-written; enums are rejected, since `Enum.equals` is already `final` and identity-based. Attributes: `identity` (default `EXACT_CLASS`, with `INSTANCE_OF` and `INSTANCE_OF_CANEQUAL` alternatives), `callSuper` (default `AUTO`), `of` / `exclude`, `cacheHashCode`, `useAccessors` (default `false`, inverting Lombok), `emitContracts`, `emitGenerated`. `hashCode` is a `result * PRIME + term` accumulator rather than `Objects.hash(...)`, so an array member hashes by content and stays consistent with the `equals` beside it; `cacheHashCode` adds a `transient int $hashCode` memo. A target or supertype already declaring either member is an error naming it, not the silent skip `@ClassBuilder` performs - an annotation asked for an equality relation must not then supply none.
- **`@ToString` - a `Name[a=1, b=2]` representation over the same member selection.** `@Retention(CLASS)`, `@Target(TYPE)`. Mirrors `@EqualsAndHashCode` in member selection and four attributes deliberately, so a type cannot count an inherited field in one member and hide it in the other. Attributes: `callSuper`, `includeFieldNames`, `style` (default `SIMPLIFIED`, with `LOMBOK` offered), `of` / `exclude`, `useAccessors`, `emitContracts`, `emitGenerated`. The one sanctioned divergence from the equality pair is `transient`, which `@ToString` keeps and `@EqualsAndHashCode` drops - a field excluded from serialization is not part of the value but is still state a debugger dump wants. An already-declared `toString` is left alone with a note rather than reported, since nothing depends on it the way a collection depends on the equality pair.
- **`@EqualsInclude` / `@EqualsExclude` / `@ToStringInclude` / `@ToStringExclude` field and method markers.** The include form overrides the `transient` skip and admits a zero-arg non-`void` method as a term. `@ToStringInclude` carries `name` and `rank`, and `@EqualsInclude` deliberately carries neither - print order is cosmetic and visible in the output, where the same attribute on the equality pair would make an emitted hash depend on an ordering rule nothing in the source shows. The `CallSuper` enum (`AUTO` / `YES` / `NO`) is shared by both annotations.
- **`of` / `exclude` naming a method now names the remedy.** A name matching a zero-arg non-`void` instance method the target declares - exactly the shape the include marker admits - is reported as `names 'key', which is a method rather than a field - mark it @EqualsInclude to make it a member`, in place of the bare `which is not a member this selection reaches`. The old report was accurate and useless: a derived value with no backing field *is* reachable, but only once the method carries the marker, and nothing said so. The processor and both inspections say it in the same words, and a shape the marker could not rescue - one taking parameters, returning `void`, or `static` - keeps the bare report rather than naming a remedy that would not work. The interface path keeps the bare report deliberately: marking a method there is an error rather than a fix, since the emitted class holds a field only for an abstract zero-arg accessor.
- **`EqualityConsistencyInspection` (IDE) reports a hand-written `equals` / `hashCode` pair that reads different state.** The two directions are different findings: a `hashCode` reading **more** than `equals` is a contract break reported at `WARNING` - two objects the relation calls equal hash apart and one of them goes missing from every hash container - while a `hashCode` reading **less** is legal and reported at `WEAK_WARNING`, since equal objects still agree on the smaller set. Folding the supertype counts as state, so an `equals` calling `super.equals` beside a `hashCode` that skips `super.hashCode` is the same finding as a dropped field. This is also the one shape `@EqualsAndHashCode` structurally cannot adopt - it drives both members from one selection and one `callSuper` - so the check doubles as the inventory of what has to be decided before a hand-written pair can be replaced by the annotation. **No quick fix is offered on purpose**: closing a split means deciding which member set is the value of the object, and nothing in the source says which. The analysis declines rather than guesses - a body delegating to a helper yields no member set and is left alone, a field assigned inside `hashCode` is read as a memo rather than a member, an accessor and the field behind it collapse to one member so the two sides may be spelled differently, and a target already carrying `@EqualsAndHashCode` is left to the inspection that owns it.
- **`ReplaceableEqualityInspection` (IDE) reports a hand-written pair the annotation *would* generate, and offers to replace it.** The counterpart to the check above: between them, a class carrying a hand-written pair gets an answer either way, which is what makes the two together an inventory rather than a nag. Reported at `WEAK_WARNING` on the `equals` name identifier, with a fix that writes `@EqualsAndHashCode` and deletes both methods. **The match is exact, and that is the whole safety argument** - every statement of the `equals` must be a recognised guard, the cast local, or the returned chain, and every term of that chain must be the comparison the annotation emits for that member's declared type. A guard that reads state, an unrecognised term, or a `float` compared with bare `==` where the annotation emits `Float.compare` each drop the class from the report entirely rather than reporting it with a caveat. **Two spellings are read as the comparison they are rather than rejected for not matching it byte for byte.** A member compared with `==` where its declared type is an **enum** matches, because `Enum.equals` is `final` and identity-based, so the `Objects.equals` the annotation emits agrees with `==` on every pair including two nulls; the same `==` over any other reference type is still rejected, since there it asks about identity where the annotation asks about value. And a member compared through **its own `equals`** - `this.name.equals(that.name)` - matches, because that is the relation `Objects.equals` expresses, delegating to the same method for every non-null receiver. Those two forms part company on exactly one input, a `null` member, where the written call throws out of `equals` and the generated pair answers: the generated pair compares with `Objects.equals` and hashes behind a null check, so adopting it turns a crash into an answer rather than changing one. **`hashCode` is held to a weaker standard on purpose**: it must read the same members and nothing more, since a hash *value* is not part of any contract - only its consistency with `equals` is - and requiring the accumulator shape too would reject almost every hand-written pair to protect a number nobody may depend on. **One difference is reported rather than rejected**: where the written `equals` compares an array member by reference - whether through `Objects.equals` or the member's own `equals`, both of which compare an array by reference - the annotation compares by content, so adopting it changes equality. That gets its own message and a fix named `(compares arrays by content)`, because the change is usually the reason to adopt it. The fix writes `identity` where it is not the default, `callSuper` wherever there is a superclass to be explicit about, `exclude` for any reachable member the pair ignored, and an `@EqualsInclude` marker on a member the selection does not reach on its own such as a derived accessor; where there is nowhere to put that marker, the class is not reported.

### `@UtilityClass`

- **`final` plus a throwing constructor, with the implicit-`static` half opt-in.** `@Retention(CLASS)`, `@Target(TYPE)`. Marks the class `final` and retrofits the constructor javac already generated into one that throws. Attributes: `makeFinal` (default `true`), `members` (`REQUIRE_STATIC` default / `MAKE_STATIC`), `nestedTypes`, `constructorAccess` (default `PRIVATE`), `message`, `emitContracts`, `emitGenerated`. Deliberately splits Lombok's feature in two: the `final`-plus-constructor half is on by default, while the half that makes every member implicitly `static` - the source of every sharp edge, since it changes the meaning of a declaration the author wrote - is opt-in behind `members = MAKE_STATIC`, and the default reports an instance member as an error instead. A target also carrying `@ClassBuilder` is rejected: one annotation exists to produce instances and the other to forbid them, and the pair would otherwise compile into a builder whose `build()` calls a constructor that throws. `UtilityClassInspection` reports an instance member under the default policy and offers a "Make static" quick-fix, and `UtilityClassAugmentProvider` contributes the added `static` under `MAKE_STATIC` so a class-qualified call to such a member does not read as an instance-from-static-context error on source that builds.

### Body rewrites: `@SilentThrows` and `@Cleanup`

- **`@SilentThrows` - wraps a body so a checked exception leaves a method that declares none.** `@Retention(CLASS)`, `@Target({METHOD, CONSTRUCTOR})`, `Class<? extends Throwable>[] value() default Throwable.class`. The body becomes `try { ... } catch (Throwable $t) { throw $silentThrow($t); }`, where `$silentThrow` is a `private static` rethrow helper injected once per declaring class rather than shipped as a runtime type - a stateless feature earns no second mandatory dependency. An explicit `this(...)` / `super(...)` constructor prologue is split off and re-prepended outside the `try`, since no `try` may enclose it, and the search runs over the whole prologue so Java 25 statements before the explicit invocation are handled. `SilentThrowsExceptionHandler` (IDE) stops the platform reporting the checked exception the wrap absorbs, and stops at the first lambda, method reference or class boundary - each its own exception-analysis context - so the editor never goes green on code javac rejects.
- **`@Cleanup` - closes a local at the end of its enclosing block via try-with-resources.** `@Retention(SOURCE)`, `@Target(LOCAL_VARIABLE)`, no attributes. Splits the block at the annotated declaration and wraps the remainder in `try (name) { ... }`, so javac's own lowering generates the close, the null skip and the `addSuppressed` bookkeeping - the result is not equivalent to try-with-resources, it is try-with-resources, which is why a close failure is suppressed rather than masking the primary exception, the one place this improves on Lombok. A declaration as its block's last statement gives a legal empty try body and is wrapped like any other, so the resource is never left silently unclosed. `value()` is not carried over, since every workspace site is bare. `CleanupSuppressor` (IDE) silences the resource-leak inspections on a variable the rewrite closes.

### Generic `@ClassBuilder` targets

- **Generic targets** - `@ClassBuilder` now works on a type that declares type parameters, across all three target shapes. `class Crate<V>`, `record Pair<A, B>(...)`, `abstract class Box<V>` with its SuperBuilder chain, and `interface Repo<T>` all generate a correctly parameterised builder. Previously any of them failed to compile with `non-static type variable V cannot be referenced from a static context`: the nested `Builder` is `static` and the interface path's builder is a separate top-level class, so neither can see the target's type variables. Both now re-declare them, and every static member that mentions one - `builder()`, `from(T)`, and the `$default$` initializer providers - carries its own copy so the caller infers them back. A SuperBuilder root becomes `Builder<V, T extends Target<V>, B extends Builder<V, T, B>>`, and a concrete link's `extends` clause reproduces the arguments the target passes up (`class StringBox extends Box<String>` yields `extends Box.Builder<String, StringBox, StringBox.Builder>`). A target that itself names a parameter `T` or `B` has the self-type parameters renamed out of its way. Because `builder()` is a generic static method with nothing in a chained call to infer from, a typed chain needs the witness - `Crate.<String>builder().item("x").build()` - exactly as Lombok's generic `@Builder` does; the bare form infers `Object` and yields a chain that only assigns to a parameterised local under an unchecked warning. The IDE plugin keeps pace: `ClassBuilderAugmentProvider` re-declares the parameters on the synthesised `Builder` (bounds included) and gives the static bootstraps their own, so a generic chain resolves in the editor before the first javac round rather than reading back as `Object`.
- **The IDE now models SuperBuilder chains.** A concrete link's synthesised `Builder` had no supertype, so the platform's inherited-member lookup had nothing to walk and every setter declared further up the chain was flagged unresolved in the editor - `K.builder().t("x")` showed red on `t` while compiling perfectly. `ClassBuilderAugmentProvider` now gives an abstract root's Builder the self-typed `<T extends Root, B extends Builder<T, B>>` parameters with abstract `self()` / `build()`, and points a link's Builder at its parent's via `extends Parent.Builder<superArgs..., Target, Builder>`. The self-typing is what makes either call order work: without it an inherited setter returns the parent's Builder and the following child setter is unresolved. Generic chains are covered too, including a target that names a parameter `T` or `B`.

### All-args constructor synthesis

- **All-args constructor synthesis** - a plain class carrying `@ClassBuilder` that declares no constructor of its own now gets one injected, matching the positional `new Target(f1, f2, ...)` the generated `build()` emits. Previously every such class had to hand-write the constructor or fail to compile. Detection follows Lombok `@Builder`'s rule (synthesise only in the absence of an author-written constructor) and stands down for records, SuperBuilder targets, a set `factoryMethod`, and fieldless targets. `ClassBuilderAugmentProvider` surfaces the constructor to the PSI layer so a same-package `new Target(...)` resolves before the first javac round.
- **`constructorAccess` attribute** on `@ClassBuilder` - sets the synthesised constructor's visibility, defaulting to `AccessLevel.PACKAGE` to match the implicit constructor Lombok `@Builder` supplies. Kept separate from `access` (which governs the builder class and bootstrap methods and stays `PUBLIC`) so the constructor cannot silently become a way to bypass `build()` and its `@BuildFlag` validation.

### Field defaults and `retainInit`

- **`retainInit` attribute** on `@ClassBuilder`, defaulting to `true` - the builder now seeds every field from its declared initializer without any per-field annotation. A field written `String name = "anonymous"` keeps `"anonymous"` as its builder default. Fields with no initializer are unaffected.
- **Field-level `@BuilderDefault` overrides the class-level `retainInit` policy.** Presence of the annotation is the signal: bare `@BuilderDefault` retains, `@BuilderDefault(false)` opts out, and a field with neither inherits the class setting. A missing initializer is an error only when a field asked for retention by name - inheriting the class-wide policy on an uninitialised field is silent, since there is nothing to retain.
- **Field initializers that read instance state can be retained as builder defaults.** `String kind = getClass().getSimpleName()`, `String derived = base + "!"`, and `@Lazy String v = compute()` all work. Such a default cannot be evaluated when the builder is created, because no target exists then, so it is computed in the generated constructor instead - where `this` is available, exactly as in the ordinary field initializer it came from. Static-safe defaults keep the existing static provider and their existing timing; only the instance-referencing ones move. The observable difference is that a static-safe default is evaluated once per builder while an instance-referencing one is evaluated per `build()`. The builder slot is retyped to `Supplier<T>` on that path so an unset slot stays distinguishable from an explicitly-set null; `@Collector` containers take a merge instead. Every field shape is supported. Works across a SuperBuilder chain too: an abstract root or a concrete link computes its own instance-referencing defaults in the copy constructor, so a default on the root sees the concrete subclass being built (`getClass()` names the child), and a link's own default runs after `super(b)` has drained the parent's slots.
- **Instance-referencing defaults now work on `@Collector` containers too.** A collected field cannot use the `Supplier` slot the other shapes take - its `add` / `put` / `clear` setters need a real container to mutate while the builder runs - so it takes a merge instead: the builder slot carries only what the caller contributed, alongside a marker recording whether they replaced the collection wholesale, and the generated constructor folds the two against the instance-computed default via a synthesised `$merge$<field>` helper. An untouched builder contributes an empty collection, so the untouched and appended-to cases are the same fold and the observable semantics match a static-safe default exactly: the default seeds the collection, `addItem` appends onto it, and `items(...)` / `clearItems()` discard it. Works on SuperBuilder chains through the copy constructor. Custom containers are included unconditionally - including ones with no usable constructor, and interfaces, which have none at all. Nothing needs to build the declared type: the builder collects into a plain `java.util` scratch and the constructor takes the real container from the field's own initializer, so the built object always holds exactly what the initializer returned rather than something reconstructed from the declared type.
- **Instance-referencing defaults now work on `boolean`, `Optional`, array and `@Formattable` fields.** The constructor-computed path was gated to plain and `@Lazy` fields on the theory that the other shapes read or mutate the builder slot as its declared type. Only `@Collector` containers actually do; the rest simply assign, so they can carry the `Supplier`-typed slot like any other field - `boolean enabled = decide();` and `String[] tags = defaults();` now compile instead of being reported. Primitive slots box to `Supplier<Boolean>` and the like. `@Collector` remains genuinely unsupported, with a diagnostic that now explains why: its `add`/`put`/`clear` setters mutate the container while the builder runs, so it must exist before `build()` creates the instance the initializer would read.
- **A retained field initializer containing a lambda with parameters no longer crashes javac.** `Supplier<String> s = () -> "x"` worked, but `Function<String,String> f = s -> s` killed the compiler with a bare `AssertionError` from `Bits.incl` and *no diagnostic at all*. The initializer is deep-cloned into a static `$default$<field>()` provider, and the copier reset every node's position to `NOPOS`. A lambda parameter's `VarSymbol` inherits that position, and `Flow$AssignAnalyzer.trackable` requires `sym.pos >= startPos` before allocating a definite-assignment address - so the parameter never got one and the subsequent `Bits.incl` assertion failed. Cloned nodes now carry the generated provider's own position. Lambdas with parameters, explicitly-typed parameters, block bodies, nested lambdas, method references, constructor references, anonymous classes, and switch expressions are all verified to survive retention.

### `@Collector` and array defaults

- **A replace setter on a seeded custom container now discards the default.** `@Collector(singular = true) Pile items = new Pile(List.of("a"))` on a custom container left `items(...)` producing `[a, x]` instead of `[x]`. A custom container's initializer is the only expression that can produce an instance of its declared type, so it served as the field's factory as well as its default - and the two roles disagree once it carries contents. The reset setters now go through a synthesised `$empty$<field>()` that empties a fresh instance, so custom and `java.util` containers behave identically. An empty initializer (`new Pile()`) was unaffected, which is why this went unnoticed.
- **An immutable `@Collector` default no longer throws on the first `add`.** `@Collector(singular = true) List<String> items = List.of("a")` compiled clean and then threw `UnsupportedOperationException` from `addItem`, because the builder slot was seeded with the initializer's own instance and the singular setters mutate that slot in place. The retained default is now copied into a fresh mutable container per builder, so the default seeds the collection and the add appends, exactly as a `new ArrayList<>(...)` default always did. Copying also stops a default that returns shared state from being mutated through the builder. Custom containers are unaffected - their provider is the field's own factory.
- **An unset array field now builds as empty rather than null.** Every other container shape already settled at a non-null empty value - `Optional.empty()`, a fresh `ArrayList`/`LinkedHashSet`/`LinkedHashMap` - but an array with no declared initializer and no setter call arrived as `null`, so iterating `build().getTags()` NPE'd depending on whether a setter happened to be called. Arrays now default to `new T[0]` on both the AST-mutation and interface-emission paths, including primitive and multi-dimensional components. A declared initializer or an explicit setter still wins, and plain references keep their `null` - there is no meaningful empty `String`.

### Field-companion annotations: the `@BuildRule` split

- **BREAKING: `@BuildRule` is removed and split into four standalone field annotations.** `@BuildRule(retainInit = true)` → delete it (now the default); `@BuildRule(retainInit = false)` → `@BuilderDefault(false)`; `@BuildRule(ignore = true)` → `@BuilderIgnore`; `@BuildRule(flag = @BuildFlag(...))` → `@BuildFlag(...)`; `@BuildRule(obtainVia = @ObtainVia(...))` → `@ObtainVia(...)`. `@BuildFlag` and `@ObtainVia` change from `@Target({})` (nested-only) to `@Target(FIELD)` and are now written directly on the field.

  The four concerns shared nothing but their attachment point, and bundling them forced a real cost: `@BuildRule` had to be `RUNTIME`-retained solely so `BuildFlagValidator` could reflect the nested `flag`, which dragged `retainInit`, `ignore`, and `obtainVia` - all consumed at annotation-processing time - into every consumer's class files. After the split only `@BuildFlag` is `RUNTIME`; the other three are `CLASS`. The split also makes the field-level surface uniform, since `@Collector`, `@Negate`, `@Formattable`, `@Lazy`, and `@KeyField` were already standalone.
- **`@BuilderDefault` / `@BuilderIgnore`** - field-level annotations replacing `@BuildRule`'s `retainInit` and `ignore` attributes. These names existed before 1.4.0 and were folded into `@BuildRule` then; 2.5.0 restores them.

### Naming: `@SetterNames`, `@BuilderNames`, `NamingStyle`

- **`@SetterNames`, `@BuilderNames`, and `NamingStyle` - a complete naming surface for every generated member.** Each name is one subject expanded into one role's pattern, where the pattern carries a `{}` placeholder that expands to the field name, the `@Negate` stem, or the `@Collector` singular, capitalised unless it opens the pattern. That one rule reproduces every previous name and reaches shapes a prefix cannot: `{}` → `animated`, `is{}` → `isAnimated`, `put{}IfAbsent` → `putCountIfAbsent`. The split between the two annotations is **cardinality, not field-versus-class**: `@SetterNames` names the six roles generated once per field (`set`, `flag`, `add`, `put`, `compute`, `clear`), so those patterns must carry `{}` or every field would collide, while `@BuilderNames` names the five members generated exactly once (`type`, `builder`, `build`, `from`, `toBuilder`), so those default to plain literals. `NamingStyle` sets all eleven at once - `SIMPLIFIED` (the default), `LOMBOK`, and `BEAN` (`setName` / `isEnabled` / `addTag`) - and an explicitly written name always beats the style. `style = NamingStyle.LOMBOK` is a complete drop-in for `@Builder`'s naming rather than the same two overrides repeated on every type. `NONE` (`"-"`, not a legal identifier) suppresses a member; `set`, `type`, and `build` refuse it, a builder with no way to assign a field, no class to name, or no way to finish not being a builder. Malformed patterns and suppressed-but-required roles are rejected at the annotation by both the processor and the IDE inspection.
- **BREAKING: `methodPrefix`, the five `*Name` attributes, and the three `generate*` flags are removed, replaced by the naming annotations above.** `builderName = "X"` → `builder = @BuilderNames(type = "X")`; `builderMethodName` / `buildMethodName` / `fromMethodName` / `toBuilderMethodName` → the matching `@BuilderNames` attribute; `methodPrefix = "with"` → `setters = @SetterNames(set = "with{}")`; `generateBuilder` / `generateFrom` / `generateMutate` → name the member `NONE`. `@ClassBuilder` drops from 19 attributes to 12.

  `methodPrefix` governed three of the seven names it should have, was ignored by three (`is` / `put` / `clear`), and was redefined by one (`add`), so a boolean field could only ever produce `isAnimated()` / `isAnimated(boolean)` and `putXIfAbsent` sat outside the model entirely, being a prefix and a suffix at once. The `generate*` flags retire because each duplicated its own name attribute - four emission sites read `generateX() && !nameX().isEmpty()` - so emptiness is now the single opt-out signal. `generateImpl` and `generateCopyConstructor` stay: they switch synthesis strategy rather than suppressing a named member, which is the line worth holding.
- **BREAKING: a boolean field's typed setter takes the bare field name.** The typed setter is now the ordinary `set` role, so `boolean animated` yields `animated(boolean)` rather than `isAnimated(boolean)`, matching Lombok. The zero-arg `isAnimated()` survives unchanged as the separate `flag` role, so the only lost name is the `is`-prefixed typed overload, which `setters = @SetterNames(set = "is{}")` restores. Before this both names were hardcoded `is`-prefixed and unreachable by any attribute.

### `@BuildFlag` on interface accessors

- **`@BuildFlag` can be declared on an interface target's accessors.** The annotation widens to `@Target({FIELD, METHOD})`, so an interface - which declares no fields to carry a constraint - can put one on the abstract accessor instead. The processor copies it onto the matching field of the generated `<Name>Impl`, which is the instance `build()` constructs and the one the validator reads, so an accessor constraint is enforced exactly as a field constraint is. Only the attributes actually written are copied, and string attributes are re-escaped, so a `pattern` regex survives the round trip rather than emitting a bare backslash. Previously an interface target could not declare validation constraints at all, and the only route was a hand-written implementation reached through `factoryMethod`. Widening a `@Target` is source-compatible, so no existing use breaks; the IDE inspection warns when a `@BuildFlag` sits on a method that is not an abstract zero-arg accessor, since nothing reads it there.

### `Optional` dual-setter disambiguation

- **Alt+Enter fix for the `Optional` dual setter's one ambiguous call.** An `Optional<T>` field generates both `x(T)` and `x(Optional<T>)` so the wrapping lives inside the builder rather than at every call site, and the price is that a *literal* `x(null)` is ambiguous per JLS 15.12.2.5 - both parameter types accept null and neither is more specific. `OptionalSetterNullIntention` rewrites it to `x(Optional.empty())`. Only that replacement is offered: the raw overload wraps with `ofNullable`, so a disambiguating `x((T) null)` stores the identical empty value while saying less about intent. The fix keys on the candidate shape - one `Optional<T>` parameter, one `T` parameter, same name - rather than on generated-code provenance, so it applies equally to a builder read from a compiled dependency and to a hand-written pair carrying the same ambiguity for the same reason.

### Interface bootstrap methods

- **Interface targets now carry the bootstrap methods, like every other target kind.** `Shape.builder()`, `Shape.from(s)` and `s.mutate()` are injected onto the interface itself - `builder` / `from` as `static` methods, `mutate` as a `default` - instead of requiring `new ShapeBuilder<>()`. The builder stays a sibling class; only the entry points move onto the interface body, which static and default methods have made possible since Java 8. Generic interfaces carry their type parameter through (`Repo.<String>builder()`), and the `generate*` opt-outs plus the skip-on-collision rule behave as they do on a class.

### `@Lazy` fixes and interop

- **`@BuilderDefault` and `@BuilderIgnore` are now permitted on a `@Lazy` field.** Both govern how the builder treats a field rather than how it is stored, so neither actually conflicts with the storage rewrite. `@BuilderDefault` gives lazy fields the per-field opt-out that previously only existed class-wide as `retainInit = false`. `@BuilderIgnore` had been broken rather than incompatible: the `@Lazy` pass was handed only the builder-visible fields, so an ignored field was silently skipped and got neither its `Lazy<T>` storage nor its getter. The pass now receives the unfiltered field list, so such a field is rewritten normally, keeps its own initializer as the value source, and simply has no setter or constructor parameter. Static fields reach the pass too, so `@Lazy` on one is reported rather than dropped. The remaining five companions stay rejected.
- **A standalone `@Lazy` initializer can reference the enclosing instance again, and may contain lambdas with parameters.** `@Lazy` wraps the initializer in place as `Lazy.of(() -> <init>)`, which stays in the field initializer and so is legitimately an instance context - but the wrapping copier overwrote every node's position with `NOPOS`. That broke two javac checks that read positions: forward-reference detection started treating an *earlier* field as a forward reference (`@Lazy String v = base + "x"` failed with "illegal forward reference"), and a lambda with parameters crashed the compiler outright via `Bits.incl`. Positions are now preserved, which is correct for a rewrite that does not move the expression. Note that on a `@ClassBuilder` target the value arrives through the builder's `static` default provider instead, so instance references remain illegal there; `@ClassBuilder(retainInit = false)` opts out.
- **A `@Lazy` field with no supplier now fails at `build()` instead of NPE-ing at first access.** `Lazy.of` declared its initializer `@NotNull` but never enforced it, so a `@Lazy` field whose setter was never called stored a null supplier and threw a bare `NullPointerException` from inside the getter, far from the cause. `Lazy.of` now enforces the contract, and generated constructors call a field-attributed overload so the message names the field and its declaring class. A `@Lazy` field defers a computation that is expected to exist, so a missing supplier is a mistake rather than an empty-but-valid field.
- **`@Lazy` combined with a field-only companion is now a compile error.** `@Lazy` has always documented `@Collector`, `@Negate`, `@Formattable`, `@BuilderDefault`, `@BuilderIgnore`, `@BuildFlag`, and `@ObtainVia` as unsupported, but only the IDE inspection objected - javac accepted the combination and produced quietly broken behavior. `@BuildFlag(nonNull = true)` degraded to a no-op, since the validator reflects the field and sees the non-null `Lazy` wrapper rather than the value, leaving no way to require a lazy field. `@BuilderIgnore` dropped the field before the lazy pass ran, so `@Lazy` did nothing at all. The processor now rejects all seven pairings.

## [2.1.0]

### Added

- **`@EnumLookup` + `@KeyField` annotation pair** - new enum-only family for caching `Enum.values()` and emitting a uniform set of static lookup helpers. `@EnumLookup` on an enum injects (via javac AST mutation) a `private static final E[] CACHED_VALUES = values()`, a populate-only static block (prepended to any existing one), and `public static` helpers: `size()`, two `forEach` overloads (`Consumer<? super E>` and `BiConsumer<Integer, ? super E>` where the second argument is the ordinal), `stream()`, `parallelStream()`, `ofName(String)` / `ofOrdinal(int)` returning nullable, and `findByName(String)` / `findByOrdinal(int)` returning `Optional<E>`. Every generated method carries `@XContract` so IDE data-flow understands the null / non-null return shapes.
- **`@KeyField` companion** (`@Target(FIELD)`) - marks an enum's instance field as a lookup key. Generates a parallel `CACHED_KEYS_<fieldName>` array (primitive-typed when the field is primitive, so `int code` produces `int[]` with `==` comparison and zero boxing) plus `of<Suffix>(T)` / `findBy<Suffix>(T)` overloads. `methodName` overrides the derived suffix; `strictKeys` and `strictNullKeys` opt into IDE-time validation only (the generated runtime is branch-free regardless of the flag values).
- **`EnumLookupAugmentProvider`** (IDE) - surfaces the synthesised fields and methods to the PSI layer so autocompletion, goto-symbol, and type resolution all work before the first javac round.
- **`EnumLookupInspection`** (IDE) - flags `@EnumLookup` on non-enum types, `@KeyField` on static fields, `@KeyField` without an enclosing `@EnumLookup`, `@KeyField(strictNullKeys = true)` on a primitive (no-op attribute), invalid `methodName` values, and method-signature collisions across multiple `@KeyField`s on the same enum.
- **`EnumLookupKeyInspection`** (IDE) - traces each enum constant's constructor arguments back to the annotated field via `this.<field> = <param>` assignments and highlights duplicate values (when `strictKeys = true`) or null assignments (when `strictNullKeys = true`). Resolves literals and references to `static final` constants; stays silent on non-resolvable expressions. Duplicate-key and null-key severities are independently configurable in the inspection settings panel (both default `ERROR`, matching `ResourcePathInspection`'s dropdown pattern).
- **`@Lazy` field annotation** - new field-level annotation that defers a field's value computation until first access and caches it thereafter. The annotation processor rewrites the storage from `T` to `Lazy<T>`, wraps the field initializer (when present) as `Lazy.of(() -> <init>)`, marks the field `final`, and synthesises a public memoizing getter (`getFoo()` for object types, `isFoo()` for `Boolean`). Field-level annotations (`@NotNull`, `@Nullable`, `@PrintFormat`, `@Deprecated`, etc.) propagate onto the synthesised getter and its return type using each annotation's declared `@Target`. The `access` attribute selects the getter's access level.
- **`@Lazy` + `@ClassBuilder` interop** - when the enclosing class carries `@ClassBuilder`, the generated builder receives a dual setter for each lazy field: a value form that wraps as `() -> value` and a `Supplier<T>` form stored verbatim. The target's matching constructor parameter is rewritten from `T` to `Supplier<T>` and the assignment becomes `this.foo = Lazy.of(supplier)`, so values flow from the builder to the target as deferred computations rather than eager values.
- **`Lazy<T>` runtime** - bundled at `dev.simplified.lazy.Lazy`. Thread-safe DCL memoiser with a sentinel for cached `null`. No external dependencies.
- **`LazyAugmentProvider`** (IDE) - surfaces the synthesised getter to the PSI layer so autocompletion, goto-symbol, and nullability inspections see `getFoo()` immediately on edit, before the first javac round.
- **`LazyFieldInspection`** (IDE) - reports `@Lazy` on static fields, record components, primitive (non-`Boolean`) types, fields without an initializer when the enclosing class has no `@ClassBuilder`, and the redundant `@Lazy` + Lombok `@Getter` combination.

### Changed

- **Package reorganisation - shared infrastructure lifted out of `classbuilder.*`.** Generic javac AST plumbing (`AstMarkers`, `JavacBridge`, `JavacTypeFactory`, the entire `compat/` subpackage with its `JavacCompat` / `JavacAccess` + v17 baselines, and the `@XContract` AST emitter `ContractAnnotations`) moved to `dev.simplified.shared.javac.*`. Generic JSR-269 utilities (`AnnotationLookup`, `SourceIntrospector`) moved to `dev.simplified.shared.apt.*`. Generic IntelliJ PSI infrastructure (`GeneratedMemberMarker` unifying the per-feature markers, `AbstractRecursionSafeAugmentProvider` lifting the duplicated `IN_PROGRESS` thread-local pattern from three augment providers, `AnnotatedLightModifierList` lifting the duplicated `LightModifierList` subclass, `DocProxyingLightMethodBuilder` lifting `GeneratedSetterMethod`'s doc-comment proxy) moved to `dev.simplified.shared.psi.*`. After the reshuffle, `dev.simplified.classbuilder.*` contains only `@ClassBuilder`-specific code and each annotation family depends on shared infrastructure rather than reaching into another family's package.
- **`@Lazy` moved to `dev.simplified.lazy.*`** matching `@EnumLookup`'s standalone layout. The `Lazy<T>` runtime is now `dev.simplified.lazy.Lazy`; `LazyFieldMutator`, `LazyAugmentProvider`, and `LazyFieldInspection` follow. Source-breaking only for consumers that imported the `Lazy<T>` runtime directly - the user-facing `@dev.simplified.annotations.Lazy` annotation FQN is unchanged.

### Fixed

- **`LazyAugmentProvider` stack-overflow recursion** - `collectExistingZeroArgMethodNames` called `target.getMethods()`, which is augment-aware and re-entered every `PsiAugmentProvider` (including `LazyAugmentProvider` itself), eventually overflowing the stack on any class with a `@Lazy` field. Switched to `PsiExtensibleClass.getOwnMethods()` (un-augmented direct member access - exactly what collision detection needs) and added a `ThreadLocal<Set<PsiClass>>` re-entry guard as belt-and-suspenders.
- **JSvg icon-loading `IllegalAccessError` in tests** - IntelliJ Platform 2025.3's `JSvgDocumentFactoryKt` calls a `ParsedElement` constructor whose signature changed in the bundled JSvg version; loading any SVG icon during a test logged a `Logger.error` which the test framework converted into a failure. Added a scoped `LoggedErrorProcessor` (`dev.simplified.testutil.JSvgErrorSuppressor`) installed by `ClassBuilderLineMarkerProviderTest` and `ClassBuilderAugmentProviderTest` that filters errors whose category or stack trace references `JSvgDocumentFactory`; every other logged error stays fatal.

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
