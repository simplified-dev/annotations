package dev.simplified.classbuilder.apt;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.ClassBuilder;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.javac.AstMarkers;
import dev.simplified.shared.javac.JavacBridge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * builder this pass will generate may not have it yet. Every question about
 * what the author wrote is therefore asked so that <em>absent</em> means "say
 * nothing" rather than "no" - a builder that is not there yet is one that will
 * be there, generated, in the shape the generator gives it. The one thing a
 * generated builder's shape leaves open is its access, which the ancestor's
 * {@code @ClassBuilder(access)} decides, so that is read from the annotation in
 * the tree view and from the generated builder's class file in the element
 * view.
 */
public final class ChainMemberIndex {

    private static final String GENERATED_FQN = "dev.simplified.annotations.Generated";

    private final boolean builderPresent;
    private final int builderTypeParameters;
    private final Set<String> authoredNoArgMethods;
    private final AccessLevel builderAccess;
    private final boolean declaresConstructors;
    private final @Nullable AccessLevel noArgumentConstructorAccess;
    private final @Nullable Boolean selfPublic;
    private final @Nullable AccessLevel generatedBuilderAccess;

    private ChainMemberIndex(boolean builderPresent, int builderTypeParameters, Set<String> authoredNoArgMethods,
                             AccessLevel builderAccess, boolean declaresConstructors,
                             @Nullable AccessLevel noArgumentConstructorAccess,
                             @Nullable Boolean selfPublic, @Nullable AccessLevel generatedBuilderAccess) {
        this.builderPresent = builderPresent;
        this.builderTypeParameters = builderTypeParameters;
        this.authoredNoArgMethods = authoredNoArgMethods;
        this.builderAccess = builderAccess;
        this.declaresConstructors = declaresConstructors;
        this.noArgumentConstructorAccess = noArgumentConstructorAccess;
        this.selfPublic = selfPublic;
        this.generatedBuilderAccess = generatedBuilderAccess;
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
        if (tree != null) return fromTree(bridge, ancestor, tree, builderName, ancestorRole);
        return fromElements(bridge, ancestor, builderName, ancestorRole);
    }

