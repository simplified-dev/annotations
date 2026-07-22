package dev.simplified.classbuilder.mutate;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCEnhancedForLoop;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.classbuilder.apt.NamingScheme;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

/**
 * Emits one or more setter {@link JCMethodDecl}s per {@link FieldSpec},
 * mirroring the shape matrix from the sibling emitter:
 * <ul>
 *   <li>plain typed setter,</li>
 *   <li>boolean zero-arg + typed pair (with optional {@code @Negate}-driven
 *       inverse pair),</li>
 *   <li>{@code Optional<T>} nullable-raw + wrapped pair (with optional
 *       {@code @Formattable} {@code @PrintFormat} overload when the inner
 *       type is {@code String}),</li>
 *   <li>{@code String} {@code @Formattable} {@code @PrintFormat} overload,</li>
 *   <li>{@code @Collector} collection or map: bulk varargs / iterable
 *       replace plus opt-in single-element add/put, clear, and (maps only)
 *       {@code putIfAbsent(K, Supplier<V>)},</li>
 *   <li>array varargs.</li>
 * </ul>
 */
final class FieldMutators {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final ContractAnnotations contracts;

    FieldMutators(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
        this.contracts = ctx.contracts();
    }


    /** Returns every setter the field should emit on the nested Builder. */
    List<JCMethodDecl> setters(FieldSpec field) {
        ListBuffer<JCMethodDecl> out = new ListBuffer<>();
        if (field.lazy) {
            // @Lazy fields take a dual shape: foo(T value) wraps as a constant
            // Supplier; foo(Supplier<T>) stores the supplier verbatim. The
            // builder slot is Supplier<T>, the target field is Lazy<T>, and
            // the build() copy wraps the Supplier as Lazy.of(supplier) at
            // construction time (via the constructor-param rewrite in
            // LazyFieldMutator).
            out.append(lazyValueSetter(field));
            out.append(lazySupplierSetter(field));
            return out.toList();
        }
        if (field.isBoolean) {
            // The typed setter is the ordinary `set` role, so a boolean is named
            // like every other field; the zero-arg form is the separate `flag`
            // role and drops out entirely when a style suppresses it.
            if (naming().emitsFlag()) out.append(booleanZeroArg(field, field.name, false));
            out.append(booleanTyped(field, field.name, false));
            if (field.negateName != null && !field.negateName.isEmpty()) {
                if (naming().emitsFlag()) out.append(booleanZeroArg(field, field.negateName, true));
                out.append(booleanTyped(field, field.negateName, true));
            }
        } else if (field.isOptional) {
            out.append(optionalNullableRaw(field));
            out.append(optionalWrapped(field));
            if (field.formattable && "java.lang.String".equals(field.optionalInner)) {
                out.append(optionalFormattable(field));
            }
        } else if (field.isArray) {
            out.append(arrayVarargs(field));
        } else if ((field.isListLike || field.isMap) && field.collector) {
            if (field.isCustomContainer && !hasInit(field)) {
                // Custom container with @Collector but no captured initializer
                // to build fresh instances from - degrade to a plain replace
                // setter (the processor emits a NOTE explaining how to enable it).
                out.append(plainSetter(field));
            } else {
                // @Collector: bulk overloads always; add/put/clear/compute opt-in.
                if (field.isMap) {
                    out.append(singularMapReplace(field));
                    if (field.singular && naming().emitsPut()) out.append(singularMapPut(field));
                    if (field.compute && naming().emitsCompute()) out.append(singularMapPutIfAbsent(field));
                } else {
                    out.append(singularCollectionVarargsReplace(field));
                    out.append(singularCollectionIterableReplace(field));
                    if (field.singular && naming().emitsAdd()) out.append(singularCollectionAdd(field));
                }
                if (field.clearable && naming().emitsClear()) out.append(singularClear(field));
            }
        } else if (field.isString && field.formattable) {
            out.append(plainSetter(field));
            out.append(stringFormattable(field));
        } else {
            out.append(plainSetter(field));
        }
        return out.toList();
    }

