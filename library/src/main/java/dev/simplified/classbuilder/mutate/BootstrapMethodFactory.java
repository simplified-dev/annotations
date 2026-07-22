package dev.simplified.classbuilder.mutate;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

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

import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.tools.Diagnostic;
import java.util.Collection;

/**
 * Injects the three bootstrap methods onto the target type:
 * <ul>
 *   <li>static {@code Builder builder()} - returns a fresh {@code Builder}.</li>
 *   <li>static {@code Builder from(T)} - reads every field off an existing
 *       instance into a fresh {@code Builder}.</li>
 *   <li>instance {@code Builder mutate()} - seeds a fresh {@code Builder}
 *       inline from {@code this} (not by delegating to {@code from(this)}), so
 *       {@code generateFrom = false} suppresses {@code from(T)} without
 *       dangling this method.</li>
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
    private final Collection<FieldSpec> fromFields;
    private final ContractAnnotations contracts;

    BootstrapMethodFactory(MutationContext ctx, Messager messager) {
        this(ctx, messager, ctx.fields());
    }

    /**
     * @param fromFields the fields to populate in {@code from(T)}. For
     *        SuperBuilder subclasses this includes inherited fields so the
     *        subclass's {@code from()} reads the full state, not only its own
     *        slice. For standalone classes this is the same as
     *        {@link MutationContext#fields()}.
     */
    BootstrapMethodFactory(MutationContext ctx, Messager messager, Collection<FieldSpec> fromFields) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.messager = messager;
        this.isRecord = ctx.targetElement().getKind() == ElementKind.RECORD;
        this.fromFields = fromFields;
        this.contracts = ctx.contracts();
    }

    /** Appends whichever bootstrap methods are missing from the target. */
    void appendAll() {
        JCClassDecl target = ctx.target();
        var config = ctx.config();
        String builderMethod = config.builderMethodName();
        String fromMethod = config.fromMethodName();
        String mutateMethod = config.toBuilderMethodName();

        // Each bootstrap honours BOTH its opt-out gate and name non-emptiness.
        // An empty name is treated as "don't emit" per the annotation Javadoc.
        if (config.generateBuilder() && !builderMethod.isEmpty())
            appendIfAbsent(target, builderMethod, 0, this::builderFactory);
        if (config.generateFrom() && !fromMethod.isEmpty())
            appendIfAbsent(target, fromMethod, 1, this::fromFactory);
        if (config.generateMutate() && !mutateMethod.isEmpty())
            appendIfAbsent(target, mutateMethod, 0, this::mutateMethod);
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
        JCExpression newBuilder = make.NewClass(null, List.nil(), ctx.builderType(), List.nil(), null);
        JCBlock body = make.Block(0, List.of(make.Return(newBuilder)));

        // builder() constructs a fresh Builder; "-> new" mirrors build().
        // On a generic target the method declares its own copies of the type
        // parameters - it is static, so the class's are not in scope, and the
        // caller infers them from the assignment context.
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(ctx.accessFlag() | Flags.STATIC, contracts.newReturnNullary()),
            names.fromString(ctx.config().builderMethodName()),
            ctx.builderType(),
            ctx.typeParams(),
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
        JCVariableDecl instanceParam = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("instance"),
            ctx.targetType(),
            null
        );

        ListBuffer<JCStatement> body = new ListBuffer<>();
        // Builder b = new Builder();
        body.append(make.VarDef(
            make.Modifiers(0),
            names.fromString("b"),
            ctx.builderType(),
            make.NewClass(null, List.nil(), ctx.builderType(), List.nil(), null)
        ));
        // Use the builder's public setters so the statement works identically
        // on standalone concrete classes AND on SuperBuilder subclasses whose
        // parent fields are inaccessible via direct field access.
        for (FieldSpec f : fromFields) {
            body.append(make.Exec(make.Apply(
                List.nil(),
                make.Select(make.Ident(names.fromString("b")), names.fromString(setterName(f))),
                List.of(readFrom(f, make.Ident(names.fromString("instance"))))
            )));
        }
        body.append(make.Return(make.Ident(names.fromString("b"))));

        // from(T) reads the target instance without mutating it; "_ -> new"
        // with pure=true matches BuilderEmitter.emitFromMethod.
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(ctx.accessFlag() | Flags.STATIC, contracts.newReturnPureUnary()),
            names.fromString(ctx.config().fromMethodName()),
            ctx.builderType(),
            ctx.typeParams(),
            List.of(instanceParam),
            List.nil(),
            make.Block(0, body.toList()),
            null
        );
        AstMarkers.markGenerated(method);
        return method;
    }

    /**
     * Builds the accessor expression that reads a field off {@code receiver}.
     * Shared by {@code from(T)} (receiver = the {@code instance} parameter) and
     * {@code mutate()} (receiver = {@code this}). Honours {@link
     * FieldSpec#obtainViaMethod} / {@link FieldSpec#obtainViaField} / {@link
     * FieldSpec#obtainViaStatic}; otherwise uses the record/interface {@code
     * name()} form, the {@code isX()} form for booleans, or {@code getX()}.
     * Wraps mutable collection reads in defensive copies. {@code receiver} is
     * consumed exactly once, so callers must pass a fresh node per field.
     */
    private JCExpression readFrom(FieldSpec f, JCExpression receiver) {
        JCExpression raw;
        if (f.obtainViaStatic && f.obtainViaMethod != null) {
            raw = make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString(ctx.targetSimpleName())),
                    names.fromString(f.obtainViaMethod)),
                List.of(receiver));
        } else if (f.obtainViaMethod != null) {
            raw = make.Apply(List.nil(),
                make.Select(receiver, names.fromString(f.obtainViaMethod)),
                List.nil());
        } else if (f.obtainViaField != null) {
            raw = make.Select(receiver, names.fromString(f.obtainViaField));
        } else if (isRecord) {
            raw = make.Apply(List.nil(),
                make.Select(receiver, names.fromString(f.name)),
                List.nil());
        } else if (f.isBoolean) {
            raw = make.Apply(List.nil(),
                make.Select(receiver, names.fromString("is" + capitalise(f.name))),
                List.nil());
        } else {
            raw = make.Apply(List.nil(),
                make.Select(receiver, names.fromString("get" + capitalise(f.name))),
                List.nil());
        }
        return wrapDefensiveCopy(f, raw);
    }

    private JCExpression wrapDefensiveCopy(FieldSpec f, JCExpression raw) {
        // A custom container can't be rebuilt with new java.util.ArrayList<>(...)
        // - that is not assignable to the field's own type. Seed the builder
        // with the source reference directly (no defensive copy); the collector
        // setter, if any, copies on write.
        if (f.isCustomContainer) return raw;
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
        ListBuffer<JCStatement> body = new ListBuffer<>();
        // Builder b = new Builder();
        // Being an instance method, this one needs no type parameters of its
        // own on a generic target - the class's are already in scope.
        body.append(make.VarDef(
            make.Modifiers(0),
            names.fromString("b"),
            ctx.builderType(),
            make.NewClass(null, List.nil(), ctx.builderType(), List.nil(), null)
        ));
        // Seed each field directly off `this` through the builder's public
        // setters - the SAME field set (fromFields, inherited fields included
        // for SuperBuilder subclasses) and accessor logic from(T) uses. Inlined
        // here rather than delegating to from(this) so generateFrom = false can
        // suppress the static factory without dangling this method.
        for (FieldSpec f : fromFields) {
            body.append(make.Exec(make.Apply(
                List.nil(),
                make.Select(make.Ident(names.fromString("b")), names.fromString(setterName(f))),
                List.of(readFrom(f, make.Ident(names._this)))
            )));
        }
        body.append(make.Return(make.Ident(names.fromString("b"))));

        // mutate() reads this implicitly and returns a fresh Builder; "-> new"
        // captures the fresh-return shape without pure (the implicit this
        // read is treated like a parameterless factory from a caller POV).
        JCModifiers mods = make.Modifiers(ctx.accessFlag(), contracts.newReturnNullary());
        JCMethodDecl method = make.MethodDef(
            mods,
            names.fromString(ctx.config().toBuilderMethodName()),
            ctx.builderType(),
            List.nil(),
            List.nil(),
            List.nil(),
            make.Block(0, body.toList()),
            null
        );
        AstMarkers.markGenerated(method);
        return method;
    }

    /**
     * Resolves the setter method name for a field. Booleans go through the same
     * {@code set} role as every other field kind, so seeding needs no special
     * case; the zero-arg {@code flag} setter takes no argument and is never the
     * one called here.
     */
    private String setterName(FieldSpec f) {
        return ctx.config().naming().setName(f.name);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
