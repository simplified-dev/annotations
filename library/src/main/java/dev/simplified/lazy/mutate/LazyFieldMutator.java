package dev.simplified.lazy.mutate;
import com.sun.tools.javac.code.Flags;
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
 * {@link dev.simplified.lazy.Lazy Lazy&lt;T&gt;}, the field gains
 * the {@code final} modifier, the original initializer (when present) is
 * wrapped as {@code Lazy.of(() -> <init>)}, and a memoizing public getter is
 * synthesised on the target.
 *
 * <p>For {@code @Lazy} fields whose name matches a constructor parameter, that
 * parameter's declared type is rewritten from {@code T} to
 * {@code Supplier<T>} and the matching {@code this.foo = foo} body assignment
 * becomes {@code this.foo = Lazy.of(foo)}. This lets values flow from
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
 * @see dev.simplified.lazy.Lazy
 */
public final class LazyFieldMutator {

    public static final String LAZY_FQN = "dev.simplified.lazy.Lazy";
    public static final String SUPPLIER_FQN = "java.util.function.Supplier";
    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";

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

        for (var def : target.defs) {
            if (!(def instanceof JCVariableDecl decl)) continue;
            FieldSpec lazy = lazyByName.get(decl.name.toString());
            if (lazy == null) continue;
            if (!validateField(lazy, decl)) continue;
            rewriteFieldDecl(lazy, decl);
            processed.add(lazy.name);
        }

        rewriteConstructorParams(processed, lazyByName);

        for (String name : processed) {
            FieldSpec lazy = lazyByName.get(name);
            String getterName = "get" + capitalise(name);
            if (existingGetters.contains(getterName)) continue;
            JCMethodDecl getter = buildGetter(lazy, getterName);
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
        if (lazy.type.getKind().isPrimitive()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy is not supported on primitive fields - use the boxed equivalent (e.g. Boolean, Integer)",
                lazy.element);
            return false;
        }
        if (lazy.type.getKind() == TypeKind.ARRAY) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy is not supported on array fields",
                lazy.element);
            return false;
        }
        if (decl.init == null && !classBuilderPresent) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy on a field without @ClassBuilder requires an initializer expression",
                lazy.element);
            return false;
        }
        return true;
    }

    private void rewriteFieldDecl(FieldSpec lazy, JCVariableDecl decl) {
        JCExpression lazyType = types.parseType(LAZY_FQN + "<" + lazy.typeDisplay + ">");
        decl.vartype = lazyType;
        decl.mods = make.Modifiers(decl.mods.flags | Flags.FINAL, decl.mods.annotations);
        if (decl.init != null) {
            JCExpression cleaned = cloneAndReset(decl.init);
            decl.init = lazyOfLambda(cleaned);
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
     *       the field's {@code Lazy<T>} type is satisfied. With
     *       {@code @ClassBuilder} the param is now a {@code Supplier<T>} and
     *       the wrap is {@code Lazy.of(foo)}; without it the param stays
     *       {@code T} and the wrap is {@code Lazy.of(() -> foo)}.</li>
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
                rewriteAssignmentsInBlock(method.body, processed, rewrittenParams);
            }
        }
    }

    /**
     * Rewrites {@code this.<name> = <name>} body assignments where
     * {@code <name>} is a processed @Lazy field. The RHS becomes
     * {@code Lazy.of(<name>)} when {@code <name>} is a param we already
     * retyped to {@code Supplier<T>}, otherwise {@code Lazy.of(() -> <name>)}
     * so the wrap survives type-checking against the {@code Lazy<T>} field.
     */
    private void rewriteAssignmentsInBlock(JCBlock block, Set<String> processed, Set<String> rewrittenParams) {
        for (JCStatement stmt : block.stats) {
            if (!(stmt instanceof JCExpressionStatement es)) continue;
            if (!(es.expr instanceof JCAssign assign)) continue;
            if (!(assign.lhs instanceof JCFieldAccess lhs)) continue;
            if (!(lhs.selected instanceof JCIdent thisIdent)) continue;
            if (!thisIdent.name.toString().equals("this")) continue;
            String fieldName = lhs.name.toString();
            if (!processed.contains(fieldName)) continue;
            if (!(assign.rhs instanceof JCIdent rhsIdent)) continue;
            if (!rhsIdent.name.toString().equals(fieldName)) continue;
            assign.rhs = rewrittenParams.contains(fieldName)
                ? lazyOfIdent(fieldName)
                : lazyOfLambda(make.Ident(names.fromString(fieldName)));
        }
    }

    // ------------------------------------------------------------------
    // Getter synthesis
    // ------------------------------------------------------------------

    private JCMethodDecl buildGetter(FieldSpec lazy, String getterName) {
        JCExpression callGet = make.Apply(
            List.nil(),
            make.Select(
                make.Select(make.Ident(names._this), names.fromString(lazy.name)),
                names.fromString("get")
            ),
            List.nil()
        );
        JCBlock body = make.Block(0, List.of(make.Return(callGet)));
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
        if (hasAnnotation(lazy, NOT_NULL_FQN))
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
                // default. A @Lazy field's storage is Lazy<T>, so the
                // synthesised getter is the only read that yields the declared
                // type - suppressing it leaves the field unreachable, and
                // silently emitting a public getter instead hides that.
                if ("NONE".equals(name)) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "@Lazy(access = NONE) would leave field '" + lazy.name
                            + "' unreadable - its storage is Lazy<T> and the synthesised getter is "
                            + "the only read that unwraps it",
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
     * neither causes nor cures. It fails on the rewritten {@code Lazy<T>}
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

    /** {@code Lazy.of(() -> <expr>)}. */
    private JCExpression lazyOfLambda(JCExpression expr) {
        var lambda = make.Lambda(List.nil(), expr);
        return make.Apply(
            List.nil(),
            make.Select(types.qualIdent(LAZY_FQN), names.fromString("of")),
            List.of(lambda)
        );
    }

    /** {@code Lazy.of(<paramName>)} - passes an existing Supplier through. */
    /**
     * {@code Lazy.of(param, owner, field)} for a constructor assignment whose
     * parameter is a {@code Supplier}. Uses the field-attributed overload
     * because this is the one path where a null supplier can arrive - the
     * builder slot was never filled - and the resulting failure should name the
     * field at {@code build()} instead of surfacing as a bare NPE at first
     * {@code get()}.
     */
    private JCExpression lazyOfIdent(String paramName) {
        return make.Apply(
            List.nil(),
            make.Select(types.qualIdent(LAZY_FQN), names.fromString("of")),
            List.of(
                make.Ident(names.fromString(paramName)),
                make.Literal(ownerQualifiedName()),
                make.Literal(paramName)
            )
        );
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
