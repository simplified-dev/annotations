package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The rules deciding whether a builder an author declares on a chain role can
 * be extended by the builders generated below it, and the sentences both halves
 * report where it cannot.
 *
 * <p>Every concrete link generates a builder that names its annotated
 * ancestor's builder in its extends clause, calls that builder's no-argument
 * constructor through the implicit {@code super()} of the constructor javac
 * gives it, and overrides the {@code self()} the ancestor's builder declares or
 * inherits. A builder the generator writes carries all three in the shape a link
 * needs, at the access its annotation asks for, which a link is judged against;
 * one the author wrote carries whatever they wrote, and a link cannot repair it,
 * so the author's builder is judged on the self-typed role that declares it and
 * on every link below it.
 *
 * <p>Every question is answered from names, flags, parameter counts and
 * rendered type text, never from a resolved type: the processor reads them off
 * a tree the round is still building or off an ancestor's class file, and the
 * editor off PSI it must not resolve. The one exception is a {@code self()} a
 * root's builder inherits, which each half finds through the builder's
 * resolved supertypes and hands over as flags and the text of its return type.
 */
public final class ChainBuilderReach {

    /** The name of the accessor a self-typed builder returns itself through. */
    public static final @NotNull String SELF = "self";

    private ChainBuilderReach() {
    }

    /**
     * A no-argument {@code self()} as the rules read it, one a builder declares
     * or one it inherits.
     *
     * @param isPublic whether it is public
     * @param isFinal whether it is final
     * @param declaringType the simple name of the supertype declaring an inherited one, null for one the builder
     *     declares
     * @param returnType the return type of an inherited one as a member of the builder, in either model's
     *     spelling, null for one the builder declares
     */
    public record SelfMethod(boolean isPublic, boolean isFinal, @Nullable String declaringType,
                             @Nullable String returnType) {

        /**
         * Constructs a {@code self()} the builder declares, read for its access
         * and finality alone.
         *
         * @param isPublic whether it is public
         * @param isFinal whether it is final
         */
        public SelfMethod(boolean isPublic, boolean isFinal) {
            this(isPublic, isFinal, null, null);
        }

    }

    /** Why a link's generated builder cannot extend its annotated ancestor's. */
    public enum Unreachable {

        /** The ancestor's builder is private and the link is in another top-level class, which cannot name it. */
        PRIVATE_BUILDER("is private"),

        /** The ancestor's builder is package-private and the link is in another package. */
        PACKAGE_BUILDER("is package-private, and '%s' is in another package"),

        /** The ancestor's builder declares constructors and none of them takes no parameters. */
        NO_NO_ARGUMENT_CONSTRUCTOR("declares no constructor taking no parameters"),

        /** The ancestor's builder's no-argument constructor is private and the link is in another top-level class. */
        PRIVATE_CONSTRUCTOR("has a private no-argument constructor"),

        /** The ancestor's builder's no-argument constructor is package-private and the link is in another package. */
        PACKAGE_CONSTRUCTOR("has a package-private no-argument constructor, and '%s' is in another package");

        private final @NotNull String clause;

        Unreachable(@NotNull String clause) {
            this.clause = clause;
        }

    }

    /**
     * Reads the access a declaration's modifiers give it.
     *
     * @param isPublic whether it carries {@code public}
     * @param isProtected whether it carries {@code protected}
     * @param isPrivate whether it carries {@code private}
     * @return the access level, {@link AccessLevel#PACKAGE} when it carries none of the three
     */
    public static @NotNull AccessLevel accessOf(boolean isPublic, boolean isProtected, boolean isPrivate) {
        if (isPublic) return AccessLevel.PUBLIC;
        if (isProtected) return AccessLevel.PROTECTED;
        if (isPrivate) return AccessLevel.PRIVATE;
        return AccessLevel.PACKAGE;
    }

