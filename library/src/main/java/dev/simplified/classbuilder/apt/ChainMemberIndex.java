package dev.simplified.classbuilder.apt;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
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

    private static final String GENERATED_FQN = "dev.simplified.annotations.Generated";

    private final boolean builderPresent;
    private final int builderTypeParameters;
    private final Set<String> authoredNoArgMethods;
    private final AccessLevel builderAccess;
    private final boolean declaresConstructors;
    private final @Nullable AccessLevel noArgumentConstructorAccess;
    private final @Nullable Boolean authoredSelfPublic;

    private ChainMemberIndex(boolean builderPresent, int builderTypeParameters, Set<String> authoredNoArgMethods,
                             AccessLevel builderAccess, boolean declaresConstructors,
                             @Nullable AccessLevel noArgumentConstructorAccess,
                             @Nullable Boolean authoredSelfPublic) {
        this.builderPresent = builderPresent;
        this.builderTypeParameters = builderTypeParameters;
        this.authoredNoArgMethods = authoredNoArgMethods;
        this.builderAccess = builderAccess;
        this.declaresConstructors = declaresConstructors;
        this.noArgumentConstructorAccess = noArgumentConstructorAccess;
        this.authoredSelfPublic = authoredSelfPublic;
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
            // A builder this round generated is not the author's, and its shape
            // is whatever the generator gave it - so it answers nothing about
            // what the author already spells and nothing about whether a clause
            // can name it. Reading one as declared blamed the ancestor's author
            // for a class they never wrote.
            if (AstMarkers.isGenerated(nested)) return absent();
            Set<String> authored = new HashSet<>();
            boolean declaresConstructors = false;
            AccessLevel noArgumentConstructor = null;
            Boolean selfPublic = null;
            for (JCTree member : nested.defs) {
                if (!(member instanceof JCMethodDecl method)) continue;
                // javac's default is in the tree before the round, flagged as
                // its own; a class declaring nothing else reads as declaring
                // nothing, which is what the element view's default also says.
                if (method.name.contentEquals("<init>")) {
                    if ((method.mods.flags & Flags.GENERATEDCONSTR) != 0) continue;
                    declaresConstructors = true;
                    if (method.params.isEmpty()) noArgumentConstructor = accessOf(method.mods.flags);
                    continue;
                }
                if (!method.params.isEmpty()) continue;
                if (AstMarkers.isGenerated(member)) continue;
                if (!recordsConcreteMatches(ancestorRole)
                    && !isAbstract(method)) {
                    continue;
                }
                authored.add(method.name.toString());
                if (method.name.contentEquals(ChainBuilderReach.SELF))
                    selfPublic = (method.mods.flags & Flags.PUBLIC) != 0;
            }
            return new ChainMemberIndex(true, nested.typarams == null ? 0 : nested.typarams.size(),
                authored, accessOf(nested.mods.flags), declaresConstructors, noArgumentConstructor, selfPublic);
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
            // The tree view reads the marker the pass set; a compiled ancestor
            // has no tree, and what survives into the class file is the
            // annotation the generator writes onto everything it emits. An
            // ancestor built with emitGenerated off is the one shape this cannot
            // tell apart, and it reads as the author's.
            if (carriesGeneratedAnnotation(nested)) return absent();
            Set<String> authored = new HashSet<>();
            boolean declaresConstructors = false;
            AccessLevel noArgumentConstructor = null;
            Boolean selfPublic = null;
            for (Element member : nested.getEnclosedElements()) {
                // A class file carries javac's default like any other
                // constructor, at the class's own access, so it is read as
                // one - the answer the tree view gives by reading the class's.
                if (member.getKind() == ElementKind.CONSTRUCTOR) {
                    declaresConstructors = true;
                    if (((ExecutableElement) member).getParameters().isEmpty())
                        noArgumentConstructor = accessOf(member.getModifiers());
                    continue;
                }
                if (member.getKind() != ElementKind.METHOD) continue;
                ExecutableElement method = (ExecutableElement) member;
                if (!method.getParameters().isEmpty()) continue;
                // A builder the author declared carries the members merged into
                // it as well, each with the annotation the generator writes, so
                // they are skipped as the tree view skips a marked node.
                if (carriesGeneratedAnnotation(method)) continue;
                boolean methodAbstract = method.getModifiers().contains(Modifier.ABSTRACT);
                if (!recordsConcreteMatches(ancestorRole) && !methodAbstract) continue;
                authored.add(method.getSimpleName().toString());
                if (method.getSimpleName().contentEquals(ChainBuilderReach.SELF))
                    selfPublic = method.getModifiers().contains(Modifier.PUBLIC);
            }
            return new ChainMemberIndex(true, nested.getTypeParameters().size(), authored,
                accessOf(nested.getModifiers()), declaresConstructors, noArgumentConstructor, selfPublic);
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

    /**
     * Whether a compiled nested type or member carries the marker the generator
     * writes onto everything it emits.
     *
     * @param element the ancestor's nested type, or a member of it
     * @return whether it was generated rather than written
     */
    private static boolean carriesGeneratedAnnotation(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (GENERATED_FQN.contentEquals(mirror.getAnnotationType().toString())) return true;
        }
        return false;
    }

    private static boolean isAbstract(JCMethodDecl method) {
        return (method.mods.flags & Flags.ABSTRACT) != 0;
    }

    /** The access a tree node's modifier flags give it. */
    private static AccessLevel accessOf(long flags) {
        return ChainBuilderReach.accessOf((flags & Flags.PUBLIC) != 0, (flags & Flags.PROTECTED) != 0,
            (flags & Flags.PRIVATE) != 0);
    }

    /** The access an element's modifiers give it. */
    private static AccessLevel accessOf(Set<Modifier> modifiers) {
        return ChainBuilderReach.accessOf(modifiers.contains(Modifier.PUBLIC),
            modifiers.contains(Modifier.PROTECTED), modifiers.contains(Modifier.PRIVATE));
    }

    private static ChainMemberIndex absent() {
        return new ChainMemberIndex(false, 0, Set.of(), AccessLevel.PUBLIC, false, null, null);
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

    /**
     * Whether the {@code self()} the ancestor's builder's author wrote is public.
     *
     * @return whether it is public, or null when the author wrote none or the builder is absent
     */
    public @Nullable Boolean authoredSelfPublic() {
        return authoredSelfPublic;
    }

    /**
     * Decides, through {@link ChainBuilderReach#unreachable}, whether a link's
     * generated builder can extend the ancestor's builder.
     *
     * @param samePackage whether the link is in the ancestor's package
     * @return why it cannot, or null when it can or the builder is absent - generated, or not generated yet
     */
    public @Nullable ChainBuilderReach.Unreachable unreachable(boolean samePackage) {
        if (!builderPresent) return null;
        return ChainBuilderReach.unreachable(builderAccess, declaresConstructors, noArgumentConstructorAccess,
            samePackage);
    }

}
