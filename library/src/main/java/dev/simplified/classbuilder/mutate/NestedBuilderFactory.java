package dev.simplified.classbuilder.mutate;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.BuilderConfig;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import javax.lang.model.element.ElementKind;

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
        this.contracts = ctx.contracts();
    }

    JCClassDecl build() {
        ListBuffer<JCTree> defs = new ListBuffer<>();
        // Fields
        for (FieldSpec f : ctx.fields()) {
            JCVariableDecl decl = fieldMutators.fieldDecl(f);
            AstMarkers.markGenerated(decl, ctx.generated());
            defs.append(decl);
            JCVariableDecl marker = fieldMutators.replacedMarkerDecl(f);
            if (marker != null) {
                AstMarkers.markGenerated(marker, ctx.generated());
                defs.append(marker);
            }
        }
        // Setters
        for (FieldSpec f : ctx.fields()) {
            for (JCMethodDecl setter : fieldMutators.setters(f)) defs.append(setter);
        }
        // build()
        defs.append(buildMethod());
        // The builder's own constructor, written out rather than left implicit:
        // an implicit one takes the class's access, so a public builder class
        // published `new Target.Builder()` as a second entry point beside
        // builder(). Declaring it is the only way to narrow it.
        defs.append(builderConstructor());

        // Builder class visibility follows @ClassBuilder.access; always STATIC
        // because nested builders must not capture an enclosing this. Being
        // static is also why a generic target's type parameters have to be
        // re-declared here - the enclosing class's are out of scope.
        JCModifiers mods = make.Modifiers(ctx.accessFlag() | Flags.STATIC);
        JCClassDecl nested = make.ClassDef(
            mods,
            names.fromString(ctx.builderName()),
            ctx.typeParams(),
            null,
            List.nil(),
            defs.toList()
        );
        AstMarkers.markGenerated(nested, ctx.generated());
        return nested;
    }

    /**
     * Emits the builder's constructor at
     * {@link BuilderConfig#builderConstructorAccess()}.
     *
     * <p>Empty and no-arg in the ordinary case: every slot carries its own
     * initializer, including the retained defaults, so there is nothing for a
     * constructor to do beyond existing at the right visibility.
     *
     * <p>A seeded slot is the exception, and the only way in for it. It has no
     * setter and is declared {@code final}, so the constructor takes it as a
     * parameter and assigns it - which is what makes {@code builder(seed)} the
     * required entry point rather than a convenience beside a setter.
     */
    private JCMethodDecl builderConstructor() {
        ListBuffer<JCVariableDecl> params = new ListBuffer<>();
        ListBuffer<JCStatement> body = new ListBuffer<>();
        for (FieldSpec seed : ctx.seeds()) {
            params.append(make.VarDef(
                make.Modifiers(Flags.PARAMETER),
                names.fromString(seed.name),
                ctx.types().parseType(seed.typeDisplay),
                null
            ));
            body.append(make.Exec(make.Assign(
                make.Select(make.Ident(names._this), names.fromString(seed.name)),
                make.Ident(names.fromString(seed.name))
            )));
        }
        JCMethodDecl ctor = make.MethodDef(
            make.Modifiers(MutationContext.accessFlagFor(ctx.config().builderConstructorAccess())),
            names.init,
            null,
            List.nil(),
            params.toList(),
            List.nil(),
            make.Block(0, body.toList()),
            null
        );
        AstMarkers.markGenerated(ctor, ctx.generated());
        return ctor;
    }

    /**
     * Emits {@code public Target build() { Target t = new Target(f1, f2, ...);
     * (validate?) BuildFlagValidator.validate(t); return t; }}.
     * Honours {@link BuilderConfig#validate()} and
     * {@link BuilderConfig#factoryMethod()}.
     *
     * <p>Validation runs against the constructed target, not the Builder,
     * because {@code @BuildFlag} annotations live on the target class's
     * fields. The Builder's own fields are synthesised and unannotated, so
     * validating {@code this} was a no-op prior to this change.
     */
    private JCMethodDecl buildMethod() {
        ListBuffer<JCStatement> body = new ListBuffer<>();

        ListBuffer<JCExpression> args = new ListBuffer<>();
        for (FieldSpec f : ctx.fields()) {
            args.append(make.Ident(names.fromString(f.name)));
            // A collected instance default passes its replaced marker alongside
            // the container, matching the extra constructor parameter.
            if (ctx.isCollectedInstanceDefault(f)) {
                args.append(make.Ident(names.fromString(MutationContext.replacedMarker(f.name))));
            }
        }

        JCExpression instantiation;
        String factory = ctx.config().factoryMethod();
        String annotatedFactory = annotatedFactoryName();
        if (annotatedFactory != null) {
            // The annotated member IS what build() calls, and the slots are its
            // parameters - so factoryMethod has nothing left to redirect.
            instantiation = make.Apply(
                List.nil(),
                make.Select(make.Ident(names.fromString(ctx.targetSimpleName())),
                    names.fromString(annotatedFactory)),
                args.toList()
            );
        } else if (factory != null && !factory.isEmpty()) {
            instantiation = make.Apply(
                List.nil(),
                make.Select(make.Ident(names.fromString(ctx.targetSimpleName())), names.fromString(factory)),
                args.toList()
            );
        } else {
            // Target, or Target<K, V> on a generic target - the Builder's own
            // type parameters, which bind one-for-one with the target's.
            instantiation = make.NewClass(
                null,
                List.nil(),
                ctx.targetType(),
                args.toList(),
                null
            );
        }

        if (ctx.config().validate() && ctx.declaresBuildFlag()) {
            // Target t = new Target(...); BuildFlagValidator.validate(t); return t;
            JCExpression targetType = ctx.builtType();
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
        JCExpression returnType = ctx.builtType();
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
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * The name of the annotated {@code static} factory {@code build()} calls, or
     * {@code null} when it calls a constructor.
     *
     * <p>An annotated constructor answers {@code null} too: {@code new Target(..)}
     * is what builds it, which is the same expression an unannotated target
     * takes.
     */
    private String annotatedFactoryName() {
        if (!ctx.isExecutableTarget()) return null;
        var executable = ctx.executable();
        if (executable.getKind() != ElementKind.METHOD) return null;
        return executable.getSimpleName().toString();
    }

}
