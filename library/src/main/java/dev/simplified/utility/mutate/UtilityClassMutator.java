package dev.simplified.utility.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.GeneratedAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;
import dev.simplified.utility.apt.UtilityConfig;

import javax.annotation.processing.Messager;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Applies {@code @UtilityClass} by AST mutation: marks the class {@code final}
 * and rewrites the constructor javac already generated into one that throws.
 *
 * <p>The constructor is <b>retrofitted, not injected</b>, and that is the one
 * non-obvious thing here. {@code MemberEnter.finishClass} prepends a default
 * constructor into {@code defs} before any processor's {@code init()} runs, so
 * appending a second no-arg constructor is a duplicate definition. Lombok
 * deletes the generated one from both the tree and the class symbol's member
 * scope; editing it in place is cheaper and leaves nothing for javac to
 * disagree about - no list surgery, no scope removal, no second symbol.
 *
 * <p>Flag edits are applied to the tree <i>and</i> the entered symbol. The tree
 * edit alone only survives when a later round re-runs {@code MemberEnter}, the
 * same two-signal shape {@code RetainedInitFactory} uses when it strips a field
 * to a blank final.
 */
public final class UtilityClassMutator {

    private static final String UOE_FQN = "java.lang.UnsupportedOperationException";

    private final JavacBridge bridge;
    private final Messager messager;
    private final TreeMaker make;
    private final Names names;
    private final JavacTypeFactory types;

    public UtilityClassMutator(JavacBridge bridge, Messager messager) {
        this.bridge = bridge;
        this.messager = messager;
        this.make = bridge.treeMaker();
        this.names = bridge.names();
        this.types = new JavacTypeFactory(make, names);
    }

    /**
     * Runs the mutation for one annotated class.
     *
     * @param targetElement the annotated type
     * @param config resolved annotation attributes
     * @return {@code true} when mutation completed; {@code false} when the
     *         element has no resolvable source tree
     */
    public boolean mutate(TypeElement targetElement, UtilityConfig config) {
        JCClassDecl target = bridge.treeOf(targetElement);
        if (target == null) return false;

        make.at(target.pos);

        ContractAnnotations contracts =
            new ContractAnnotations(make, names, types, config.emitContracts());
        GeneratedAnnotations generated =
            new GeneratedAnnotations(make, types, config.emitGenerated());

        if (config.makeFinal()) markFinal(target);
        retrofitConstructor(target, targetElement, config, contracts, generated);
        applyMemberPolicy(target, targetElement, config);
        return true;
    }

    // ------------------------------------------------------------------
    // final
    // ------------------------------------------------------------------

    private void markFinal(JCClassDecl target) {
        target.mods.flags |= Flags.FINAL;
        if (target.sym != null) target.sym.flags_field |= Flags.FINAL;
    }

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    private void retrofitConstructor(JCClassDecl target, TypeElement targetElement,
                                     UtilityConfig config, ContractAnnotations contracts,
                                     GeneratedAnnotations generated) {
        AccessLevel access = config.constructorAccess();
        if (!access.emits()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@UtilityClass(constructorAccess = NONE) is not expressible - every class has a "
                    + "constructor, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC",
                targetElement);
            return;
        }

        JCMethodDecl generatedCtor = null;
        boolean authorDeclaredOne = false;
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl m)) continue;
            if (!m.name.contentEquals("<init>")) continue;
            if ((m.mods.flags & Flags.GENERATEDCONSTR) != 0) generatedCtor = m;
            else authorDeclaredOne = true;
        }

        if (authorDeclaredOne) {
            // The author's constructor wins, as everywhere else in this
            // pipeline - but unlike a skipped bootstrap method this one defeats
            // the annotation outright, so it warrants a warning rather than a note.
            messager.printMessage(Diagnostic.Kind.WARNING,
                "@UtilityClass did not synthesise a throwing constructor - "
                    + targetElement.getSimpleName() + " declares its own. Delete it, or drop the "
                    + "annotation if the class is meant to be instantiable",
                targetElement);
            return;
        }
        if (generatedCtor == null) return;

        generatedCtor.mods.flags &= ~(Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE
            | Flags.GENERATEDCONSTR);
        long accessFlag = accessFlagFor(access);
        generatedCtor.mods.flags |= accessFlag;
        if (generatedCtor.sym != null) {
            generatedCtor.sym.flags_field &= ~(Flags.PUBLIC | Flags.PROTECTED | Flags.PRIVATE
                | Flags.GENERATEDCONSTR);
            generatedCtor.sym.flags_field |= accessFlag;
        }

        // Appended after the existing super() call rather than replacing the
        // body: a constructor body must still begin with the super invocation.
        JCExpression thrown = make.NewClass(
            null,
            List.nil(),
            types.qualIdent(UOE_FQN),
            List.of(make.Literal(config.message())),
            null
        );
        generatedCtor.body.stats = generatedCtor.body.stats.append(make.Throw(thrown));

        generatedCtor.mods.annotations = generatedCtor.mods.annotations
            .appendList(contracts.contract("-> fail", false, null));
        AstMarkers.markGenerated(generatedCtor, generated);
    }

    private static long accessFlagFor(AccessLevel access) {
        return switch (access) {
            case PUBLIC -> Flags.PUBLIC;
            case PROTECTED -> Flags.PROTECTED;
            case PRIVATE -> Flags.PRIVATE;
            case PACKAGE -> 0L;
            case NONE -> throw new IllegalStateException("guarded by emits() above");
        };
    }

    // ------------------------------------------------------------------
    // Members
    // ------------------------------------------------------------------

    private void applyMemberPolicy(JCClassDecl target, TypeElement targetElement,
                                   UtilityConfig config) {
        boolean rewrite = config.members() == UtilityClass.Members.MAKE_STATIC;
        for (JCTree def : target.defs) {
            if (def instanceof JCClassDecl nested) {
                if (config.nestedTypes()) markStatic(nested.mods, nested.sym);
                continue;
            }
            if (def instanceof JCMethodDecl m) {
                if (m.name.contentEquals("<init>")) continue;
                if ((m.mods.flags & Flags.STATIC) != 0) continue;
                if (rewrite) markStatic(m.mods, m.sym);
                else reportInstanceMember(targetElement, "method", m.name.toString());
                continue;
            }
            if (def instanceof JCVariableDecl f) {
                if ((f.mods.flags & Flags.STATIC) != 0) continue;
                if (rewrite) markStatic(f.mods, f.sym);
                else reportInstanceMember(targetElement, "field", f.name.toString());
            }
        }
    }

    private void markStatic(JCTree.JCModifiers mods, com.sun.tools.javac.code.Symbol sym) {
        mods.flags |= Flags.STATIC;
        if (sym != null) sym.flags_field |= Flags.STATIC;
    }

    private void reportInstanceMember(TypeElement targetElement, String kind, String name) {
        messager.printMessage(Diagnostic.Kind.ERROR,
            "@UtilityClass requires every member to be static - " + kind + " '" + name
                + "' is not. Add static, or write @UtilityClass(members = MAKE_STATIC) to have it "
                + "added implicitly",
            targetElement);
    }

}
