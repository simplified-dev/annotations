package dev.sbs.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCEnhancedForLoop;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import dev.sbs.classbuilder.apt.FieldSpec;

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
 *   <li>{@code @Singular} collection or map: replace + per-element add/put
 *       + clear,</li>
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
        this.contracts = new ContractAnnotations(ctx);
    }

    /** Returns every setter the field should emit on the nested Builder. */
    List<JCMethodDecl> setters(FieldSpec field) {
        ListBuffer<JCMethodDecl> out = new ListBuffer<>();
        if (field.isBoolean) {
            out.append(booleanZeroArg(field, field.name, false));
            out.append(booleanTyped(field, field.name, false));
            if (field.negateName != null && !field.negateName.isEmpty()) {
                out.append(booleanZeroArg(field, field.negateName, true));
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
        } else if ((field.isListLike || field.isMap) && field.singularName != null) {
            // @Singular: replace + per-element add/put + clear.
            if (field.isMap) {
                out.append(singularMapReplace(field));
                out.append(singularMapPut(field));
            } else {
                out.append(singularCollectionVarargsReplace(field));
                out.append(singularCollectionIterableReplace(field));
                out.append(singularCollectionAdd(field));
            }
            out.append(singularClear(field));
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
     * never null.
     */
    JCVariableDecl fieldDecl(FieldSpec field) {
        JCExpression fieldType = types.parseType(field.typeDisplay);
        JCExpression init = defaultInitializer(field);
        return make.VarDef(
            make.Modifiers(Flags.PRIVATE),
            names.fromString(field.name),
            fieldType,
            init
        );
    }

    private JCExpression defaultInitializer(FieldSpec field) {
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

    // ------------------------------------------------------------------
    // Setter shapes
    // ------------------------------------------------------------------

    private JCMethodDecl plainSetter(FieldSpec field) {
        String setterName = methodName(field.name, false);
        JCExpression fieldType = types.parseType(field.typeDisplay);
        return methodDef(setterName, param(field.name, fieldType), assignAndReturnThis(field.name));
    }

    private JCMethodDecl arrayVarargs(FieldSpec field) {
        String setterName = methodName(field.name, false);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl p = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS),
            names.fromString(field.name),
            make.TypeArray(elemType),
            null
        );
        return methodDefRaw(setterName, List.of(p), assignAndReturnThis(field.name));
    }

    private JCMethodDecl booleanZeroArg(FieldSpec field, String methodBase, boolean inverse) {
        String setterName = "is" + capitalise(methodBase);
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            make.Literal(!inverse)
        ));
        return methodDefRaw(setterName, List.nil(), List.of(assign, returnThis()));
    }

    private JCMethodDecl booleanTyped(FieldSpec field, String methodBase, boolean inverse) {
        String setterName = "is" + capitalise(methodBase);
        JCExpression paramRef = make.Ident(names.fromString(methodBase));
        JCExpression value = inverse ? make.Unary(JCTree.Tag.NOT, paramRef) : paramRef;
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            value
        ));
        JCVariableDecl p = param(methodBase, make.TypeIdent(com.sun.tools.javac.code.TypeTag.BOOLEAN));
        return methodDefRaw(setterName, List.of(p), List.of(assign, returnThis()));
    }

    /** {@code Builder withX(T x)} where x is the Optional's inner type, wraps via {@code Optional.ofNullable}. */
    private JCMethodDecl optionalNullableRaw(FieldSpec field) {
        String setterName = methodName(field.name, false);
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
        String setterName = methodName(field.name, false);
        JCExpression optType = make.TypeApply(
            types.qualIdent("java.util.Optional"),
            List.of(types.parseType(field.optionalInner))
        );
        return methodDef(setterName, param(field.name, optType), assignAndReturnThis(field.name));
    }

    // ------------------------------------------------------------------
    // @Formattable shapes
    // ------------------------------------------------------------------

    /**
     * {@code Builder withName(@PrintFormat String name, Object... args)} that
     * stores {@code String.format(name, args)}. When the field or the
     * {@code @Formattable.nullable} attribute is set, routes through
     * {@code Strings.formatNullable} so a null format string survives.
     */
    private JCMethodDecl stringFormattable(FieldSpec field) {
        String setterName = methodName(field.name, false);
        boolean nullable = field.formattableNullable || field.nullable;
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
                    make.Select(types.qualIdent("dev.sbs.classbuilder.validate.Strings"),
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
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            rhs
        ));
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
        String setterName = methodName(field.name, false);
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
            make.Select(types.qualIdent("dev.sbs.classbuilder.validate.Strings"),
                names.fromString("formatNullable")),
            List.of(make.Ident(names.fromString(field.name)),
                make.Ident(names.fromString("args")))
        );
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            rhs
        ));
        return methodDefRaw(setterName, List.of(formatParam, argsParam),
            List.of(assign, returnThis()));
    }

    // ------------------------------------------------------------------
    // @Singular shapes
    // ------------------------------------------------------------------

    /**
     * {@code Builder withEntries(T... entries)} that resets the underlying
     * collection and copies every element. Used for List/Set @Singular fields.
     */
    private JCMethodDecl singularCollectionVarargsReplace(FieldSpec field) {
        String setterName = methodName(field.name, false);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl varargs = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS),
            names.fromString(field.name),
            make.TypeArray(elemType),
            null
        );
        // this.field = new <Container>();
        String containerFqn = field.isSet ? "java.util.LinkedHashSet" : "java.util.ArrayList";
        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            make.NewClass(null, List.nil(),
                make.TypeApply(types.qualIdent(containerFqn), List.nil()),
                List.nil(), null)
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
            List.of(assignFresh, loop, returnThis()));
    }

    /**
     * {@code Builder withEntries(Iterable<T> entries)} that resets the
     * collection and forEach-adds every element.
     */
    private JCMethodDecl singularCollectionIterableReplace(FieldSpec field) {
        String setterName = methodName(field.name, false);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCExpression iterableType = make.TypeApply(
            types.qualIdent("java.lang.Iterable"),
            List.of(elemType)
        );
        JCVariableDecl iterableParam = param(field.name, iterableType);
        String containerFqn = field.isSet ? "java.util.LinkedHashSet" : "java.util.ArrayList";

        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            make.NewClass(null, List.nil(),
                make.TypeApply(types.qualIdent(containerFqn), List.nil()),
                List.nil(), null)
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
            List.of(assignFresh, forEach, returnThis()));
    }

    /** {@code Builder addEntry(T entry)} that appends to the existing collection. */
    private JCMethodDecl singularCollectionAdd(FieldSpec field) {
        String prefix = ctx.config().methodPrefix().isEmpty() ? "add" : ctx.config().methodPrefix();
        String addName = prefix + capitalise(field.singularName);
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
        String setterName = methodName(field.name, false);
        JCExpression keyType = types.parseType(field.mapKey);
        JCExpression valueType = types.parseType(field.mapValue);
        JCExpression mapType = make.TypeApply(
            types.qualIdent("java.util.Map"),
            List.of(keyType, valueType)
        );
        JCVariableDecl mapParam = param(field.name, mapType);
        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            make.NewClass(null, List.nil(),
                make.TypeApply(types.qualIdent("java.util.LinkedHashMap"), List.nil()),
                List.of(make.Ident(names.fromString(field.name))),
                null)
        ));
        return methodDefRaw(setterName, List.of(mapParam),
            List.of(assignFresh, returnThis()));
    }

    /** {@code Builder putEntry(K key, V value)} that puts into the existing map. */
    private JCMethodDecl singularMapPut(FieldSpec field) {
        String putName = "put" + capitalise(field.singularName);
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

    /** {@code Builder clearEntries()} that empties the underlying collection or map. */
    private JCMethodDecl singularClear(FieldSpec field) {
        String clearName = "clear" + capitalise(field.name);
        JCStatement clear = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("clear")),
            List.nil()
        ));
        return methodDefRaw(clearName, List.nil(), List.of(clear, returnThis()));
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
     * enclosing nested Builder's simple name so inherited this-chaining works
     * without qualification. The attached {@code @XContract} (when
     * {@code emitContracts = true}) is chosen by parameter arity: every
     * setter shape here returns {@code this} and mutates the builder.
     */
    private JCMethodDecl methodDefRaw(String methodName, List<JCVariableDecl> params, List<JCStatement> body) {
        JCModifiers mods = make.Modifiers(Flags.PUBLIC, thisReturnContract(params.size()));
        Name name = names.fromString(methodName);
        JCExpression returnType = make.Ident(names.fromString(ctx.builderName()));
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

    private String methodName(String fieldName, boolean forceBoolean) {
        String prefix = forceBoolean ? "is" : ctx.config().methodPrefix();
        if (prefix.isEmpty()) return fieldName;
        return prefix + capitalise(fieldName);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
