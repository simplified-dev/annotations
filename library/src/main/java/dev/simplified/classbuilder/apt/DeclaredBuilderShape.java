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

    /**
     * The qualifier in front of a name, which the element model spells and a
     * name read as written may not.
     */
    private static final Pattern QUALIFIER = Pattern.compile("(?:[A-Za-z_$][\\w$]*\\.)+(?=[A-Za-z_$])");

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
     * @param superBuilderType the nearest annotated ancestor's builder, qualified by the ancestor's simple name, or null when there is none
     * @return the required supertype name, or null when the role requires no extends clause
     */
    public static @Nullable String expectedSuperType(@NotNull ChainRole role,
                                                     @Nullable String superBuilderType) {
        return role.hasAnnotatedSuper() ? superBuilderType : null;
    }

    /**
     * Names the two trailing parameters a self-typed role's builder is spelled
     * in.
     *
     * <p>The declaration's own last two, when it declares at least the target's
     * parameters plus two: the author names the pair, and the generated members
     * merged into the declaration have to use those names. With fewer there is
     * no pair to read, and the names are the ones the generator would write -
     * {@code T} and {@code B}, each suffixed with {@code $} until it is not one
     * of the target's own - so the parameter-list rejection names the pair the
     * generator would have declared.
     *
     * @param role the position the target holds in a builder chain
     * @param targetTypeParameters the target's own type parameter names, in declaration order
     * @param declaredTypeParameters the declared builder's type parameter names, in declaration order
     * @return the built-type name followed by the builder-type name
     */
    public static @NotNull List<String> selfNames(@NotNull ChainRole role,
                                                  @NotNull List<String> targetTypeParameters,
                                                  @NotNull List<String> declaredTypeParameters) {
        int declared = declaredTypeParameters.size();
        if (role.isSelfTyped() && declared >= targetTypeParameters.size() + 2)
            return List.copyOf(declaredTypeParameters.subList(declared - 2, declared));
        List<String> taken = new ArrayList<>(targetTypeParameters);
        String built = freeName("T", taken);
        taken.add(built);
        return List.of(built, freeName("B", taken));
    }

    /**
     * Names the arguments a role requires its declared builder's extends clause
     * to pass, as erased simple names.
     *
     * <p>They are the ones the generated members are typed against: the
     * ancestor's own arguments as the target passes them to its superclass, then
     * the self-typed pair - bound to the link and its builder on a concrete
     * link, and forwarded as the declaration's own trailing pair on a chained
     * abstract. Erased and unqualified, because that is what both models can
     * read of a written argument without resolving it.
     *
     * @param role the position the target holds in a builder chain
     * @param superArguments the type arguments the target passes to its superclass, as either model renders them
     * @param targetName the target's simple name
     * @param builderName the builder's simple name
     * @param selfNames the declaration's trailing pair, from {@link #selfNames}
     * @return the required arguments, empty when the role requires no extends clause
     */
    public static @NotNull List<String> expectedSuperTypeArguments(@NotNull ChainRole role,
                                                                   @NotNull List<String> superArguments,
                                                                   @NotNull String targetName,
                                                                   @NotNull String builderName,
                                                                   @NotNull List<String> selfNames) {
        if (!role.hasAnnotatedSuper()) return List.of();
        List<String> out = erasedNames(superArguments);
        if (role.isSelfTyped()) {
            out.addAll(selfNames);
        } else {
            out.add(targetName);
            out.add(builderName);
        }
        return List.copyOf(out);
    }

    /**
     * Derives everything a role requires of the builder declared for it, from
     * names both models can read.
     *
     * @param role the position the target holds in a builder chain
     * @param targetName the target's simple name
     * @param builderName the builder's simple name
     * @param targetTypeParameters the type parameter names the builder re-declares, in declaration order
     * @param declaredTypeParameters the declared builder's type parameter names, in declaration order
     * @param ancestorName the annotated superclass's simple name, or null when there is none
     * @param superArguments the type arguments the target passes to its superclass, in order
     * @return the expectation the declaration is measured against
     */
    public static @NotNull RoleExpectation expectation(@NotNull ChainRole role,
                                                       @NotNull String targetName,
                                                       @NotNull String builderName,
                                                       @NotNull List<String> targetTypeParameters,
                                                       @NotNull List<String> declaredTypeParameters,
                                                       @Nullable String ancestorName,
                                                       @NotNull List<String> superArguments) {
        return expectation(role, targetName, builderName, targetTypeParameters, List.of(),
            declaredTypeParameters, ancestorName, superArguments);
    }

    /**
     * Derives everything a role requires of the builder declared for it, the
     * bounds on the target's own type parameters included.
     *
     * @param role the position the target holds in a chain
     * @param targetName the target's simple name
     * @param builderName the builder's simple name
     * @param targetTypeParameters the type parameter names the builder re-declares, in declaration order
     * @param targetTypeParameterBounds the bounds written on each of those parameters, as
     *     {@link DeclaredBuilderFacts#typeParameterBounds} holds a declaration's, null where none is written
     * @param declaredTypeParameters the declared builder's type parameter names, in declaration order
     * @param ancestorName the annotated superclass's simple name, or null when there is none
     * @param superArguments the type arguments the target passes to its superclass, in order
     * @return the expectation the declaration is measured against
     */
    public static @NotNull RoleExpectation expectation(@NotNull ChainRole role,
                                                       @NotNull String targetName,
                                                       @NotNull String builderName,
                                                       @NotNull List<String> targetTypeParameters,
                                                       @NotNull List<@Nullable String> targetTypeParameterBounds,
                                                       @NotNull List<String> declaredTypeParameters,
                                                       @Nullable String ancestorName,
                                                       @NotNull List<String> superArguments) {
        List<String> pair = selfNames(role, targetTypeParameters, declaredTypeParameters);
        return new RoleExpectation(
            expectedTypeParameters(role, targetTypeParameters, pair),
            expectedSuperType(role, ancestorName == null ? null : ancestorName + "." + builderName),
            expectedBuildReturnType(role, targetName, pair.get(0)),
            expectedSuperTypeArguments(role, superArguments, targetName, builderName, pair),
            acceptedBuildReturnTypes(role, targetName, pair.get(0)),
            targetTypeParameterBounds);
    }

    /**
     * Names every type a declared build method may return on a role and still
     * stand in for the generated one.
     *
     * <p>The one the role builds, and on an abstract root the root itself too:
     * nothing above a root declares a build method it has to override, and the
     * one each concrete link generates returns the link, a subtype of the root,
     * so it overrides a root's build method returning the root. Below a root the
     * build method a role declares overrides the self-typed one it inherits, and
     * only the type that self type is bound to can.
     *
     * @param role the position the target holds in a builder chain
     * @param targetType the erased target type
     * @param builtTypeName the name a self-typed role binds to the type it builds
     * @return the erased return types accepted, the one the role builds first
     */
    public static @NotNull List<String> acceptedBuildReturnTypes(@NotNull ChainRole role,
                                                                 @NotNull String targetType,
                                                                 @NotNull String builtTypeName) {
        String built = expectedBuildReturnType(role, targetType, builtTypeName);
        return role == ChainRole.ABSTRACT_ROOT ? List.of(built, targetType) : List.of(built);
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
     * <p>Ordered so the first answer is the one worth acting on: the kind and
     * the modifiers decide whether the class can hold the members at all, the
     * parameter list and its bounds decide whether their types can be named, and
     * the extends clause and the build method decide whether what they resolve
     * to is right. Reporting the later ones over an unusable class would send
     * the author after the consequence rather than the cause.
     *
     * <p>The kind comes first because a record, an enum and an interface are
     * each implicitly {@code static}, which the javac tree does not record on
     * the declaration and PSI does, so asking the modifier of one would split the
     * halves - and none of the three can be a builder whatever its modifiers: a
     * record takes no instance field, an enum no {@code new}, an interface
     * neither.
     *
     * @param role the position the target holds in a builder chain
     * @param facts the declared builder as written
     * @param expectation the parameter names, supertype and build return type the role requires
     * @return the rejection, or null when the shape is usable
     */
    public static @Nullable DeclaredBuilderRejection check(@NotNull ChainRole role,
                                                           @NotNull DeclaredBuilderFacts facts,
                                                           @NotNull RoleExpectation expectation) {
        if (!DeclaredBuilderFacts.CLASS.equals(facts.kind())) return DeclaredBuilderRejection.NOT_A_CLASS;
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
        if (!targetBoundsKept(facts, expectation)) return DeclaredBuilderRejection.TYPE_PARAMETER_BOUNDS;
        if (role.isSelfTyped() && !selfTypeBoundsWritten(facts, expectation)) {
            return DeclaredBuilderRejection.SELF_TYPE_BOUNDS;
        }
        String expectedSuper = expectation.superType();
        if (expectedSuper != null) {
            String written = facts.writtenSuperType();
            if (written == null) return DeclaredBuilderRejection.MISSING_SUPER_TYPE;
            if (!namesType(written, expectedSuper)) return DeclaredBuilderRejection.WRONG_SUPER_TYPE;
            // A raw clause passes nothing to compare, and whether the members
            // generated against it compile is not a question of names.
            if (!facts.superTypeArguments().isEmpty()
                && !erasedNames(facts.superTypeArguments()).equals(expectation.superTypeArguments())) {
                return DeclaredBuilderRejection.SUPER_TYPE_ARGUMENTS;
            }
        }
        // Asked only where a generated member depends on the answer. On a chain
        // the build method a link inherits has to be one every link's generated
        // build() overrides, so a return type outside the accepted ones cannot
        // stand in for it. Standing alone nothing generated calls build() at
        // all - the author's is simply kept and reported as kept - so rejecting
        // one there refuses source javac accepts.
        DeclaredBuildMethod build = facts.buildMethod();
        if (role.isChained() && build != null
            && !expectation.buildReturnTypes().contains(build.returnType())) {
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
     * @param role the position the target holds in a builder chain
     * @param declaredName the declared builder's simple name
     * @param targetName the annotated type's simple name
     * @param builderMethodName the configured name of the static entry point
     * @param facts the declared builder as written
     * @param expectation what the role required
     * @return the diagnostic text
     */
    public static @NotNull String describe(@NotNull DeclaredBuilderRejection rejection,
                                           @NotNull ChainRole role,
                                           @NotNull String declaredName,
                                           @NotNull String targetName,
                                           @NotNull String builderMethodName,
                                           @NotNull DeclaredBuilderFacts facts,
                                           @NotNull RoleExpectation expectation) {
        return switch (rejection) {
            case NOT_A_CLASS -> rejection.message(declaredName, kindPhrase(facts.kind()), builderMethodName);
            case NOT_STATIC, ABSTRACT_ON_CONCRETE_ROLE ->
                rejection.message(declaredName, builderMethodName);
            case NOT_ABSTRACT -> rejection.message(declaredName, targetName);
            case TYPE_PARAMETERS -> rejection.message(declaredName,
                requiredParameters(role, expectation.typeParameterNames()),
                names(facts.typeParameterNames()));
            case TYPE_PARAMETER_BOUNDS -> rejection.message(declaredName,
                boundedParameters(facts.typeParameterNames(), expectation.typeParameterBounds(),
                    expectation.typeParameterBounds().size()),
                boundedParameters(facts.typeParameterNames(), facts.typeParameterBounds(),
                    expectation.typeParameterBounds().size()));
            case SELF_TYPE_BOUNDS -> rejection.message(declaredName,
                requiredBounds(expectation.typeParameterNames(), targetName, declaredName),
                writtenPair(facts));
            case MISSING_SUPER_TYPE -> rejection.message(declaredName, expectation.superType());
            case WRONG_SUPER_TYPE -> rejection.message(declaredName, expectation.superType(),
                facts.writtenSuperType());
            case SUPER_TYPE_ARGUMENTS -> rejection.message(declaredName, expectation.superType(),
                "<" + String.join(", ", expectation.superTypeArguments()) + ">",
                "<" + String.join(", ", erasedNames(facts.superTypeArguments())) + ">");
            case BUILD_RETURN_TYPE -> rejection.message(declaredName,
                facts.buildMethod() == null ? "nothing" : facts.buildMethod().returnType(),
                expectation.buildReturnType());
        };
    }

    /**
     * Says which parameters a builder for the role has to declare, for the
     * parameter-list rejection.
     *
     * <p>A self-typed role's list is the target's own followed by the pair it
     * declares for itself, and on a target that is not generic the pair is all
     * of it - calling that list the target's type parameters would name
     * parameters the target does not have.
     *
     * @param role the position the target holds in a builder chain
     * @param expected the parameter names the role requires, in order
     * @return the clause naming them
     */
    private static String requiredParameters(ChainRole role, List<String> expected) {
        if (!role.isSelfTyped()) {
            return expected.isEmpty()
                ? "for a target with no type parameters has to declare none"
                : "for a generic target has to re-declare the target's type parameters " + names(expected);
        }
        int own = Math.max(0, expected.size() - 2);
        String pair = "the self-typed pair " + names(expected.subList(own, expected.size()));
        String opening = "for an abstract target in a builder chain has to ";
        return own == 0
            ? opening + "declare " + pair
            : opening + "re-declare the target's type parameters " + names(expected.subList(0, own))
                + " followed by " + pair;
    }

    /**
     * Renders the bounds a self-typed builder's trailing pair needs - the built
     * type bounded by the target, applied to its own parameters, and the
     * builder type bounded by the builder applied to every parameter.
     *
     * @param expected the parameter names the role requires, the pair last
     * @param targetName the target's simple name
     * @param declaredName the declared builder's simple name
     * @return the pair with its bounds, in angle brackets
     */
    private static String requiredBounds(List<String> expected, String targetName, String declaredName) {
        int own = Math.max(0, expected.size() - 2);
        List<String> ownNames = expected.subList(0, own);
        String built = expected.size() > own ? expected.get(own) : "T";
        String builder = expected.size() > own + 1 ? expected.get(own + 1) : "B";
        String target = ownNames.isEmpty() ? targetName : targetName + "<" + String.join(", ", ownNames) + ">";
        return "<" + built + " extends " + target + ", " + builder + " extends " + declaredName
            + "<" + String.join(", ", expected) + ">>";
    }

    /**
     * Renders a declaration's trailing pair with the bounds written on it.
     *
     * @param facts the declared builder as written
     * @return the pair in angle brackets, each parameter followed by its bound where one is written
     */
    private static String writtenPair(DeclaredBuilderFacts facts) {
        List<String> names = facts.typeParameterNames();
        List<@Nullable String> bounds = facts.typeParameterBounds();
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, names.size() - 2); i < names.size(); i++) {
            String bound = i < bounds.size() ? bounds.get(i) : null;
            out.add(bound == null || bound.isEmpty()
                ? names.get(i)
                : names.get(i) + " extends " + typeText(bound));
        }
        return out.isEmpty() ? "none" : "<" + String.join(", ", out) + ">";
    }

    /**
     * Renders the leading type parameters with the bounds given for them, for
     * the bound rejection.
     *
     * @param names the declaration's parameter names, which lead with the target's own
     * @param bounds the bounds to show beside them, in the same order, null where none is written
     * @param count how many leading parameters to render
     * @return the parameters in angle brackets, each followed by its bounds where there are any
     */
    private static String boundedParameters(List<String> names, List<@Nullable String> bounds, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count && i < names.size(); i++) {
            List<String> written = new ArrayList<>();
            for (String bound : boundList(i < bounds.size() ? bounds.get(i) : null))
                written.add(QUALIFIER.matcher(bound).replaceAll(""));
            out.add(written.isEmpty()
                ? names.get(i)
                : names.get(i) + " extends " + String.join(" & ", written));
        }
        return out.isEmpty() ? "none" : "<" + String.join(", ", out) + ">";
    }

    /** A declared kind as a sentence names it - {@code a record}, {@code an enum}. */
    private static String kindPhrase(String kind) {
        return switch (kind) {
            case DeclaredBuilderFacts.ENUM -> "an enum";
            case DeclaredBuilderFacts.INTERFACE -> "an interface";
            case DeclaredBuilderFacts.ANNOTATION -> "an annotation interface";
            default -> "a " + kind;
        };
    }

    /**
     * Whether the declared builder bounds each of the target's own type
     * parameters as the target does.
     *
     * <p>The generated members apply the builder's parameters to the target and
     * the target's to the builder - {@code build()} returns {@code Target<T>}
     * from inside the builder, {@code builder()} returns {@code Builder<T>} from
     * the target - so a bound looser on either side fails the other's bound
     * check on a line the author never wrote. The bounds are compared as simple
     * names at every level, in any order, a written {@code Object} being what no
     * bound means.
     *
     * @param facts the declared builder as written
     * @param expectation what the role requires, the target's bounds among it
     * @return whether every leading parameter carries the target's bounds
     */
    private static boolean targetBoundsKept(DeclaredBuilderFacts facts, RoleExpectation expectation) {
        List<@Nullable String> expected = expectation.typeParameterBounds();
        List<@Nullable String> written = facts.typeParameterBounds();
        for (int i = 0; i < expected.size(); i++) {
            if (!sameBounds(boundList(expected.get(i)), boundList(i < written.size() ? written.get(i) : null)))
                return false;
        }
        return true;
    }

    /** Whether two bound lists name the same types, in any order. */
    private static boolean sameBounds(List<String> expected, List<String> written) {
        if (expected.size() != written.size()) return false;
        List<String> unmatched = new ArrayList<>(written);
        for (String bound : expected) {
            boolean found = false;
            for (int i = 0; i < unmatched.size() && !found; i++) {
                if (sameSimpleType(bound, unmatched.get(i))) {
                    unmatched.remove(i);
                    found = true;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /**
     * Splits a parameter's bounds as written at their top-level {@code &},
     * each rendered through {@link #typeText}, dropping an {@code Object} bound.
     *
     * @param bounds the bounds as written, or null when none is
     * @return the bounds, in order
     */
    private static List<String> boundList(@Nullable String bounds) {
        List<String> out = new ArrayList<>();
        if (bounds == null || bounds.isBlank()) return out;
        String text = typeText(bounds);
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : '&';
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c != '&' || depth != 0) continue;
            String bound = text.substring(start, i).trim();
            if (!bound.isEmpty() && !"Object".equals(erasedName(bound))) out.add(bound);
            start = i + 1;
        }
        return out;
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
     * <p>Compared on simple names at every level - the type's own and each of
     * its type arguments', recursively - which is what both halves can read
     * without a resolve. The field has to hold the storage type exactly, since
     * the generated setter assigns into it and {@code build()} reads it back out,
     * so an argument differing at any depth is reported, a wildcard among them.
     * A raw spelling on either side is not, assigning and reading back with an
     * unchecked warning rather than an error, and neither is a primitive spelled
     * over its box or the reverse, which the setter assigns and {@code build()}
     * reads back under boxing and unboxing. Both types are rendered through
     * {@link #typeText} before they are compared or printed, so the sentence does
     * not depend on which model spelled them.
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
        if (sameSimpleType(written, storage) || boxedTwins(written, storage)) return null;
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
        String boxed = boxOf(type);
        return "java.util.function.Supplier<" + (boxed == null ? type : boxed) + ">";
    }

    /** The qualified box of a primitive, or {@code null} when the type is not one. */
    private static @Nullable String boxOf(String type) {
        return switch (type) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "char" -> "java.lang.Character";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            default -> null;
        };
    }

    /** Whether one rendered type is a primitive and the other its box, by simple name. */
    private static boolean boxedTwins(String written, String storage) {
        String writtenBox = boxOf(written);
        if (writtenBox != null) return writtenBox.equals(storage) || erasedName(writtenBox).equals(storage);
        String storageBox = boxOf(storage);
        return storageBox != null && (storageBox.equals(written) || erasedName(storageBox).equals(written));
    }

    /**
     * Reports a declared builder field under a slot's name that is
     * {@code final}, which the generated setter assigns on a line the author
     * never wrote.
     *
     * <p>Asked of every slot the setters assign, which is every slot but a
     * seed: a seed is appended {@code final} itself and assigned by the author's
     * constructor alone.
     *
     * @param declaredName the declared builder's simple name
     * @param slotName the slot's name, which the declared field shares
     * @return the diagnostic text both halves report
     */
    public static @NotNull String finalSlot(@NotNull String declaredName, @NotNull String slotName) {
        return "@ClassBuilder merged into '" + declaredName + "' finds '" + slotName
            + "' declared final, and the generated setter assigns it";
    }

    /**
     * Keys a method by what decides whether the declared builder already spells
     * it - its name and the erasure of each parameter type, by simple name.
     *
     * <p>That is the signature Java itself refuses to see twice: an author
     * method matching a generated one under it is the setter they wrote instead,
     * and appending the generated one beside it would be a duplicate. A method
     * sharing only the name and arity takes another type, is an overload rather
     * than a replacement, and leaves the generated setter to be appended -
     * {@code from(T)} and {@code mutate()} pass the slot's own type to it. A
     * varargs parameter is keyed as the array it is.
     *
     * @param name the method's name
     * @param parameterTypes each parameter's type as either model renders it, in order
     * @return the key both halves compare
     */
    public static @NotNull String methodKey(@NotNull String name, @NotNull List<String> parameterTypes) {
        List<String> erased = new ArrayList<>(parameterTypes.size());
        for (String type : parameterTypes) {
            String text = typeText(type.replace("...", "[]"));
            erased.add(erasedName(withoutDimensions(text)) + "[]".repeat(dimensions(text)));
        }
        return name + "(" + String.join(",", erased) + ")";
    }

    /**
     * Decides whether the entry points can instantiate a declared builder.
     *
     * <p>Every entry point calls the builder's constructor with the seeds, in
     * parameter order, so it needs a constructor among the ones the author wrote
     * taking exactly the seeds' types in that order. The types are compared by
     * erasure and simple name, as {@link #methodKey} compares a method's, which
     * is what both halves can read without a resolve; a constructor of the seed
     * count taking other types, or the seeds' types in another order, is not one
     * the entry points can pass them to. A class declaring none keeps the
     * implicit default, which takes nothing and so serves only an entry point
     * passing nothing. On a type target there is no seed, a seed being a
     * parameter's alone.
     *
     * <p>A constructor declaring a throws clause is not one the entry points can
     * call either: each of them calls it with nothing around the call to handle
     * what it throws, and whether a thrown type is checked is a question of what
     * it resolves to, which neither half asks. So a throws clause of any type
     * takes the constructor out of the count.
     *
     * @param declaredConstructors the parameter types of each constructor the author declared, as
     *     either model renders them
     * @param callableConstructors the parameter types of each of those declaring no throws clause
     * @param seedTypes the type of each seed the entry points pass, in parameter order
     * @return whether a constructor the entry points can call exists
     */
    public static boolean instantiable(@NotNull List<List<String>> declaredConstructors,
                                       @NotNull List<List<String>> callableConstructors,
                                       @NotNull List<String> seedTypes) {
        if (declaredConstructors.isEmpty()) return seedTypes.isEmpty();
        return takesTheSeeds(callableConstructors, seedTypes);
    }

    /**
     * Whether the entry points are skipped only because every constructor
     * taking what they pass declares a throws clause, which decides the note's
     * wording.
     *
     * @param declaredConstructors the parameter types of each constructor the author declared, as
     *     either model renders them
     * @param callableConstructors the parameter types of each of those declaring no throws clause
     * @param seedTypes the type of each seed the entry points pass, in parameter order
     * @return whether a constructor taking the seeds exists and none of them is callable
     */
    public static boolean skippedForAThrowsClause(@NotNull List<List<String>> declaredConstructors,
                                                  @NotNull List<List<String>> callableConstructors,
                                                  @NotNull List<String> seedTypes) {
        return takesTheSeeds(declaredConstructors, seedTypes)
            && !takesTheSeeds(callableConstructors, seedTypes);
    }

    /**
     * Whether one of the constructors takes the seeds' types in seed order, by
     * the erasure {@link #methodKey} keys a parameter list under.
     *
     * @param constructors the parameter types of each constructor, as either model renders them
     * @param seedTypes the type of each seed, in parameter order
     * @return whether one of them takes exactly those
     */
    private static boolean takesTheSeeds(List<List<String>> constructors, List<String> seedTypes) {
        String seeds = methodKey("<init>", seedTypes);
        for (List<String> parameters : constructors) {
            if (methodKey("<init>", parameters).equals(seeds)) return true;
        }
        return false;
    }

    /**
     * Renders the note for entry points skipped because the declared builder has
     * no constructor they can call, as both halves report it - the processor as a
     * note, the editor as a weak warning on the annotation.
     *
     * <p>Names the entry points the path emits and no others: all three on a type
     * target, {@code builder(..)} alone on a constructor or factory target, and
     * never one named {@code NONE}. A path emitting none skips nothing, so there
     * is no note.
     *
     * @param declaredName the declared builder's simple name
     * @param names the resolved names, an entry point named {@code NONE} being empty
     * @param executable whether the annotation sits on a constructor or factory method
     * @param seedNames the seeded slots the entry points pass, in parameter order
     * @return the note text, or {@code null} when the path emits no entry point
     */
    public static @Nullable String entryPointsSkipped(@NotNull String declaredName,
                                                      @NotNull BuilderScheme names,
                                                      boolean executable,
                                                      @NotNull List<String> seedNames) {
        return entryPointsSkipped(declaredName, names, executable, seedNames, false);
    }

    /**
     * Renders the note for entry points skipped because the declared builder has
     * no constructor they can call, worded by why.
     *
     * @param declaredName the declared builder's simple name
     * @param names the resolved names, an entry point named {@code NONE} being empty
     * @param executable whether the annotation sits on a constructor or factory method
     * @param seedNames the seeded slots the entry points pass, in parameter order
     * @param throwsClause whether a constructor taking what they pass exists and declares a throws
     *     clause, from {@link #skippedForAThrowsClause}
     * @return the note text, or {@code null} when the path emits no entry point
     */
    public static @Nullable String entryPointsSkipped(@NotNull String declaredName,
                                                      @NotNull BuilderScheme names,
                                                      boolean executable,
                                                      @NotNull List<String> seedNames,
                                                      boolean throwsClause) {
        List<String> entryPoints = new ArrayList<>();
        entryPoints.add(names.builder());
        if (!executable) {
            entryPoints.add(names.from());
            entryPoints.add(names.toBuilder());
        }
        entryPoints.removeIf(String::isEmpty);
        if (entryPoints.isEmpty()) return null;
        return throwsClause
            ? throwingConstructor(declaredName, entryPoints, seedNames)
            : uninstantiable(declaredName, entryPoints, seedNames);
    }

    /**
     * Renders the note for entry points skipped because the constructor they
     * would call declares a throws clause.
     *
     * @param declaredName the declared builder's simple name
     * @param entryPoints the names of the entry points that were not added
     * @param seedNames the seeded slots the entry points pass, in parameter order
     * @return the note text
     */
    public static @NotNull String throwingConstructor(@NotNull String declaredName,
                                                      @NotNull List<String> entryPoints,
                                                      @NotNull List<String> seedNames) {
        boolean single = entryPoints.size() == 1;
        String skipped = quotedList(entryPoints) + (single ? " was" : " were") + " not added";
        String constructor = seedNames.isEmpty()
            ? "its no-argument constructor"
            : "its constructor taking " + (seedNames.size() == 1 ? "the seed" : "the " + seedNames.size()
                + " seeds") + " " + quotedList(entryPoints) + " passes";
        return "@ClassBuilder merged into '" + declaredName + "' but " + constructor
            + " declares a throws clause, so " + skipped + " - declare one that throws nothing or write "
            + (single ? "it" : "them");
    }

    /**
     * Renders the note for entry points skipped because the declared builder has
     * no constructor they can call.
     *
     * <p>Worded by what the entry points pass: with no seed the missing
     * constructor is a no-argument one, and with seeds it is one taking exactly
     * those, which on a constructor or factory target is only ever
     * {@code builder(..)}.
     *
     * @param declaredName the declared builder's simple name
     * @param entryPoints the names of the entry points that were not added
     * @param seedNames the seeded slots the entry points pass, in parameter order
     * @return the note text
     */
    public static @NotNull String uninstantiable(@NotNull String declaredName,
                                                 @NotNull List<String> entryPoints,
                                                 @NotNull List<String> seedNames) {
        boolean single = entryPoints.size() == 1;
        String skipped = quotedList(entryPoints) + (single ? " was" : " were") + " not added";
        String pronoun = single ? "it" : "them";
        if (seedNames.isEmpty()) {
            return "@ClassBuilder merged into '" + declaredName + "' but every constructor it "
                + "declares takes parameters, so " + skipped + " - declare a no-argument "
                + "constructor or write " + pronoun;
        }
        String seeds = seedNames.size() == 1 ? "the seed" : "the " + seedNames.size() + " seeds";
        return "@ClassBuilder merged into '" + declaredName + "' but none of its constructors "
            + "takes " + seeds + " " + quotedList(entryPoints) + " passes, so " + skipped
            + " - declare a constructor taking (" + String.join(", ", seedNames) + ") or write "
            + pronoun;
    }

    /**
     * Renders the diagnostic for a seed the merge appends as a {@code final}
     * field that the declared builder never assigns.
     *
     * <p>{@code builder(seed)} hands a seed to the builder's constructor, which
     * is the only place a final field can take it. In a declared builder that
     * constructor is the author's, so the field is theirs to assign, and javac
     * refuses a constructor - or the implicit default of a class declaring none -
     * that leaves it unassigned.
     *
     * @param declaredName the declared builder's simple name
     * @param seedName the seeded slot's name, which the appended field carries
     * @param declaresConstructor whether the builder declares a constructor, the
     *     diagnostic then naming it rather than the missing one
     * @return the diagnostic text
     */
    public static @NotNull String unassignedSeed(@NotNull String declaredName,
                                                 @NotNull String seedName,
                                                 boolean declaresConstructor) {
        String appended = "@ClassBuilder merged into '" + declaredName + "' appends the seed '"
            + seedName + "' as a final field, ";
        return declaresConstructor
            ? appended + "and this constructor leaves it unassigned"
            : appended + "and '" + declaredName + "' declares no constructor to assign it";
    }

    /** Quoted names joined as a sentence reads them - {@code 'a', 'b' and 'c'}. */
    private static String quotedList(List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out.append(i == names.size() - 1 ? " and " : ", ");
            out.append('\'').append(names.get(i)).append('\'');
        }
        return out.toString();
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
     * Whether two rendered types name the same type by simple names at every
     * level.
     *
     * <p>The type itself, its array dimensions and each type argument are
     * compared, an argument recursively and a wildcard by its keyword and bound.
     * A side with no arguments at all is a raw spelling, which matches whatever
     * the other side passes.
     *
     * @param written the one type, in the spelling {@link #typeText} gives it
     * @param storage the other, in the same spelling
     * @return whether the two are the same type as far as simple names can tell
     */
    private static boolean sameSimpleType(String written, String storage) {
        boolean writtenWildcard = written.startsWith("?");
        if (writtenWildcard || storage.startsWith("?")) {
            return writtenWildcard && storage.startsWith("?")
                && sameWildcard(written.substring(1).trim(), storage.substring(1).trim());
        }
        if (dimensions(written) != dimensions(storage)) return false;
        String writtenType = withoutDimensions(written);
        String storageType = withoutDimensions(storage);
        if (!erasedName(writtenType).equals(erasedName(storageType))) return false;
        if (writtenType.indexOf('<') < 0 || storageType.indexOf('<') < 0) return true;
        List<String> writtenArguments = typeArguments(writtenType);
        List<String> storageArguments = typeArguments(storageType);
        if (writtenArguments.size() != storageArguments.size()) return false;
        for (int i = 0; i < writtenArguments.size(); i++) {
            if (!sameSimpleType(writtenArguments.get(i), storageArguments.get(i))) return false;
        }
        return true;
    }

    /** Whether two wildcard bounds, each read after its {@code ?}, are the same bound. */
    private static boolean sameWildcard(String written, String storage) {
        if (written.isEmpty() || storage.isEmpty()) return written.isEmpty() && storage.isEmpty();
        int writtenSpace = written.indexOf(' ');
        int storageSpace = storage.indexOf(' ');
        if (writtenSpace < 0 || storageSpace < 0) return false;
        return written.substring(0, writtenSpace).equals(storage.substring(0, storageSpace))
            && sameSimpleType(written.substring(writtenSpace + 1).trim(),
                storage.substring(storageSpace + 1).trim());
    }

    /** How many array dimensions trail the rendered type. */
    private static int dimensions(String type) {
        int count = 0;
        for (String rest = type; rest.endsWith("[]"); rest = rest.substring(0, rest.length() - 2)) count++;
        return count;
    }

    /** The rendered type with its trailing array dimensions removed. */
    private static String withoutDimensions(String type) {
        String rest = type;
        while (rest.endsWith("[]")) rest = rest.substring(0, rest.length() - 2);
        return rest;
    }

    /** The top-level type arguments of a rendered type, each trimmed, in order. */
    private static List<String> typeArguments(String type) {
        List<String> out = new ArrayList<>();
        int open = type.indexOf('<');
        int depth = 0;
        int start = open + 1;
        for (int i = open; i < type.length(); i++) {
            char c = type.charAt(i);
            if (c == '<') depth++;
            if (c == '>') depth--;
            boolean closes = c == '>' && depth == 0;
            if (closes || (c == ',' && depth == 1)) {
                out.add(type.substring(start, i).trim());
                start = i + 1;
            }
            if (closes) break;
        }
        return out;
    }

    /**
     * A written type with its type arguments removed and its qualifier kept, in
     * the spelling {@link #typeText} gives it.
     *
     * <p>What an extends clause is compared by. The qualifier is what tells the
     * ancestor's {@code Base.Builder} from another type's builder of the same
     * simple name, and neither model can say more of a written name without
     * resolving it.
     *
     * @param type the type as written
     * @return the type without any type arguments
     */
    public static @NotNull String rawType(@NotNull String type) {
        String text = typeText(type);
        StringBuilder out = new StringBuilder(text.length());
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0) out.append(c);
        }
        return out.toString().trim();
    }

    /**
     * Whether a written type names the expected one, reading its qualifier.
     *
     * <p>The written type may qualify the expected one further - a package, or
     * the classes an ancestor nests in - so it names it when it is the expected
     * one or ends with it after a dot.
     *
     * @param written the type as written
     * @param expected the qualified name required
     * @return whether the written type names it
     */
    private static boolean namesType(String written, String expected) {
        String raw = rawType(written);
        return raw.equals(expected) || raw.endsWith("." + expected);
    }

    /** Each type, rendered and reduced to its erased simple name. */
    private static List<String> erasedNames(List<String> types) {
        List<String> out = new ArrayList<>(types.size());
        for (String type : types) out.add(erasedName(typeText(type)));
        return out;
    }

    /** Appends {@code $} until the name is not one already taken. */
    private static String freeName(String preferred, List<String> taken) {
        String candidate = preferred;
        while (taken.contains(candidate)) candidate = candidate + "$";
        return candidate;
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
