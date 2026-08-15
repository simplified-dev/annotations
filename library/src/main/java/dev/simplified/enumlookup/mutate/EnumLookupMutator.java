package dev.simplified.enumlookup.mutate;

import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree.Tag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.enumlookup.apt.EnumKeySpec;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.GeneratedAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Injects, via javac AST mutation, the cached values array, per-key arrays,
 * static initialiser, and the full set of static helper methods that the
 * {@code @EnumLookup} contract promises.
 *
 * <p>Runtime is deliberately branch-free - the {@code strictKeys} /
 * {@code strictNullKeys} flags on {@code @KeyField} drive editor inspections
 * only and never affect the generated populate loop.
 *
 * <p>Reuses the public javac plumbing from {@code dev.simplified.classbuilder.mutate}
 * ({@link JavacBridge}, {@link JavacTypeFactory}, {@link AstMarkers}) rather
 * than depending on any {@code @ClassBuilder}-specific machinery.
 */
public final class EnumLookupMutator {

    private static final String FQN_CONSUMER = "java.util.function.Consumer";
    private static final String FQN_BICONSUMER = "java.util.function.BiConsumer";
    private static final String FQN_STREAM = "java.util.stream.Stream";
    private static final String FQN_ARRAYS = "java.util.Arrays";
    private static final String FQN_OPTIONAL = "java.util.Optional";
    private static final String FQN_OBJECTS = "java.util.Objects";
    private static final String FQN_INTEGER = "java.lang.Integer";
    private static final String FQN_STRING = "java.lang.String";

    private static final String CACHED_VALUES = "CACHED_VALUES";
    private static final String CACHED_KEYS_PREFIX = "CACHED_KEYS_";

    private final JavacBridge bridge;
    private final Messager messager;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final ContractAnnotations contracts;
    private final GeneratedAnnotations generated;

    public EnumLookupMutator(JavacBridge bridge, Messager messager) {
        this.bridge = bridge;
        this.messager = messager;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(make, names);
        // EnumLookup always emits contracts - no per-target opt-out attribute.
        this.contracts = new ContractAnnotations(make, names, types, true);
        // Same for @Generated: the members below are ours whoever asked for them.
        this.generated = GeneratedAnnotations.always(make, types);
    }

