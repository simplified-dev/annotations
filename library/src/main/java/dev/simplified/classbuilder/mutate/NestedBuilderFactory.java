package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.FieldSpec;

/**
 * Builds the nested {@code Builder} {@link JCClassDecl} that gets appended to
 * the target class's {@code defs} list. Handles fields, setters via
 * {@link FieldMutators}, and the terminal {@code build()} method.
 */
final class NestedBuilderFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final FieldMutators fieldMutators;
    private final ContractAnnotations contracts;

    NestedBuilderFactory(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.fieldMutators = new FieldMutators(ctx);
        this.contracts = new ContractAnnotations(ctx);
    }

    JCClassDecl build() {
        ListBuffer<JCTree> defs = new ListBuffer<>();
        // Fields
        for (FieldSpec f : ctx.fields()) {
            JCVariableDecl decl = fieldMutators.fieldDecl(f);
            AstMarkers.markGenerated(decl);
            defs.append(decl);
        }
        // Setters
        for (FieldSpec f : ctx.fields()) {
            for (JCMethodDecl setter : fieldMutators.setters(f)) defs.append(setter);
        }
        // build()
        defs.append(buildMethod());

        // Builder class visibility follows @ClassBuilder.access; always STATIC
        // because nested builders must not capture an enclosing this.
        JCModifiers mods = make.Modifiers(ctx.accessFlag() | Flags.STATIC);
        JCClassDecl nested = make.ClassDef(
            mods,
            names.fromString(ctx.builderName()),
            List.nil(),
            null,
            List.nil(),
            defs.toList()
        );
        AstMarkers.markGenerated(nested);
        return nested;
    }

    /**
     * Emits {@code public Target build() { Target t = new Target(f1, f2, ...);
     * (validate?) BuildFlagValidator.validate(t); return t; }}.
     * Honours {@link dev.simplified.classbuilder.apt.BuilderConfig#validate()} and
     * {@link dev.simplified.classbuilder.apt.BuilderConfig#factoryMethod()}.
     *
     * <p>Validation runs against the constructed target, not the Builder,
     * because {@code @BuildRule} annotations live on the target class's
     * fields. The Builder's own fields are synthesised and unannotated, so
     * validating {@code this} was a no-op prior to this change.
     */
    private JCMethodDecl buildMethod() {
        ListBuffer<JCStatement> body = new ListBuffer<>();

        ListBuffer<JCExpression> args = new ListBuffer<>();
        for (FieldSpec f : ctx.fields()) args.append(make.Ident(names.fromString(f.name)));

        JCExpression instantiation;
        String factory = ctx.config().factoryMethod();
        if (factory != null && !factory.isEmpty()) {
            instantiation = make.Apply(
                List.nil(),
                make.Select(make.Ident(names.fromString(ctx.targetSimpleName())), names.fromString(factory)),
                args.toList()
            );
        } else {
            instantiation = make.NewClass(
                null,
                List.nil(),
                make.Ident(names.fromString(ctx.targetSimpleName())),
                args.toList(),
                null
            );
        }

        if (ctx.config().validate()) {
            // Target t = new Target(...); BuildFlagValidator.validate(t); return t;
            JCExpression targetType = make.Ident(names.fromString(ctx.targetSimpleName()));
            JCVariableDecl targetVar = make.VarDef(
                make.Modifiers(Flags.FINAL),
                names.fromString("$result"),
                targetType,
                instantiation
            );
            body.append(targetVar);
            body.append(make.Exec(make.Apply(
                List.nil(),
                make.Select(
                    ctx.types().qualIdent("dev.simplified.classbuilder.validate.BuildFlagValidator"),
                    names.fromString("validate")
                ),
                List.of(make.Ident(names.fromString("$result")))
            )));
            body.append(make.Return(make.Ident(names.fromString("$result"))));
        } else {
            body.append(make.Return(instantiation));
        }

        JCBlock block = make.Block(0, body.toList());
        JCExpression returnType = make.Ident(names.fromString(ctx.targetSimpleName()));
        // build() always returns a fresh target instance; "-> new" without
        // mutates or pure matches BuilderEmitter.emitBuildMethod.
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PUBLIC, contracts.newReturnNullary()),
            names.fromString(ctx.config().buildMethodName()),
            returnType,
            List.nil(),
            List.nil(),
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(method);
        return method;
    }

}
