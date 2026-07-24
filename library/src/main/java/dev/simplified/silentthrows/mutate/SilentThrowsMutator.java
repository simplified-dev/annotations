package dev.simplified.silentthrows.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCCatch;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewArray;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTry;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.GeneratedAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import java.util.ArrayList;

/**
 * Wraps the body of every {@code @SilentThrows} member on a class in a
 * {@code try} whose catch clause rethrows through a generic helper, so a checked
 * exception leaves a declaration that lists no {@code throws}.
 *
 * <p>The emitted shape per annotated member is
 * {@code try { <body> } catch (Throwable $t) { throw Owner.<RuntimeException>$silentThrow($t); }},
 * plus one helper per declaring class:
 *
 * <pre><code>
 * &#64;SuppressWarnings("unchecked") &#64;Generated
 * private static &lt;T extends Throwable&gt; RuntimeException $silentThrow(Throwable $t) throws T {
 *     throw (T) $t;
 * }
 * </code></pre>
 *
 * <p>The helper is emitted rather than shipped so the feature adds no runtime
 * classpath entry - it holds no state and needs no configuration, which is what
 * separates it from {@code dev.simplified.lazy.Lazy}.
 *
 * <p><b>The existing {@link JCBlock} is reused as the try body rather than
 * copied.</b> Nothing is re-parented into a fresh tree, so there is no
 * re-attribution step and no position reset - which matters because
 * {@code Flow$AssignAnalyzer.trackable} gates definite-assignment on a
 * declaration's position and a {@link JCVariableDecl} at {@code Position.NOPOS}
 * can make javac assert with no diagnostic at all. Every node this mutator does
 * create is minted after {@code make.at(body.pos)} for the same reason.
 *
 * <p>The type witness on the rethrow is written out rather than inferred, so the
 * result does not depend on the JLS 18.1.3 rule for an unbound {@code throws}
 * type variable behaving identically on every JDK from 17 through 25.
 *
 * @see dev.simplified.annotations.SilentThrows
 */
public final class SilentThrowsMutator {

    /** Simple name of the annotation, matched on the declaration's own tree. */
    private static final String ANNOTATION_SIMPLE_NAME = "SilentThrows";

    /** Key for this pass's idempotency marks. */
    private static final String PASS = "silentThrows";

    private static final String HELPER_NAME = "$silentThrow";
    private static final String CATCH_PARAM = "$t";
    private static final String TYPE_VAR = "T";
    private static final String THROWABLE_FQN = "java.lang.Throwable";
    private static final String RUNTIME_EXCEPTION_FQN = "java.lang.RuntimeException";
    private static final String SUPPRESS_WARNINGS_FQN = "java.lang.SuppressWarnings";

    private final JavacBridge bridge;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;
    private final GeneratedAnnotations generated;

    public SilentThrowsMutator(JavacBridge bridge) {
        this.bridge = bridge;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(make, names);
        // Unconditional: the annotation carries no emitGenerated attribute, and
        // the helper is a member no author wrote.
        this.generated = GeneratedAnnotations.always(make, types);
    }