    /**
     * Private-access field declaration on the nested Builder itself, matching
     * the target's type. Collection/Map/Optional types receive the same
     * defensive initialisers the sibling emitter uses so unset slots are
     * never null. {@code @Lazy} fields are stored as
     * {@code Supplier<T>} so callers can opt into eager-from-value or
     * lazy-from-supplier semantics through the dual setter pair.
     */
    JCVariableDecl fieldDecl(FieldSpec field) {
        // A field whose default reads instance state is stored as Supplier<T>
        // for the same reason a @Lazy field is: the constructor must tell "never
        // set" from "set to null" without a parallel flag, and null is
        // unambiguous on a Supplier-typed slot because every setter wraps its
        // argument. The constructor reads null as "apply my default instead".
        // A collected instance default keeps its declared container type - the
        // add/put/clear setters need something real to mutate - and carries the
        // caller's contributions only, which the constructor folds onto the
        // instance-computed default. A separate marker records a wholesale
        // replace, the one case where the default must be discarded.
        if (ctx.isCollectedInstanceDefault(field)) {
            return make.VarDef(
                make.Modifiers(Flags.PRIVATE),
                names.fromString(field.name),
                ctx.collectedSlotType(field),
                freshContainer(field)
            );
        }
        boolean supplierTyped = field.lazy || ctx.isInstanceDefault(field.name);
        JCExpression fieldType = supplierTyped
            ? make.TypeApply(types.qualIdent("java.util.function.Supplier"),
                List.of(types.parseBoxedType(field.typeDisplay)))
            : types.parseType(field.typeDisplay);
        JCExpression init;
        if (ctx.isInstanceDefault(field.name)) {
            // No slot default: $default$<name>() is an instance method here, so
            // it cannot be called before a target exists.
            init = null;
        } else if (field.lazy) {
            init = lazyDefaultInitializer(field);
        } else {
            init = defaultInitializer(field);
        }
        return make.VarDef(
            make.Modifiers(Flags.PRIVATE),
            names.fromString(field.name),
            fieldType,
            init
        );
    }

    /**
     * Builder-slot default for a {@code @Lazy} field. The slot is typed
     * {@code Supplier<T>} while the provider returns {@code T}, so the call is
     * wrapped in a lambda rather than used directly. That also keeps both
     * properties the two features promise separately: evaluation stays deferred
     * to the first {@code get()}, and each builder holds its own lambda so the
     * default is still computed fresh per {@code build()}.
     *
     * <p>Only a captured initializer produces a default; a {@code @Lazy} field
     * without one leaves the slot null, and the setter must fill it.
     */
    private JCExpression lazyDefaultInitializer(FieldSpec field) {
        if (!hasInit(field)) return null;
        return make.Lambda(List.nil(), providerCall(field));
    }

    private JCExpression defaultInitializer(FieldSpec field) {
        // A captured field initializer (from a retained initializer or an
        // auto-captured @Collector on a custom container) becomes a call to the
        // synthesised Target.$default$<fieldName>() static method. That method
        // (injected by RetainedInitFactory) contains the original declared
        // initializer expression inside a normal method body, so javac's flow
        // analyser handles it correctly. Embedding the expression directly in
        // this field init was attempted and causes position-bookkeeping crashes
        // in Flow$AssignAnalyzer.
        if (hasInit(field)) return mutableIfCollected(field, providerCall(field));
        // A custom container has no new ArrayList<>()-style default that is
        // assignable to its own type; without a captured initializer the
        // builder slot stays null and a plain replace setter fills it.
        if (field.isCustomContainer) return null;
        // An array is a container like the rest, so an unset slot is empty
        // rather than null - iterating the result of build() should not depend
        // on whether a setter happened to be called. The setter is varargs, so
        // this is also what calling it with no arguments already produces.
        if (field.isArray) return emptyArray(field);
        if (field.isOptional) {
            return make.Apply(
                List.nil(),
                make.Select(types.qualIdent("java.util.Optional"), names.fromString("empty")),
                List.nil()
            );
        }
        if (field.isMap) return make.NewClass(null, List.nil(),
            make.TypeApply(types.qualIdent("java.util.LinkedHashMap"), List.nil()),
            List.nil(), null);
        if (field.isSet) return make.NewClass(null, List.nil(),
            make.TypeApply(types.qualIdent("java.util.LinkedHashSet"), List.nil()),
            List.nil(), null);
        if (field.isListLike) return make.NewClass(null, List.nil(),
            make.TypeApply(types.qualIdent("java.util.ArrayList"), List.nil()),
            List.nil(), null);
        return null;
    }