    /**
     * Decides whether a link's generated builder can extend the builder its
     * annotated ancestor's author declared.
     *
     * <p>The extends clause has to name the builder, which a private one refuses
     * to every class outside the top-level class it nests in and a
     * package-private one to a link in another package; a protected one is a
     * member of the ancestor the link inherits. The implicit {@code super()} has
     * to reach a no-argument constructor on the same terms: a builder declaring
     * none has javac's default, at the builder's own access, and one declaring
     * constructors none of which takes no parameters has nothing to call. A
     * protected constructor is reached from the link's builder in any package,
     * which is a subclass, and a private one from a link nested in the same
     * top-level class, which is a nestmate.
     *
     * @param builderAccess the ancestor's builder's access
     * @param declaresConstructors whether the ancestor's builder declares any constructor, javac's default
     *     entered for one declaring none aside
     * @param noArgumentConstructorAccess the access of the no-argument constructor it declares, or null when it
     *     declares none
     * @param samePackage whether the link is in the ancestor's package
     * @param sameTopLevel whether the link's outermost enclosing class is the ancestor's
     * @return why the link cannot extend it, or null when it can
     */
    public static @Nullable Unreachable unreachable(@NotNull AccessLevel builderAccess,
                                                    boolean declaresConstructors,
                                                    @Nullable AccessLevel noArgumentConstructorAccess,
                                                    boolean samePackage,
                                                    boolean sameTopLevel) {
        if (builderAccess == AccessLevel.PRIVATE && !sameTopLevel) return Unreachable.PRIVATE_BUILDER;
        if (builderAccess == AccessLevel.PACKAGE && !samePackage) return Unreachable.PACKAGE_BUILDER;
        AccessLevel constructor = declaresConstructors ? noArgumentConstructorAccess : builderAccess;
        if (constructor == null) return Unreachable.NO_NO_ARGUMENT_CONSTRUCTOR;
        if (constructor == AccessLevel.PRIVATE && !sameTopLevel) return Unreachable.PRIVATE_CONSTRUCTOR;
        if (constructor == AccessLevel.PACKAGE && !samePackage) return Unreachable.PACKAGE_CONSTRUCTOR;
        return null;
    }

    /**
     * Decides whether a link's generated builder can extend the builder the
     * generator writes for its annotated ancestor, at the access that
     * ancestor's {@code @ClassBuilder(access)} asks for.
     *
     * <p>The rule {@link #unreachable} applies to an author's builder: a
     * generated builder on a chain role declares no constructor, so javac's
     * default at the builder's own access is the one a link calls.
     *
     * @param builderAccess the access the ancestor's builder is generated at
     * @param samePackage whether the link is in the ancestor's package
     * @param sameTopLevel whether the link's outermost enclosing class is the ancestor's
     * @return why the link cannot extend it, or null when it can
     */
    public static @Nullable Unreachable unreachableGenerated(@NotNull AccessLevel builderAccess, boolean samePackage,
                                                             boolean sameTopLevel) {
        return unreachable(builderAccess, false, null, samePackage, sameTopLevel);
    }

    /**
     * Renders the error a link is refused with where its ancestor's builder is
     * out of its reach, reported on the link's annotation.
     *
     * @param reason what {@link #unreachable} returned
     * @param targetName the link's simple name
     * @param ancestorName the annotated ancestor's simple name
     * @param builderName the builder class name the chain is written in
     * @return the sentence both halves report
     */
    public static @NotNull String unreachableAncestorBuilder(@NotNull Unreachable reason,
                                                             @NotNull String targetName,
                                                             @NotNull String ancestorName,
                                                             @NotNull String builderName) {
        return "@ClassBuilder generates no builder on '" + targetName + "' - '" + ancestorName + "."
            + builderName + "', which its builder has to extend, " + String.format(reason.clause, targetName);
    }

