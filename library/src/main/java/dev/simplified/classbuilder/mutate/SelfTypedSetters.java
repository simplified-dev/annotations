package dev.simplified.classbuilder.mutate;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCEnhancedForLoop;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.classbuilder.apt.SetterScheme;
import dev.simplified.classbuilder.apt.SetterShape;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import java.util.Optional;

/**
 * Self-typed variant of {@link FieldMutators}: emits setters whose return
 * type is the Builder type-parameter {@code B}, with {@code return self();}
 * for the trailing statement. Used by the SuperBuilder chain so subclass
 * builders see their own concrete type flowing through inherited setters.
 *
 * <p>Shape coverage now matches {@link FieldMutators} one-for-one: plain,
 * boolean zero-arg/typed pair plus optional {@code @Negate} inverse pair,
 * {@code Optional} nullable-raw/wrapped pair plus optional
 * {@code @Formattable} overload, {@code @Collector} collection
 * (varargs-replace + iterable-replace + add + clear) and map (replace + put
 * + clear), array varargs, String {@code @Formattable} overload. The two
 * classes are deliberately kept as parallel siblings rather than refactored
 * behind a shared base because the only diverging surface is the return-
 * type expression and the trailing statement - factoring those out adds an
 * abstraction layer that the codebase doesn't otherwise need.
 */
final class SelfTypedSetters {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final ContractAnnotations contracts;
    /** The name of the builder's self-type parameter, which every setter returns. */
    private final String selfBuilderName;

    /**
     * Creates setters returning the self-type parameter under the name the
     * generator gives it.
     *
     * @param ctx the per-target mutation context
     */
    SelfTypedSetters(MutationContext ctx) {
        this(ctx, ctx.selfBuilderName());
    }

