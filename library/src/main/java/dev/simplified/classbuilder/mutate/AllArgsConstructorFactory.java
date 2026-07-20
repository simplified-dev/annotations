package dev.simplified.classbuilder.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.shared.javac.AstMarkers;

/**
 * Produces the all-args constructor the generated {@code build()} invokes:
 *
 * <pre>{@code
 * Target(String name, int count) { this.name = name; this.count = count; }
 * }</pre>
 *
 * <p>Parameters follow field-declaration order so the positional
 * {@code new Target(f1, f2, ...)} call {@code NestedBuilderFactory} emits lines
 * up. Synthesis is suppressed when the target already declares a constructor of
 * its own, mirroring Lombok {@code @Builder}, which supplies its implicit
 * constructor only in the absence of an explicit one.
 *
 * <p>Runs before {@code LazyFieldMutator} so {@code @Lazy} fields get the same
 * parameter and assignment rewrite a hand-written constructor receives.
 */
final class AllArgsConstructorFactory {

    private final MutationContext ctx;
    private final TreeMaker make;
    private final Names names;

    AllArgsConstructorFactory(MutationContext ctx) {
        this.ctx = ctx;
        this.make = ctx.make();
        this.names = ctx.names();
    }

    /**
     * Builds the all-args constructor, assigning every builder-visible field
     * from a like-named parameter.
     */
    JCMethodDecl build() {
        ListBuffer<JCVariableDecl> params = new ListBuffer<>();
        ListBuffer<JCStatement> body = new ListBuffer<>();
        for (FieldSpec f : ctx.fields()) {
            params.append(make.VarDef(
                make.Modifiers(Flags.PARAMETER),
                names.fromString(f.name),
                ctx.types().parseType(f.typeDisplay),
                null
            ));
            JCExpression lhs = make.Select(make.Ident(names._this), names.fromString(f.name));
            body.append(make.Exec(make.Assign(lhs, make.Ident(names.fromString(f.name)))));
        }
        JCBlock block = make.Block(0, body.toList());
        // Javac spells the constructor name as <init>.
        JCMethodDecl ctor = make.MethodDef(
            make.Modifiers(MutationContext.accessFlagFor(ctx.config().constructorAccess())),
            names.init,
            null,
            List.nil(),
            params.toList(),
            List.nil(),
            block,
            null
        );
        AstMarkers.markGenerated(ctor);
        return ctor;
    }

    /**
     * Detects a constructor the author wrote. Javac's own default constructor
     * carries {@link Flags#GENERATEDCONSTR} and is not treated as explicit, so a
     * class declaring no constructor at all still qualifies for synthesis.
     *
     * @param target the class declaration to scan
     * @return whether the target declares a constructor of its own
     */
    static boolean hasExplicitConstructor(JCClassDecl target) {
        for (JCTree def : target.defs) {
            if (!(def instanceof JCMethodDecl m)) continue;
            if (!m.name.toString().equals("<init>")) continue;
            if ((m.mods.flags & Flags.GENERATEDCONSTR) != 0) continue;
            return true;
        }
        return false;
    }

}
