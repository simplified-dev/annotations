package dev.simplified.lazy.mutate;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Position;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.GeneratedAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites {@code @Lazy} fields in place: storage type becomes
 * {@code AtomicReference&lt;Supplier&lt;T&gt;&gt;} holding the deferred
 * computation, the field gains the {@code final} modifier, the original
 * initializer (when present) becomes the supplier body, a sibling
 * {@code $value$<name>} field is synthesised to hold the memoized result, and a
 * memoizing getter is synthesised on the target.
 *
 * <p>The supplier reference doubles as the state token and as the monitor the
 * getter locks on. Clearing it is what marks the value computed, so a memoized
 * {@code null} needs no sentinel to tell it from an unread slot, and the
 * supplier - with everything its lambda captured - becomes collectable as soon
 * as the value exists.
 *
 * <p>A primitive field keeps a primitive value slot and boxes only the
 * supplier's type argument, so the single boxing happens when the value is
 * computed and never on a read.
 *
 * <p>For {@code @Lazy} fields whose name matches a constructor parameter, that
 * parameter's declared type is rewritten from {@code T} to
 * {@code Supplier<T>} and the matching {@code this.foo = foo} body assignment
 * wraps it in a fresh holder. This lets values flow from
 * {@code @ClassBuilder} setters through to the target as deferred
 * computations rather than eager values.
 *
 * <p>Runs as the first phase of the mutation pipeline so downstream factories
 * ({@code RetainedInitFactory}, {@code NestedBuilderFactory},
 * {@code FieldMutators}) see the rewritten field types when they read
 * {@link FieldSpec#typeDisplay} - actually, {@code FieldSpec} is captured
 * pre-mutation, so the original type is preserved on the IR side. The
 * downstream factories use that captured value to drive setter shapes; the
 * AST contains the rewritten type, which is what javac compiles.
 *
 * @see dev.simplified.annotations.Lazy
 */
public final class LazyFieldMutator {

    public static final String ATOMIC_REFERENCE_FQN = "java.util.concurrent.atomic.AtomicReference";
    public static final String SUPPLIER_FQN = "java.util.function.Supplier";
    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";
    private static final String OBJECTS_FQN = "java.util.Objects";
    /** Local holding the supplier read inside the synthesised getter's lock. */
    private static final String SUPPLIER_LOCAL = "$s";

    /**
     * Name of the field holding a {@code @Lazy} field's memoized value.
     *
     * <p>Spelled with the {@code $prefix$} shape the rest of the pipeline uses
     * so the plugin's synthetic-member filters, which test for a leading
     * {@code $}, keep recognising it.
     *
     * @param name the annotated field's name
     * @return the value field's name
     */
    public static String valueField(String name) {
        return "$value$" + name;
    }

    /**
     * Annotation FQNs (and their bare simple names) that must NOT propagate
     * from the {@code @Lazy} field declaration onto the synthesised getter.
     *
     * <p>This carries the <b>semantic</b> exclusions only - an annotation that
     * would be legal on the getter but says something about the field's
     * contract rather than about the accessor, {@code @BuildFlag} being the
     * one that is not already excluded by its target. Legality itself is
     * decided structurally by {@link #targetsMethod}, because a list that has
     * to be extended by hand for every new field-level annotation is a list
     * that will not be.
     */
    private static final Set<String> SKIP_ANNOTATIONS = Set.of(
        "dev.simplified.annotations.Lazy", "Lazy",
        "dev.simplified.annotations.Collector", "Collector",
        "dev.simplified.annotations.Negate", "Negate",
        "dev.simplified.annotations.Formattable", "Formattable",
        "dev.simplified.annotations.BuilderDefault", "BuilderDefault",
        "dev.simplified.annotations.BuilderIgnore", "BuilderIgnore",
        "dev.simplified.annotations.BuildFlag", "BuildFlag",
        "dev.simplified.annotations.ObtainVia", "ObtainVia"
    );

    private final JavacBridge bridge;
    private final TypeElement targetElement;
    private final JCClassDecl target;
    private final java.util.List<FieldSpec> fields;
    private final boolean classBuilderPresent;
    private final Messager messager;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final GeneratedAnnotations generated;
    private final ContractAnnotations contracts;

    public LazyFieldMutator(JavacBridge bridge,
                            TypeElement targetElement,
                            JCClassDecl target,
                            java.util.List<FieldSpec> fields,
                            boolean classBuilderPresent,
                            Messager messager) {
        this.bridge = bridge;
        this.targetElement = targetElement;
        this.target = target;
        this.fields = fields;
        this.classBuilderPresent = classBuilderPresent;
        this.messager = messager;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(make, names);
        // @Lazy has no opt-out attribute, so the marker is unconditional.
        this.generated = GeneratedAnnotations.always(make, types);
        // Unconditional for the same reason, and the two answer one question:
        // if @Lazy ever gains emitContracts it gains emitGenerated with it,
        // since a consumer who wants neither in their class files is asking
        // about both. Neither adds a runtime dependency.
        this.contracts = new ContractAnnotations(make, names, types, true);
    }

    /**
     * Runs the @Lazy AST surgery on the target. Returns the names of fields
     * that were rewritten - useful for downstream phases that need to know
     * which builder slots should be {@code Supplier}-typed.
     */
    public Set<String> mutate() {
        Map<String, FieldSpec> lazyByName = new LinkedHashMap<>();
        for (FieldSpec f : fields) {
            if (f.element == null) continue;
            // Read directly from the model element; FieldSpec doesn't yet
            // expose a `lazy` flag at this stage of the patch but the
            // annotation lookup is cheap and authoritative.
            if (hasLazyAnnotation(f)) lazyByName.put(f.name, f);
        }
        if (lazyByName.isEmpty()) return Set.of();

        Set<String> processed = new HashSet<>();
        Set<String> existingGetters = collectExistingGetterNames(target);
        // Kept so the getter can be pointed back at the declaration it reads,
        // which is where its documentation is written.
        Map<String, JCVariableDecl> declsByName = new LinkedHashMap<>();

        for (var def : target.defs) {
            if (!(def instanceof JCVariableDecl decl)) continue;
            FieldSpec lazy = lazyByName.get(decl.name.toString());
            if (lazy == null) continue;
            if (!validateField(lazy, decl)) continue;
            rewriteFieldDecl(lazy, decl);
            declsByName.put(lazy.name, decl);
            processed.add(lazy.name);
        }

        // Appended after the walk rather than inside it - these are new members
        // on the very class whose defs the loop above is reading. Driven from
        // declsByName because it preserves declaration order, so the emitted
        // members land in the same sequence on every compile.
        for (String name : declsByName.keySet()) {
            FieldSpec lazy = lazyByName.get(name);
            bridge.compat().appendDef(target, valueFieldDecl(lazy));
            bridge.compat().appendDef(target, buildResolver(lazy));
        }

        rewriteConstructorParams(processed, lazyByName);

        for (String name : processed) {
            FieldSpec lazy = lazyByName.get(name);
            String getterName = "get" + capitalise(name);
            if (existingGetters.contains(getterName)) continue;
            JCMethodDecl getter = buildGetter(lazy, getterName);
            // The getter's documentation is the field's documentation, and this
            // is the only point where both nodes are in hand.
            AstMarkers.markDocSource(getter, declsByName.get(name));
            bridge.compat().appendDef(target, getter);
        }
        return processed;
    }

    // ------------------------------------------------------------------
    // Field-decl rewrite
    // ------------------------------------------------------------------

    /**
     * Validates field-level constraints before mutation. Returns {@code false}
     * (and reports an error) when the field shouldn't be rewritten - keeps
     * the surrounding pipeline from generating malformed AST for known-bad
     * inputs.
     */
    private boolean validateField(FieldSpec lazy, JCVariableDecl decl) {
        if (lazy.element.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy is not supported on static fields",
                lazy.element);
            return false;
        }
        if (lazy.type.getKind() == TypeKind.ARRAY) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy is not supported on array fields",
                lazy.element);
            return false;
        }
        if (decl.init == null && !classBuilderPresent && !assignedByAConstructor(lazy.name)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy on '" + lazy.name + "' has nothing to defer - give the field an initializer, "
                    + "or assign it in a constructor, either of which becomes the supplier body",
                lazy.element);
            return false;
        }
        return true;
    }

    /**
     * Whether any constructor on the target assigns {@code this.<name>}.
     *
     * <p>The other place a supplier body can come from. A field's own
     * initializer is the obvious one and was the only one; a field computed from
     * constructor arguments or from sibling fields has no initializer to hold
     * that expression and had to be written as a hand-rolled
     * a hand-written holder, which is what left the type in ten field
     * declarations that no annotation expressed.
     *
     * @param name the field's name
     * @return whether a constructor assigns it
     */
    private boolean assignedByAConstructor(String name) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl method)) continue;
            if (!method.name.toString().equals("<init>")) continue;
            if (method.body != null && !assignmentsTo(method.body, name).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Every {@code this.<name> = ...} in a body, nested statements included.
     *
     * <p>Descends through the statement forms a constructor can put an
     * assignment inside - a block, either arm of an {@code if}, a {@code try} -
     * because a field assigned in only one of them is still a field this pass
     * has to rewrite. Missing one leaves the author with a type error on
     * the storage type against {@code T} at a line they wrote and did not change.
     *
     * <p>Lambda and anonymous-class bodies are not descended into. An assignment
     * there runs after the constructor rather than during it, so it is not what
     * initialises the field - and the field is {@code final} by then, which
     * javac rejects on the author's own line.
     */
    private java.util.List<JCAssign> assignmentsTo(JCStatement statement, String name) {
        java.util.List<JCAssign> out = new java.util.ArrayList<>();
        collectAssignments(statement, name, out);
        return out;
    }

    private void collectAssignments(JCStatement statement, String name,
                                    java.util.List<JCAssign> out) {
        if (statement == null) return;
        if (statement instanceof JCBlock block) {
            for (JCStatement nested : block.stats) collectAssignments(nested, name, out);
        } else if (statement instanceof JCTree.JCIf branch) {
            collectAssignments(branch.thenpart, name, out);
            collectAssignments(branch.elsepart, name, out);
        } else if (statement instanceof JCTree.JCTry attempt) {
            collectAssignments(attempt.body, name, out);
            for (JCTree.JCCatch handler : attempt.catchers) {
                collectAssignments(handler.body, name, out);
            }
            collectAssignments(attempt.finalizer, name, out);
        } else if (statement instanceof JCTree.JCSynchronized guarded) {
            collectAssignments(guarded.body, name, out);
        } else if (statement instanceof JCTree.JCLabeledStatement labelled) {
            collectAssignments(labelled.body, name, out);
        } else if (statement instanceof JCExpressionStatement expression
            && expression.expr instanceof JCAssign assign && assignsField(assign, name)) {
            out.add(assign);
        }
    }

    /** Whether an assignment's left-hand side is {@code this.<name>}. */
    private static boolean assignsField(JCAssign assign, String name) {
        return assign.lhs instanceof JCFieldAccess lhs
            && lhs.selected instanceof JCIdent receiver
            && receiver.name.toString().equals("this")
            && lhs.name.toString().equals(name);
    }

    private void rewriteFieldDecl(FieldSpec lazy, JCVariableDecl decl) {
        decl.vartype = holderType(lazy);
        decl.mods = make.Modifiers(decl.mods.flags | Flags.FINAL, decl.mods.annotations);
        if (decl.init != null) {
            JCExpression cleaned = cloneAndReset(decl.init);
            decl.init = newHolderOfLambda(lazy, cleaned);
        }
        // Marked, but deliberately not annotated: this is the author's own
        // field declaration rewritten in place, not a member we introduced.
        // The mark exists for downstream collision detection; @Generated on it
        // would claim authorship of a field the author wrote.
        AstMarkers.markGenerated(decl);
    }

    // ------------------------------------------------------------------
    // Constructor rewrite
    // ------------------------------------------------------------------

    /**
     * Walks every constructor on the target and:
     * <ul>
     *   <li>(when {@code classBuilderPresent}) rewrites parameters whose
     *       names match a processed @Lazy field from {@code T} to
     *       {@code Supplier<T>} so the builder can pass a supplier through;</li>
     *   <li>rewrites the matching {@code this.foo = foo} body assignment so
     *       the field's rewritten storage type is satisfied. With
     *       {@code @ClassBuilder} the param is now a {@code Supplier<T>} and
     *       the wrap is a fresh holder over it; without it the param stays
     *       {@code T} and the wrap is a holder over {@code () -> foo}.</li>
     * </ul>
     */
    private void rewriteConstructorParams(Set<String> processed, Map<String, FieldSpec> lazyByName) {
        if (processed.isEmpty()) return;
        for (var def : target.defs) {
            if (!(def instanceof JCMethodDecl method)) continue;
            if (!method.name.toString().equals("<init>")) continue;
            Set<String> rewrittenParams = new HashSet<>();
            for (JCVariableDecl param : method.params) {
                String pname = param.name.toString();
                FieldSpec lazy = lazyByName.get(pname);
                if (lazy == null || !processed.contains(pname)) continue;
                if (classBuilderPresent) {
                    param.vartype = types.parseType(SUPPLIER_FQN + "<" + lazy.typeDisplay + ">");
                    rewrittenParams.add(pname);
                }
            }
            if (method.body != null) {
                rewriteAssignmentsInBlock(method.body, processed, lazyByName, rewrittenParams,
                    AstMarkers.isGenerated(method));
            }
        }
    }

    /**
     * Rewrites every {@code this.<name> = <expr>} in a constructor body where
     * {@code <name>} is a processed {@code @Lazy} field, so the assignment
     * satisfies the field's rewritten storage type.
     *
     * <p>A parameter this pass retyped to {@code Supplier<T>} passes straight
     * through into a fresh holder - the caller already deferred it.
     * Everything else is <b>the value</b>, and is wrapped as
     * a holder over {@code () -> <expr>} so it is computed on first read rather than
     * in the constructor. That is what makes the whole expression the supplier
     * body, which is the point: a field derived from sibling fields or from
     * constructor arguments has no initializer to put that expression in, and
     * writing it inline is what the annotation is supposed to replace.
     *
     * <p>Any {@code <expr>} qualifies in a constructor the <b>author</b> wrote,
     * not only a parameter of the same name. Restricting it to that spelled one
     * shape and left every other assignment un-rewritten, which javac then
     * rejects as {@code T} against the storage type - on the author's own
     * constructor line, about a type they never wrote.
     *
     * <p>A constructor <b>this pipeline</b> generated is the opposite case and
     * takes only the pass-through. {@code AllArgsConstructorFactory} already
     * emits a complete holder right-hand side for every shape it
     * knows - a supplied slot wrapped verbatim, an instance default deferred
     * over its provider - so wrapping again would nest one holder inside another
     * and the field would no longer accept it.
     */
    private void rewriteAssignmentsInBlock(JCBlock block, Set<String> processed,
                                           Map<String, FieldSpec> lazyByName,
                                           Set<String> rewrittenParams, boolean generated) {
        for (String name : processed) {
            FieldSpec lazy = lazyByName.get(name);
            for (JCAssign assign : assignmentsTo(block, name)) {
                boolean passesSupplierThrough = rewrittenParams.contains(name)
                    && assign.rhs instanceof JCIdent rhs
                    && rhs.name.toString().equals(name);
                if (passesSupplierThrough) {
                    assign.rhs = newHolderOfIdent(lazy, name);
                } else if (!generated) {
                    assign.rhs = newHolderOfLambda(lazy, assign.rhs);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Getter synthesis
    // ------------------------------------------------------------------

    /**
     * The memoizing read itself, kept private and separate from the getter.
     *
     * <p>An author who writes their own getter still needs one correct way to
     * read the field: the storage holds the supplier rather than the value, and
     * calling that supplier by hand would recompute on every read instead of
     * memoizing.
     */
    private JCMethodDecl buildResolver(FieldSpec lazy) {
        JCMethodDecl resolver = make.MethodDef(
            make.Modifiers(Flags.PRIVATE),
            names.fromString(LazyHolders.resolver(lazy.name)),
            types.parseType(lazy.typeDisplay),
            List.nil(),
            List.nil(),
            List.nil(),
            make.Block(0, List.of(fastPath(lazy), computeUnderLock(lazy))),
            null
        );
        AstMarkers.markGenerated(resolver, generated);
        return resolver;
    }

    private JCMethodDecl buildGetter(FieldSpec lazy, String getterName) {
        JCBlock body = make.Block(0, List.of(make.Return(make.Apply(
            List.nil(),
            make.Ident(names.fromString(LazyHolders.resolver(lazy.name))),
            List.nil()
        ))));
        JCExpression returnType = types.parseType(lazy.typeDisplay);
        List<JCAnnotation> declAnnotations = collectDeclarationAnnotations(lazy);
        // The contract states only what the field states. A @NotNull field
        // memoizes a non-null value, so the getter returns one - the same claim
        // the accessor pass makes for @Getter, resting on the author's
        // annotation rather than on proof. Without it there is nothing to say:
        // an unannotated field's getter returns whatever the supplier produced,
        // so this emits no contract at all rather than the bare purity claim
        // the accessor pass falls back to.
        //
        // Never pure, which is the one place this deliberately differs from
        // @Getter. A field read has no effect; the first call to this getter
        // runs the author's supplier - arbitrary code that may do IO or throw -
        // and pure would license the IDE to drop or reorder the call that
        // triggers it.
        //
        // Never on a primitive return either, where non-nullness is not a claim
        // there is any way to violate.
        if (!lazy.type.getKind().isPrimitive() && hasAnnotation(lazy, NOT_NULL_FQN))
            declAnnotations = contracts.returnNonNull().appendList(declAnnotations);
        JCModifiers mods = make.Modifiers(accessFlagFor(lazy), declAnnotations);
        JCMethodDecl getter = make.MethodDef(
            mods,
            names.fromString(getterName),
            returnType,
            List.nil(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(getter, generated);
        return getter;
    }

    /**
     * Reads the {@code access} attribute from the field's {@code @Lazy}
     * annotation and translates it to the matching javac modifier flag.
     * {@link AccessLevel#PACKAGE PACKAGE} maps
     * to {@code 0L} (no keyword). Default when the attribute is absent is
     * {@link Flags#PUBLIC}, matching the annotation's declared default.
     */
    private long accessFlagFor(FieldSpec lazy) {
        if (lazy.element == null) return Flags.PUBLIC;
        for (AnnotationMirror m : lazy.element.getAnnotationMirrors()) {
            if (!"dev.simplified.annotations.Lazy".equals(m.getAnnotationType().toString())) continue;
            for (var entry : m.getElementValues().entrySet()) {
                if (!entry.getKey().getSimpleName().contentEquals("access")) continue;
                Object raw = entry.getValue().getValue();
                if (raw == null) continue;
                String name = raw.toString();
                int dot = name.lastIndexOf('.');
                if (dot >= 0) name = name.substring(dot + 1);
                // NONE is named explicitly rather than falling through to the
                // default. A @Lazy field's storage holds a supplier, so the
                // synthesised getter is the only read that yields the declared
                // type - suppressing it leaves the field unreachable, and
                // silently emitting a public getter instead hides that.
                if ("NONE".equals(name)) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "@Lazy(access = NONE) would leave field '" + lazy.name
                            + "' unreadable - its storage holds the deferred supplier and the "
                            + "synthesised getter is the only read that resolves it",
                        lazy.element);
                    return Flags.PUBLIC;
                }
                return switch (name) {
                    case "PROTECTED" -> Flags.PROTECTED;
                    case "PRIVATE" -> Flags.PRIVATE;
                    case "PACKAGE" -> 0L;
                    default -> Flags.PUBLIC;
                };
            }
        }
        return Flags.PUBLIC;
    }

    /**
     * Builds a list of fresh {@link JCAnnotation} nodes for each non-skipped
     * declaration-level annotation on the source field. Legality is decided
     * structurally by {@link #targetsMethod}, so an annotation that cannot
     * appear on a method never reaches the getter's modifier list.
     *
     * <p>An annotation with no {@link Target} defaults to "any declaration"
     * (JLS 9.6.4.1), so it propagates. {@code @Deprecated},
     * {@code @SuppressWarnings}, and JetBrains
     * {@code @NotNull}/{@code @Nullable} (which list {@link ElementType#METHOD}
     * among their targets) all land here.
     */
    private List<JCAnnotation> collectDeclarationAnnotations(FieldSpec lazy) {
        ListBuffer<JCAnnotation> out = new ListBuffer<>();
        if (lazy.element == null) return out.toList();
        for (AnnotationMirror mirror : lazy.element.getAnnotationMirrors()) {
            String fqn = mirror.getAnnotationType().toString();
            if (SKIP_ANNOTATIONS.contains(fqn)) continue;
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (SKIP_ANNOTATIONS.contains(simple)) continue;
            if (!targetsMethod(mirror)) continue;
            out.append(make.Annotation(types.qualIdent(fqn), List.nil()));
        }
        return out.toList();
    }

    /**
     * Whether an annotation written on the field is legal on the getter this
     * pass synthesises.
     *
     * <p>{@link ElementType#METHOD} is required, not merely some declaration
     * target. Asking only whether the annotation had <b>any</b> declaration
     * target let a field-only one through - {@code @Setter} and {@code @Getter}
     * are {@code @Target({TYPE, FIELD})} - and javac then rejected the
     * synthesised getter with "annotation interface not applicable to this kind
     * of declaration", anchored on the author's field. The
     * {@code SKIP_ANNOTATIONS} denylist cannot be the defence: it has to be
     * extended by hand for every field-level annotation that is ever added, and
     * it silently was not.
     *
     * <p>{@link ElementType#TYPE_USE} is not itself a reason to refuse. A
     * dual-target annotation that also lists {@code METHOD} - JetBrains
     * {@code @NotNull}/{@code @Nullable} being the pair that matters - is
     * copied, so the getter states the same nullness its field does, matching
     * both what {@code AccessorMutator} emits for a {@code @Getter} on the same
     * field and what the IDE-side {@code LazyAugmentProvider} already shows.
     * There is nothing to double: the return type is rebuilt through
     * {@code JavacTypeFactory.parseType}, which strips type-use annotations off
     * the display string on every path, so the emitted type carries none for a
     * declaration-position copy to collide with. javac then records the single
     * written annotation in both the declaration and the type-annotation
     * channel of the class file, which is its ordinary handling of one
     * dual-target annotation.
     *
     * <p>A {@code TYPE_USE}-only annotation is a separate matter this method
     * neither causes nor cures. It fails on the rewritten storage
     * <i>field</i> type with "scoping construct cannot be annotated with
     * type-use annotation", before the getter is ever built.
     *
     * @param mirror the annotation written on the field
     * @return whether it may be copied onto the getter
     */
    private boolean targetsMethod(AnnotationMirror mirror) {
        Element annotationType = mirror.getAnnotationType().asElement();
        if (!(annotationType instanceof TypeElement te)) return true;
        Target target = te.getAnnotation(Target.class);
        // No @Target at all means every declaration context, METHOD included.
        if (target == null) return true;
        boolean method = false;
        for (ElementType e : target.value()) {
            if (e == ElementType.METHOD) method = true;
        }
        return method;
    }

    // ------------------------------------------------------------------
    // AST helpers
    // ------------------------------------------------------------------

    /** {@code AtomicReference<Supplier<T>>} - the rewritten field's storage. */
    private JCExpression holderType(FieldSpec lazy) {
        return LazyHolders.holderType(make, types, lazy.typeDisplay);
    }

    /** {@code Supplier<T>}, boxed when the field is primitive. */
    private JCExpression supplierType(FieldSpec lazy) {
        return LazyHolders.supplierType(make, types, lazy.typeDisplay);
    }

    /** {@code new AtomicReference<Supplier<T>>(<supplier>)}. */
    private JCExpression newHolder(FieldSpec lazy, JCExpression supplier) {
        return LazyHolders.newHolder(make, types, lazy.typeDisplay, supplier);
    }

    /** {@code new AtomicReference<Supplier<T>>(() -> <expr>)}. */
    private JCExpression newHolderOfLambda(FieldSpec lazy, JCExpression expr) {
        return newHolder(lazy, make.Lambda(List.nil(), expr));
    }

    /**
     * {@code new AtomicReference<>(Objects.requireNonNull(param, ...))} for a
     * constructor assignment whose parameter is already a {@code Supplier}.
     * Null-checked here because this is the one path where a missing supplier
     * can arrive - the builder slot was never filled - and the resulting
     * failure should name the field at {@code build()} instead of surfacing as
     * a bare NPE at the first read.
     */
    private JCExpression newHolderOfIdent(FieldSpec lazy, String paramName) {
        return LazyHolders.newCheckedHolder(make, names, types, lazy.typeDisplay,
            make.Ident(names.fromString(paramName)), ownerQualifiedName(), paramName);
    }

    /** {@code private T $value$<name>;} - the slot the memoized value lands in. */
    private JCVariableDecl valueFieldDecl(FieldSpec lazy) {
        JCVariableDecl field = make.VarDef(
            make.Modifiers(Flags.PRIVATE),
            names.fromString(valueField(lazy.name)),
            types.parseType(lazy.typeDisplay),
            null
        );
        AstMarkers.markGenerated(field, generated);
        return field;
    }

    /** {@code this.<name>} - the holder the supplier lives in. */
    private JCExpression holderRead(FieldSpec lazy) {
        return make.Select(make.Ident(names._this), names.fromString(lazy.name));
    }

    /** {@code this.<name>.get()} - a volatile read of the supplier. */
    private JCExpression holderGet(FieldSpec lazy) {
        return make.Apply(List.nil(),
            make.Select(holderRead(lazy), names.fromString("get")), List.nil());
    }

    /** {@code this.$value$<name>}. */
    private JCExpression valueRead(FieldSpec lazy) {
        return make.Select(make.Ident(names._this), names.fromString(valueField(lazy.name)));
    }

    /**
     * {@code if (this.<name>.get() == null) return this.$value$<name>;}
     *
     * <p>The uncontended read, and the reason the supplier rather than the
     * value carries the state: a cleared supplier is a volatile read that
     * happens-after the value was written, so the plain value field is safely
     * published without being volatile itself.
     */
    private JCStatement fastPath(FieldSpec lazy) {
        JCExpression computed = make.Binary(JCTree.Tag.EQ, holderGet(lazy),
            make.Literal(TypeTag.BOT, null));
        return make.If(computed, make.Return(valueRead(lazy)), null);
    }

    /**
     * The double-checked half: re-reads the supplier under the holder's own
     * monitor, computes and stores exactly once, then clears the supplier so
     * it and everything its lambda captured become collectable.
     *
     * <p>The store precedes the clear, which is what the fast path's volatile
     * read pairs with. An initializer that throws propagates with the supplier
     * still set, so the next call retries rather than caching a failure.
     */
    private JCStatement computeUnderLock(FieldSpec lazy) {
        JCVariableDecl local = make.VarDef(
            make.Modifiers(0),
            names.fromString(SUPPLIER_LOCAL),
            supplierType(lazy),
            holderGet(lazy)
        );
        JCExpression stillSet = make.Binary(JCTree.Tag.NE,
            make.Ident(names.fromString(SUPPLIER_LOCAL)),
            make.Literal(TypeTag.BOT, null));
        JCStatement store = make.Exec(make.Assign(valueRead(lazy),
            make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString(SUPPLIER_LOCAL)), names.fromString("get")),
                List.nil())));
        JCStatement clear = make.Exec(make.Apply(List.nil(),
            make.Select(holderRead(lazy), names.fromString("set")),
            List.of(make.Literal(TypeTag.BOT, null))));
        return make.Synchronized(holderRead(lazy), make.Block(0, List.of(
            local,
            make.If(stillSet, make.Block(0, List.of(store, clear)), null),
            make.Return(valueRead(lazy))
        )));
    }

    /** Fully-qualified name of the class declaring the field, for error messages. */
    private String ownerQualifiedName() {
        return targetElement.getQualifiedName().toString();
    }

    private boolean hasLazyAnnotation(FieldSpec f) {
        return hasAnnotation(f, "dev.simplified.annotations.Lazy");
    }

    /**
     * Whether a field carries the named annotation.
     *
     * @param f the selected field
     * @param fqn the annotation's fully qualified name
     * @return whether the field declares it
     */
    private boolean hasAnnotation(FieldSpec f, String fqn) {
        if (f.element == null) return false;
        for (var m : f.element.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals(fqn)) return true;
        }
        return false;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Set<String> collectExistingGetterNames(JCClassDecl target) {
        Set<String> out = new HashSet<>();
        for (var def : target.defs) {
            if (def instanceof JCMethodDecl m && m.params.isEmpty()) {
                out.add(m.name.toString());
            }
        }
        return out;
    }

    /**
     * Deep-clones a tree with every {@code sym}, {@code type}, and
     * {@code pos} reset so javac re-attributes against the new scope. Mirrors
     * the {@code ResettingCopier} pattern in {@code RetainedInitFactory}.
     */
    @SuppressWarnings("unchecked")
    private <T extends JCTree> T cloneAndReset(T tree) {
        return (T) new ResettingCopier(make).copy(tree);
    }

    private static final class ResettingCopier extends TreeCopier<Void> {
        ResettingCopier(TreeMaker maker) { super(maker); }

        @Override
        public <T extends JCTree> T copy(T tree, Void unused) {
            T copy = super.copy(tree, unused);
            if (copy != null) {
                // Positions are deliberately left as-is. Unlike the retained
                // initializer, which moves into a separate provider method,
                // this expression stays exactly where it was and is only
                // wrapped in a lambda - so its original positions are the
                // correct ones. Overwriting them breaks two javac checks that
                // read positions: forward-reference detection compares a
                // referenced field's position against the reference's, so an
                // earlier field starts looking like a forward reference; and
                // Flow$AssignAnalyzer.trackable gates a lambda parameter's
                // definite-assignment address on its position, failing which
                // the following Bits.incl asserts and javac dies with no
                // diagnostic at all. Only sym / type need clearing, so javac
                // re-attributes inside the lambda body.
                copy.type = null;
                if (copy instanceof JCIdent id) id.sym = null;
                else if (copy instanceof JCFieldAccess fa) fa.sym = null;
                else if (copy instanceof JCTree.JCMethodInvocation mi) mi.polyKind = null;
                else if (copy instanceof JCTree.JCNewClass nc) {
                    nc.constructor = null;
                    nc.constructorType = null;
                    nc.varargsElement = null;
                }
            }
            return copy;
        }
    }

}
