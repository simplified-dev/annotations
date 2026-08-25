---
name: classbuilder-pipeline
description: End-to-end @ClassBuilder processing pipeline - the javac round, the AST mutation path for classes and records, the sibling-emission path for interfaces, runtime validation, editor synthesis, and JDK compatibility. Use when changing builder synthesis or its consumer requirements.
---

### @ClassBuilder data flow

Consumer's `javac` â†’ `META-INF/services` registers `ClassBuilderProcessor` â†’ `process()`:
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
Validation: generated `build()` calls a `$validate$($result)` it emits into the builder, when `validate=true` and `BuildFlags.of(target)` finds an enforceable constraint. **No runtime classpath entry at all** - the checks, and the `$flagEmpty$` / `$flagSize$` / `$flagText$` / `$flagNumber$` helpers they call, are generated members. The flag walk climbs the superclass chain, so it reaches `@BuilderIgnore`d, excluded and inherited fields the builder never models; a private field on a *parent* is skipped, being unreachable from a builder nested in the child. **A `factoryMethod` is the one narrowing**: constraints are resolved against the declared type, so a subtype the factory returns can carry flags nothing at processing time can enumerate, and `BuilderMutator.warnFactoryValidation` warns rather than letting that pass in silence. Rejections throw `IllegalStateException`.

Editor: `ClassBuilderAugmentProvider` surfaces the bootstrap methods AND the nested `Builder` class to the PSI layer so autocompletion, goto-symbol, and type resolution all work before the first javac round. The synthesised Builder mirrors `FieldMutators.setters` in full - boolean zero-arg/typed pair plus `@Negate` inverse, `Optional` nullable-raw/wrapped plus `@Formattable` overload, `@Collector` varargs/iterable bulk overloads with opt-in single-element add/put/clear and (map) put-if-absent, array varargs, String `@Formattable` overload. Parameter-level annotations (`@PrintFormat`, `@Nullable`, `@NotNull`) propagate live from field annotations via `buildParam` + type-use annotations. `ClassBuilderLineMarkerProvider` shows a gutter icon (`/icons/generated.svg`, with `generated_dark.svg` as its dark-theme companion) on every `@ClassBuilder` annotation.

JDK compatibility: `mutate/compat/` carries the `JavacCompat` interface plus the `JavacCompatV17` baseline. Every currently supported JDK (17 through 25) uses the baseline because every javac internal the pipeline touches has been stable across those versions. `JavacCompatFactory.forRuntime()` stays wired up as the single entry point so a future divergence is a new subclass + one gate - no caller change required.

Consumer requirements: javac-only (no ecj), and nothing else - **no `--add-exports` flags**. `dev.simplified.shared.javac.compat.JavacAccess` opens `jdk.compiler/com.sun.tools.javac.*` from the processor's static initializer, through `sun.misc.Unsafe` to reach `MethodHandles.Lookup.IMPL_LOOKUP` and from there `Module.implAddOpens` - the same bootstrap Lombok uses - so a consumer build needs only the artifact on its annotation processor path.

**The `--add-exports` lists in `library/build.gradle.kts` are not a consumer requirement, and reading them as one is the mistake to avoid.** They serve this build and no other: `javacCompileExports` is what compiles the mutator's own source, which imports `com.sun.tools.javac.*` directly and so must see those packages at compile time, and `javacRuntimeExports` adds three more that `com.google.testing.compile` reaches into when it drives javac inside the `aptTest` JVM. The `showcase` source set is the in-repo proof of the distinction: it is a consumer-shaped build exercising the processor over ordinary source, and it sets no fork args at all.

