package dev.sbs.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCEnhancedForLoop;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.sbs.classbuilder.apt.FieldSpec;

/**
 * Self-typed variant of {@link FieldMutators}: emits setters whose return
 * type is the Builder type-parameter {@code B}, with {@code return self();}
 * for the trailing statement. Used by the SuperBuilder chain so subclass
 * builders see their own concrete type flowing through inherited setters.
 *
 * <p>Shape coverage now matches {@link FieldMutators} one-for-one: plain,
 * boolean zero-arg/typed pair plus optional {@code @Negate} inverse pair,
 * {@code Optional} nullable-raw/wrapped pair plus optional
 * {@code @Formattable} overload, {@code @Singular} collection
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

    SelfTypedSetters(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
    }

    /** Mirrors {@link FieldMutators#setters} but always with {@code return self();}. */
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

    // ------------------------------------------------------------------
    // Plain / boolean / array shapes
    // ------------------------------------------------------------------

    private JCMethodDecl plainSetter(FieldSpec field) {
        JCExpression fieldType = types.parseType(field.typeDisplay);
        return method(methodName(field.name, false), List.of(param(field.name, fieldType)),
            assignAndReturnSelf(field.name));
    }

    private JCMethodDecl arrayVarargs(FieldSpec field) {
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl p = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS),
            names.fromString(field.name),
            make.TypeArray(elemType),
            null
        );
        return method(methodName(field.name, false), List.of(p), assignAndReturnSelf(field.name));
    }

    private JCMethodDecl booleanZeroArg(FieldSpec field, String methodBase, boolean inverse) {
        String setterName = "is" + capitalise(methodBase);
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            make.Literal(!inverse)
        ));
        return method(setterName, List.nil(), List.of(assign, returnSelf()));
    }

    private JCMethodDecl booleanTyped(FieldSpec field, String methodBase, boolean inverse) {
        String setterName = "is" + capitalise(methodBase);
        JCExpression paramRef = make.Ident(names.fromString(methodBase));
        JCExpression value = inverse ? make.Unary(JCTree.Tag.NOT, paramRef) : paramRef;
        JCStatement assign = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            value
        ));
        JCVariableDecl p = param(methodBase, make.TypeIdent(TypeTag.BOOLEAN));
        return method(setterName, List.of(p), List.of(assign, returnSelf()));
    }

    // ------------------------------------------------------------------
    // Optional shapes
    // ------------------------------------------------------------------

    /** {@code B withX(T x)} - inner-type overload that wraps via {@code Optional.ofNullable}. */
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
        return method(setterName, List.of(p), List.of(make.Return(chained)));
    }

    /** {@code B withX(Optional<T> x)} - direct Optional assignment. */
    private JCMethodDecl optionalWrapped(FieldSpec field) {
        String setterName = methodName(field.name, false);
        JCExpression optType = make.TypeApply(
            types.qualIdent("java.util.Optional"),
            List.of(types.parseType(field.optionalInner))
        );
        return method(setterName, List.of(param(field.name, optType)), assignAndReturnSelf(field.name));
    }

    // ------------------------------------------------------------------
    // @Formattable shapes
    // ------------------------------------------------------------------

    /**
     * {@code B withName(@PrintFormat String format, Object... args)} that
     * stores {@code String.format(format, args)}. Falls back to
     * {@link dev.sbs.classbuilder.validate.Strings#formatNullable} when the
     * field carries {@code @Formattable(nullable = true)} or {@code @Nullable}.
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
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS, List.of(nullableAnnotation())),
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
                List.of(make.Literal(TypeTag.BOT, null))
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
        return method(setterName, List.of(formatParam, argsParam),
            List.of(assign, returnSelf()));
    }

    /**
     * {@code B withDescription(@PrintFormat @Nullable String format, Object... args)}
     * for an {@code Optional<String>} field; assigns
     * {@code Strings.formatNullable(format, args)} so the Optional wrapper
     * is preserved.
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
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS, List.of(nullableAnnotation())),
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
        return method(setterName, List.of(formatParam, argsParam),
            List.of(assign, returnSelf()));
    }

    // ------------------------------------------------------------------
    // @Singular shapes
    // ------------------------------------------------------------------

    /** {@code B withEntries(T... entries)} - reset-and-copy varargs replace. */
    private JCMethodDecl singularCollectionVarargsReplace(FieldSpec field) {
        String setterName = methodName(field.name, false);
        JCExpression elemType = types.parseType(field.collectionElement);
        JCVariableDecl varargs = make.VarDef(
            make.Modifiers(Flags.PARAMETER | Flags.VARARGS),
            names.fromString(field.name),
            make.TypeArray(elemType),
            null
        );
        String containerFqn = field.isSet ? "java.util.LinkedHashSet" : "java.util.ArrayList";
        JCStatement assignFresh = make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(field.name)),
            make.NewClass(null, List.nil(),
                make.TypeApply(types.qualIdent(containerFqn), List.nil()),
                List.nil(), null)
        ));
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
        return method(setterName, List.of(varargs),
            List.of(assignFresh, loop, returnSelf()));
    }

    /** {@code B withEntries(Iterable<T> entries)} - reset-and-forEach replace. */
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
        return method(setterName, List.of(iterableParam),
            List.of(assignFresh, forEach, returnSelf()));
    }

    /** {@code B addEntry(T entry)} - append one element to the existing collection. */
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
        return method(addName, List.of(entryParam), List.of(add, returnSelf()));
    }

    /** {@code B withEntries(Map<K, V> entries)} - replace with a fresh LinkedHashMap. */
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
        return method(setterName, List.of(mapParam),
            List.of(assignFresh, returnSelf()));
    }

    /** {@code B putEntry(K key, V value)} - put one entry into the existing map. */
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
        return method(putName, List.of(keyParam, valueParam),
            List.of(put, returnSelf()));
    }

    /** {@code B clearEntries()} - empty the underlying collection or map. */
    private JCMethodDecl singularClear(FieldSpec field) {
        String clearName = "clear" + capitalise(field.name);
        JCStatement clear = make.Exec(make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(field.name)),
                names.fromString("clear")),
            List.nil()
        ));
        return method(clearName, List.nil(), List.of(clear, returnSelf()));
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

    /** {@code return self();} - used in place of {@code return this;} under self-typed generics. */
    private JCStatement returnSelf() {
        return make.Return(make.Apply(List.nil(), make.Ident(names.fromString("self")), List.nil()));
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

    private JCMethodDecl method(String methodName, List<JCVariableDecl> params, List<JCStatement> body) {
        JCBlock block = make.Block(0, body);
        JCExpression returnType = make.Ident(names.fromString("B"));
        JCMethodDecl m = make.MethodDef(
            make.Modifiers(Flags.PUBLIC),
            names.fromString(methodName),
            returnType,
            List.nil(),
            params,
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(m);
        return m;
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