    /**
     * Creates setters returning the self-type parameter under the given name,
     * which is the one a declared builder spells its trailing parameter with.
     *
     * @param ctx the per-target mutation context
     * @param selfBuilderName the name of the builder's self-type parameter
     */
    SelfTypedSetters(MutationContext ctx, String selfBuilderName) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
        this.contracts = ctx.contracts();
        this.selfBuilderName = selfBuilderName;
    }

    /**
     * Mirrors {@link FieldMutators#setters} but always with {@code return self();},
     * each setter recorded on the context as the field's with its
     * {@link SetterShape}.
     *
     * @param field the slot
     * @return its setters, in emission order
     */
    List<JCMethodDecl> setters(FieldSpec field) {
        ListBuffer<JCMethodDecl> out = new ListBuffer<>();
        if (field.lazy) {
            out.append(tag(field, SetterShape.LAZY_VALUE, lazyValueSetter(field)));
            out.append(tag(field, SetterShape.LAZY_SUPPLIER, lazySupplierSetter(field)));
            return out.toList();
        }
        if (field.isBoolean) {
            // Typed setter is the ordinary `set` role; the zero-arg form is the
            // separate `flag` role and drops out when a style suppresses it.
            if (field.setters.emitsFlag())
                out.append(tag(field, SetterShape.FLAG, booleanZeroArg(field, field.name, false)));
            out.append(tag(field, SetterShape.BOOLEAN, booleanTyped(field, field.name, false)));
            if (field.negateName != null && !field.negateName.isEmpty()) {
                if (field.setters.emitsFlag())
                    out.append(tag(field, SetterShape.FLAG, booleanZeroArg(field, field.negateName, true)));
                out.append(tag(field, SetterShape.NEGATED, booleanTyped(field, field.negateName, true)));
            }
        } else if (field.isOptional) {
            out.append(tag(field, SetterShape.OPTIONAL_VALUE, optionalNullableRaw(field)));
            out.append(tag(field, SetterShape.OPTIONAL, optionalWrapped(field)));
            if (field.formattable && field.isOptionalString)
                out.append(tag(field, SetterShape.FORMAT, optionalFormattable(field)));
        } else if (field.isArray) {
            out.append(tag(field, SetterShape.ARRAY, arrayVarargs(field)));
        } else if ((field.isListLike || field.isMap) && field.collector) {
            if (field.isCustomContainer && !hasInit(field)) {
                // Custom container with @Collector but no captured initializer -
                // degrade to a plain replace setter (processor emits a NOTE).
                out.append(tag(field, SetterShape.PLAIN, plainSetter(field)));
            } else {
                if (field.isMap) {
                    out.append(tag(field, SetterShape.BULK_MAP, singularMapReplace(field)));
                    if (field.singular && field.setters.emitsPut())
                        out.append(tag(field, SetterShape.PUT, singularMapPut(field)));
                    if (field.compute && field.setters.emitsCompute())
                        out.append(tag(field, SetterShape.PUT_IF_ABSENT, singularMapPutIfAbsent(field)));
                } else {
                    out.append(tag(field, SetterShape.BULK_VARARGS, singularCollectionVarargsReplace(field)));
                    out.append(tag(field, SetterShape.BULK_ITERABLE, singularCollectionIterableReplace(field)));
                    if (field.singular && field.setters.emitsAdd())
                        out.append(tag(field, SetterShape.ADD, singularCollectionAdd(field)));
                }
                if (field.clearable && field.setters.emitsClear())
                    out.append(tag(field, SetterShape.CLEAR, singularClear(field)));
                if (field.removable && field.setters.emitsRemove())
                    out.append(tag(field, SetterShape.REMOVE, singularRemove(field)));
            }
        } else if (field.isString && field.formattable) {
            out.append(tag(field, SetterShape.PLAIN, plainSetter(field)));
            out.append(tag(field, SetterShape.FORMAT, stringFormattable(field)));
        } else {
            out.append(tag(field, SetterShape.PLAIN, plainSetter(field)));
        }
        appendAssignViaOverloads(field, out);
        return out.toList();
    }

    /** Records a setter on the context as the slot's, in the given shape. */
    private JCMethodDecl tag(FieldSpec field, SetterShape shape, JCMethodDecl setter) {
        return ctx.recordSetter(field, shape, setter);
    }

    // ------------------------------------------------------------------
    // @Lazy shapes
    // ------------------------------------------------------------------

    /** {@code B withFoo(T value)} - eager value form, stores {@code () -> value}. */
    private JCMethodDecl lazyValueSetter(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        JCExpression valueType = types.parseType(field.typeDisplay);
        JCVariableDecl p = param(field.name, valueType);
        JCExpression lambda = make.Lambda(List.nil(), make.Ident(names.fromString(field.name)));
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            lambda
        ));
        return method(setterName, List.of(p), List.of(assign, returnSelf()));
    }

    /** {@code B withFoo(Supplier<T> supplier)} - true lazy form, stores the supplier, boxed for a primitive. */
    private JCMethodDecl lazySupplierSetter(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        JCExpression supplierType = make.TypeApply(
            types.qualIdent("java.util.function.Supplier"),
            List.of(types.parseBoxedType(field.typeDisplay))
        );
        return method(setterName, List.of(param(field.name, supplierType)),
            assignAndReturnSelf(field.name));
    }

    // ------------------------------------------------------------------
    // Plain / boolean / array shapes
    // ------------------------------------------------------------------

    private JCMethodDecl plainSetter(FieldSpec field) {
        JCExpression fieldType = types.parseType(field.typeDisplay);
        JCVariableDecl p = nullnessParam(field.name, fieldType, field);
        return method(field.setters.setName(field.name, field.isBoolean), List.of(p), assignAndReturnSelf(field));
    }

    private JCMethodDecl arrayVarargs(FieldSpec field) {
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl p = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS),
            names.fromString(field.name),
            make.TypeArray(elemType),
            null
        );
        return method(field.setters.setName(field.name, field.isBoolean), List.of(p), assignAndReturnSelf(field));
    }

    private JCMethodDecl booleanZeroArg(FieldSpec field, String methodBase, boolean inverse) {
        String setterName = field.setters.flagName(methodBase);
        JCStatement assign = slotAssign(field, make.Literal(!inverse));
        return method(setterName, List.nil(), List.of(assign, returnSelf()));
    }

    private JCMethodDecl booleanTyped(FieldSpec field, String methodBase, boolean inverse) {
        String setterName = field.setters.setName(methodBase, true);
        JCExpression paramRef = make.Ident(names.fromString(methodBase));
        JCExpression value = inverse ? make.Unary(JCTree.Tag.NOT, paramRef) : paramRef;
        JCStatement assign = slotAssign(field, value);
        JCVariableDecl p = param(methodBase, make.TypeIdent(TypeTag.BOOLEAN));
        return method(setterName, List.of(p), List.of(assign, returnSelf()));
    }

    // ------------------------------------------------------------------
    // Optional shapes
    // ------------------------------------------------------------------

    /** {@code B withX(T x)} - inner-type overload that wraps via {@code Optional.ofNullable}. */
    private JCMethodDecl optionalNullableRaw(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
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
        return method(setterName, List.of(p), List.of(make.Return(chained)));
    }

    /** {@code B withX(Optional<T> x)} - direct Optional assignment. */
    private JCMethodDecl optionalWrapped(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        JCExpression optType = make.TypeApply(
            types.qualIdent("java.util.Optional"),
            List.of(types.parseType(field.optionalInner))
        );
        return method(setterName, List.of(param(field.name, optType)), assignAndReturnSelf(field));
    }

    // ------------------------------------------------------------------
    // @Formattable shapes
    // ------------------------------------------------------------------

    /**
     * {@code B withName(@PrintFormat String format, Object... args)} that
     * stores {@code String.format(format, args)}. When the field carries
     * {@code @Nullable}, a null format string is stored as-is rather than
     * handed to {@link String#format}.
     */
    private JCMethodDecl stringFormattable(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        boolean nullable = field.nullable;
        JCExpression stringType = types.qualIdent("java.lang.String");
        JCVariableDecl formatParam = annotatedParam(
            field.name, stringType,
            printFormatAnnotation(),
            nullable ? nullableAnnotation() : notNullAnnotation()
        );
        JCVariableDecl argsParam = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS, List.of(nullableAnnotation())),
            names.fromString("args"),
            make.TypeArray(types.qualIdent("java.lang.Object")),
            null
        );

        JCExpression rhs = nullable
            ? formattedOrNull(field.name)
            : formatted(field.name);
        JCStatement assign = slotAssign(field, rhs);
        return method(setterName, List.of(formatParam, argsParam),
            List.of(assign, returnSelf()));
    }

    /**
     * {@code B withDescription(@PrintFormat @Nullable String format, Object... args)}
     * for an {@code Optional<String>} field; wraps the formatted value so the
     * Optional wrapper is preserved and a null format string becomes
     * {@link Optional#empty()}.
     */
    private JCMethodDecl optionalFormattable(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        JCExpression stringType = types.qualIdent("java.lang.String");
        JCVariableDecl formatParam = annotatedParam(
            field.name, stringType,
            printFormatAnnotation(),
            nullableAnnotation()
        );
        JCVariableDecl argsParam = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS, List.of(nullableAnnotation())),
            names.fromString("args"),
            make.TypeArray(types.qualIdent("java.lang.Object")),
            null
        );
        JCExpression rhs = make.Apply(
            List.nil(),
            make.Select(types.qualIdent("java.util.Optional"), names.fromString("ofNullable")),
            List.of(formattedOrNull(field.name))
        );
        JCStatement assign = slotAssign(field, rhs);
        return method(setterName, List.of(formatParam, argsParam),
            List.of(assign, returnSelf()));
    }

    /** {@code String.format(<param>, args)}. */
    private JCExpression formatted(String param) {
        return make.Apply(
            List.nil(),
            make.Select(types.qualIdent("java.lang.String"), names.fromString("format")),
            List.of(make.Ident(names.fromString(param)),
                make.Ident(names.fromString("args")))
        );
    }

    /**
     * {@code <param> == null ? null : String.format(<param>, args)}.
     *
     * <p>A {@code @Formattable} slot that accepts a null format string cannot
     * hand it to {@link String#format}, which would throw. Storing the null
     * instead is what lets the setter's parameter be {@code @Nullable}.
     */
    private JCExpression formattedOrNull(String param) {
        JCExpression isNull = make.Binary(JCTree.Tag.EQ,
            make.Ident(names.fromString(param)),
            make.Literal(TypeTag.BOT, null));
        return make.Conditional(isNull, make.Literal(TypeTag.BOT, null), formatted(param));
    }

    // ------------------------------------------------------------------
    // @Collector shapes
    // ------------------------------------------------------------------

    /**
     * {@code B withEntries(T... entries)} - copies every element in, resetting
     * the container first unless the field asked to
     * {@link FieldSpec#append}.
     */
    private JCMethodDecl singularCollectionVarargsReplace(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl varargs = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS),
            names.fromString(field.name),
            make.TypeArray(elemType),
            null
        );
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
        return method(setterName, List.of(varargs), bulkBody(field, loop));
    }

    /**
     * {@code B withEntries(Iterable<T> entries)} - forEach-adds every element,
     * resetting the container first unless the field asked to
     * {@link FieldSpec#append}.
     */
    private JCMethodDecl singularCollectionIterableReplace(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCExpression iterableType = make.TypeApply(
            types.qualIdent("java.lang.Iterable"),
            List.of(elemType)
        );
        JCVariableDecl iterableParam = param(field.name, iterableType);

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
        return method(setterName, List.of(iterableParam), bulkBody(field, forEach));
    }

    /**
     * The body of a bulk setter: the copy step, preceded by a fresh container
     * unless the field appends into the one already there.
     *
     * @param field the collection or map field
     * @param copy the statement moving the argument's contents into the slot
     * @return the setter's statements, ending in {@code return self()}
     */
    private List<JCStatement> bulkBody(FieldSpec field, JCStatement copy) {
        if (field.append) return List.of(copy, returnSelf());
        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            freshContainer(field)
        ));
        return withReplacedMark(field, List.of(assignFresh, copy, returnSelf()));
    }

    /** {@code B addEntry(T entry)} - append one element to the existing collection. */
    private JCMethodDecl singularCollectionAdd(FieldSpec field) {
        String addName = field.setters.addName(field.singularName);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl entryParam = param(field.singularName, elemType);
        JCStatement add = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("add")),
            List.of(make.Ident(names.fromString(field.singularName)))
        ));
        return method(addName, List.of(entryParam), List.of(add, returnSelf()));
    }

    /** {@code B withEntries(Map<K, V> entries)} - replace with a fresh LinkedHashMap. */
    private JCMethodDecl singularMapReplace(FieldSpec field) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        JCExpression keyType = types.parseType(field.mapKey);
        JCExpression valueType = types.parseType(field.mapValue);
        JCExpression mapType = make.TypeApply(
            types.qualIdent("java.util.Map"),
            List.of(keyType, valueType)
        );
        JCVariableDecl mapParam = param(field.name, mapType);
        // this.field.putAll(field)
        JCStatement putAll = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("putAll")),
            List.of(make.Ident(names.fromString(field.name)))
        ));
        if (field.append) {
            return method(setterName, List.of(mapParam), List.of(putAll, returnSelf()));
        }
        if (field.isCustomContainer) {
            // this.field = $default$field(); this.field.putAll(field);
            JCStatement assignFresh = make.Exec(make.Assign(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                freshContainer(field)
            ));
            return method(setterName, List.of(mapParam),
                withReplacedMark(field, List.of(assignFresh, putAll, returnSelf())));
        }
        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            make.NewClass(null, List.nil(),
                make.TypeApply(types.qualIdent("java.util.LinkedHashMap"), List.nil()),
                List.of(make.Ident(names.fromString(field.name))),
                null)
        ));
        return method(setterName, List.of(mapParam),
            withReplacedMark(field, List.of(assignFresh, returnSelf())));
    }

    /**
     * {@code B putEntry(K key, V value)} - put one entry into the existing map,
     * or {@code B putEntry(V value)} keying on {@code value.<key>()} under
     * {@code @Collector(key)}. Mirrors {@link FieldMutators#singularMapPut}.
     */
    private JCMethodDecl singularMapPut(FieldSpec field) {
        String putName = field.setters.putName(field.singularName);
        JCExpression valueType = types.parseType(field.mapValue);
        JCVariableDecl valueParam = param("value", valueType);
        JCExpression keyArgument = field.keyMethod == null
            ? make.Ident(names.fromString("key"))
            : make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString("value")),
                    names.fromString(field.keyMethod)),
                List.nil());
        JCStatement put = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("put")),
            List.of(keyArgument, make.Ident(names.fromString("value")))
        ));
        if (field.keyMethod != null) {
            return method(putName, List.of(valueParam), List.of(put, returnSelf()));
        }
        JCVariableDecl keyParam = param("key", types.parseType(field.mapKey));
        return method(putName, List.of(keyParam, valueParam),
            List.of(put, returnSelf()));
    }

    /**
     * {@code B removeEntry(T entry)} or {@code B removeEntry(K key)} - one
     * element or entry back out. Mirrors {@link FieldMutators#singularRemove},
     * cast to {@link Object} on a collection for the reason given there.
     */
    private JCMethodDecl singularRemove(FieldSpec field) {
        String removeName = field.setters.removeName(field.singularName);
        String subjectType = field.isMap ? field.mapKey : field.collectionElement;
        JCVariableDecl subject = param(field.singularName, types.parseType(subjectType));
        JCExpression argument = make.Ident(names.fromString(field.singularName));
        if (!field.isMap) {
            argument = make.TypeCast(types.qualIdent("java.lang.Object"), argument);
        }
        JCStatement remove = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("remove")),
            List.of(argument)
        ));
        return method(removeName, List.of(subject), List.of(remove, returnSelf()));
    }

    /**
     * {@code B putEntryIfAbsent(K key, Supplier<V> valueSupplier)} - puts only
     * when the key is not already present, calling the supplier lazily.
     * Gated on {@code @Collector(compute = true)}.
     */
    private JCMethodDecl singularMapPutIfAbsent(FieldSpec field) {
        String putName = field.setters.computeName(field.singularName);
        JCExpression keyType = types.parseType(field.mapKey);
        JCExpression supplierType = make.TypeApply(
            types.qualIdent("java.util.function.Supplier"),
            List.of(types.parseType(field.mapValue))
        );
        JCVariableDecl keyParam = param("key", keyType);
        JCVariableDecl supplierParam = param("valueSupplier", supplierType);
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
        return method(putName, List.of(keyParam, supplierParam),
            List.of(guardedPut, returnSelf()));
    }

    /** {@code B clearEntries()} - empty the underlying collection or map. */
    private JCMethodDecl singularClear(FieldSpec field) {
        String clearName = field.setters.clearName(field.name);
        JCStatement clear = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("clear")),
            List.nil()
        ));
        return method(clearName, List.nil(),
            withReplacedMark(field, List.of(clear, returnSelf())));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<JCStatement> assignAndReturnSelf(String fieldName) {
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(fieldName)),
            make.Ident(names.fromString(fieldName))
        ));
        return List.of(assign, returnSelf());
    }

    /**
     * Assigns the builder slot, routing the value through the slot's direct
     * {@code @AssignVia} transform where one is written. Mirrors
     * {@link FieldMutators#slotAssign}.
     */
    private JCStatement slotAssign(FieldSpec field, JCExpression value) {
        return slotAssignRaw(field, coerce(field, value));
    }

    /**
     * Assigns the builder slot verbatim, wrapping the value as
     * {@code () -> value} when the field takes the constructor-computed path.
     * Mirrors {@link FieldMutators#slotAssignRaw}: the slot is
     * {@code Supplier<T>} there, and every setter has to wrap so null keeps
     * meaning "never set".
     */
    private JCStatement slotAssignRaw(FieldSpec field, JCExpression value) {
        JCExpression rhs = ctx.isInstanceDefault(field.name)
            ? make.Lambda(List.nil(), value)
            : value;
        return make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            rhs
        ));
    }

    /** Mirrors {@link FieldMutators#coerce}. */
    private JCExpression coerce(FieldSpec field, JCExpression value) {
        String transform = field.directAssign();
        return transform == null ? value : staticCall(transform, value);
    }

    /** {@code Target.method(argument)}. */
    private JCExpression staticCall(String method, JCExpression argument) {
        return make.Apply(
            List.nil(),
            make.Select(make.Ident(names.fromString(ctx.targetSimpleName())),
                names.fromString(method)),
            List.of(argument)
        );
    }

    /** Mirrors {@link FieldMutators#assignViaOverload}, returning {@code self()}. */
    private JCMethodDecl assignViaOverload(FieldSpec field, FieldSpec.AssignTransform transform) {
        String setterName = field.setters.setName(field.name, field.isBoolean);
        JCVariableDecl p = param(field.name, types.parseType(transform.paramDisplay()));
        JCStatement assign = slotAssignRaw(field,
            staticCall(transform.method(), make.Ident(names.fromString(field.name))));
        return method(setterName, List.of(p), List.of(assign, returnSelf()));
    }

    /** Mirrors {@link FieldMutators#appendAssignViaOverloads}. */
    private void appendAssignViaOverloads(FieldSpec field, ListBuffer<JCMethodDecl> out) {
        if (field.collector) return;
        for (FieldSpec.AssignTransform transform : field.assignVia) {
            if (transform.direct() || !transform.resolved()) continue;
            out.append(tag(field, SetterShape.ASSIGN_VIA, assignViaOverload(field, transform)));
        }
    }

    /**
     * Prepends the replaced marker to a wholesale-replace setter's body when the
     * field takes the collected merge path. Mirrors
     * {@link FieldMutators#withReplacedMark}.
     */
    private List<JCStatement> withReplacedMark(FieldSpec field, List<JCStatement> body) {
        if (!ctx.isCollectedInstanceDefault(field)) return body;
        JCStatement mark = make.Exec(make.Assign(
            make.Select(make.Ident(names._this),
                names.fromString(MutationContext.replacedMarker(field.name))),
            make.Literal(true)
        ));
        return body.prepend(mark);
    }

    /** Slot assignment from a like-named parameter, then {@code return self();}. */
    private List<JCStatement> assignAndReturnSelf(FieldSpec field) {
        return List.of(slotAssign(field, make.Ident(names.fromString(field.name))), returnSelf());
    }

    /** {@code return self();} - used in place of {@code return this;} under self-typed generics. */
    private JCStatement returnSelf() {
        return make.Return(make.Apply(List.nil(), make.Ident(names.fromString("self")), List.nil()));
    }

    private JCVariableDecl param(String name, JCExpression type) {
        return make.VarDef(make.Modifiers(Flags.PARAMETER), names.fromString(name), type, null);
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

    /**
     * A fresh, empty container for a {@code @Collector} reset setter. A custom
     * container comes from the field's own {@code $default$} provider (a
     * {@code new ArrayList<>()} would not be assignable to the field type);
     * java.util containers use the matching concrete implementation.
     *
     * @param field the collector slot being reset
     * @return the expression producing the empty container
     */
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
     * annotation when it carries one. Mirrors {@link FieldMutators#nullnessParam}
     * so the SuperBuilder path restores the {@code @NotNull}/{@code @Nullable}
     * hint {@code parseType} strips out of the field type.
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

    private JCMethodDecl method(String methodName, List<JCVariableDecl> params, List<JCStatement> body) {
        JCBlock block = make.Block(0, body);
        // The self-type parameter. Under the generator's name it dodges its
        // usual "B" spelling when the target declares a type parameter of that
        // name; a declared builder's own spelling is taken as written.
        JCExpression returnType = make.Ident(names.fromString(selfBuilderName));
        // Contract mirrors FieldMutators: every setter returns this via
        // self() and mutates the builder. Arity picks the left-hand side.
        List<JCAnnotation> contract = switch (params.size()) {
            case 0 -> contracts.thisReturnNullary();
            case 1 -> contracts.thisReturnUnary();
            case 2 -> contracts.thisReturnBinary();
            default -> List.nil();
        };
        JCMethodDecl m = make.MethodDef(
            make.Modifiers(Flags.PUBLIC, contract),
            names.fromString(methodName),
            returnType,
            List.nil(),
            params,
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(m, ctx.generated());
        return m;
    }


}
