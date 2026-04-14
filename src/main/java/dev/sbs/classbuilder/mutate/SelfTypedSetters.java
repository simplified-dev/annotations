package dev.sbs.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
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
 * <p>Shape coverage is the Phase 4 subset - plain, boolean pair, Optional
 * dual, array varargs - mirroring {@link FieldMutators}. The higher shapes
 * ({@code @Singular}, {@code @Formattable}, {@code @Negate}) remain on the
 * sibling path for now.
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

    private JCMethodDecl optionalWrapped(FieldSpec field) {
        String setterName = methodName(field.name, false);
        JCExpression optType = make.TypeApply(
            types.qualIdent("java.util.Optional"),
            List.of(types.parseType(field.optionalInner))
        );
        return method(setterName, List.of(param(field.name, optType)), assignAndReturnSelf(field.name));
    }

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
