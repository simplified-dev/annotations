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
 * needs; one the author wrote carries whatever they wrote, and a link cannot
 * repair it, so the author's builder is judged on the self-typed role that
 * declares it and on every link below it.
 *
 * <p>Every question is answered from names, flags and parameter counts, never
 * from a resolved type: the processor reads them off a tree the round is still
 * building or off an ancestor's class file, and the editor off PSI it must not
 * resolve.
 */
public final class ChainBuilderReach {

    /** The name of the accessor a self-typed builder returns itself through. */
    public static final @NotNull String SELF = "self";

    private ChainBuilderReach() {
    }

    /** Why a link's generated builder cannot extend its annotated ancestor's. */
    public enum Unreachable {

        /** The ancestor's builder is private, so no other class's extends clause can name it. */
        PRIVATE_BUILDER("is private"),

        /** The ancestor's builder is package-private and the link is in another package. */
        PACKAGE_BUILDER("is package-private, and '%s' is in another package"),

        /** The ancestor's builder declares constructors and none of them takes no parameters. */
        NO_NO_ARGUMENT_CONSTRUCTOR("declares no constructor taking no parameters"),

        /** The ancestor's builder's no-argument constructor is private. */
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
     * to every other class and a package-private one to a link in another
     * package; a protected one is a member of the ancestor the link inherits. The
     * implicit {@code super()} has to reach a no-argument constructor on the same
     * terms: a builder declaring none has javac's default, at the builder's own
     * access, and one declaring constructors none of which takes no parameters
     * has nothing to call. A protected constructor is reached from the link's
     * builder in any package, which is a subclass.
     *
     * @param builderAccess the ancestor's builder's access
     * @param declaresConstructors whether the ancestor's builder declares any constructor, javac's default
     *     entered for one declaring none aside
     * @param noArgumentConstructorAccess the access of the no-argument constructor it declares, or null when it
     *     declares none
     * @param samePackage whether the link is in the ancestor's package
     * @return why the link cannot extend it, or null when it can
     */
    public static @Nullable Unreachable unreachable(@NotNull AccessLevel builderAccess,
                                                    boolean declaresConstructors,
                                                    @Nullable AccessLevel noArgumentConstructorAccess,
                                                    boolean samePackage) {
        if (builderAccess == AccessLevel.PRIVATE) return Unreachable.PRIVATE_BUILDER;
        if (builderAccess == AccessLevel.PACKAGE && !samePackage) return Unreachable.PACKAGE_BUILDER;
        AccessLevel constructor = declaresConstructors ? noArgumentConstructorAccess : builderAccess;
        if (constructor == null) return Unreachable.NO_NO_ARGUMENT_CONSTRUCTOR;
        if (constructor == AccessLevel.PRIVATE) return Unreachable.PRIVATE_CONSTRUCTOR;
        if (constructor == AccessLevel.PACKAGE && !samePackage) return Unreachable.PACKAGE_CONSTRUCTOR;
        return null;
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
     * <p>A private builder cannot be named by any link's extends clause, and one
     * declaring constructors none of which takes no parameters leaves every
     * link's implicit {@code super()} nothing to call. A builder declaring none
     * keeps javac's default, which takes none.
     *
     * @param declaredName the declared builder's simple name
     * @param targetName the annotated type's simple name
     * @param builderPrivate whether the declared builder is private
     * @param declaredConstructors the parameter types of each constructor the builder declares, the ones a
     *     constructor annotation written on it appends included and javac's default excluded
     * @return the error, or null when a link below can extend the builder
     */
    public static @Nullable String unextendableBuilder(@NotNull String declaredName,
                                                       @NotNull String targetName,
                                                       boolean builderPrivate,
                                                       @NotNull List<List<String>> declaredConstructors) {
        if (builderPrivate) {
            return "@ClassBuilder merged into '" + declaredName + "' but it is private, so no builder "
                + "generated below '" + targetName + "' can extend it";
        }
        if (declaredConstructors.isEmpty() || declaredConstructors.stream().anyMatch(List::isEmpty)) return null;
        return "@ClassBuilder merged into '" + declaredName + "' but every constructor it declares takes "
            + "parameters, so no builder generated below '" + targetName + "' has one to call - declare a "
            + "no-argument constructor";
    }

    /**
     * Reports a {@code final} {@code self()} declared on a self-typed role's
     * builder, reported on the declared builder: every concrete link below it
     * overrides {@code self()} to return its own builder.
     *
     * @param declaredName the declared builder's simple name
     * @param targetName the annotated type's simple name
     * @param selfFinal whether the builder declares a {@code final} no-argument {@code self()}
     * @return the error, or null when the builder declares no final one
     */
    public static @Nullable String finalSelf(@NotNull String declaredName, @NotNull String targetName,
                                             boolean selfFinal) {
        if (!selfFinal) return null;
        return "@ClassBuilder merged into '" + declaredName + "' but its self() is final, so no builder "
            + "generated below '" + targetName + "' can override it";
    }

    /**
     * Decides whether the {@code self()} a link's generated builder overrides
     * with is public.
     *
     * <p>An override cannot narrow the access of the method it overrides, so
     * the nearest {@code self()} an ancestor's author wrote decides: a public
     * one is overridden publicly, and a protected one, or the protected one the
     * generator writes where no author wrote any, keeps the generated override
     * protected.
     *
     * @param nearestAuthoredSelfPublic whether the nearest annotated ancestor whose builder's author declares a
     *     no-argument {@code self()} declares it public, or null when none does
     * @return whether the link's {@code self()} is public
     */
    public static boolean linkSelfPublic(@Nullable Boolean nearestAuthoredSelfPublic) {
        return Boolean.TRUE.equals(nearestAuthoredSelfPublic);
    }

}