    /**
     * The {@code boolean $replaced$<name>} marker accompanying a collected
     * instance default's slot, or {@code null} when the field needs none.
     * Emitted alongside the slot so the constructor can tell a wholesale
     * replace (discard the default) from an append (fold onto it).
     *
     * @param field the field being declared
     * @return the marker declaration, or {@code null}
     */
    JCVariableDecl replacedMarkerDecl(FieldSpec field) {
        if (!ctx.isCollectedInstanceDefault(field)) return null;
        return make.VarDef(
            make.Modifiers(Flags.PRIVATE),
            names.fromString(MutationContext.replacedMarker(field.name)),
            make.TypeIdent(com.sun.tools.javac.code.TypeTag.BOOLEAN),
            make.Literal(false)
        );
    }

    /** {@code this.$replaced$<name> = true;} - marks the default discarded. */
    private JCStatement markReplaced(FieldSpec field) {
        return make.Exec(make.Assign(
            make.Select(make.Ident(names._this),
                names.fromString(MutationContext.replacedMarker(field.name))),
            make.Literal(true)
        ));
    }

    /**
     * Prepends the replaced marker to a wholesale-replace setter's body when the
     * field takes the merge path; otherwise returns the body unchanged.
     */
    private List<JCStatement> withReplacedMark(FieldSpec field, List<JCStatement> body) {
        if (!ctx.isCollectedInstanceDefault(field)) return body;
        return body.prepend(markReplaced(field));
    }

    /**
     * Copies a {@code @Collector} field's retained default into a fresh mutable
     * container. The field's own {@code add} / {@code put} / {@code clear}
     * setters mutate the slot in place, so seeding it with the initializer's
     * own instance makes an immutable default - {@code List.of("a")}, the
     * idiomatic way to write a small one - throw
     * {@link UnsupportedOperationException} on the first call. Copying also
     * stops a default that returns shared state from being mutated through the
     * builder.
     *
     * <p>A custom container is left alone: {@code new ArrayList<>(...)} is not
     * assignable to its type, and its provider is the field's own factory, so
     * it already yields something the setters can work with.
     *
     * @param field the field being defaulted
     * @param provider the call to the field's {@code $default$} provider
     * @return the provider call, wrapped in a mutable copy where one is needed
     */
    private JCExpression mutableIfCollected(FieldSpec field, JCExpression provider) {
        if (!field.collector || field.isCustomContainer) return provider;
        String fqn = field.isMap ? "java.util.LinkedHashMap"
            : field.isSet ? "java.util.LinkedHashSet"
            : field.isListLike ? "java.util.ArrayList"
            : null;
        if (fqn == null) return provider;
        return make.NewClass(null, List.nil(),
            make.TypeApply(types.qualIdent(fqn), List.nil()), List.of(provider), null);
    }

    /**
     * {@code new T[0]} for an array field, using the declared component type so
     * a multi-dimensional field yields a correctly-shaped empty outer array.
     */
    private JCExpression emptyArray(FieldSpec field) {
        JCExpression component = types.parseType(field.collectionElement);
        return make.NewArray(component, List.of(make.Literal(0)), null);
    }

