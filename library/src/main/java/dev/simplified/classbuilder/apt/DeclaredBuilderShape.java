package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    /** A type annotation, with its arguments when it carries any. */
    private static final Pattern TYPE_ANNOTATION = Pattern.compile("@\\s*[\\w$.]+(\\s*\\([^()]*\\))?");

    /** A run of whitespace. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** A type punctuation character with the whitespace on either side of it. */
    private static final Pattern AROUND_PUNCTUATION = Pattern.compile("\\s*([<>,\\[\\].])\\s*");

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
        // Asked only where a generated member depends on the answer. On a chain
        // the build method a link inherits has to be the one its role declares,
        // so a different return type cannot stand in for it. Standing alone
        // nothing generated calls build() at all - the author's is simply kept
        // and reported as kept - so rejecting one there refuses source javac
        // accepts.
        DeclaredBuildMethod build = facts.buildMethod();
        if (role.isChained() && build != null
            && !expectation.buildReturnType().equals(build.returnType())) {
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

    /**
     * Reports a declared builder field whose type is not the one the merge holds
     * the slot of its name in, which the generated setter would otherwise fail to
     * assign on a line the author never wrote.
     *
     * <p>Compared against the <em>storage</em> type rather than the declared one,
     * because the two differ for three shapes and the setters assign into the
     * first. Comparing against the declared type gets a lazy field wrong in both
     * directions at once: it rejects the supplier spelling, which is the only one
     * the generated setters can assign, and accepts the natural one, which then
     * fails on a generated line.
     *
     * <p>Compared on the erased simple name of each rendered type, which is what
     * both halves can read without a resolve. Two types sharing an erasure and
     * differing in their arguments therefore pass - a missed diagnostic, never a
     * false one, javac still refusing the assignment. Both types are rendered
     * through {@link #typeText} before they are compared or printed, so the
     * sentence does not depend on which model spelled them.
     *
     * @param declaredName the declared builder's simple name
     * @param slotName the slot's name, which the declared field shares
     * @param writtenType the declared field's type as written
     * @param storageType the type the merge holds the slot in
     * @param holding the form the slot is held in, which decides the trailing clause
     * @return the diagnostic text both halves report, or {@code null} when the field can hold the slot
     */
    public static @Nullable String mistypedSlot(@NotNull String declaredName,
                                                @NotNull String slotName,
                                                @NotNull String writtenType,
                                                @NotNull String storageType,
                                                @NotNull SlotHolding holding) {
        String written = typeText(writtenType);
        String storage = typeText(storageType);
        if (erasedName(written).equals(erasedName(storage))) return null;
        return "@ClassBuilder merged into '" + declaredName + "' finds '" + slotName
            + "' declared as " + written + ", and the slot it stands for is " + storage
            + " - the generated setter has nothing to assign it to" + holding.clause();
    }

    /**
     * Renders the supplier a slot is held as when its {@link SlotHolding} is one.
     *
     * <p>A primitive is boxed, because the generated storage is
     * {@code Supplier<Integer>} and {@code Supplier<int>} names no type at all.
     *
     * @param declaredType the slot's declared type
     * @return the supplier type holding it
     */
    public static @NotNull String supplierOf(@NotNull String declaredType) {
        String type = typeText(declaredType);
        String boxed = switch (type) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "char" -> "java.lang.Character";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            default -> type;
        };
        return "java.util.function.Supplier<" + boxed + ">";
    }

    /**
     * A rendered type in the one spelling both halves print it in.
     *
     * <p>The two models render the same type differently: javac joins type
     * arguments with a bare comma and can carry a type annotation into the
     * rendering, PSI separates arguments with a comma and a space and leaves the
     * annotation out, and a type read as written keeps whatever spacing the
     * author typed. Type annotations are dropped, whitespace is collapsed and
     * removed around the type punctuation, and every comma is followed by one
     * space.
     *
     * @param type the type as either model renders it
     * @return the normalised rendering
     */
    public static @NotNull String typeText(@NotNull String type) {
        String out = TYPE_ANNOTATION.matcher(type).replaceAll(" ");
        out = WHITESPACE.matcher(out).replaceAll(" ").trim();
        out = AROUND_PUNCTUATION.matcher(out).replaceAll("$1");
        return out.replace(",", ", ");
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
