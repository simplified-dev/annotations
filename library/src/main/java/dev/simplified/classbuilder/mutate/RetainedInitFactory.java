package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Position;
import dev.simplified.classbuilder.apt.FieldSpec;

/**
 * Emits a {@code private static <FieldType> $default$<fieldName>()} method on
 * the target class for every field carrying {@code @BuildRule(retainInit = true)}.
 * The method body returns a deep-cloned copy of the original field initializer
 * expression; the generated Builder's field default becomes a call to this
 * static provider.
 *
 * <p>This mirrors Lombok's {@code @Builder.Default} plumbing: embedding the
 * initializer expression directly in the Builder's field declaration clashes
 * with javac's flow analyser (manifests as {@code Bits.incl} assertions) and
 * reparsing via {@link com.sun.tools.javac.parser.ParserFactory} produces a
 * tree whose internal state still confuses flow analysis. Cloning the
 * already-parsed tree from the target's own compilation unit - with symbol
 * and type pointers reset so javac re-attributes in the method-body scope -
 * is the approach that works reliably across the JDK 17 - 25 matrix.
 *
 * <p>Because the expression now lives inside a normal method body that javac
 * attributes through the standard pipeline, arbitrary Java expressions are
 * supported: method calls ({@code UUID.randomUUID()}), constructor calls
 * ({@code new ArrayList<>()}), factory methods ({@code List.of(...)}), field
 * accesses, ternaries, and so on.
 */
final class RetainedInitFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;

    RetainedInitFactory(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
    }

    /** The convention-named static provider for a field's retained initializer. */
    static String providerName(String fieldName) {
        return "$default$" + fieldName;
    }

    /**
     * For every field with {@code @BuildRule(retainInit = true)} whose
     * initializer tree was captured by {@link dev.simplified.classbuilder.apt.SourceIntrospector},
     * appends a provider method to the target class. Fields without a
     * captured tree (text-only, record components, etc.) are skipped - the
     * Builder falls back to its per-type default.
     */
    void appendAll() {
        JCClassDecl target = ctx.target();
        for (FieldSpec f : ctx.fields()) {
            if (!f.builderDefault) continue;
            Object captured = f.sourceInitializerTree;
            if (!(captured instanceof JCExpression original)) continue;
            if (hasExistingProvider(target, providerName(f.name))) continue;
            JCMethodDecl provider = buildProvider(f, original);
            if (provider != null) ctx.bridge().compat().appendDef(target, provider);
        }
    }

    /**
     * Builds {@code private static T $default$<fieldName>() { return <cloned>; }}
     * for a single field. The cloned tree has every {@code sym}, {@code type},
     * and {@code pos} field stripped so javac re-resolves the expression
     * against the method body's scope during its normal Attr pass.
     */
    private JCMethodDecl buildProvider(FieldSpec field, JCExpression original) {
        JCExpression cleaned = new ResettingCopier(make).copy(original);
        if (cleaned == null) return null;
        JCStatement returnStmt = make.Return(cleaned);
        JCBlock body = make.Block(0, List.of(returnStmt));
        JCExpression returnType = types.parseType(field.typeDisplay);
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC),
            names.fromString(providerName(field.name)),
            returnType,
            List.nil(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method);
        return method;
    }

    /** Idempotency guard for re-entrant annotation processing rounds. */
    private static boolean hasExistingProvider(JCClassDecl target, String name) {
        for (var def : target.defs) {
            if (def instanceof JCMethodDecl m && m.name.toString().equals(name)) return true;
        }
        return false;
    }

    /**
     * {@link TreeCopier} that resets every per-node symbol / type / position
     * so javac treats the copy as a freshly-parsed expression. Without this
     * reset the Builder's new method body inherits attribution pointers from
     * the original field's scope, which javac's type-checker then NPEs on
     * when it tries to reconcile them against the method's own scope.
     */
    private static final class ResettingCopier extends TreeCopier<Void> {
        ResettingCopier(TreeMaker maker) { super(maker); }

        @Override
        public <T extends JCTree> T copy(T tree, Void unused) {
            T copy = super.copy(tree, unused);
            if (copy != null) {
                copy.pos = Position.NOPOS;
                copy.type = null;
                if (copy instanceof JCIdent id) id.sym = null;
                else if (copy instanceof JCFieldAccess fa) fa.sym = null;
                else if (copy instanceof JCMethodInvocation mi) mi.polyKind = null;
                else if (copy instanceof JCNewClass nc) {
                    nc.constructor = null;
                    nc.constructorType = null;
                    nc.varargsElement = null;
                }
            }
            return copy;
        }
    }

}
