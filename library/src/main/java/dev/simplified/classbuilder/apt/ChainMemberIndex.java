package dev.simplified.classbuilder.apt;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.HashSet;
import java.util.Set;

/**
 * What an annotated ancestor's builder already carries, read across the two
 * views an ancestor can be in.
 *
 * <p>An ancestor compiled in this round has a tree and no settled element model;
 * one compiled earlier has an element model and no tree. Neither view subsumes
 * the other and a read that used only one would be wrong for half of every
 * chain, so this asks {@link JavacBridge#treeOf} first and falls back to the
 * element model where it declines - the pairing
 * {@code MutationContext.collectZeroArgMethods} already uses for the same
 * reason.
 *
 * <p>The tree view carries one hazard the element view does not: within a round
 * the order targets are processed in is unspecified, so an ancestor whose
 * builder this pass will generate may not have it yet. Every question here is
 * therefore asked so that <em>absent</em> means "say nothing" rather than "no" -
 * a builder that is not there yet is one that will be there, generated, in the
 * shape the generator gives it.
 */
public final class ChainMemberIndex {

    private final boolean builderPresent;
    private final int builderTypeParameters;
    private final Set<String> authoredNoArgMethods;

    private ChainMemberIndex(boolean builderPresent, int builderTypeParameters,
                             Set<String> authoredNoArgMethods) {
        this.builderPresent = builderPresent;
        this.builderTypeParameters = builderTypeParameters;
        this.authoredNoArgMethods = authoredNoArgMethods;
    }

    /**
     * Indexes an annotated ancestor's builder.
     *
     * @param bridge the javac bridge
     * @param ancestor the annotated superclass
     * @param builderName the builder class name the chain is written in
     * @param ancestorRole where the ancestor itself sits in the chain
     * @return the index, reporting nothing present when the ancestor has no builder yet
     */
    public static @NotNull ChainMemberIndex of(@NotNull JavacBridge bridge,
                                               @NotNull TypeElement ancestor,
                                               @NotNull String builderName,
                                               @NotNull ChainRole ancestorRole) {
        JCClassDecl tree = bridge.treeOf(ancestor);
        if (tree != null) return fromTree(tree, builderName, ancestorRole);
        return fromElements(ancestor, builderName, ancestorRole);
    }

    /**
     * The tree view, which is the only one an ancestor in this round has.
     *
     * @param ancestor the ancestor's class declaration
     * @param builderName the builder class name the chain is written in
     * @param ancestorRole where the ancestor sits in the chain
     * @return the index
     */
    private static ChainMemberIndex fromTree(JCClassDecl ancestor, String builderName,
                                             ChainRole ancestorRole) {
        for (JCTree def : ancestor.defs) {
            if (!(def instanceof JCClassDecl nested)) continue;
            if (!nested.name.contentEquals(builderName)) continue;
            Set<String> authored = new HashSet<>();
            for (JCTree member : nested.defs) {
                if (!(member instanceof JCMethodDecl method) || !method.params.isEmpty()) continue;
                if (AstMarkers.isGenerated(member)) continue;
                if (!recordsConcreteMatches(ancestorRole)
                    && !isAbstract(method)) {
                    continue;
                }
                authored.add(method.name.toString());
            }
            return new ChainMemberIndex(true, nested.typarams == null ? 0 : nested.typarams.size(),
                authored);
        }
        return absent();
    }

    /**
     * The element view, for an ancestor compiled before this round.
     *
     * @param ancestor the ancestor's type element
     * @param builderName the builder class name the chain is written in
     * @param ancestorRole where the ancestor sits in the chain
     * @return the index
     */
    private static ChainMemberIndex fromElements(TypeElement ancestor, String builderName,
                                                 ChainRole ancestorRole) {
        for (Element enclosed : ancestor.getEnclosedElements()) {
            if (!(enclosed instanceof TypeElement nested)) continue;
            if (!nested.getSimpleName().contentEquals(builderName)) continue;
            Set<String> authored = new HashSet<>();
            for (Element member : nested.getEnclosedElements()) {
                if (member.getKind() != ElementKind.METHOD) continue;
                ExecutableElement method = (ExecutableElement) member;
                if (!method.getParameters().isEmpty()) continue;
                boolean methodAbstract = method.getModifiers().contains(Modifier.ABSTRACT);
                if (!recordsConcreteMatches(ancestorRole) && !methodAbstract) continue;
                authored.add(method.getSimpleName().toString());
            }
            return new ChainMemberIndex(true, nested.getTypeParameters().size(), authored);
        }
        return absent();
    }

    /**
     * Whether a concrete member found on this ancestor can be read as the
     * author's.
     *
     * <p>The test is one-directional and scoping it is the difference between
     * the index being right and being backwards. A self-typed role generates an
     * <em>abstract</em> build method and self accessor, so a concrete one found
     * on such an ancestor is necessarily the author's. A concrete link generates
     * a <em>concrete</em> pair, so a concrete one found there says nothing at all
     * - and reading it as authored would suppress the generated member on every
     * link below it.
     *
     * @param ancestorRole where the ancestor sits in the chain
     * @return whether a concrete match counts
     */
    private static boolean recordsConcreteMatches(ChainRole ancestorRole) {
        return ancestorRole.isSelfTyped();
    }

    private static boolean isAbstract(JCMethodDecl method) {
        return (method.mods.flags & Flags.ABSTRACT) != 0;
    }

    private static ChainMemberIndex absent() {
        return new ChainMemberIndex(false, 0, Set.of());
    }

    /**
     * Whether the ancestor carries a builder of that name at all.
     *
     * <p>False also means "not yet": within a round an ancestor's builder may be
     * generated after this is read, so a caller must treat false as no answer
     * rather than as a negative one.
     */
    public boolean builderPresent() {
        return builderPresent;
    }

    /** How many type parameters the ancestor's builder declares. */
    public int builderTypeParameters() {
        return builderTypeParameters;
    }

    /**
     * Whether the ancestor's builder already spells a no-argument method of that
     * name that the author wrote.
     *
     * @param name the member being considered for generation
     * @return whether generating it would land beside one the author already has
     */
    public boolean suppliesNoArg(@Nullable String name) {
        return name != null && authoredNoArgMethods.contains(name);
    }

}
