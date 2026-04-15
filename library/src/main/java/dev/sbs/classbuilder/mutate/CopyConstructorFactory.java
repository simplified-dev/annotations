package dev.sbs.classbuilder.mutate;

import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Flags;
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
 * Produces the copy constructor used by the SuperBuilder chain:
 *
 * <pre>{@code
 * // abstract Target, no annotated super:
 * protected Target(Builder<?, ?> b) { this.f1 = b.f1; this.f2 = b.f2; ... }
 *
 * // concrete Target that extends an annotated super:
 * protected Target(Builder b) { super(b); this.ownField = b.ownField; ... }
 * }</pre>
 *
 * <p>Fields are assigned in declaration order. Nested {@code Builder.field}
 * access is legal here because {@code Builder} is declared inside
 * {@code Target}, so private members are reachable from the enclosing class.
 */
final class CopyConstructorFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;

    CopyConstructorFactory(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
    }

    /**
     * Builds the copy constructor for an abstract target at the root of a
     * SuperBuilder chain. Parameter type is the wildcard form
     * {@code Builder<?, ?>} so the constructor accepts any subclass builder.
     */
    JCMethodDecl abstractRoot() {
        JCExpression builderWild = make.TypeApply(
            make.Ident(names.fromString(ctx.builderName())),
            List.of(make.Wildcard(make.TypeBoundKind(BoundKind.UNBOUND), null),
                make.Wildcard(make.TypeBoundKind(BoundKind.UNBOUND), null))
        );
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("b"),
            builderWild,
            null
        );
        ListBuffer<JCStatement> body = new ListBuffer<>();
        for (FieldSpec f : ctx.fields()) body.append(assignFromBuilder(f));
        return buildCtor(List.of(param), body.toList(), Flags.PROTECTED);
    }

    /**
     * Builds the copy constructor for a concrete target in a SuperBuilder chain
     * whose direct super is annotated. Calls {@code super(b)} first to let the
     * parent drain the shared fields, then copies this type's own fields.
     */
    JCMethodDecl concreteLink() {
        JCExpression builderType = make.Ident(names.fromString(ctx.builderName()));
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("b"),
            builderType,
            null
        );
        ListBuffer<JCStatement> body = new ListBuffer<>();
        // super(b);
        body.append(make.Exec(make.Apply(
            List.nil(),
            make.Ident(names._super),
            List.of(make.Ident(names.fromString("b")))
        )));
        for (FieldSpec f : ctx.fields()) body.append(assignFromBuilder(f));
        return buildCtor(List.of(param), body.toList(), Flags.PROTECTED);
    }

    private JCStatement assignFromBuilder(FieldSpec f) {
        return make.Exec(make.Assign(
            make.Select(make.Ident(names._this), names.fromString(f.name)),
            make.Select(make.Ident(names.fromString("b")), names.fromString(f.name))
        ));
    }

    private JCMethodDecl buildCtor(List<JCVariableDecl> params, List<JCStatement> body, long modifiers) {
        JCBlock block = make.Block(0, body);
        // Javac spells the constructor name as <init>.
        JCMethodDecl ctor = make.MethodDef(
            make.Modifiers(modifiers),
            names.init,
            null,
            List.nil(),
            params,
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(ctor);
        return ctor;
    }

    /**
     * Detects a hand-written constructor with a single {@code Builder}-typed
     * parameter (by simple name) so we can respect the user's version.
     */
    static boolean hasCopyConstructor(com.sun.tools.javac.tree.JCTree.JCClassDecl target, String builderSimpleName) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl m)) continue;
            if (!m.name.toString().equals("<init>")) continue;
            if (m.params.size() != 1) continue;
            String paramType = m.params.head.vartype.toString();
            if (paramType.equals(builderSimpleName)) return true;
            if (paramType.startsWith(builderSimpleName + "<")) return true;
        }
        return false;
    }

}