    /**
     * Reports a builder declared on a self-typed role that no builder generated
     * below it can extend, reported on the declared builder.
     *
     * <p>A builder declaring constructors none of which takes no parameters
     * leaves every link's implicit {@code super()} nothing to call. A builder
     * declaring none keeps javac's default, which takes none. A private builder
     * is not refused here: a link nested in the same top-level class can extend
     * it, and a link anywhere else is refused on its own annotation through
     * {@link #unreachable}.
     *
     * @param declaredName the declared builder's simple name
     * @param targetName the annotated type's simple name
     * @param declaredConstructors the parameter types of each constructor the builder declares, the ones a
     *     constructor annotation written on it appends included and javac's default excluded
     * @return the error, or null when a link below can extend the builder
     */
    public static @Nullable String unextendableBuilder(@NotNull String declaredName,
                                                       @NotNull String targetName,
                                                       @NotNull List<List<String>> declaredConstructors) {
        if (declaredConstructors.isEmpty() || declaredConstructors.stream().anyMatch(List::isEmpty)) return null;
        return "@ClassBuilder merged into '" + declaredName + "' but every constructor it declares takes "
            + "parameters, so no builder generated below '" + targetName + "' has one to call - declare a "
            + "no-argument constructor";
    }

    /**
     * Decides whether a method a root's declared builder finds on one of its
     * supertypes is a {@code self()} the builder inherits.
     *
     * <p>A root's builder that inherits a {@code self()} is read as declaring
     * it: the merge appends none beside it, a final one refuses the builders
     * generated below, and a public one makes their overrides public. Only a
     * method the builder inherits counts - not a private one, not a static one,
     * which no link overrides, and not a package-private one declared in
     * another package.
     *
     * @param name the method's name
     * @param parameterCount how many parameters it takes
     * @param isStatic whether it is static
     * @param access its access
     * @param samePackage whether the supertype declaring it is in the builder's package
     * @return whether it is the builder's inherited {@code self()}
     */
    public static boolean inheritedAsSelf(@NotNull String name, int parameterCount, boolean isStatic,
                                          @NotNull AccessLevel access, boolean samePackage) {
        if (!SELF.equals(name) || parameterCount != 0 || isStatic) return false;
        if (access == AccessLevel.PRIVATE) return false;
        return access != AccessLevel.PACKAGE || samePackage;
    }

    /**
     * Picks the {@code self()} standing for a root's declared builder's own:
     * the one its author declared, or where they declared none the nearest one
     * it inherits.
     *
     * @param declared the no-argument {@code self()} the author declared, or null when they declared none
     * @param inherited the nearest {@code self()} the builder inherits, or null when it inherits none
     * @return the method the rules read, or null when there is none
     */
    public static @Nullable SelfMethod rootSelf(@Nullable SelfMethod declared, @Nullable SelfMethod inherited) {
        return declared != null ? declared : inherited;
    }

    /**
     * Decides whether the merge into a root's declared builder appends the
     * abstract {@code self()} the root generates, which a {@code self()} the
     * builder inherits covers as one its author declares does.
     *
     * @param inherited the nearest {@code self()} the builder inherits, or null when it inherits none
     * @return whether the generated one is appended
     */
    public static boolean appendsSelf(@Nullable SelfMethod inherited) {
        return inherited == null;
    }

    /**
     * Reports a {@code final} {@code self()} on a self-typed role's builder,
     * declared or on a root inherited, reported on the declared builder: every
     * concrete link below it overrides {@code self()} to return its own builder.
     *
     * @param declaredName the declared builder's simple name
     * @param targetName the annotated type's simple name
     * @param selfFinal whether the builder's no-argument {@code self()} is {@code final}
     * @return the error, or null when it is not
     */
    public static @Nullable String finalSelf(@NotNull String declaredName, @NotNull String targetName,
                                             boolean selfFinal) {
        if (!selfFinal) return null;
        return "@ClassBuilder merged into '" + declaredName + "' but its self() is final, so no builder "
            + "generated below '" + targetName + "' can override it";
    }