    /**
     * Runs the mutation for a single {@code @EnumLookup}-annotated enum.
     *
     * @param targetElement the annotated enum
     * @param keys IR built from each {@code @KeyField} on the enum's fields
     * @return {@code true} when mutation completed; {@code false} when the
     *         element has no resolvable source tree
     */
    public boolean mutate(TypeElement targetElement, java.util.List<EnumKeySpec> keys) {
        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;
        if ((target.mods.flags & Flags.ENUM) == 0) return false;

        make.at(target.pos);

        String enumName = targetElement.getSimpleName().toString();

        if (reportCacheCollisions(target, targetElement, keys)) return false;

        // Fields.
        JCVariableDecl values = cachedValuesField(enumName);
        AstMarkers.markGenerated(values, generated);
        bridge.compat().appendDef(target, values);
        for (EnumKeySpec spec : keys) {
            String name = CACHED_KEYS_PREFIX + spec.fieldName();
            JCVariableDecl keysArr = cachedKeysField(spec, name);
            AstMarkers.markGenerated(keysArr, generated);
            bridge.compat().appendDef(target, keysArr);
        }

        // Static block - prepend our populate statements to any existing block
        // so the cache is initialised before any user code that might read it.
        attachToStaticBlock(target, populateStatements(enumName, keys));

        // Per-enum methods.
        appendMethodIfAbsent(target, targetElement, "size", 0, this::sizeMethod);
        appendForEachOverloads(target, targetElement, enumName);
        appendMethodIfAbsent(target, targetElement, "stream", 0, () -> streamMethod(enumName, false));
        appendMethodIfAbsent(target, targetElement, "parallelStream", 0, () -> streamMethod(enumName, true));
        appendMethodIfAbsent(target, targetElement, "ofName", 1, () -> ofNameMethod(enumName));
        appendMethodIfAbsent(target, targetElement, "ofOrdinal", 1, () -> ofOrdinalMethod(enumName));
        appendMethodIfAbsent(target, targetElement, "findByName", 1,
            () -> findByMethod(enumName, "findByName", "ofName", "name", FQN_STRING));
        appendMethodIfAbsent(target, targetElement, "findByOrdinal", 1,
            () -> findByMethod(enumName, "findByOrdinal", "ofOrdinal", "ordinal", "int"));

        // Per-key methods.
        for (EnumKeySpec spec : keys) {
            String ofName = "of" + spec.methodSuffix();
            String findName = "findBy" + spec.methodSuffix();
            appendMethodIfAbsent(target, targetElement, ofName, 1,
                () -> ofKeyMethod(enumName, spec, ofName));
            appendMethodIfAbsent(target, targetElement, findName, 1,
                () -> findByMethod(enumName, findName, ofName, "key", spec.declaredTypeDisplay()));
        }

        return true;
    }

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    private JCVariableDecl cachedValuesField(String enumName) {
        return make.VarDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC | Flags.FINAL),
            names.fromString(CACHED_VALUES),
            make.TypeArray(make.Ident(names.fromString(enumName))),
            null
        );
    }

    private JCVariableDecl cachedKeysField(EnumKeySpec spec, String fieldName) {
        return make.VarDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC | Flags.FINAL),
            names.fromString(fieldName),
            make.TypeArray(types.parseType(spec.declaredTypeDisplay())),
            null
        );
    }

    // ------------------------------------------------------------------
    // Static block
    // ------------------------------------------------------------------

    private java.util.List<JCStatement> populateStatements(String enumName, java.util.List<EnumKeySpec> keys) {
        ListBuffer<JCStatement> out = new ListBuffer<>();

        // CACHED_VALUES = EnumName.values();
        out.append(make.Exec(make.Assign(
            ident(CACHED_VALUES),
            make.Apply(List.nil(),
                make.Select(make.Ident(names.fromString(enumName)), names.fromString("values")),
                List.nil())
        )));

        // For each key: CACHED_KEYS_X = new <type>[CACHED_VALUES.length];
        for (EnumKeySpec spec : keys) {
            String fieldName = CACHED_KEYS_PREFIX + spec.fieldName();
            out.append(make.Exec(make.Assign(
                ident(fieldName),
                make.NewArray(
                    types.parseType(spec.declaredTypeDisplay()),
                    List.of(make.Select(ident(CACHED_VALUES), names.fromString("length"))),
                    null
                )
            )));
        }

        if (!keys.isEmpty()) {
            // for (int i = 0; i < CACHED_VALUES.length; i++) { CACHED_KEYS_X[i] = ...; }
            ListBuffer<JCStatement> loopBody = new ListBuffer<>();
            for (EnumKeySpec spec : keys) {
                String fieldName = CACHED_KEYS_PREFIX + spec.fieldName();
                loopBody.append(make.Exec(make.Assign(
                    make.Indexed(ident(fieldName), ident("i")),
                    make.Select(make.Indexed(ident(CACHED_VALUES), ident("i")),
                        names.fromString(spec.fieldName()))
                )));
            }
            out.append(make.ForLoop(
                List.of(make.VarDef(make.Modifiers(0),
                    names.fromString("i"),
                    make.TypeIdent(TypeTag.INT),
                    make.Literal(0))),
                make.Binary(Tag.LT, ident("i"),
                    make.Select(ident(CACHED_VALUES), names.fromString("length"))),
                List.of(make.Exec(make.Unary(Tag.POSTINC, ident("i")))),
                make.Block(0, loopBody.toList())
            ));
        }

        java.util.List<JCStatement> result = new java.util.ArrayList<>(out.size());
        for (JCStatement s : out) result.add(s);
        return result;
    }

    /**
     * Finds an existing static initialiser block on the target and prepends
     * our populate statements to its body, or creates a fresh static block
     * when none exists.
     */
    private void attachToStaticBlock(JCClassDecl target, java.util.List<JCStatement> populate) {
        JCBlock existing = null;
        for (JCTree def : target.defs) {
            if (def instanceof JCBlock b && (b.flags & Flags.STATIC) != 0) {
                existing = b;
                break;
            }
        }
        if (existing != null) {
            ListBuffer<JCStatement> merged = new ListBuffer<>();
            for (JCStatement s : populate) merged.append(s);
            for (JCStatement s : existing.stats) merged.append(s);
            existing.stats = merged.toList();
            AstMarkers.markGenerated(existing);
        } else {
            ListBuffer<JCStatement> body = new ListBuffer<>();
            for (JCStatement s : populate) body.append(s);
            JCBlock fresh = make.Block(Flags.STATIC, body.toList());
            AstMarkers.markGenerated(fresh);
            bridge.compat().appendDef(target, fresh);
        }
    }

    // ------------------------------------------------------------------
    // Per-enum methods
    // ------------------------------------------------------------------

    private JCMethodDecl sizeMethod() {
        JCBlock body = make.Block(0, List.of(make.Return(
            make.Select(ident(CACHED_VALUES), names.fromString("length"))
        )));
        return staticMethod("size",
            make.TypeIdent(TypeTag.INT),
            List.nil(),
            body,
            contracts.pure());
    }

    /**
     * Emits both {@code forEach} overloads. Any non-generated {@code forEach/1}
     * on the target suppresses both overloads; otherwise both are emitted as
     * a pair.
     */
    private void appendForEachOverloads(JCClassDecl target, TypeElement element, String enumName) {
        if (hasMethod(target, "forEach", 1)) {
            messager.printMessage(Diagnostic.Kind.NOTE,
                "@EnumLookup skipped 'forEach/1' overloads - target already declares forEach/1",
                element);
            return;
        }
        bridge.compat().appendDef(target, forEachConsumer(enumName));
        bridge.compat().appendDef(target, forEachBiConsumer(enumName));
    }

    private JCMethodDecl forEachConsumer(String enumName) {
        // for (E v : CACHED_VALUES) action.accept(v);
        JCStatement loop = make.ForeachLoop(
            make.VarDef(make.Modifiers(0),
                names.fromString("v"),
                make.Ident(names.fromString(enumName)),
                null),
            ident(CACHED_VALUES),
            make.Exec(make.Apply(List.nil(),
                make.Select(ident("action"), names.fromString("accept")),
                List.of(ident("v"))))
        );
        JCExpression paramType = make.TypeApply(
            types.qualIdent(FQN_CONSUMER),
            List.of(wildcardSuper(enumName))
        );
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("action"),
            paramType,
            null
        );
        return staticMethod("forEach",
            make.TypeIdent(TypeTag.VOID),
            List.of(param),
            make.Block(0, List.of(loop)),
            contracts.nullParamFails());
    }

    private JCMethodDecl forEachBiConsumer(String enumName) {
        // for (int i = 0; i < CACHED_VALUES.length; i++) action.accept(i, CACHED_VALUES[i]);
        JCStatement loop = make.ForLoop(
            List.of(make.VarDef(make.Modifiers(0),
                names.fromString("i"),
                make.TypeIdent(TypeTag.INT),
                make.Literal(0))),
            make.Binary(Tag.LT, ident("i"),
                make.Select(ident(CACHED_VALUES), names.fromString("length"))),
            List.of(make.Exec(make.Unary(Tag.POSTINC, ident("i")))),
            make.Exec(make.Apply(List.nil(),
                make.Select(ident("action"), names.fromString("accept")),
                List.of(ident("i"), make.Indexed(ident(CACHED_VALUES), ident("i")))))
        );
        JCExpression paramType = make.TypeApply(
            types.qualIdent(FQN_BICONSUMER),
            List.of(types.qualIdent(FQN_INTEGER), wildcardSuper(enumName))
        );
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("action"),
            paramType,
            null
        );
        return staticMethod("forEach",
            make.TypeIdent(TypeTag.VOID),
            List.of(param),
            make.Block(0, List.of(loop)),
            contracts.nullParamFails());
    }

    private JCMethodDecl streamMethod(String enumName, boolean parallel) {
        JCExpression call = make.Apply(List.nil(),
            make.Select(types.qualIdent(FQN_ARRAYS), names.fromString("stream")),
            List.of(ident(CACHED_VALUES)));
        if (parallel) {
            call = make.Apply(List.nil(),
                make.Select(call, names.fromString("parallel")),
                List.nil());
        }
        JCBlock body = make.Block(0, List.of(make.Return(call)));
        JCExpression returnType = make.TypeApply(
            types.qualIdent(FQN_STREAM),
            List.of(make.Ident(names.fromString(enumName)))
        );
        return staticMethod(parallel ? "parallelStream" : "stream",
            returnType,
            List.nil(),
            body,
            contracts.pureReturnNonNull());
    }

    private JCMethodDecl ofNameMethod(String enumName) {
        // for (E v : CACHED_VALUES) if (v.name().equalsIgnoreCase(name)) return v;
        // return null;
        JCStatement loop = make.ForeachLoop(
            make.VarDef(make.Modifiers(0),
                names.fromString("v"),
                make.Ident(names.fromString(enumName)),
                null),
            ident(CACHED_VALUES),
            make.If(
                make.Apply(List.nil(),
                    make.Select(
                        make.Apply(List.nil(),
                            make.Select(ident("v"), names.fromString("name")),
                            List.nil()),
                        names.fromString("equalsIgnoreCase")),
                    List.of(ident("name"))),
                make.Return(ident("v")),
                null
            )
        );
        JCBlock body = make.Block(0, List.of(loop, make.Return(nullLit())));
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("name"),
            types.qualIdent(FQN_STRING),
            null
        );
        return staticMethod("ofName",
            make.Ident(names.fromString(enumName)),
            List.of(param),
            body,
            contracts.pureNullParamNullReturn());
    }

    private JCMethodDecl ofOrdinalMethod(String enumName) {
        // return (ordinal < 0 || ordinal >= CACHED_VALUES.length) ? null : CACHED_VALUES[ordinal];
        JCExpression cond = make.Binary(Tag.OR,
            make.Binary(Tag.LT, ident("ordinal"), make.Literal(0)),
            make.Binary(Tag.GE, ident("ordinal"),
                make.Select(ident(CACHED_VALUES), names.fromString("length")))
        );
        JCBlock body = make.Block(0, List.of(make.Return(make.Conditional(
            cond,
            nullLit(),
            make.Indexed(ident(CACHED_VALUES), ident("ordinal"))
        ))));
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("ordinal"),
            make.TypeIdent(TypeTag.INT),
            null
        );
        return staticMethod("ofOrdinal",
            make.Ident(names.fromString(enumName)),
            List.of(param),
            body,
            contracts.pure());
    }

    private JCMethodDecl findByMethod(String enumName, String methodName, String delegateName,
                                       String paramName, String paramTypeDisplay) {
        // return Optional.ofNullable(<delegate>(<param>));
        JCBlock body = make.Block(0, List.of(make.Return(
            make.Apply(List.nil(),
                make.Select(types.qualIdent(FQN_OPTIONAL), names.fromString("ofNullable")),
                List.of(make.Apply(List.nil(),
                    make.Ident(names.fromString(delegateName)),
                    List.of(ident(paramName)))))
        )));
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString(paramName),
            types.parseType(paramTypeDisplay),
            null
        );
        JCExpression returnType = make.TypeApply(
            types.qualIdent(FQN_OPTIONAL),
            List.of(make.Ident(names.fromString(enumName)))
        );
        return staticMethod(methodName,
            returnType,
            List.of(param),
            body,
            contracts.pureUnaryReturnNonNull());
    }

    // ------------------------------------------------------------------
    // Per-key methods
    // ------------------------------------------------------------------

    /**
     * The per-element test the {@code of<Key>} scan runs.
     *
     * <p>Three shapes rather than two. A primitive key compares with {@code ==}.
     * A reference key compares with {@code Objects.equals}, which tolerates a
     * null on either side. A {@code String} key asking to
     * {@code @KeyField(ignoreCase)} compares with
     * {@code equalsIgnoreCase}, guarded so it keeps exactly the null tolerance
     * {@code Objects.equals} has - a null stored key matches a null argument and
     * nothing else - because calling the method on a null element would throw
     * inside generated code the author cannot see.
     *
     * @param spec the resolved key
     * @param keysFieldName the parallel key array's name
     * @return the comparison expression
     */
    private JCExpression keyComparison(EnumKeySpec spec, String keysFieldName) {
        JCExpression element = make.Indexed(ident(keysFieldName), ident("i"));
        if (spec.isPrimitive()) {
            return make.Binary(Tag.EQ, element, ident("key"));
        }
        if (spec.ignoreCase() && spec.isString()) {
            // element == null ? key == null : element.equalsIgnoreCase(key)
            return make.Conditional(
                make.Binary(Tag.EQ, make.Indexed(ident(keysFieldName), ident("i")), nullLit()),
                make.Binary(Tag.EQ, ident("key"), nullLit()),
                make.Apply(List.nil(),
                    make.Select(element, names.fromString("equalsIgnoreCase")),
                    List.of(ident("key")))
            );
        }
        return make.Apply(List.nil(),
            make.Select(types.qualIdent(FQN_OBJECTS), names.fromString("equals")),
            List.of(element, ident("key")));
    }

    private JCMethodDecl ofKeyMethod(String enumName, EnumKeySpec spec, String methodName) {
        // for (int i = 0; i < CACHED_KEYS_X.length; i++)
        //     if (<comparison>) return CACHED_VALUES[i];
        // return null;
        String keysFieldName = CACHED_KEYS_PREFIX + spec.fieldName();
        JCExpression comparison = keyComparison(spec, keysFieldName);

        JCStatement loop = make.ForLoop(
            List.of(make.VarDef(make.Modifiers(0),
                names.fromString("i"),
                make.TypeIdent(TypeTag.INT),
                make.Literal(0))),
            make.Binary(Tag.LT, ident("i"),
                make.Select(ident(keysFieldName), names.fromString("length"))),
            List.of(make.Exec(make.Unary(Tag.POSTINC, ident("i")))),
            make.If(
                comparison,
                make.Return(make.Indexed(ident(CACHED_VALUES), ident("i"))),
                null
            )
        );
        JCBlock body = make.Block(0, List.of(loop, make.Return(nullLit())));

        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString("key"),
            types.parseType(spec.declaredTypeDisplay()),
            null
        );

        // Primitive keys can't be null; the @XContract "null -> null" only
        // applies to reference parameters.
        List<JCAnnotation> contract = spec.isPrimitive()
            ? contracts.pure()
            : contracts.pureNullParamNullReturn();

        return staticMethod(methodName,
            make.Ident(names.fromString(enumName)),
            List.of(param),
            body,
            contract);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private interface MethodSupplier {
        JCMethodDecl get();
    }

    private void appendMethodIfAbsent(JCClassDecl target, TypeElement element,
                                      String name, int arity, MethodSupplier supplier) {
        // Two collision shapes:
        //   1. user has hand-written the method - we yield, emit a NOTE.
        //   2. we already emitted the same name/arity earlier in this pass
        //      (e.g. a @KeyField named 'name' produces ofName(String) that
        //      collides with the per-enum ofName(String)) - silently skip
        //      so the per-enum method wins; suggest methodName via a NOTE.
        switch (collisionState(target, name, arity)) {
            case USER -> messager.printMessage(Diagnostic.Kind.NOTE,
                "@EnumLookup skipped '" + name + "/" + arity
                    + "' - target already declares a matching method",
                element);
            case GENERATED -> messager.printMessage(Diagnostic.Kind.NOTE,
                "@EnumLookup skipped duplicate '" + name + "/" + arity
                    + "' - set @KeyField(methodName=...) to disambiguate per-key lookups",
                element);
            case NONE -> bridge.compat().appendDef(target, supplier.get());
        }
    }

    private enum Collision { NONE, USER, GENERATED }

    private static Collision collisionState(JCClassDecl target, String name, int arity) {
        for (JCTree def : target.defs) {
            if (def instanceof JCMethodDecl m
                && m.name.toString().equals(name)
                && m.params.size() == arity) {
                return AstMarkers.isGenerated(m) ? Collision.GENERATED : Collision.USER;
            }
        }
        return Collision.NONE;
    }

    private JCMethodDecl staticMethod(String name,
                                      JCExpression returnType,
                                      List<JCVariableDecl> params,
                                      JCBlock body,
                                      List<JCAnnotation> annotations) {
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PUBLIC | Flags.STATIC, annotations),
            names.fromString(name),
            returnType,
            List.nil(),
            params,
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, generated);
        return method;
    }

    /** Wildcard {@code ? super E}. */
    private JCExpression wildcardSuper(String enumName) {
        return make.Wildcard(
            make.TypeBoundKind(BoundKind.SUPER),
            make.Ident(names.fromString(enumName))
        );
    }

    private JCExpression ident(String name) {
        return make.Ident(names.fromString(name));
    }

    private JCExpression nullLit() {
        return make.Literal(TypeTag.BOT, null);
    }

    /**
     * True when {@code target} already declares a non-generated method with
     * the given name and arity. AST-marked methods we synthesised earlier in
     * the same pass don't count - their presence is expected.
     */
    private static boolean hasMethod(JCClassDecl target, String name, int arity) {
        for (JCTree def : target.defs) {
            if (def instanceof JCMethodDecl m
                && m.name.toString().equals(name)
                && m.params.size() == arity
                && !AstMarkers.isGenerated(m)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFieldNamed(JCClassDecl target, String name) {
        for (JCTree def : target.defs) {
            if (def instanceof JCVariableDecl v && v.name.toString().equals(name)) return true;
        }
        return false;
    }

    /**
     * Reports every cache field the enum already declares under a name this
     * mutator owns, and says so before anything is emitted.
     *
     * <p>Skipping the declaration and emitting the populate statements anyway is
     * what this replaces, and it failed in the worst available way: the static
     * block assigned a second value to the author's own {@code final} field, so
     * javac reported a definite-assignment error on a line the author wrote,
     * naming neither the annotation nor the collision. The hand-rolled cache
     * these enums carry is exactly what {@code @EnumLookup} is adopted to
     * delete, so a plain instruction to delete it is the whole fix.
     *
     * @param target the enum's source tree
     * @param targetElement the enum, for the diagnostic's position
     * @param keys the resolved {@code @KeyField} specs
     * @return whether a collision was reported, in which case nothing is emitted
     */
    private boolean reportCacheCollisions(JCClassDecl target, TypeElement targetElement,
                                          java.util.List<EnumKeySpec> keys) {
        java.util.List<String> owned = new java.util.ArrayList<>();
        owned.add(CACHED_VALUES);
        for (EnumKeySpec spec : keys) owned.add(CACHED_KEYS_PREFIX + spec.fieldName());

        boolean collided = false;
        for (String name : owned) {
            if (!hasFieldNamed(target, name)) continue;
            collided = true;
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@EnumLookup generates a field named '" + name + "' and "
                    + targetElement.getSimpleName() + " already declares one - delete the declaration "
                    + "and read the generated field, which is private static final and carries the "
                    + "same name",
                targetElement);
        }
        return collided;
    }
}
