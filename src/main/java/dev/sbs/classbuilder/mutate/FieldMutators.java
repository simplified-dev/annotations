package dev.sbs.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
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
 * mirroring the shape matrix from the sibling emitter: plain typed setter,
 * boolean zero-arg + typed pair, {@code Optional<T>} nullable-raw + wrapped
 * pair, collection-replace, and array varargs.
 *
 * <p>Higher-shape setters ({@code @Singular} add/put/clear, {@code @Negate}
 * inverse boolean pair, {@code @Formattable} {@code @PrintFormat} overload)
 * are intentionally deferred to a follow-up pass; the Phase 2 goal is a
 * round-trip-capable nested Builder, not full feature parity.
 */
final class FieldMutators {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;

    FieldMutators(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
    }

    /** Returns every setter the field should emit on the nested Builder. */
    List<JCMethodDecl> setters(FieldSpec field) {
        ListBuffer<JCMethodDecl> out = new ListBuffer<>();
        if (field.isBoolean) {
            out.append(booleanZeroArg(field, field.name, false));
            out.append(booleanTyped(field, field.name, false));
        } else if (field.isOptional) {
            out.append(optionalNullableRaw(field));
            out.append(optionalWrapped(field));
        } else if (field.isArray) {
            out.append(arrayVarargs(field));
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
     * without qualification.
     */
    private JCMethodDecl methodDefRaw(String methodName, List<JCVariableDecl> params, List<JCStatement> body) {
        JCModifiers mods = make.Modifiers(Flags.PUBLIC);
        Name name = names.fromString(methodName);
        JCExpression returnType = make.Ident(names.fromString(ctx.builderName()));
        JCBlock block = make.Block(0, body);
        JCMethodDecl method = make.MethodDef(mods, name, returnType, List.nil(), params, List.nil(), block, null);
        AstMarkers.markGenerated(method);
        return method;
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
