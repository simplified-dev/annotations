package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether an author-declared nested builder can carry the members a role
 * generates.
 *
 * <p>The processor asks this before appending anything and reports what comes
 * back on the annotated class; the editor asks it before contributing anything
 * and the inspection reports the same answer on the declaration. Re-deriving the
 * rule on the plugin side is the drift the module split exists to prevent - the
 * editor would populate a builder javac rejects, and the author would see a full
 * completion list up to the moment the build failed.
 *
 * <p>Every question here is asked of names and flags, never of a resolved type,
 * because the javac side runs while the round is still building the tree and the
 * PSI side must not resolve anything - a resolve started from inside an augment
 * provider re-enters it.
 */
public final class DeclaredBuilderShape {

    private DeclaredBuilderShape() {
    }

    /**
     * Names the type parameters a declared builder has to re-declare for a role.
     *
     * <p>A self-typed role appends the pair to the target's own, in that order,
     * which is what lets a subclass bind the leading parameters exactly as the
     * target declares them. Every other role re-declares the target's and
     * nothing else.
     *
     * @param role the position the target holds in a builder chain
     * @param targetTypeParameters the target's own type parameter names, in declaration order
     * @param selfNames the two trailing parameter names a self-typed role appends
     * @return the required parameter names, in declaration order
     */
    public static @NotNull List<String> expectedTypeParameters(@NotNull ChainRole role,
                                                               @NotNull List<String> targetTypeParameters,
                                                               @NotNull List<String> selfNames) {
        if (!role.isSelfTyped()) return List.copyOf(targetTypeParameters);
        List<String> out = new ArrayList<>(targetTypeParameters.size() + selfNames.size());
        out.addAll(targetTypeParameters);
        out.addAll(selfNames);
        return List.copyOf(out);
    }

    /**
     * Names the builder supertype a role requires in the declared builder's
     * extends clause.
     *
     * @param role the position the target holds in a builder chain
     * @param superBuilderType the erased builder type of the nearest annotated ancestor, or null when there is none
     * @return the required supertype name, or null when the role requires no extends clause
     */
    public static @Nullable String expectedSuperType(@NotNull ChainRole role,
                                                     @Nullable String superBuilderType) {
        return role.hasAnnotatedSuper() ? superBuilderType : null;
    }

    /**
     * Names the type a role's build method returns.
     *
     * <p>A self-typed role builds the first of its trailing pair, each concrete
     * link below it producing its own; every other role builds the target.
     *
     * @param role the position the target holds in a builder chain
     * @param targetType the erased target type
     * @param builtTypeName the name a self-typed role binds to the type it builds
     * @return the erased return type the build method has to carry
     */
    public static @NotNull String expectedBuildReturnType(@NotNull ChainRole role,
                                                          @NotNull String targetType,
                                                          @NotNull String builtTypeName) {
        return role.isSelfTyped() ? builtTypeName : targetType;
    }

    /**
     * Reports why a declared builder cannot carry the generated members.
     *
     * <p>Ordered so the first answer is the one worth acting on: the modifiers
     * decide whether the class can hold the members at all, the parameter list
     * decides whether their types can be named, and the extends clause and the
     * build method decide whether what they resolve to is right. Reporting the
     * later ones over an unusable class would send the author after the
     * consequence rather than the cause.
     *
     * @param role the position the target holds in a builder chain
     * @param facts the declared builder as written
     * @param expectation the parameter names, supertype and build return type the role requires
     * @return the rejection, or null when the shape is usable
     */
    public static @Nullable DeclaredBuilderRejection check(@NotNull ChainRole role,
                                                           @NotNull DeclaredBuilderFacts facts,
                                                           @NotNull RoleExpectation expectation) {
        if (!facts.nestedStatic()) return DeclaredBuilderRejection.NOT_STATIC;
        if (role.isSelfTyped() && !facts.nestedAbstract()) {
            return DeclaredBuilderRejection.NOT_ABSTRACT;
        }
        if (!role.isSelfTyped() && facts.nestedAbstract()) {
            return DeclaredBuilderRejection.ABSTRACT_ON_CONCRETE_ROLE;
        }
        if (!facts.typeParameterNames().equals(expectation.typeParameterNames())) {
            return DeclaredBuilderRejection.TYPE_PARAMETERS;
        }
        if (role.isSelfTyped() && !selfTypeBoundsWritten(facts, expectation)) {
            return DeclaredBuilderRejection.SELF_TYPE_BOUNDS;
        }
        String expectedSuper = expectation.superType();
        if (expectedSuper != null) {
            if (facts.writtenSuperType() == null) return DeclaredBuilderRejection.MISSING_SUPER_TYPE;
            if (!expectedSuper.equals(facts.writtenSuperType())) {
                return DeclaredBuilderRejection.WRONG_SUPER_TYPE;
            }
        }
        DeclaredBuildMethod build = facts.buildMethod();
        if (build != null && !expectation.buildReturnType().equals(build.returnType())) {
            return DeclaredBuilderRejection.BUILD_RETURN_TYPE;
        }
        return null;
    }

