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
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.lazy.mutate.LazyFieldMutator;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Injects the three bootstrap methods onto the target type:
 * <ul>
 *   <li>static {@code Builder builder()} - returns a fresh {@code Builder}.</li>
 *   <li>static {@code Builder from(T)} - reads every field off an existing
 *       instance into a fresh {@code Builder}.</li>
 *   <li>instance {@code Builder mutate()} - seeds a fresh {@code Builder}
 *       inline from {@code this} (not by delegating to {@code from(this)}), so
 *       {@code @BuilderNames(from = NONE)} suppresses {@code from(T)} without
 *       dangling this method.</li>
 * </ul>
 *
 * <p>Collision policy: if the target already declares a method that would
 * collide, skip the injection and emit a {@link Diagnostic.Kind#NOTE} - the
 * user's hand-written method wins. This mirrors Lombok's behaviour and lets
 * consumers migrate off the old hand-rolled-bootstrap pattern without
 * coordinated edits. What counts as a collision is
 * {@link BootstrapCollisions}, which the interface path shares.
 */
final class BootstrapMethodFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final Messager messager;
    private final boolean isRecord;
    private final Collection<FieldSpec> fromFields;
    private final ContractAnnotations contracts;

    private final @Nullable JCClassDecl mergedInto;

    BootstrapMethodFactory(MutationContext ctx, Messager messager) {
        this(ctx, messager, ctx.fields(), null);
    }

    /**
     * @param fromFields the fields to populate in {@code from(T)}. For
     *        SuperBuilder subclasses this includes inherited fields so the
     *        subclass's {@code from()} reads the full state, not only its own
     *        slice. For standalone classes this is the same as
     *        {@link MutationContext#fields()}.
     */
    BootstrapMethodFactory(MutationContext ctx, Messager messager, Collection<FieldSpec> fromFields) {
        this(ctx, messager, fromFields, null);
    }

    /**
     * @param fromFields the fields to populate in {@code from(T)}
     * @param mergedInto the builder the author declared and the generated members
     *        were appended to, or {@code null} when the builder was synthesised.
     *        Every entry point instantiates the builder, and an author's own
     *        class is the one case where there may be no constructor to do it
     *        with
     */
    BootstrapMethodFactory(MutationContext ctx, Messager messager, Collection<FieldSpec> fromFields,
                           @Nullable JCClassDecl mergedInto) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.messager = messager;
        this.isRecord = ctx.targetElement().getKind() == ElementKind.RECORD;
        this.fromFields = fromFields;
        this.contracts = ctx.contracts();
        this.mergedInto = mergedInto;
    }

    /**
     * Whether the builder the entry points would instantiate has a constructor
     * they can call.
     *
     * <p>Only ever false for a merged builder: a synthesised one is given the
     * constructor it needs. The author's is theirs throughout - javac's own
     * default included - and so is one a constructor annotation written on it
     * appended in the constructor pass, which is in the tree by now; a class
     * where none of those is one javac would call with the seeds leaves the
     * entry points with nothing to call, and they are skipped with a note
     * rather than emitted onto a line javac rejects - and so does one whose
     * selected constructor declares a throws clause that may name a checked
     * exception, which the entry points call with nothing to handle it. The
     * decision is {@link DeclaredBuilderShape#instantiable}, which the editor
     * asks of the same parameter types read out of PSI.
     *
     * @return whether the entry points can be emitted
     */
    private boolean builderCanBeInstantiated() {
        if (mergedInto == null) return true;
        return DeclaredBuilderShape.instantiable(constructorSignatures(false), constructorSignatures(true),
            seedTypes(), DeclaredBuilderMerge.declaredParameterNames(mergedInto));
    }

    /**
     * The parameter types of each constructor the merged builder declares, as
     * written.
     *
     * @param callableOnly whether to read only the ones whose throws clause
     *     {@link DeclaredBuilderShape#throwsNothingChecked} accepts
     * @return each constructor's parameter types, in declaration order
     */
    private java.util.List<java.util.List<String>> constructorSignatures(boolean callableOnly) {
        java.util.List<java.util.List<String>> signatures = new ArrayList<>();
        for (JCTree def : mergedInto.defs) {
            if (!(def instanceof JCMethodDecl method)) continue;
            if (!method.name.contentEquals("<init>")) continue;
            // javac's own default is in the tree by now; it is what a class
            // declaring nothing falls back to, not a constructor the author wrote.
            if ((method.mods.flags & Flags.GENERATEDCONSTR) != 0) continue;
            if (callableOnly && !throwsNothingChecked(method)) continue;
            java.util.List<String> types = new ArrayList<>(method.params.size());
            for (JCVariableDecl parameter : method.params)
                types.add(parameter.vartype == null ? "" : parameter.vartype.toString());
            signatures.add(types);
        }
        return signatures;
    }

    /**
     * Whether a constructor's throws clause names only unchecked exception
     * types, as {@link DeclaredBuilderShape#throwsNothingChecked} decides it,
     * each name it does not list answered from the element model.
     *
     * <p>Each name is read as the type the constructor's symbol throws in its
     * position - or, with no symbol, as the type attributed to the written
     * name - and counted unchecked where that is a subtype of
     * {@link RuntimeException} or {@link Error}. A name that resolved to
     * nothing is an error type, a subtype of neither, and stays checked.
     *
     * @param constructor the author's constructor
     * @return whether the entry points can call it with nothing to handle what it throws
     */
    private boolean throwsNothingChecked(JCMethodDecl constructor) {
        java.util.List<String> names = new ArrayList<>();
        Map<String, Boolean> unchecked = new HashMap<>();
        if (constructor.thrown != null) {
            java.util.List<? extends TypeMirror> resolved = constructor.sym == null
                ? null
                : constructor.sym.getThrownTypes();
            int index = 0;
            for (JCExpression thrown : constructor.thrown) {
                TypeMirror type = resolved != null && index < resolved.size() ? resolved.get(index) : thrown.type;
                String name = thrown.toString();
                names.add(name);
                unchecked.merge(name, isUnchecked(type), Boolean::logicalAnd);
                index++;
            }
        }
        return DeclaredBuilderShape.throwsNothingChecked(names, name -> unchecked.getOrDefault(name, false));
    }

    /**
     * Whether a thrown type is a subtype of {@link RuntimeException} or
     * {@link Error}.
     *
     * @param type the thrown type, or {@code null} when it was never attributed
     * @return whether a call throwing it needs nothing to handle it
     */
    private boolean isUnchecked(@Nullable TypeMirror type) {
        if (type == null || (type.getKind() != TypeKind.DECLARED && type.getKind() != TypeKind.TYPEVAR))
            return false;
        Types types = ctx.bridge().processingEnvironment().getTypeUtils();
        Elements elements = ctx.bridge().processingEnvironment().getElementUtils();
        for (Class<?> root : java.util.List.of(RuntimeException.class, Error.class)) {
            TypeElement element = elements.getTypeElement(root.getName());
            if (element != null && types.isSubtype(types.erasure(type), element.asType())) return true;
        }
        return false;
    }

    /**
     * Reports each setter the merge left out for an author method covering it
     * with another parameterisation of the same generic type, which the copy
     * entry points about to be emitted would pass the slot's own type.
     *
     * <p>The rule and its wording are
     * {@link DeclaredBuilderShape#setterWithOtherTypeArguments}, which the
     * editor's inspection asks of the same types read out of PSI and which
     * judges only the setter shape the copy entry points call - the one
     * {@link #fromFactory} and {@link #mutateMethod} pass each slot to; what is
     * known only here is which copy entry points are emitted.
     *
     * @param copyEntryPoints the names of the copy entry points about to be emitted
     */
    private void rejectCoveredSetters(java.util.List<String> copyEntryPoints) {
        if (mergedInto == null || copyEntryPoints.isEmpty()) return;
        java.util.List<String> typeParameters = DeclaredBuilderMerge.declaredParameterNames(mergedInto);
        for (DeclaredBuilderMerge.CoveredSetter covered : ctx.coveredSetters()) {
            String message = DeclaredBuilderShape.setterWithOtherTypeArguments(mergedInto.name.toString(),
                covered.name(), covered.shape(), covered.writtenTypes(), covered.generatedTypes(), typeParameters,
                copyEntryPoints);
            if (message != null) messager.printMessage(Diagnostic.Kind.ERROR, message, ctx.targetElement());
        }
    }

    /** The type of each seed the entry points pass, in parameter order. */
    private java.util.List<String> seedTypes() {
        java.util.List<String> types = new ArrayList<>();
        for (FieldSpec seed : ctx.seeds()) types.add(seed.typeDisplay);
        return types;
    }

    /**
     * The note for entry points skipped because the merged builder has no
     * constructor they can call, in the text the editor's weak warning shows.
     *
     * @return the note text naming only the entry points this path emits, or
     *     {@code null} when it emits none
     */
    private @Nullable String uninstantiableNote() {
        java.util.List<String> seedNames = new ArrayList<>();
        for (FieldSpec seed : ctx.seeds()) seedNames.add(seed.name);
        boolean throwsClause = DeclaredBuilderShape.skippedForAThrowsClause(constructorSignatures(false),
            constructorSignatures(true), seedTypes(), DeclaredBuilderMerge.declaredParameterNames(mergedInto));
        return DeclaredBuilderShape.entryPointsSkipped(mergedInto.name.toString(), ctx.config().names(),
            ctx.isExecutableTarget(), seedNames, throwsClause);
    }

    /** Appends whichever bootstrap methods are missing from the target. */
    void appendAll() {
        JCClassDecl target = ctx.target();
        var config = ctx.config();
        String builderMethod = config.builderMethodName();
        String fromMethod = config.fromMethodName();
        String mutateMethod = config.toBuilderMethodName();

        // An empty name is the single opt-out signal: BuilderScheme resolves a
        // @BuilderNames(x = NONE) to the empty string, so there is no second
        // generate-flag to consult.
        int seeds = ctx.seeds().size();
        if (!builderCanBeInstantiated()) {
            // On the member the annotation is written on, where the editor's
            // weak warning sits: the annotated constructor or factory on that
            // path, the type on every other.
            Element anchor = ctx.isExecutableTarget() ? ctx.executable() : ctx.targetElement();
            String note = uninstantiableNote();
            if (note != null) messager.printMessage(Diagnostic.Kind.NOTE, note, anchor);
            return;
        }
        if (!builderMethod.isEmpty())
            appendUnless(target, builderMethod, "/" + seeds,
                BootstrapCollisions.declaresArity(target, builderMethod, seeds), this::builderFactory);

        // from(T) and mutate() read every slot back off a built instance, and on
        // the executable path there is nothing to read them through: a slot is a
        // parameter, and no mapping from one to an accessor exists to be guessed
        // at. Both are suppressed rather than emitted against a guess.
        if (ctx.isExecutableTarget()) return;

        boolean fromDeclared = BootstrapCollisions.declaresCopyFactory(ctx.targetElement(), fromMethod);
        boolean mutateDeclared = BootstrapCollisions.declaresNullary(target, mutateMethod);
        java.util.List<String> copyEntryPoints = new ArrayList<>();
        if (!fromMethod.isEmpty() && !fromDeclared) copyEntryPoints.add(fromMethod);
        if (!mutateMethod.isEmpty() && !mutateDeclared) copyEntryPoints.add(mutateMethod);
        rejectCoveredSetters(copyEntryPoints);

        if (!fromMethod.isEmpty())
            appendUnless(target, fromMethod, "(" + ctx.targetSimpleName() + ")", fromDeclared,
                this::fromFactory);
        if (!mutateMethod.isEmpty())
            appendUnless(target, mutateMethod, "/0", mutateDeclared, this::mutateMethod);
    }

    /**
     * Appends a method produced by {@code supplier} unless the target already
     * declares one that collides with it. Emits a {@link Diagnostic.Kind#NOTE}
     * on skip so the note is discoverable but does not pollute
     * warning-as-error builds, on the member the annotation is written on - the
     * annotated constructor or factory on that path, the type on every other.
     *
     * @param target the target's tree
     * @param name the bootstrap name
     * @param signature how the collided-with signature reads in the note
     * @param collides whether the author already declares it, per
     *                 {@link BootstrapCollisions}
     * @param supplier builds the method to append
     */
    private void appendUnless(JCClassDecl target, String name, String signature, boolean collides,
                              java.util.function.Supplier<JCMethodDecl> supplier) {
        if (collides) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@ClassBuilder skipped bootstrap '" + name + "' - target already declares "
                    + name + signature,
                ctx.isExecutableTarget() ? ctx.executable() : ctx.targetElement());
            return;
        }
        ctx.bridge().compat().appendDef(target, supplier.get());
    }

    // ------------------------------------------------------------------
    // builder()
    // ------------------------------------------------------------------

    private JCMethodDecl builderFactory() {
        // A seeded slot has no setter, so the value has to enter here and be
        // forwarded to the builder's constructor, which is the only thing that
        // can assign a final slot.
        ListBuffer<JCVariableDecl> params = new ListBuffer<>();
        ListBuffer<JCExpression> args = new ListBuffer<>();
        for (FieldSpec seed : ctx.seeds()) {
            params.append(make.VarDef(
                make.Modifiers(Flags.PARAMETER),
                names.fromString(seed.name),
                ctx.types().parseType(seed.typeDisplay),
                null
            ));
            args.append(make.Ident(names.fromString(seed.name)));
        }
        JCExpression newBuilder =
            make.NewClass(null, List.nil(), ctx.builderType(), args.toList(), null);
        JCBlock body = make.Block(0, List.of(make.Return(newBuilder)));

        // builder() constructs a fresh Builder; "-> new" mirrors build().
        // On a generic target the method declares its own copies of the type
        // parameters - it is static, so the class's are not in scope, and the
        // caller infers them from the assignment context.
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(ctx.accessFlag() | Flags.STATIC, newReturnContract(params.size())),
            names.fromString(ctx.config().builderMethodName()),
            ctx.builderType(),
            ctx.typeParams(),
            params.toList(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * The fresh-return {@code @XContract} for an entry point of this arity.
     * A seeded {@code builder(...)} takes as many parameters as there are
     * seeds, and the contract's left-hand side has to match; past the two
     * shapes the vocabulary carries, no contract is emitted rather than one
     * that does not parse.
     */
    private List<JCTree.JCAnnotation> newReturnContract(int arity) {
        return switch (arity) {
            case 0 -> contracts.newReturnNullary();
            // The same shape from(T) carries: reads its argument, returns a
            // fresh builder, touches nothing else.
            case 1 -> contracts.newReturnPureUnary();
            default -> List.nil();
        };
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
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * Builds the expression that reads a field off {@code receiver}. Shared by
     * {@code from(T)} (receiver = the {@code instance} parameter) and
     * {@code mutate()} (receiver = {@code this}). Wraps mutable collection
     * reads in defensive copies. {@code receiver} is consumed exactly once, so
     * callers must pass a fresh node per field.
     *
     * <p>The read is resolved through an ordered ladder rather than by
     * assuming a bean accessor exists. That assumption is what forced every
     * target to carry a getter-generating annotation whether or not it wanted
     * public accessors: the emitted {@code instance.getX()} named a method
     * nobody had written, and the failure surfaced as {@code cannot find
     * symbol} inside generated code.
     *
     * <ol>
     *   <li>{@link FieldSpec#obtainViaMethod} / {@link FieldSpec#obtainViaField}
     *       / {@link FieldSpec#obtainViaStatic} - the author's explicit
     *       override, which outranks everything.</li>
     *   <li>A record component, read through its canonical accessor.</li>
     *   <li>A {@code @Lazy} field, pinned to the getter that unwraps its
     *       storage, under the name its own scheme spells.</li>
     *   <li>An author-declared zero-argument accessor, in any spelling a getter
     *       generator produces, returning a type the field's setter accepts.
     *       Above the direct field read on purpose: when the author wrote the
     *       accessor, a normalising or defensive-copying body is the behaviour
     *       they asked for, and calling it preserves that. A method of that
     *       name returning something else is not the field's reader.</li>
     *   <li>A direct field read, where one is legal.</li>
     *   <li>The bean accessor, as before, with a note naming the field.</li>
     * </ol>
     *
     * <p>Steps 1 to 4 are what this method already did, so the ladder only
     * changes behaviour where the old form did not compile.
     */
    private JCExpression readFrom(FieldSpec f, JCExpression receiver) {
        return wrapDefensiveCopy(f, resolveRead(f, receiver));
    }

    private JCExpression resolveRead(FieldSpec f, JCExpression receiver) {
        if (f.obtainViaStatic && f.obtainViaMethod != null) {
            return make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString(ctx.targetSimpleName())),
                    names.fromString(f.obtainViaMethod)),
                List.of(receiver));
        }
        if (f.obtainViaMethod != null) return call(receiver, f.obtainViaMethod);
        if (f.obtainViaField != null) return make.Select(receiver, names.fromString(f.obtainViaField));

        // A record component's accessor is the contract, and it is always
        // present - there is nothing to probe for.
        if (isRecord) return call(receiver, f.name);

        // @Lazy rewrites storage to a deferred holder, so the synthesised getter is the
        // only read that yields the field's declared type. Pinned rather than
        // probed because LazyFieldMutator appends that getter after this
        // context snapshotted the target's methods, and named by the call that
        // names it there.
        if (f.lazy) return call(receiver, LazyFieldMutator.getterName(f));

        for (String candidate : accessorCandidates(f)) {
            if (ctx.declaresAccessor(candidate, f)) return call(receiver, candidate);
        }

        if (isDirectlyReadable(f)) return make.Select(receiver, names.fromString(f.name));

        // Nothing resolvable: an inherited field this class cannot reach, with
        // no accessor visible either. Emitting the bean call keeps whatever
        // made this compile before - an accessor generated later in the same
        // round, which no scan of the model can see - so the note is a NOTE
        // rather than an error. It costs nothing when the build succeeds and
        // names the field when javac is about to fail on generated code.
        String fallback = (f.isBoolean ? "is" : "get") + capitalise(f.name);
        messager.printMessage(Diagnostic.Kind.NOTE,
            "@ClassBuilder cannot reach field '" + f.name + "' to seed "
                + ctx.config().fromMethodName() + "/" + ctx.config().toBuilderMethodName()
                + " - it is inherited and not accessible here, and no zero-arg accessor was found. "
                + "Emitting '" + fallback + "()'; annotate the field with @ObtainVia if that is wrong",
            ctx.targetElement());
        return call(receiver, fallback);
    }

    private JCExpression call(JCExpression receiver, String method) {
        return make.Apply(List.nil(), make.Select(receiver, names.fromString(method)), List.nil());
    }

    /**
     * Accessor spellings to probe for, in the order a call should prefer them.
     * The boolean {@code isX} form comes first so a target carrying both
     * {@code isX()} and {@code getX()} resolves the way javac's own bean
     * conventions read it, and the bare field name is tried last so a fluent
     * accessor is found rather than bypassed.
     */
    private java.util.List<String> accessorCandidates(FieldSpec f) {
        java.util.List<String> out = new ArrayList<>(3);
        if (f.isBoolean) out.add("is" + capitalise(f.name));
        out.add("get" + capitalise(f.name));
        out.add(f.name);
        return out;
    }

    /**
     * Whether {@code receiver.<field>} compiles from inside the target.
     *
     * <p>{@code from(T)} and {@code mutate()} are members of the target
     * itself, so a field the target declares is readable whatever its
     * modifiers - which covers every standalone target. Only a SuperBuilder
     * subclass reads fields it did not declare, and there the modifier
     * decides: {@code protected} is reachable because the receiver is typed as
     * the subclass (JLS 6.6.2), package-private is reachable from the same
     * package, and {@code private} is not reachable at all.
     */
    private boolean isDirectlyReadable(FieldSpec f) {
        if (f.element == null) return false;
        Element owner = f.element.getEnclosingElement();
        if (!(owner instanceof TypeElement declaring)) return false;
        if (declaring.equals(ctx.targetElement())) return true;

        var modifiers = f.element.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE)) return false;
        if (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED)) return true;
        var elements = ctx.bridge().elements();
        return elements.getPackageOf(declaring).equals(elements.getPackageOf(ctx.targetElement()));
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
        // here rather than delegating to from(this) so a suppressed from can
        // drop the static factory without dangling this method.
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
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * Resolves the setter method name for a field. Booleans go through the same
     * {@code set} role as every other field kind, so seeding needs no special
     * case; the zero-arg {@code flag} setter takes no argument and is never the
     * one called here.
     *
     * <p>Read off the field rather than off the config, and it has to be: a
     * {@code @SetterNames} written on the field renames the setter this call is
     * about to name, and asking the target would emit a call to a method the
     * builder does not have.
     */
    private String setterName(FieldSpec f) {
        return f.setters.setName(f.name, f.isBoolean);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

}
