package dev.sbs.classbuilder.mutate;

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
import dev.sbs.classbuilder.apt.FieldSpec;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.tools.Diagnostic;

/**
 * Injects the three bootstrap methods onto the target type:
 * <ul>
 *   <li>static {@code Builder builder()} - returns a fresh {@code Builder}.</li>
 *   <li>static {@code Builder from(T)} - reads every field off an existing
 *       instance into a fresh {@code Builder}.</li>
 *   <li>instance {@code Builder mutate()} - delegates to {@code from(this)}.</li>
 * </ul>
 *
 * <p>Collision policy: if the target already declares a method with the
 * matching name and arity, skip the injection and emit a {@link
 * Diagnostic.Kind#NOTE} - the user's hand-written method wins. This mirrors
 * Lombok's behaviour and lets consumers migrate off the old
 * hand-rolled-bootstrap pattern without coordinated edits.
 */
final class BootstrapMethodFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final Messager messager;
    private final boolean isRecord;

    BootstrapMethodFactory(MutationContext ctx, Messager messager) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.messager = messager;
        this.isRecord = ctx.targetElement().getKind() == ElementKind.RECORD;
    }

    /** Appends whichever bootstrap methods are missing from the target. */
    void appendAll() {
        JCClassDecl target = ctx.target();
        String builderMethod = ctx.config().builderMethodName();
        String fromMethod = ctx.config().fromMethodName();
        String mutateMethod = ctx.config().toBuilderMethodName();

        if (!builderMethod.isEmpty()) appendIfAbsent(target, builderMethod, 0, this::builderFactory);
        if (!fromMethod.isEmpty()) appendIfAbsent(target, fromMethod, 1, this::fromFactory);
        if (!mutateMethod.isEmpty()) appendIfAbsent(target, mutateMethod, 0, this::mutateMethod);
    }

    /**
     * Appends a method produced by {@code supplier} unless the target already
     * declares a method with the same {@code name} and {@code arity}. Emits a
     * {@link Diagnostic.Kind#NOTE} on skip so the note is discoverable but
     * does not pollute warning-as-error builds.
     */
    private void appendIfAbsent(JCClassDecl target, String name, int arity,
                                java.util.function.Supplier<JCMethodDecl> supplier) {
        if (hasMethod(target, name, arity)) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@ClassBuilder skipped bootstrap '" + name + "' - target already declares "
                    + name + "/" + arity,
                ctx.targetElement());
            return;
        }
        ctx.bridge().compat().appendDef(target, supplier.get());
    }

    private static boolean hasMethod(JCClassDecl target, String name, int arity) {
        for (JCTree def : target.defs) {
            if (def instanceof JCMethodDecl m
                && m.name.toString().equals(name)
                && m.params.size() == arity) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // builder()
    // ------------------------------------------------------------------

    private JCMethodDecl builderFactory() {
        JCExpression builderType = make.Ident(names.fromString(ctx.builderName()));
        JCExpression newBuilder = make.NewClass(null, List.nil(), builderType, List.nil(), null);
        JCBlock body = make.Block(0, List.of(make.Return(newBuilder)));

        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PUBLIC | Flags.STATIC),
            names.fromString(ctx.config().builderMethodName()),
            make.Ident(names.fromString(ctx.builderName())),
            List.nil(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method);
        return method;
    }

    // ------------------------------------------------------------------
    // from(Target instance)
    // ------------------------------------------------------------------

    private JCMethodDecl fromFactory() {
        JCExpression targetType = make.Ident(names.fromString(ctx.targetSimpleName()));
        JCExpression builderType = make.Ident(names.fromString(ctx.builderName()));
        JCVariableDecl instanceParam = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("instance"),
            targetType,
            null
        );

        ListBuffer<JCStatement> body = new ListBuffer<>();
        // Builder b = new Builder();
        body.append(make.VarDef(
            make.Modifiers(0),
            names.fromString("b"),
            builderType,
            make.NewClass(null, List.nil(), make.Ident(names.fromString(ctx.builderName())), List.nil(), null)
        ));
        // b.field = <read from instance>;
        for (FieldSpec f : ctx.fields()) {
            JCExpression assign = make.Assign(
                make.Select(make.Ident(names.fromString("b")), names.fromString(f.name)),
                readFromInstance(f)
            );
            body.append(make.Exec(assign));
        }
        body.append(make.Return(make.Ident(names.fromString("b"))));

        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PUBLIC | Flags.STATIC),
            names.fromString(ctx.config().fromMethodName()),
            builderType,
            List.nil(),
            List.of(instanceParam),
            List.nil(),
            make.Block(0, body.toList()),
            null
        );
        AstMarkers.markGenerated(method);
        return method;
    }

    /**
     * Builds the accessor expression used in {@code from(T)} for a given field.
     * Honours {@link FieldSpec#obtainViaMethod} / {@link FieldSpec#obtainViaField}
     * / {@link FieldSpec#obtainViaStatic}; otherwise uses the record/interface
     * {@code name()} form, the {@code isX()} form for booleans, or {@code getX()}.
     * Wraps mutable collection reads in defensive copies.
     */
    private JCExpression readFromInstance(FieldSpec f) {
        JCExpression raw;
        if (f.obtainViaStatic && f.obtainViaMethod != null) {
            raw = make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString(ctx.targetSimpleName())),
                    names.fromString(f.obtainViaMethod)),
                List.of(make.Ident(names.fromString("instance"))));
        } else if (f.obtainViaMethod != null) {
            raw = make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString("instance")),
                    names.fromString(f.obtainViaMethod)),
                List.nil());
        } else if (f.obtainViaField != null) {
            raw = make.Select(make.Ident(names.fromString("instance")),
                names.fromString(f.obtainViaField));
        } else if (isRecord) {
            raw = make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString("instance")), names.fromString(f.name)),
                List.nil());
        } else if (f.isBoolean) {
            raw = make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString("instance")),
                    names.fromString("is" + capitalise(f.name))),
                List.nil());
        } else {
            raw = make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString("instance")),
                    names.fromString("get" + capitalise(f.name))),
                List.nil());
        }
        return wrapDefensiveCopy(f, raw);
    }

    private JCExpression wrapDefensiveCopy(FieldSpec f, JCExpression raw) {
        if (f.isMap) return newCollectionCopy("java.util.LinkedHashMap", raw);
        if (f.isSet) return newCollectionCopy("java.util.LinkedHashSet", raw);
        if (f.isListLike) return newCollectionCopy("java.util.ArrayList", raw);
        return raw;
    }

    private JCExpression newCollectionCopy(String fqn, JCExpression source) {
        return make.NewClass(
            null,
            List.nil(),
            make.TypeApply(ctx.types().qualIdent(fqn), List.nil()),
            List.of(source),
            null
        );
    }

    // ------------------------------------------------------------------
    // mutate()
    // ------------------------------------------------------------------

    private JCMethodDecl mutateMethod() {
        JCExpression builderType = make.Ident(names.fromString(ctx.builderName()));
        // return <TargetName>.<fromMethod>(this);
        JCExpression fromCall = make.Apply(
            List.nil(),
            make.Select(make.Ident(names.fromString(ctx.targetSimpleName())),
                names.fromString(ctx.config().fromMethodName())),
            List.of(make.Ident(names._this))
        );
        JCBlock body = make.Block(0, List.of(make.Return(fromCall)));

        JCModifiers mods = make.Modifiers(Flags.PUBLIC);
        JCMethodDecl method = make.MethodDef(
            mods,
            names.fromString(ctx.config().toBuilderMethodName()),
            builderType,
            List.nil(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method);
        return method;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