    /**
     * Assigns the builder slot, wrapping the value as {@code () -> value} when
     * the field takes the constructor-computed path. The slot is
     * {@code Supplier<T>} there, and every setter has to wrap so that null keeps
     * meaning "never set" - an explicit null becomes {@code () -> null} and
     * survives as the caller's chosen value.
     *
     * @param field the field being set
     * @param value the value expression, in the slot's declared type
     * @return the assignment statement
     */
    private JCStatement slotAssign(FieldSpec field, JCExpression value) {
        JCExpression rhs = ctx.isInstanceDefault(field.name)
            ? make.Lambda(List.nil(), value)
            : value;
        return make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            rhs
        ));
    }

    /** Slot assignment from a like-named parameter, then {@code return this;}. */
    private List<JCStatement> assignAndReturnThis(FieldSpec field) {
        return List.of(slotAssign(field, make.Ident(names.fromString(field.name))), returnThis());
    }

    /** Whether the field's declared initializer was captured for reuse as a builder default. */
    private static boolean hasInit(FieldSpec field) {
        return field.sourceInitializer != null && !field.sourceInitializer.isEmpty();
    }

    /** Call to the target's synthesised {@code $default$<field>()} initializer provider. */
    private JCExpression providerCall(FieldSpec field) {
        return make.Apply(
            List.nil(),
            make.Select(
                make.Ident(names.fromString(ctx.targetSimpleName())),
                names.fromString(RetainedInitFactory.providerName(field.name))
            ),
            List.nil()
        );
    }

    /**
     * A fresh, empty container for a {@code @Collector} reset setter. A custom
     * container comes from the field's own {@code $default$} provider, because
     * {@code new ArrayList<>()} (etc.) is not assignable to the field's own
     * type; java.util containers use the matching concrete implementation.
     */
    /** Call to the target's synthesised {@code $empty$<field>()} factory. */
    private JCExpression emptyFactoryCall(FieldSpec field) {
        return make.Apply(
            List.nil(),
            make.Select(
                make.Ident(names.fromString(ctx.targetSimpleName())),
                names.fromString(RetainedInitFactory.emptyName(field.name))
            ),
            List.nil()
        );
    }

    private JCExpression freshContainer(FieldSpec field) {
        // A collected instance default collects into a plain java.util scratch:
        // the real container comes from the initializer in the constructor, so
        // nothing here has to build the declared type at all.
        if (ctx.isCollectedInstanceDefault(field)) return ctx.freshCollectedSlot(field);
        // A custom container otherwise resets through the emptied copy of its
        // own initializer - the only expression able to produce the declared
        // type - and the initializer is the field's default as well as its
        // factory, so a replace setter must not keep its contents.
        if (field.isCustomContainer) return emptyFactoryCall(field);
        String fqn = field.isMap ? "java.util.LinkedHashMap"
            : field.isSet ? "java.util.LinkedHashSet"
            : "java.util.ArrayList";
        return make.NewClass(null, List.nil(),
            make.TypeApply(types.qualIdent(fqn), List.nil()), List.nil(), null);
    }

    // ------------------------------------------------------------------
    // Setter shapes
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // @Lazy shapes
    // ------------------------------------------------------------------

    /**
     * {@code Builder withFoo(T value)} - eager value form. Stores
     * {@code () -> value} in the {@code Supplier<T>} slot so the eventual
     * {@code Lazy.of(supplier)} on the target side returns the value
     * immediately on first {@code get()}. Slot is {@code Supplier<T>} so
     * the synthesized constructor receives a Supplier and wraps as
     * {@code Lazy.of(supplier)}.
     */
    private JCMethodDecl lazyValueSetter(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression valueType = types.parseType(field.typeDisplay);
        JCVariableDecl p = param(field.name, valueType);
        // this.<name> = () -> <name>;
        JCExpression lambda = make.Lambda(List.nil(), make.Ident(names.fromString(field.name)));
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            lambda
        ));
        return methodDefRaw(setterName, List.of(p), List.of(assign, returnThis()));
    }

    /**
     * {@code Builder withFoo(Supplier<T> supplier)} - true lazy form.
     * Stores the supplier verbatim; first call to the target's getter
     * evaluates the supplier and memoizes the result.
     */
    private JCMethodDecl lazySupplierSetter(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression supplierType = make.TypeApply(
            types.qualIdent("java.util.function.Supplier"),
            List.of(types.parseType(field.typeDisplay))
        );
        return methodDef(setterName, param(field.name, supplierType), assignAndReturnThis(field.name));
    }

    private JCMethodDecl plainSetter(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression fieldType = types.parseType(field.typeDisplay);
        JCVariableDecl p = nullnessParam(field.name, fieldType, field);
        return methodDef(setterName, p, assignAndReturnThis(field));
    }

    private JCMethodDecl arrayVarargs(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl p = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS),
            names.fromString(field.name),
            make.TypeArray(elemType),
            null
        );
        return methodDefRaw(setterName, List.of(p), assignAndReturnThis(field));
    }

    private JCMethodDecl booleanZeroArg(FieldSpec field, String methodBase, boolean inverse) {
        String setterName = naming().flagName(methodBase);
        JCStatement assign = slotAssign(field, make.Literal(!inverse));
        return methodDefRaw(setterName, List.nil(), List.of(assign, returnThis()));
    }

    private JCMethodDecl booleanTyped(FieldSpec field, String methodBase, boolean inverse) {
        String setterName = naming().setName(methodBase);
        JCExpression paramRef = make.Ident(names.fromString(methodBase));
        JCExpression value = inverse ? make.Unary(JCTree.Tag.NOT, paramRef) : paramRef;
        JCStatement assign = slotAssign(field, value);
        JCVariableDecl p = param(methodBase, make.TypeIdent(com.sun.tools.javac.code.TypeTag.BOOLEAN));
        return methodDefRaw(setterName, List.of(p), List.of(assign, returnThis()));
    }

    /** {@code Builder withX(T x)} where x is the Optional's inner type, wraps via {@code Optional.ofNullable}. */
    private JCMethodDecl optionalNullableRaw(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression inner = types.parseType(field.optionalInner);
        JCVariableDecl p = param(field.name, inner);

        // return this.withField(Optional.ofNullable(field));
        JCExpression wrappedCall = make.Apply(
            List.nil(),
            make.Select(types.qualIdent("java.util.Optional"), names.fromString("ofNullable")),
            List.of(make.Ident(names.fromString(field.name)))
        );
        JCExpression chained = make.Apply(
            List.nil(),
            make.Select(make.Ident(names._this), names.fromString(setterName)),
            List.of(wrappedCall)
        );
        return methodDefRaw(setterName, List.of(p), List.of(make.Return(chained)));
    }

    /** {@code Builder withX(Optional<T> x)} assigns directly. */
    private JCMethodDecl optionalWrapped(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression optType = make.TypeApply(
            types.qualIdent("java.util.Optional"),
            List.of(types.parseType(field.optionalInner))
        );
        return methodDef(setterName, param(field.name, optType), assignAndReturnThis(field));
    }

    // ------------------------------------------------------------------
    // @Formattable shapes
    // ------------------------------------------------------------------

    /**
     * {@code Builder withName(@PrintFormat String name, Object... args)} that
     * stores {@code String.format(name, args)}. When the field is
     * {@code @Nullable}, routes through {@code Strings.formatNullable} so a
     * null format string survives.
     */
    private JCMethodDecl stringFormattable(FieldSpec field) {
        String setterName = naming().setName(field.name);
        boolean nullable = field.nullable;
        JCExpression stringType = types.qualIdent("java.lang.String");
        JCVariableDecl formatParam = annotatedParam(
            field.name, stringType,
            printFormatAnnotation(),
            nullable ? nullableAnnotation() : notNullAnnotation()
        );
        JCVariableDecl argsParam = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS,
                List.of(nullableAnnotation())),
            names.fromString("args"),
            make.TypeArray(types.qualIdent("java.lang.Object")),
            null
        );

        JCExpression rhs;
        if (nullable) {
            rhs = make.Apply(
                List.nil(),
                make.Select(make.Apply(
                    List.nil(),
                    make.Select(types.qualIdent("dev.simplified.classbuilder.validate.Strings"),
                        names.fromString("formatNullable")),
                    List.of(make.Ident(names.fromString(field.name)),
                        make.Ident(names.fromString("args")))
                ), names.fromString("orElse")),
                List.of(make.Literal(com.sun.tools.javac.code.TypeTag.BOT, null))
            );
        } else {
            rhs = make.Apply(
                List.nil(),
                make.Select(types.qualIdent("java.lang.String"), names.fromString("format")),
                List.of(make.Ident(names.fromString(field.name)),
                    make.Ident(names.fromString("args")))
            );
        }
        JCStatement assign = slotAssign(field, rhs);
        return methodDefRaw(setterName, List.of(formatParam, argsParam),
            List.of(assign, returnThis()));
    }

    /**
     * {@code Builder withDescription(@PrintFormat @Nullable String description, Object... args)}
     * for an {@code Optional<String>} field; assigns
     * {@code Strings.formatNullable(description, args)} directly so the
     * Optional wrapper is preserved.
     */
    private JCMethodDecl optionalFormattable(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression stringType = types.qualIdent("java.lang.String");
        JCVariableDecl formatParam = annotatedParam(
            field.name, stringType,
            printFormatAnnotation(),
            nullableAnnotation()
        );
        JCVariableDecl argsParam = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS,
                List.of(nullableAnnotation())),
            names.fromString("args"),
            make.TypeArray(types.qualIdent("java.lang.Object")),
            null
        );
        JCExpression rhs = make.Apply(
            List.nil(),
            make.Select(types.qualIdent("dev.simplified.classbuilder.validate.Strings"),
                names.fromString("formatNullable")),
            List.of(make.Ident(names.fromString(field.name)),
                make.Ident(names.fromString("args")))
        );
        JCStatement assign = slotAssign(field, rhs);
        return methodDefRaw(setterName, List.of(formatParam, argsParam),
            List.of(assign, returnThis()));
    }

    // ------------------------------------------------------------------
    // @Collector shapes
    // ------------------------------------------------------------------

    /**
     * {@code Builder withEntries(T... entries)} that resets the underlying
     * collection and copies every element. Used for List/Set @Collector fields.
     */
    private JCMethodDecl singularCollectionVarargsReplace(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl varargs = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS),
            names.fromString(field.name),
            make.TypeArray(elemType),
            null
        );
        // this.field = <fresh empty container>;
        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            freshContainer(field)
        ));
        // for (T e : field) this.field.add(e);
        JCEnhancedForLoop loop = make.ForeachLoop(
            make.VarDef(make.Modifiers(Flags.PARAMETER), names.fromString("e"), elemType, null),
            make.Ident(names.fromString(field.name)),
            make.Exec(make.Apply(
                List.nil(),
                make.Select(
                    make.Select(make.Ident(names._this), names.fromString(field.name)),
                    names.fromString("add")),
                List.of(make.Ident(names.fromString("e")))
            ))
        );
        return methodDefRaw(setterName, List.of(varargs),
            withReplacedMark(field, List.of(assignFresh, loop, returnThis())));
    }

    /**
     * {@code Builder withEntries(Iterable<T> entries)} that resets the
     * collection and forEach-adds every element.
     */
    private JCMethodDecl singularCollectionIterableReplace(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCExpression iterableType = make.TypeApply(
            types.qualIdent("java.lang.Iterable"),
            List.of(elemType)
        );
        JCVariableDecl iterableParam = param(field.name, iterableType);

        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            freshContainer(field)
        ));
        // entries.forEach(this.field::add)
        JCExpression methodRef = make.Reference(
            JCTree.JCMemberReference.ReferenceMode.INVOKE,
            names.fromString("add"),
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            null
        );
        JCStatement forEach = make.Exec(make.Apply(
            List.nil(),
            make.Select(make.Ident(names.fromString(field.name)), names.fromString("forEach")),
            List.of(methodRef)
        ));
        return methodDefRaw(setterName, List.of(iterableParam),
            withReplacedMark(field, List.of(assignFresh, forEach, returnThis())));
    }

    /** {@code Builder addEntry(T entry)} that appends to the existing collection. */
    private JCMethodDecl singularCollectionAdd(FieldSpec field) {
        String addName = naming().addName(field.singularName);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl entryParam = param(field.singularName, elemType);
        JCStatement add = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("add")),
            List.of(make.Ident(names.fromString(field.singularName)))
        ));
        return methodDefRaw(addName, List.of(entryParam),
            List.of(add, returnThis()));
    }

    /** {@code Builder withEntries(Map<K, V> entries)} that replaces with a fresh LinkedHashMap. */
    private JCMethodDecl singularMapReplace(FieldSpec field) {
        String setterName = naming().setName(field.name);
        JCExpression keyType = types.parseType(field.mapKey);
        JCExpression valueType = types.parseType(field.mapValue);
        JCExpression mapType = make.TypeApply(
            types.qualIdent("java.util.Map"),
            List.of(keyType, valueType)
        );
        JCVariableDecl mapParam = param(field.name, mapType);
        if (field.isCustomContainer) {
            // this.field = $default$field(); this.field.putAll(field);
            JCStatement assignFresh = make.Exec(make.Assign(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                freshContainer(field)
            ));
            JCStatement putAll = make.Exec(make.Apply(
                List.nil(),
                make.Select(
                    make.Select(make.Ident(names._this), names.fromString(field.name)),
                    names.fromString("putAll")),
                List.of(make.Ident(names.fromString(field.name)))
            ));
            return methodDefRaw(setterName, List.of(mapParam),
                withReplacedMark(field, List.of(assignFresh, putAll, returnThis())));
        }
        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            make.NewClass(null, List.nil(),
                make.TypeApply(types.qualIdent("java.util.LinkedHashMap"), List.nil()),
                List.of(make.Ident(names.fromString(field.name))),
                null)
        ));
        return methodDefRaw(setterName, List.of(mapParam),
            withReplacedMark(field, List.of(assignFresh, returnThis())));
    }

    /** {@code Builder putEntry(K key, V value)} that puts into the existing map. */
    private JCMethodDecl singularMapPut(FieldSpec field) {
        String putName = naming().putName(field.singularName);
        JCExpression keyType = types.parseType(field.mapKey);
        JCExpression valueType = types.parseType(field.mapValue);
        JCVariableDecl keyParam = param("key", keyType);
        JCVariableDecl valueParam = param("value", valueType);
        JCStatement put = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("put")),
            List.of(make.Ident(names.fromString("key")),
                make.Ident(names.fromString("value")))
        ));
        return methodDefRaw(putName, List.of(keyParam, valueParam),
            List.of(put, returnThis()));
    }

    /**
     * {@code Builder putEntryIfAbsent(K key, Supplier<V> valueSupplier)} -
     * puts only when the key is not already present, calling the supplier
     * lazily for the value. Gated on {@code @Collector(compute = true)}.
     */
    private JCMethodDecl singularMapPutIfAbsent(FieldSpec field) {
        String putName = naming().computeName(field.singularName);
        JCExpression keyType = types.parseType(field.mapKey);
        JCExpression supplierType = make.TypeApply(
            types.qualIdent("java.util.function.Supplier"),
            List.of(types.parseType(field.mapValue))
        );
        JCVariableDecl keyParam = param("key", keyType);
        JCVariableDecl supplierParam = param("valueSupplier", supplierType);
        // if (!this.field.containsKey(key)) this.field.put(key, valueSupplier.get());
        JCExpression fieldRef = make.Select(make.Ident(names._this), names.fromString(field.name));
        JCExpression containsKey = make.Apply(
            List.nil(),
            make.Select(fieldRef, names.fromString("containsKey")),
            List.of(make.Ident(names.fromString("key")))
        );
        JCExpression negated = make.Unary(com.sun.tools.javac.tree.JCTree.Tag.NOT, containsKey);
        JCExpression supplierGet = make.Apply(
            List.nil(),
            make.Select(make.Ident(names.fromString("valueSupplier")), names.fromString("get")),
            List.nil()
        );
        JCExpression putCall = make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("put")),
            List.of(make.Ident(names.fromString("key")), supplierGet)
        );
        JCStatement guardedPut = make.If(negated, make.Exec(putCall), null);
        return methodDefRaw(putName, List.of(keyParam, supplierParam),
            List.of(guardedPut, returnThis()));
    }

    /** {@code Builder clearEntries()} that empties the underlying collection or map. */
    private JCMethodDecl singularClear(FieldSpec field) {
        String clearName = naming().clearName(field.name);
        JCStatement clear = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("clear")),
            List.nil()
        ));
        return methodDefRaw(clearName, List.nil(),
            withReplacedMark(field, List.of(clear, returnThis())));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<JCStatement> assignAndReturnThis(String fieldName) {
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(fieldName)),
            make.Ident(names.fromString(fieldName))
        ));
        return List.of(assign, returnThis());
    }

    private JCStatement returnThis() {
        return make.Return(make.Ident(names._this));
    }

    private JCVariableDecl param(String name, JCExpression type) {
        return make.VarDef(make.Modifiers(Flags.PARAMETER), names.fromString(name), type, null);
    }

    /** Parameter declaration carrying the supplied annotations. */
    private JCVariableDecl annotatedParam(String name, JCExpression type, JCAnnotation... annotations) {
        return make.VarDef(
            make.Modifiers(Flags.PARAMETER, List.from(annotations)),
            names.fromString(name),
            type,
            null
        );
    }

    /**
     * Field-type setter parameter that re-emits the field's own nullness
     * annotation when it carries one. {@code parseType} strips any type-use
     * {@code @NotNull}/{@code @Nullable} out of the field type (it would
     * otherwise corrupt the qualified-name tree), so the hint is re-attached
     * here as a plain declaration annotation on the parameter - restoring the
     * IDE null-analysis the field declared. Fields without a nullness
     * annotation get a bare parameter, exactly as before.
     */
    private JCVariableDecl nullnessParam(String name, JCExpression type, FieldSpec field) {
        if (field.notNull) return annotatedParam(name, type, notNullAnnotation());
        if (field.nullable) return annotatedParam(name, type, nullableAnnotation());
        return param(name, type);
    }

    private JCAnnotation printFormatAnnotation() {
        return make.Annotation(types.qualIdent("org.intellij.lang.annotations.PrintFormat"), List.nil());
    }

    private JCAnnotation nullableAnnotation() {
        return make.Annotation(types.qualIdent("org.jetbrains.annotations.Nullable"), List.nil());
    }

    private JCAnnotation notNullAnnotation() {
        return make.Annotation(types.qualIdent("org.jetbrains.annotations.NotNull"), List.nil());
    }

    /**
     * Convenience for single-parameter methods. Wraps the parameter in a one-
     * element javac list and delegates to {@link #methodDefRaw}.
     */
    private JCMethodDecl methodDef(String methodName, JCVariableDecl param, List<JCStatement> body) {
        return methodDefRaw(methodName, List.of(param), body);
    }

    /**
     * Core method-declaration builder. Public modifier; return type is the
     * enclosing nested Builder so inherited this-chaining works without
     * qualification, carrying the target's type arguments on a generic target -
     * returning the bare name there would erase the builder to a raw type and
     * silently drop the parameter for the rest of the chain. The attached
     * {@code @XContract} (when {@code emitContracts = true}) is chosen by
     * parameter arity: every setter shape here returns {@code this} and mutates
     * the builder.
     */
    private JCMethodDecl methodDefRaw(String methodName, List<JCVariableDecl> params, List<JCStatement> body) {
        JCModifiers mods = make.Modifiers(Flags.PUBLIC, thisReturnContract(params.size()));
        Name name = names.fromString(methodName);
        JCExpression returnType = ctx.builderType();
        JCBlock block = make.Block(0, body);
        JCMethodDecl method = make.MethodDef(mods, name, returnType, List.nil(), params, List.nil(), block, null);
        AstMarkers.markGenerated(method);
        return method;
    }

    /**
     * Picks the right {@code @XContract} flavour by parameter count. All
     * setters here return {@code this} and mutate the builder - only the
     * left-hand side of the contract ({@code "_"}, {@code "_, _"}, or nothing)
     * varies with arity. Arities > 2 fall back to no contract rather than
     * guessing at a shape that hasn't been established by the existing
     * emitter vocabulary.
     */
    private List<com.sun.tools.javac.tree.JCTree.JCAnnotation> thisReturnContract(int arity) {
        return switch (arity) {
            case 0 -> contracts.thisReturnNullary();
            case 1 -> contracts.thisReturnUnary();
            case 2 -> contracts.thisReturnBinary();
            default -> List.nil();
        };
    }

    private NamingScheme naming() {
        return ctx.config().naming();
    }

}
