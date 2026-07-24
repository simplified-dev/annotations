package dev.simplified.classbuilder.mutate;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Position;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.apt.SourceIntrospector;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

/**
 * Emits a {@code private static <FieldType> $default$<fieldName>()} method on
 * the target class for every field whose declared initializer is retained.
 * The method body returns a deep-cloned copy of the original field initializer
 * expression; the generated Builder's field default becomes a call to this
 * static provider.
 *
 * <p>This mirrors Lombok's {@code @Builder.Default} plumbing: embedding the
 * initializer expression directly in the Builder's field declaration clashes
 * with javac's flow analyser (manifests as {@code Bits.incl} assertions) and
 * reparsing via {@link ParserFactory} produces a
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
    private final Messager messager;

    RetainedInitFactory(MutationContext ctx, Messager messager) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
        this.types = ctx.types();
        this.messager = messager;
    }

    /** The convention-named static provider for a field's retained initializer. */
    static String providerName(String fieldName) {
        return "$default$" + fieldName;
    }

    /** The convention-named merge helper for a collected instance default. */
    static String mergeName(String fieldName) {
        return "$merge$" + fieldName;
    }

    /** The convention-named empty-container factory for a collected custom container. */
    static String emptyName(String fieldName) {
        return "$empty$" + fieldName;
    }

    /**
     * For every field whose initializer tree was captured by
     * {@link SourceIntrospector}, appends a provider
     * method to the target class. A tree is captured either for
     * a retained initializer or for a {@code @Collector} on a
     * custom (non-java.util) container, which needs the field's own factory to
     * build fresh instances. Fields without a captured tree (text-only, record
     * components, etc.) are skipped - the Builder falls back to its per-type
     * default.
     */
    void appendAll() {
        JCClassDecl target = ctx.target();
        for (FieldSpec f : ctx.fields()) {
            Object captured = f.sourceInitializerTree;
            if (!(captured instanceof JCExpression original)) {
                // A field that asked for retention by name but has nothing to
                // retain would otherwise be silently inert. Fields that merely
                // inherited the class-wide retainInit policy stay quiet - most
                // of a class's fields have no initializer and that is normal.
                if (f.builderDefaultExplicit && f.element != null) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                        "@BuilderDefault has no effect on '" + f.name
                            + "' - the field declares no initializer to retain",
                        f.element
                    );
                }
            } else if (!hasExistingProvider(target, providerName(f.name))) {
                JCMethodDecl provider = buildProvider(f, original);
                if (provider != null) ctx.bridge().compat().appendDef(target, provider);
                // A collected instance default also needs the merge helper that
                // folds the builder's contributions onto the computed default.
                if (ctx.isCollectedInstanceDefault(f) && !hasExistingProvider(target, mergeName(f.name))) {
                    ctx.bridge().compat().appendDef(target, buildMerge(f));
                }
                // A custom container's initializer is the field's factory as
                // well as its default, and the two want different things once
                // it carries contents. Split them here.
                if (MutationContext.isCollected(f) && f.isCustomContainer
                    && !ctx.isCollectedInstanceDefault(f)
                    && !hasExistingProvider(target, emptyName(f.name))) {
                    ctx.bridge().compat().appendDef(target, buildEmptyFactory(f));
                }
            }
            // Blank-final lift: a final field carrying both an initializer AND
            // the builder-called constructor's `this.<name> = <name>` assignment
            // is doubly defined and javac rejects it ("cannot assign a value to
            // final variable"). Stripping the initializer makes the constructor
            // assignment the sole definite assignment. Non-final fields keep
            // their (dead but legal) initializer, matching prior behaviour.
            //
            // Runs for every field, not only those whose initializer was
            // retained: the constructor assigns all of them either way, so
            // turning retention off (@BuilderDefault(false), or a class-level
            // retainInit = false) must not leave a final field's initializer
            // in place. Such a field simply defaults to null, exactly as the
            // non-final case already did.
            stripToBlankFinal(target, f.name);
        }
    }

    /**
     * Removes a {@code final} field's declared initializer so the
     * builder-populated constructor can assign it. Nulls the tree initializer -
     * which survives javac's re-{@code MemberEnter} passes under multi-round
     * (Lombok-co-resident) processing, so the field symbol is re-derived as a
     * genuine blank final - and clears {@code HASINIT} on the field symbol for
     * the single-round case where no re-enter happens. Idempotent across rounds
     * (an already-blank field is left alone).
     */
    private void stripToBlankFinal(JCClassDecl target, String fieldName) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCVariableDecl decl)) continue;
            if (!decl.name.toString().equals(fieldName)) continue;
            // Read finality off the tree rather than FieldSpec.isFinal: @Lazy
            // adds Flags.FINAL during its own earlier pass, so the FieldSpec
            // snapshot under-reports it and a @Lazy field would keep both its
            // initializer and the constructor's assignment.
            if ((decl.mods.flags & Flags.FINAL) == 0) return;
            if (decl.init == null) return; // already blank (re-run idempotency)
            decl.init = null;
            if (decl.sym != null) decl.sym.flags_field &= ~Flags.HASINIT;
            return;
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
        // An initializer reading instance state cannot be evaluated when the
        // builder is created, since no target exists then. Its provider is an
        // instance method, called from the generated constructor where `this`
        // is available - the same place an ordinary field initializer runs.
        boolean instance = ctx.isInstanceDefault(field.name);
        long modifiers = instance ? Flags.PRIVATE : Flags.PRIVATE | Flags.STATIC;
        // A static provider on a generic target cannot see the class's type
        // variables, so it re-declares them; the call site infers them back
        // from the builder slot's own type. The instance form needs none -
        // it runs with the class's parameters already in scope.
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(modifiers),
            names.fromString(providerName(field.name)),
            returnType,
            instance ? List.nil() : ctx.typeParams(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * Builds the merge helper for a {@code @Collector} field whose default reads
     * instance state:
     *
     * <pre>{@code
     * private List<String> $merge$items(List<String> contributed, boolean replaced) {
     *     if (replaced) return contributed;
     *     List<String> base = $default$items();
     *     base.addAll(contributed);
     *     return base;
     * }
     * }</pre>
     *
     * <p>The builder cannot seed its slot from an instance-reading default -
     * no target exists when the builder is created - so the slot carries only
     * what the caller contributed and the fold happens here, where {@code this}
     * is available. An untouched builder contributes an empty collection, which
     * makes the untouched and appended-to cases the same code path; only a
     * wholesale replace ({@code items(...)}, {@code clearItems()}) has to
     * discard the default, which is what {@code replaced} records.
     */
    private JCMethodDecl buildMerge(FieldSpec field) {
        JCExpression fieldType = types.parseType(field.typeDisplay);
        Name contributed = names.fromString("contributed");
        Name replaced = names.fromString("replaced");
        Name base = names.fromString("base");

        // The container always comes from the field's own initializer, even
        // when the caller replaced its contents - so the built object holds
        // exactly what the initializer returns, subclass and all, and nothing
        // has to construct the declared type.
        JCStatement declareBase = make.VarDef(
            make.Modifiers(0), base, types.parseType(field.typeDisplay),
            make.Apply(List.nil(), make.Ident(names.fromString(providerName(field.name))), List.nil()));
        JCStatement discardDefault = make.If(
            make.Ident(replaced),
            make.Exec(make.Apply(List.nil(),
                make.Select(make.Ident(base), names.fromString("clear")), List.nil())),
            null);
        // addAll for a collection, putAll for a map.
        JCStatement fold = make.Exec(make.Apply(
            List.nil(),
            make.Select(make.Ident(base), names.fromString(field.isMap ? "putAll" : "addAll")),
            List.of(make.Ident(contributed))
        ));
        JCBlock body = make.Block(0,
            List.of(declareBase, discardDefault, fold, make.Return(make.Ident(base))));

        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PRIVATE),
            names.fromString(mergeName(field.name)),
            fieldType,
            List.nil(),
            List.of(
                make.VarDef(make.Modifiers(Flags.PARAMETER), contributed, ctx.collectedSlotType(field), null),
                make.VarDef(make.Modifiers(Flags.PARAMETER), replaced,
                    make.TypeIdent(com.sun.tools.javac.code.TypeTag.BOOLEAN), null)
            ),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, ctx.generated());
        return method;
    }

    /**
     * Builds the empty-container factory for a {@code @Collector} on a custom
     * container:
     *
     * <pre>{@code
     * private static ConcurrentList<String> $empty$items() {
     *     ConcurrentList<String> fresh = $default$items();
     *     fresh.clear();
     *     return fresh;
     * }
     * }</pre>
     *
     * <p>A {@code java.util} container has a universal way to make a fresh
     * empty one - {@code new ArrayList<>()} and friends - so its default and
     * its factory are independent. A custom container has no such expression:
     * the only thing that can produce one is the field's own initializer. That
     * makes the initializer serve both roles, and the roles disagree the moment
     * it carries contents - a replace setter resetting through it would keep
     * the default's elements instead of discarding them. Emptying a fresh
     * instance separates the two.
     */
    private JCMethodDecl buildEmptyFactory(FieldSpec field) {
        Name fresh = names.fromString("fresh");
        JCStatement declare = make.VarDef(
            make.Modifiers(0), fresh, types.parseType(field.typeDisplay),
            make.Apply(List.nil(), make.Ident(names.fromString(providerName(field.name))), List.nil()));
        JCStatement clear = make.Exec(make.Apply(
            List.nil(),
            make.Select(make.Ident(fresh), names.fromString("clear")),
            List.nil()
        ));
        JCBlock body = make.Block(0, List.of(declare, clear, make.Return(make.Ident(fresh))));
        JCMethodDecl method = make.MethodDef(
            make.Modifiers(Flags.PRIVATE | Flags.STATIC),
            names.fromString(emptyName(field.name)),
            types.parseType(field.typeDisplay),
            ctx.typeParams(),
            List.nil(),
            List.nil(),
            body,
            null
        );
        AstMarkers.markGenerated(method, ctx.generated());
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
     *
     * <p>{@link JCVariableDecl} covers a lambda's parameters and any locals in
     * its block body. Their {@code VarSymbol}s carry a definite-assignment
     * address allocated in the field initializer's scope; left in place,
     * {@code Flow$AssignAnalyzer.visitLambda} feeds that stale address to
     * {@code Bits.incl} and javac dies on an assertion with no diagnostic.
     * Nulling the symbol makes the provider method's own scope allocate fresh
     * addresses, which is the same reason the expression is moved into a
     * method body rather than embedded in a field initializer.
     */
    private static final class ResettingCopier extends TreeCopier<Void> {

        private final int pos;

        ResettingCopier(TreeMaker maker) {
            super(maker);
            this.pos = maker.pos;
        }

        @Override
        public <T extends JCTree> T copy(T tree, Void unused) {
            T copy = super.copy(tree, unused);
            if (copy != null) {
                copy.pos = pos;
                copy.type = null;
                if (copy instanceof JCIdent id) id.sym = null;
                else if (copy instanceof JCFieldAccess fa) fa.sym = null;
                else if (copy instanceof JCMethodInvocation mi) mi.polyKind = null;
                else if (copy instanceof JCVariableDecl vd) vd.sym = null;
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