    /**
     * Wraps every annotated member declared directly by the target and injects
     * the rethrow helper once when at least one member was wrapped.
     *
     * <p>Idempotent: a body this pass already replaced carries an
     * {@link AstMarkers} mark, so whichever dispatch path reaches the tree first
     * wins and any later one is a no-op. Nested types are not descended into -
     * each is its own target with its own helper.
     *
     * @param target the class declaration to rewrite
     * @return {@code true} when at least one member was wrapped
     */
    public boolean mutate(JCClassDecl target) {
        boolean wrapped = false;
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl method)) continue;
            java.util.List<String> caught = catchTypeNames(method);
            if (caught == null) continue;
            // Abstract, native and otherwise bodyless - nothing to wrap. The IDE
            // inspection reports it; here it is simply skipped.
            if (method.body == null) continue;
            if (AstMarkers.isPassMarked(method.body, PASS)) continue;
            wrap(target, method, caught);
            wrapped = true;
        }
        if (wrapped) injectHelper(target);
        return wrapped;
    }

    // ------------------------------------------------------------------
    // Body rewrite
    // ------------------------------------------------------------------

    /**
     * Re-parents the member's existing body under a new block holding the
     * {@code try}. An explicit {@code this(...)} / {@code super(...)} is split
     * off first and re-prepended outside the try - an explicit constructor
     * invocation nested in a {@code try} is a hard compile error, and the
     * relaxation Java 25 grants constructor bodies does not extend to it. The
     * head is re-read here rather than cached, since an earlier body pass may
     * have relocated everything after it.
     */
    private void wrap(JCClassDecl target, JCMethodDecl method, java.util.List<String> caughtTypes) {
        JCBlock body = method.body;
        make.at(body.pos);

        ListBuffer<JCStatement> prologue = new ListBuffer<>();
        if (method.name.contentEquals("<init>")) splitPrologue(body, prologue);

        ListBuffer<JCCatch> catchers = new ListBuffer<>();
        for (String caught : caughtTypes) catchers.append(catchClause(target, caught));

        JCTry wrapper = make.Try(List.nil(), body, catchers.toList(), null);
        JCBlock replacement = make.Block(0, prologue.append(wrapper).toList());
        // Marked, not annotated: a block carries no modifiers. The generated
        // mark records authorship; the pass mark is what makes a second
        // dispatch path a no-op, and is kept separate so a sibling body rewrite
        // reading the generated flag cannot be mistaken for this one.
        AstMarkers.markGenerated(replacement);
        AstMarkers.markPass(replacement, PASS);
        method.body = replacement;
    }

    /**
     * Moves the constructor's prologue - everything up to and including an
     * explicit {@code this(...)} / {@code super(...)} - out of the block and
     * into the buffer, leaving the block holding what may be wrapped.
     *
     * <p>The invocation is searched for at any index rather than only at the
     * head, because Java 25 allows statements to precede it. Those statements
     * are part of the prologue and stay outside the try with it: the
     * relaxation Java 25 grants constructor bodies does not extend to nesting
     * the invocation itself, so there is no position for a try that starts
     * before it and ends after it.
     *
     * @param body the member's body, left holding the wrappable remainder
     * @param prologue sink for the statements that must stay outside the try
     */
    private static void splitPrologue(JCBlock body, ListBuffer<JCStatement> prologue) {
        List<JCStatement> rest = body.stats;
        ListBuffer<JCStatement> leading = new ListBuffer<>();
        while (rest.nonEmpty()) {
            JCStatement stmt = rest.head;
            rest = rest.tail;
            leading.append(stmt);
            if (isSelfCall(stmt)) {
                prologue.appendList(leading.toList());
                body.stats = rest;
                return;
            }
        }
    }

    /**
     * Whether the statement is an explicit constructor invocation.
     *
     * <p>Determined structurally rather than through {@code TreeInfo}, whose
     * {@code isSelfCall} was removed in JDK 25 - a call compiled against an
     * earlier release links there and then fails with {@code NoSuchMethodError}
     * at the first constructor it meets.
     */
    private static boolean isSelfCall(JCStatement statement) {
        if (!(statement instanceof JCExpressionStatement expression)) return false;
        if (!(expression.expr instanceof JCMethodInvocation call)) return false;
        JCExpression target = call.meth;
        Name name;
        if (target instanceof JCIdent ident) {
            name = ident.name;
        } else if (target instanceof JCFieldAccess access) {
            // Outer.super(..) in an inner class, and the qualified form of this(..).
            name = access.name;
        } else {
            return false;
        }
        return name.contentEquals("this") || name.contentEquals("super");
    }

    /** {@code catch (<caught> $t) { throw Owner.<RuntimeException>$silentThrow($t); }}. */
    private JCCatch catchClause(JCClassDecl target, String caughtType) {
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString(CATCH_PARAM),
            types.parseType(caughtType),
            null
        );
        JCExpression rethrow = make.Apply(
            List.of(types.qualIdent(RUNTIME_EXCEPTION_FQN)),
            make.Select(make.Ident(target.name), names.fromString(HELPER_NAME)),
            List.of(make.Ident(names.fromString(CATCH_PARAM)))
        );
        return make.Catch(param, make.Block(0, List.of(make.Throw(rethrow))));
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    /**
     * Appends the rethrow helper, once per declaring class. Dedupe is a scan of
     * the target's own definitions rather than a marker, so a helper contributed
     * by another dispatch path - or written by hand - is also honoured.
     */
    private void injectHelper(JCClassDecl target) {
        for (JCTree def : target.defs) {
            if (def instanceof JCMethodDecl m && m.name.contentEquals(HELPER_NAME)) return;
        }
        make.at(target.pos);

        Name typeVar = names.fromString(TYPE_VAR);
        JCVariableDecl param = make.VarDef(
            make.Modifiers(Flags.PARAMETER),
            names.fromString(CATCH_PARAM),
            types.qualIdent(THROWABLE_FQN),
            null
        );
        JCBlock body = make.Block(0, List.of(
            make.Throw(make.TypeCast(make.Ident(typeVar), make.Ident(names.fromString(CATCH_PARAM))))
        ));
        // Mandatory, not tidiness: the (T) cast is an unchecked conversion and
        // consumer builds run -Werror.
        JCAnnotation unchecked = make.Annotation(
            types.qualIdent(SUPPRESS_WARNINGS_FQN),
            List.of(make.Literal("unchecked"))
        );

        JCMethodDecl helper = make.MethodDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC, List.of(unchecked)),
            names.fromString(HELPER_NAME),
            types.qualIdent(RUNTIME_EXCEPTION_FQN),
            List.of(make.TypeParameter(typeVar, List.of(types.qualIdent(THROWABLE_FQN)))),
            List.of(param),
            List.of(make.Ident(typeVar)),
            body,
            null
        );
        // Marked and annotated like every other member the pipeline mints, so
        // coverage tools skip it and a later defs scan does not read it as
        // author-written.
        AstMarkers.markGenerated(helper, generated);
        bridge.compat().appendDef(target, helper);
    }

    // ------------------------------------------------------------------
    // Annotation reading
    // ------------------------------------------------------------------

    /**
     * The catch types the member asks for, read off its own declaration tree.
     *
     * @param method the member to inspect
     * @return the type names to catch, or {@code null} when the member carries
     *         no {@code @SilentThrows}
     */
    private java.util.List<String> catchTypeNames(JCMethodDecl method) {
        for (JCAnnotation annotation : method.mods.annotations) {
            if (!isSilentThrows(annotation)) continue;
            java.util.List<String> out = new ArrayList<>();
            for (JCExpression arg : annotation.args) {
                JCExpression value = arg instanceof JCAssign assign ? assign.rhs : arg;
                if (value instanceof JCNewArray array) {
                    for (JCExpression element : array.elems) addClassLiteral(out, element);
                } else {
                    addClassLiteral(out, value);
                }
            }
            // Matches the annotation's own default, and is what keeps the bare
            // form exact - a Throwable clause is never unreachable.
            if (out.isEmpty()) out.add(THROWABLE_FQN);
            return out;
        }
        return null;
    }

    /**
     * Records the type named by a {@code Foo.class} literal. The name is kept as
     * written, so a simple name still resolves through the compilation unit's
     * own imports once the rebuilt reference is attributed.
     */
    private static void addClassLiteral(java.util.List<String> out, JCExpression expression) {
        if (expression instanceof JCFieldAccess access && access.name.contentEquals("class")) {
            out.add(access.selected.toString());
        }
    }

    private static boolean isSilentThrows(JCAnnotation annotation) {
        JCTree type = annotation.annotationType;
        if (type instanceof JCFieldAccess access) return access.name.contentEquals(ANNOTATION_SIMPLE_NAME);
        if (type instanceof JCIdent ident) return ident.name.contentEquals(ANNOTATION_SIMPLE_NAME);
        return false;
    }

}
