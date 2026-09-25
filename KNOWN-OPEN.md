# Known open

Open items in the annotation processor and its IntelliJ plugin. Each stays here until it is closed
or accepted.

> #### The editor reads a lifted `final` field as the constant it no longer holds
> `@ClassBuilder` lifts a `final` field's initializer into the builder's default where the author's
> constructor assigns the field, and javac then compiles a blank final: every read is a `getfield`,
> and a value set on the builder reaches the instance. The editor still reads
> `private final long queryResultsTTL = 30;` as a constant, so the platform's constant-condition
> inspection (`ConstantValue`) reports a read of it such as `this.queryResultsTTL <= 0` as always
> `false`, and its quick fix to simplify the condition deletes the branch a builder value of `0`
> takes. `GeneratedFieldAccess.liftedBlankFinal` already knows the field is lifted, but only
> `GeneratedMemberHighlightFilter` asks it, to drop javac's `Cannot assign a value to final
> variable`; `GeneratedMemberSuppressor` names neither `ConstantValue` nor any other dataflow tool,
> so nothing tells the editor's analysis of a read that the initializer is no longer the value.
>
> Seen on persistence's `RelationalSource`, whose `queryResultsTTL` (default `30`) and
> `cacheExpiryMs` (default `60_000`) are both lifted. Its `builderSettingsReachHibernate` test shows
> 7 s and 5 000 ms set on the builder reaching Hibernate, and the compiled class carries no constant
> value for either field.
>
> - Affected: `plugin/src/main/java/dev/simplified/shared/inspect/GeneratedMemberSuppressor.java:55-90` -
>   `ASSIGNMENT_TOOL_IDS`, `USAGE_TOOL_IDS`;
>   `plugin/src/main/java/dev/simplified/shared/inspect/GeneratedFieldAccess.java:165` -
>   `liftedBlankFinal`; seen at
>   `Simplified-Dev/persistence/src/main/java/dev/simplified/persistence/source/RelationalSource.java:264`,
>   `:548`
> - Type: **GAP**
> - Status: **OPEN**

> #### The editor types a builder's slot field with its target field's `@NotNull`
> The editor contributes the slot fields the processor declares on a builder, so an author's copy
> constructor can read `builder.layers`. `GeneratedMemberFactory.synthesizeBuilderFields` gives each
> the type `MergedSlotStorage.declaredType` answers - the target field's declared type, which carries
> that field's type-use `@NotNull`. A slot is not that field: it is `null` until its setter runs, and
> a builder built with a slot never set hands the constructor `null`. The constant-condition
> inspection therefore reports the null check an author writes in a copy constructor -
> `if (builder.layers == null) throw ...` - as always `false`, and its quick fix removes the only
> refusal of an unset slot. `@BuildFlag` cannot stand in for that check, since the check it emits runs
> on the built instance, after the constructor has run.
>
> Seen on persistence's `DocumentSource`, on its `refactor/document-source` branch, whose four
> copy-constructor checks - layers, text and parser on the root, the edit instruction on `ReadWrite` -
> are each asserted by `DocumentLayerMergeTest.anIncompleteSourceIsRefused`.
>
> - Affected: `plugin/src/main/java/dev/simplified/classbuilder/editor/GeneratedMemberFactory.java:1092-1095` -
>   `synthesizeBuilderFields`;
>   `plugin/src/main/java/dev/simplified/classbuilder/editor/MergedSlotStorage.java:841` -
>   `declaredType`; seen at
>   `Simplified-Dev/persistence/src/main/java/dev/simplified/persistence/source/DocumentSource.java:83`,
>   `:86`, `:89`, `:261`
> - Type: **GAP**
> - Status: **OPEN**