    /**
     * Reports a {@code self()} a root's declared builder inherits whose return
     * type, as a member of the builder, is not the builder's own self-type
     * parameter, reported on the declared builder.
     *
     * <p>An inherited {@code self()} stands for one the author declares, so the
     * merge appends none beside it, and every generated setter returns
     * {@code self()} as the pair's builder parameter - {@code B} on a builder
     * extending {@code Fluent<B>}. Where the builder passes the supertype
     * another type, {@code Fluent<String>}, each setter's return fails on a
     * generated line. The return type is compared by its unqualified text, so
     * only the pair's own parameter, read by its name, passes.
     *
     * @param declaredName the declared builder's simple name
     * @param selfBuilderName the name of the pair's builder parameter, which the generated setters return
     * @param inherited the {@code self()} the builder inherits, or null when it inherits none or declares its own
     * @return the error, or null when the inherited {@code self()} returns the pair's builder parameter
     */
    public static @Nullable String mistypedInheritedSelf(@NotNull String declaredName,
                                                         @NotNull String selfBuilderName,
                                                         @Nullable SelfMethod inherited) {
        if (inherited == null || inherited.returnType() == null) return null;
        String returned = DeclaredBuilderShape.unqualified(DeclaredBuilderShape.typeText(inherited.returnType()));
        if (returned.equals(selfBuilderName)) return null;
        return "@ClassBuilder merged into '" + declaredName + "' finds self() inherited from "
            + inherited.declaringType() + " returning " + returned + ", where the generated setters need it to "
            + "return " + selfBuilderName;
    }

    /**
     * Reports a {@code final} {@code self()} on the builder of an annotated
     * ancestor the processor did not judge, reported on a concrete link's
     * annotation.
     *
     * <p>{@link #finalSelf} is reported where the ancestor's own merge runs, on
     * its builder, and a link below an ancestor compiled in the same round
     * adds nothing to it. An ancestor compiled without the processor reaches a
     * link through its class file with nothing reported, and the link's
     * generated builder overrides {@code self()} - so the link is refused on its
     * own annotation, and nothing is generated for it.
     *
     * @param targetName the link's simple name
     * @param ancestorName the simple name of the annotated ancestor whose builder holds the nearest {@code self()}
     * @param builderName the builder class name the chain is written in
     * @param nearest the nearest {@code self()} an annotated ancestor's builder declares, or on a root inherits,
     *     or null when none does
     * @param judged whether the processor judged that ancestor's builder - one compiled in the same round
     * @return the error, or null when the link can override it or the ancestor's own error stands
     */
    public static @Nullable String unjudgedFinalSelf(@NotNull String targetName, @NotNull String ancestorName,
                                                     @NotNull String builderName, @Nullable SelfMethod nearest,
                                                     boolean judged) {
        if (nearest == null || !nearest.isFinal() || judged) return null;
        return "@ClassBuilder generates no builder on '" + targetName + "' - the self() of '" + ancestorName + "."
            + builderName + "' is final, so its builder cannot override it";
    }

    /**
     * Decides whether the {@code self()} a link's generated builder overrides
     * with is public.
     *
     * <p>An override cannot narrow the access of the method it overrides, so
     * the nearest {@code self()} an ancestor's author wrote, or a root's builder
     * inherits, decides: a public one is overridden publicly, and a protected
     * one, or the protected one the generator writes where there is none, keeps
     * the generated override protected.
     *
     * @param nearestAuthoredSelfPublic whether the nearest annotated ancestor whose builder's author declares a
     *     no-argument {@code self()}, or on a root inherits one, has it public, or null when none does
     * @return whether the link's {@code self()} is public
     */
    public static boolean linkSelfPublic(@Nullable Boolean nearestAuthoredSelfPublic) {
        return Boolean.TRUE.equals(nearestAuthoredSelfPublic);
    }

}
