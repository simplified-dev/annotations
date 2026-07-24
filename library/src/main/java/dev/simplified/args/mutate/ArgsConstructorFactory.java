package dev.simplified.args.mutate;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.GeneratedAnnotations;

/**
 * The single place a constructor declaration is minted.
 *
 * <p>Every constructor this library generates - the four constructor
 * annotations and the one a {@code @ClassBuilder} target's {@code build()}
 * invokes - passes through here, so the access-flag rule, the {@code <init>}
 * spelling and the coverage marker cannot drift between them. Callers supply
 * parameters and body statements already shaped, because the builder's are not
 * a plain one-parameter-per-field loop.
 */
public final class ArgsConstructorFactory {

    private final TreeMaker make;
    private final Names names;

    public ArgsConstructorFactory(TreeMaker make, Names names) {
        this.make = make;
        this.names = names;
    }

    /**
     * Builds the constructor declaration.
     *
     * @param access the requested visibility
     * @param isEnum whether the enclosing type is an {@code enum}
     * @param params the parameter declarations, in order
     * @param body the statements to run, in order
     * @param generated the coverage-marker attacher
     * @return the constructor, marked as generated
     */
    public JCMethodDecl mint(AccessLevel access, boolean isEnum, List<JCVariableDecl> params,
                             List<JCStatement> body, GeneratedAnnotations generated) {
        JCMethodDecl ctor = make.MethodDef(
            make.Modifiers(accessFlags(access, isEnum)),
            // Javac spells the constructor name as <init>.
            names.init,
            null,
            List.nil(),
            params,
            List.nil(),
            make.Block(0, body),
            null
        );
        AstMarkers.markGenerated(ctor, generated);
        return ctor;
    }

    /**
     * Translates an access level into javac modifier flags.
     *
     * <p>An {@code enum} constructor is forced {@code private}: the language
     * permits nothing else, and a written {@code access} there is a no-op that
     * has to be accepted rather than rejected, since it is a common spelling
     * carried over from other members of the same type.
     *
     * @param access the requested visibility
     * @param isEnum whether the enclosing type is an {@code enum}
     * @return the modifier flags
     * @throws IllegalStateException when passed {@link AccessLevel#NONE}, which
     *         callers reject before reaching here
     */
    public static long accessFlags(AccessLevel access, boolean isEnum) {
        if (isEnum) return Flags.PRIVATE;
        return switch (access) {
            case PUBLIC -> Flags.PUBLIC;
            case PROTECTED -> Flags.PROTECTED;
            case PRIVATE -> Flags.PRIVATE;
            case PACKAGE -> 0L;
            case NONE -> throw new IllegalStateException(
                "AccessLevel.NONE is rejected before constructor synthesis");
        };
    }

}