    /**
     * The tree view, which is the only one an ancestor in this round has.
     *
     * @param bridge the javac bridge
     * @param element the ancestor's type element, whose annotation and supertypes the round has entered
     * @param ancestor the ancestor's class declaration
     * @param builderName the builder class name the chain is written in
     * @param ancestorRole where the ancestor sits in the chain
     * @return the index
     */
    private static ChainMemberIndex fromTree(JavacBridge bridge, TypeElement element, JCClassDecl ancestor,
                                             String builderName, ChainRole ancestorRole) {
        for (JCTree def : ancestor.defs) {
            if (!(def instanceof JCClassDecl nested)) continue;
            if (!nested.name.contentEquals(builderName)) continue;
            // A builder this round generated is not the author's, and its shape
            // is whatever the generator gave it - so it answers nothing about
            // what the author already spells. Reading one as declared blamed the
            // ancestor's author for a class they never wrote. Its access is the
            // one the ancestor's annotation asks for.
            if (AstMarkers.isGenerated(nested)) return generated(generatedAccessOf(element));
            Set<String> authored = new HashSet<>();
            boolean declaresConstructors = false;
            AccessLevel noArgumentConstructor = null;
            ChainBuilderReach.SelfMethod declaredSelf = null;
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
                if (method.name.contentEquals(ChainBuilderReach.SELF)) {
                    declaredSelf = new ChainBuilderReach.SelfMethod((method.mods.flags & Flags.PUBLIC) != 0,
                        (method.mods.flags & Flags.FINAL) != 0);
                }
            }
            return new ChainMemberIndex(true, nested.typarams == null ? 0 : nested.typarams.size(),
                authored, accessOf(nested.mods.flags), declaresConstructors, noArgumentConstructor,
                selfPublic(bridge, ancestorRole, declaredSelf, nested.sym), null);
        }
        // Not generated yet: the generator writes it at the annotation's access.
        return generated(generatedAccessOf(element));
    }

    /**
     * The element view, for an ancestor compiled before this round.
     *
     * @param bridge the javac bridge
     * @param ancestor the ancestor's type element
     * @param builderName the builder class name the chain is written in
     * @param ancestorRole where the ancestor sits in the chain
     * @return the index
     */
    private static ChainMemberIndex fromElements(JavacBridge bridge, TypeElement ancestor, String builderName,
                                                 ChainRole ancestorRole) {
        for (Element enclosed : ancestor.getEnclosedElements()) {
            if (!(enclosed instanceof TypeElement nested)) continue;
            if (!nested.getSimpleName().contentEquals(builderName)) continue;
            // The tree view reads the marker the pass set; a compiled ancestor
            // has no tree, and what survives into the class file is the
            // annotation the generator writes onto everything it emits, beside
            // the access it generated the builder at. An ancestor built with
            // emitGenerated off is the one shape this cannot tell apart, and it
            // reads as the author's.
            if (carriesGeneratedAnnotation(nested)) return generated(accessOf(nested.getModifiers()));
            Set<String> authored = new HashSet<>();
            boolean declaresConstructors = false;
            AccessLevel noArgumentConstructor = null;
            ChainBuilderReach.SelfMethod declaredSelf = null;
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
                if (method.getSimpleName().contentEquals(ChainBuilderReach.SELF)) {
                    declaredSelf = new ChainBuilderReach.SelfMethod(method.getModifiers().contains(Modifier.PUBLIC),
                        method.getModifiers().contains(Modifier.FINAL));
                }
            }
            return new ChainMemberIndex(true, nested.getTypeParameters().size(), authored,
                accessOf(nested.getModifiers()), declaresConstructors, noArgumentConstructor,
                selfPublic(bridge, ancestorRole, declaredSelf, nested), null);
        }
        return absent();
    }

    /**
     * Whether the {@code self()} standing for the ancestor's builder's own is
     * public - the one its author wrote, or on a root the one the builder
     * inherits where the author wrote none, as {@link ChainBuilderReach#rootSelf}
     * picks it.
     *
     * @param bridge the javac bridge
     * @param ancestorRole where the ancestor sits in the chain
     * @param declared the {@code self()} the author wrote, or null when they wrote none
     * @param builder the builder's element, or null when the tree has none entered
     * @return whether it is public, or null when there is none
     */
    private static @Nullable Boolean selfPublic(JavacBridge bridge, ChainRole ancestorRole,
                                                ChainBuilderReach.@Nullable SelfMethod declared,
                                                @Nullable TypeElement builder) {
        ChainBuilderReach.SelfMethod inherited = declared == null && ancestorRole == ChainRole.ABSTRACT_ROOT
            && builder != null ? inheritedSelf(bridge, builder) : null;
        ChainBuilderReach.SelfMethod self = ChainBuilderReach.rootSelf(declared, inherited);
        return self == null ? null : self.isPublic();
    }

    /**
     * The nearest {@code self()} a root's declared builder inherits, read from
     * the element model.
     *
     * <p>Its supertypes are walked depth first, each superclass ahead of the
     * interfaces beside it, {@code java.lang.Object} left out, and the first
     * method {@link ChainBuilderReach#inheritedAsSelf} accepts is the one. A
     * supertype compiled in the same round is read through the members written
     * in it, and one compiled before it through its class file.
     *
     * @param bridge the javac bridge
     * @param builder the declared builder's element
     * @return the inherited method, or null when the builder inherits none
     */
    public static ChainBuilderReach.@Nullable SelfMethod inheritedSelf(@NotNull JavacBridge bridge,
                                                                      @NotNull TypeElement builder) {
        Types types = bridge.processingEnvironment().getTypeUtils();
        Elements elements = bridge.processingEnvironment().getElementUtils();
        PackageElement home = elements.getPackageOf(builder);
        List<TypeElement> supertypes = new ArrayList<>();
        collectSupertypes(types, builder.asType(), supertypes, new HashSet<>());
        for (TypeElement supertype : supertypes) {
            boolean samePackage = elements.getPackageOf(supertype).equals(home);
            for (ExecutableElement method : ElementFilter.methodsIn(supertype.getEnclosedElements())) {
                Set<Modifier> modifiers = method.getModifiers();
                if (!ChainBuilderReach.inheritedAsSelf(method.getSimpleName().toString(),
                    method.getParameters().size(), modifiers.contains(Modifier.STATIC), accessOf(modifiers),
                    samePackage)) {
                    continue;
                }
                return new ChainBuilderReach.SelfMethod(modifiers.contains(Modifier.PUBLIC),
                    modifiers.contains(Modifier.FINAL));
            }
        }
        return null;
    }

    /**
     * Collects every supertype of a type but {@code java.lang.Object}, depth
     * first, each once.
     *
     * @param types the type utilities
     * @param type the type whose supertypes are collected
     * @param out the supertypes collected so far, appended to
     * @param seen the qualified names already collected
     */
    private static void collectSupertypes(Types types, TypeMirror type, List<TypeElement> out, Set<String> seen) {
        for (TypeMirror direct : types.directSupertypes(type)) {
            if (!(direct instanceof DeclaredType declaredType)
                || !(declaredType.asElement() instanceof TypeElement element)) continue;
            String name = element.getQualifiedName().toString();
            if (Object.class.getName().equals(name) || !seen.add(name)) continue;
            out.add(element);
            collectSupertypes(types, direct, out, seen);
        }
    }

    /**
     * The access the ancestor's {@code @ClassBuilder(access)} generates its
     * builder at, read as {@link BuilderAccess#generatedAt} reads it.
     *
     * @param ancestor the annotated ancestor
     * @return the access
     */
    private static AccessLevel generatedAccessOf(TypeElement ancestor) {
        return BuilderAccess.generatedAt(new AnnotationLookup().stringAttr(ancestor, ClassBuilder.class.getName(),
            BuilderAccess.ATTRIBUTE, null));
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
        return new ChainMemberIndex(false, 0, Set.of(), AccessLevel.PUBLIC, false, null, null, null);
    }

    /** An index of a builder the generator writes, which says nothing but the access it has. */
    private static ChainMemberIndex generated(AccessLevel access) {
        return new ChainMemberIndex(false, 0, Set.of(), AccessLevel.PUBLIC, false, null, null, access);
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
     * Whether the {@code self()} standing for the ancestor's builder's own is
     * public - the one its author wrote, or on a root the one the builder
     * inherits where the author wrote none.
     *
     * @return whether it is public, or null when there is none or the builder is absent
     */
    public @Nullable Boolean selfPublic() {
        return selfPublic;
    }

    /**
     * Decides whether a link's generated builder can extend the ancestor's
     * builder - one its author wrote through {@link ChainBuilderReach#unreachable},
     * one the generator writes, or will write, through
     * {@link ChainBuilderReach#unreachableGenerated} at the access it has.
     *
     * @param samePackage whether the link is in the ancestor's package
     * @param sameTopLevel whether the link's outermost enclosing class is the ancestor's
     * @return why it cannot, or null when it can or nothing is known of the builder
     */
    public @Nullable ChainBuilderReach.Unreachable unreachable(boolean samePackage, boolean sameTopLevel) {
        if (builderPresent) {
            return ChainBuilderReach.unreachable(builderAccess, declaresConstructors, noArgumentConstructorAccess,
                samePackage, sameTopLevel);
        }
        return generatedBuilderAccess == null
            ? null
            : ChainBuilderReach.unreachableGenerated(generatedBuilderAccess, samePackage, sameTopLevel);
    }

}