    /**
     * Renders a rejection with the operands its wording names.
     *
     * <p>Which values a rejection interpolates is part of the wording rather
     * than of the caller, so it is decided here: the processor reporting on the
     * annotated class and the inspection reporting on the declaration produce
     * one sentence, and a test asserting a substring on one side is asserting it
     * of the other.
     *
     * @param rejection what {@link #check} returned
     * @param declaredName the declared builder's simple name
     * @param targetName the annotated type's simple name
     * @param builderMethodName the configured name of the static entry point
     * @param facts the declared builder as written
     * @param expectation what the role required
     * @return the diagnostic text
     */
    public static @NotNull String describe(@NotNull DeclaredBuilderRejection rejection,
                                           @NotNull String declaredName,
                                           @NotNull String targetName,
                                           @NotNull String builderMethodName,
                                           @NotNull DeclaredBuilderFacts facts,
                                           @NotNull RoleExpectation expectation) {
        return switch (rejection) {
            case NOT_STATIC, ABSTRACT_ON_CONCRETE_ROLE ->
                rejection.message(declaredName, builderMethodName);
            case NOT_ABSTRACT -> rejection.message(declaredName, targetName);
            case TYPE_PARAMETERS, SELF_TYPE_BOUNDS -> rejection.message(declaredName,
                names(expectation.typeParameterNames()), names(facts.typeParameterNames()));
            case MISSING_SUPER_TYPE -> rejection.message(declaredName, expectation.superType());
            case WRONG_SUPER_TYPE -> rejection.message(declaredName, expectation.superType(),
                facts.writtenSuperType());
            case BUILD_RETURN_TYPE -> rejection.message(declaredName,
                facts.buildMethod() == null ? "nothing" : facts.buildMethod().returnType(),
                expectation.buildReturnType());
        };
    }

    /**
     * The diagnostic for a link whose annotated supertype declares its own
     * nested builder.
     *
     * <p>Not one of the {@link DeclaredBuilderRejection} constants, which are
     * about the builder a target declares for itself. This one is about the
     * builder above it: the extends clause a link generates names the ancestor's
     * builder and passes it the ancestor's arguments plus two, and a builder the
     * ancestor's author wrote takes whatever they declared - usually none. The
     * clause then fails to resolve on a line nobody wrote, and the editor's own
     * answer was to leave the child's builder with no supertype and report
     * nothing, which is what hid the condition until the build ran.
     *
     * @param targetName the link's simple name
     * @param ancestorName the annotated supertype's simple name
     * @return the diagnostic text both halves report
     */
    public static @NotNull String ancestorDeclaresItsOwnBuilder(@NotNull String targetName,
                                                                @NotNull String ancestorName) {
        return "@ClassBuilder generates no builder on '" + targetName + "' - its annotated "
            + "supertype '" + ancestorName + "' declares its own nested builder";
    }

    /** A type-parameter list as it reads in a diagnostic, or {@code none}. */
    private static String names(List<String> parameters) {
        List<String> distinct = new ArrayList<>();
        for (String name : parameters) {
            if (!distinct.contains(name)) distinct.add(name);
        }
        return distinct.isEmpty() ? "none" : "<" + String.join(", ", distinct) + ">";
    }

    /**
     * A written type stripped of its arguments and its qualifier, for a
     * same-erasure comparison.
     *
     * <p>Shared because the two models render an applied type differently and
     * neither can be resolved where it is read - the javac side runs mid-round
     * and the PSI side must not start a resolve. Comparing the erased simple
     * name is what both can do, and it is what the facts are stated in.
     *
     * @param type the type as written
     * @return its erased simple name
     */
    public static @NotNull String erasedName(@NotNull String type) {
        int generics = type.indexOf('<');
        String raw = (generics < 0 ? type : type.substring(0, generics)).trim();
        int dot = raw.lastIndexOf('.');
        return dot < 0 ? raw : raw.substring(dot + 1);
    }

    /**
     * Whether the trailing pair carries any bound at all.
     *
     * <p>Presence rather than shape: the bound a self-typed pair needs is
     * spelled in the author's own parameter names and mentions the builder they
     * are declared on, so matching it textually would be matching a rendering
     * rather than a requirement. An unbounded pair is the one shape that cannot
     * work whatever the spelling - a setter returning it would return something
     * with no members - and it is what this catches.
     *
     * @param facts the declared builder as written
     * @param expectation what the role requires
     * @return whether both trailing parameters are bounded
     */
    private static boolean selfTypeBoundsWritten(DeclaredBuilderFacts facts,
                                                 RoleExpectation expectation) {
        int trailing = expectation.typeParameterNames().size() - 2;
        List<@Nullable String> bounds = facts.typeParameterBounds();
        if (trailing < 0 || bounds.size() < expectation.typeParameterNames().size()) return false;
        for (int i = trailing; i < bounds.size(); i++) {
            String bound = bounds.get(i);
            if (bound == null || bound.isEmpty()) return false;
        }
        return true;
    }

}
