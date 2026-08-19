---
name: accessor-visibility
description: How GetterVisibilityInspection decides an accessor is wider than its usage, and how PromoteToGetterFix matches hand-written accessors. Use when changing accessor inspections or their quick-fixes.
---

### Accessor visibility (`GetterVisibilityInspection`)

The one inspection here that is not error-mirroring - it reports code javac accepts, so both triggers are judgement rather than a restated diagnostic.

- **Trigger A - the accessor is wider than its usage.** A field generating a `PUBLIC` read accessor whose references fall entirely inside the declaring class, its subclasses, or its package, offering `SetAccessLevelFix` for the level that fits. No reads outside the declaring class offers **two** fixes, `NONE` and `PRIVATE` - "delete the accessor" and "keep it private" are different intents and picking one for the author would be wrong. This is the workspace's 119 hand-written `AccessLevel.NONE` sites read backwards, and the reason `AccessLevel` gained the constant.
  - **It must stay `WEAK_WARNING`, and the description has to say why.** `:library` publishes to Maven Central, so narrowing a public accessor is a downstream break. It is a review aid, not a cleanup sweep, and a `WARNING` would invite a batch-apply that silently breaks consumers.
  - `SetAccessLevelFix` writes to the **field**, never to the type-level annotation. The field-level override *is* the mechanism, and that is what keeps the fix local; it carries the effective annotation's other attributes over so narrowing a level never doubles as a rename.
  - Trigger A searches by **name** through `PsiSearchHelper` rather than resolving the synthesised accessor and handing it to `ReferencesSearch`. The accessor is an augment-provided light method, whose search scope and identity are the classic trap; sidestepping it also keeps the hot path cheap, which matters because this runs on every class.
- **Trigger B - these hand-written accessors are a `@Getter`.** Two or more zero-arg methods whose entire body returns a field of matching type, under the name the resolved `AccessorScheme` would mint, promoted to a type-level `@Getter` by `PromoteToGetterFix` (with `style = FLUENT` when the matched accessors were fluent).
  - **The body test is exact, and that is the whole safety argument.** Exactly one `PsiReturnStatement` over a `PsiReferenceExpression` - bare or `this`-qualified - resolving to a non-static field of the enclosing class, with the return type *equal* to the field type. A null check, a defensive copy, a cast, a ternary, a widening or boxing return, a `synchronized` method, a `throws` clause, an annotation, or a supertype declaration all disqualify it. The `from(T)` seeding ladder deliberately ranks an author-declared accessor **above** a direct field read precisely when the body does real work, so a false match here would have `PromoteToGetterFix` delete a method that was doing that work and change behaviour silently. Every near-miss shape is pinned by its own test.
  - The fix **aborts and says so** rather than silently no-opping when a method it would delete is reached through a method reference or declared by a supertype, and emits `@Getter(exclude = ...)` for every own field no deleted method exposed - promoting must not publish a field that had no accessor.

