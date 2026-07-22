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

    /**
     * Annotation FQNs (and their bare simple names) that must NOT propagate
     * from the {@code @Lazy} field declaration onto the synthesised getter.
     * The {@code @Lazy} annotation itself is field-only by target; the
     * {@code @ClassBuilder} companions are field-contract-specific.
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
        AstMarkers.markGenerated(getter);
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
     * declaration-level annotation on the source field. Annotations whose
     * {@link Target} is exclusively {@link ElementType#TYPE_USE} are
     * skipped - propagating those onto the modifier list of a method whose
     * return type is a qualified name causes javac to migrate them into
     * the type tree's Select chain ({@code java.lang.@Foo String}), which
     * breaks attribution. The IDE-side {@code LazyAugmentProvider} handles
     * type-use cases for hover and DFA separately.
     *
     * <p>Annotations with no {@code @Target} default to "any declaration"
     * (JLS 9.6.4.1), so they propagate. {@code @Deprecated},
     * {@code @SuppressWarnings}, and JetBrains
     * {@code @NotNull}/{@code @Nullable} (which list METHOD in their
     * targets) all land here cleanly.
     */
    private List<JCAnnotation> collectDeclarationAnnotations(FieldSpec lazy) {
        ListBuffer<JCAnnotation> out = new ListBuffer<>();
        if (lazy.element == null) return out.toList();
        for (AnnotationMirror mirror : lazy.element.getAnnotationMirrors()) {
            String fqn = mirror.getAnnotationType().toString();
            if (SKIP_ANNOTATIONS.contains(fqn)) continue;
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (SKIP_ANNOTATIONS.contains(simple)) continue;
            if (!hasDeclarationTarget(mirror)) continue;
            out.append(make.Annotation(types.qualIdent(fqn), List.nil()));
        }
        return out.toList();
    }

    /**
     * Returns {@code true} only when the annotation has at least one
     * declaration target AND does not list {@link ElementType#TYPE_USE}.
     * Annotations with TYPE_USE are skipped entirely on this path - javac
     * auto-lifts them off the modifier list into the return-type tree, and
     * when the type is a qualified name the lift produces malformed AST.
     * The IDE-side {@code LazyAugmentProvider} surfaces type-use cases for
     * hover and DFA without going through javac.
     *
     * <p>An annotation with no {@code @Target} at all defaults to "any
     * declaration" (JLS 9.6.4.1), so it's safe to propagate.
     */
    private boolean hasDeclarationTarget(AnnotationMirror mirror) {
        Element annotationType = mirror.getAnnotationType().asElement();
        if (!(annotationType instanceof TypeElement te)) return true;
        Target target = te.getAnnotation(Target.class);
        if (target == null) return true;
        boolean hasDecl = false;
        for (ElementType e : target.value()) {
            if (e == ElementType.TYPE_USE) return false;
            switch (e) {
                case METHOD, FIELD, TYPE, ANNOTATION_TYPE, PACKAGE,
                     CONSTRUCTOR, PARAMETER, LOCAL_VARIABLE, MODULE,
                     RECORD_COMPONENT -> hasDecl = true;
                default -> {}
            }
        }
        return hasDecl;
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
        if (f.element == null) return false;
        for (var m : f.element.getAnnotationMirrors()) {
            if (m.getAnnotationType().toString().equals("dev.simplified.annotations.Lazy")) return true;
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
