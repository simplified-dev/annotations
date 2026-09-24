package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Matcher;
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

    /** A Java identifier, as a written type spells its names. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][\\w$]*");

    /**
     * The exception types known to be unchecked, each by its simple name and by
     * its {@code java.lang} or {@code java.util} qualified name - the names a
     * throws clause may be written in that neither half has to resolve to
     * judge.
     */
    private static final Set<String> UNCHECKED_EXCEPTIONS = uncheckedExceptions();

    private DeclaredBuilderShape() {
    }

    /**
     * Lists every spelling {@link #UNCHECKED_EXCEPTIONS} holds.
     *
     * @return the simple and qualified names
     */
    private static Set<String> uncheckedExceptions() {
        Set<String> out = new HashSet<>();
        for (String name : List.of("RuntimeException", "Error", "IllegalArgumentException",
            "IllegalStateException", "UnsupportedOperationException", "NullPointerException",
            "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException", "StringIndexOutOfBoundsException",
            "ArithmeticException", "ClassCastException", "NegativeArraySizeException", "ArrayStoreException",
            "SecurityException", "NumberFormatException", "AssertionError")) {
            out.add(name);
            out.add("java.lang." + name);
        }
        for (String name : List.of("ConcurrentModificationException", "NoSuchElementException")) {
            out.add(name);
            out.add("java.util." + name);
        }
        return Set.copyOf(out);
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
        return expectation(role, targetName, builderName, targetTypeParameters, targetTypeParameterBounds,
            declaredTypeParameters, ancestorName, superArguments, List.of());
    }

    /**
     * Derives everything a role requires of the builder declared for it, the
     * bounds on the target's own type parameters and the supertypes its own
     * clauses name included.
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
     * @param targetSupertypes each type the target's own extends and implements clauses name, as written
     * @return the expectation the declaration is measured against
     */
    public static @NotNull RoleExpectation expectation(@NotNull ChainRole role,
                                                       @NotNull String targetName,
                                                       @NotNull String builderName,
                                                       @NotNull List<String> targetTypeParameters,
                                                       @NotNull List<@Nullable String> targetTypeParameterBounds,
                                                       @NotNull List<String> declaredTypeParameters,
                                                       @Nullable String ancestorName,
                                                       @NotNull List<String> superArguments,
                                                       @NotNull List<String> targetSupertypes) {
        List<String> pair = selfNames(role, targetTypeParameters, declaredTypeParameters);
        return new RoleExpectation(
            expectedTypeParameters(role, targetTypeParameters, pair),
            expectedSuperType(role, ancestorName == null ? null : ancestorName + "." + builderName),
            expectedBuildReturnType(role, targetName, pair.get(0)),
            expectedSuperTypeArguments(role, superArguments, targetName, builderName, pair),
            acceptedBuildReturnTypes(role, targetName, pair.get(0)),
            targetTypeParameterBounds,
            targetName,
            builderName,
            erasedNames(targetSupertypes));
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
        if (role.isSelfTyped() && !selfTypeBoundsKept(facts, expectation)) {
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
     * over its box, which the setter assigns under unboxing and {@code build()}
     * reads back boxed. A box spelled over its primitive is reported with its own
     * clause: the setter assigns it, but a field left unset is {@code null}, and
     * {@code build()} hands that to the primitive constructor or factory
     * parameter, which throws where a builder the generator writes whole passes
     * the primitive's zero. Both types are rendered through {@link #typeText}
     * before they are compared or printed, so the sentence does not depend on
     * which model spelled them.
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
        if (sameSimpleType(written, storage) || primitiveOverItsBox(written, storage)) return null;
        String opening = "@ClassBuilder merged into '" + declaredName + "' finds '" + slotName
            + "' declared as " + written + ", and the slot it stands for is " + storage;
        if (primitiveOverItsBox(storage, written)) {
            return opening + " - an unset " + written + " field reaches the primitive constructor parameter "
                + "as null" + holding.clause();
        }
        return opening + " - the generated setter has nothing to assign it to" + holding.clause();
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

    /**
     * Names the {@code java.util} interface a collected slot gathers into while
     * its default reads instance state - {@link SlotHolding#COLLECTED_SCRATCH}.
     *
     * @param map whether the slot is a map
     * @param set whether the slot is a set, and not a map
     * @return the interface's qualified name
     */
    public static @NotNull String scratchContainerName(boolean map, boolean set) {
        return map ? "java.util.Map" : set ? "java.util.Set" : "java.util.List";
    }

    /**
     * Renders the scratch container a collected slot is held in when its
     * {@link SlotHolding} is {@link SlotHolding#COLLECTED_SCRATCH}.
     *
     * <p>Typed off the arguments of the {@code java.util} supertype the slot's
     * declared type was matched through, so a {@code LinkedHashMap<K, V>} slot
     * is held as {@code Map<K, V>}; an argument neither model can read, a raw
     * container's, is {@code Object}. Rendered through {@link #typeText}, so
     * both halves print it in one spelling.
     *
     * @param map whether the slot is a map
     * @param set whether the slot is a set, and not a map
     * @param element a list's or set's element type, or {@code null} when unread
     * @param key a map's key type, or {@code null} when unread
     * @param value a map's value type, or {@code null} when unread
     * @return the scratch container's type
     */
    public static @NotNull String scratchContainerOf(boolean map, boolean set, @Nullable String element,
                                                     @Nullable String key, @Nullable String value) {
        String arguments = map
            ? argumentOrObject(key) + ", " + argumentOrObject(value)
            : argumentOrObject(element);
        return typeText(scratchContainerName(map, set) + "<" + arguments + ">");
    }

    /** A type argument as read, or {@code Object} where none could be. */
    private static String argumentOrObject(@Nullable String argument) {
        return argument == null ? "java.lang.Object" : argument;
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

    /**
     * Whether the first rendered type is a primitive and the second its box, by
     * simple name.
     *
     * @param primitive the type that may be the primitive
     * @param box the type that may be its box
     * @return whether it is that pair, in that order
     */
    private static boolean primitiveOverItsBox(String primitive, String box) {
        String boxed = boxOf(primitive);
        return boxed != null && (boxed.equals(box) || erasedName(boxed).equals(box));
    }

    /**
     * Reports a declared builder field under a slot's name that is
     * {@code final} where a member the merge appends assigns it.
     *
     * <p>What the merge appends to assign a slot is its generated setters, and
     * not all of them assign it: {@link SetterShape#assigns} names the shapes
     * that call a method on the container the field holds, or hand the value on
     * to another setter, instead. The field is reported exactly when a setter
     * that assigns it is not covered by an author method under
     * {@link #methodKey} - that setter is appended and assigns it on a line the
     * author never wrote. Where the author spells every one, nothing generated
     * assigns the field and the author's own members are javac's to judge. A
     * seed has no setter, and the constructor that would assign it as one is
     * never appended into a declared builder, so a seed's field is never
     * reported.
     *
     * @param declaredName the declared builder's simple name
     * @param slotName the slot's name, which the declared field shares
     * @param generatedSetters the {@link #methodKey} of each setter generated for the slot, with its shape
     * @param append whether the slot's bulk setters append rather than replace, per {@code @Collector(append)}
     * @param authorMethodKeys the {@link #methodKey} of each method the author declared on the builder
     * @return the diagnostic text both halves report, or {@code null} when nothing appended assigns the field
     */
    public static @Nullable String finalSlot(@NotNull String declaredName, @NotNull String slotName,
                                             @NotNull Map<String, SetterShape> generatedSetters, boolean append,
                                             @NotNull Collection<String> authorMethodKeys) {
        for (Map.Entry<String, SetterShape> setter : generatedSetters.entrySet()) {
            if (setter.getValue().assigns(append) && !authorMethodKeys.contains(setter.getKey())) {
                return "@ClassBuilder merged into '" + declaredName + "' finds '" + slotName
                    + "' declared final, and the generated setter assigns it";
            }
        }
        return null;
    }

    /**
     * Reports an author method that covers a generated setter under
     * {@link #methodKey} while taking another parameterisation of the same
     * generic type, which the copy entry points then pass the slot's own type.
     *
     * <p>{@code from(T)} and {@code mutate()} seed each slot through the one
     * setter {@link SetterShape#copied} names, so where that setter is covered
     * the call lands on the author's method; a covered setter of any other
     * shape is never passed the slot and is not judged. Two distinct concrete
     * parameterisations of one generic type are never assignable to each other,
     * so javac rejects the copy's call on a generated line exactly when a
     * parameter pair carries type arguments on both sides, neither holds a
     * wildcard or one of the declared builder's own type parameters - either
     * of which can accept the slot's type - and the arguments differ by simple
     * name at some depth. Every other shape is left as it is: it compiles, or
     * whether it does is not a question of names.
     *
     * @param declaredName the declared builder's simple name
     * @param methodName the method's name, which the author's and the generated one share
     * @param shape the covered setter's shape
     * @param writtenTypes each parameter type of the author's method as written, in order
     * @param generatedTypes each parameter type of the generated setter as either model renders it, in order
     * @param builderTypeParameters the declared builder's own type parameter names
     * @param copyEntryPoints the names of the copy entry points emitted, empty when none is
     * @return the diagnostic text both halves report, or {@code null} when the copy compiles
     */
    public static @Nullable String setterWithOtherTypeArguments(@NotNull String declaredName,
                                                                @NotNull String methodName,
                                                                @NotNull SetterShape shape,
                                                                @NotNull List<String> writtenTypes,
                                                                @NotNull List<String> generatedTypes,
                                                                @NotNull Collection<String> builderTypeParameters,
                                                                @NotNull List<String> copyEntryPoints) {
        if (!shape.copied() || copyEntryPoints.isEmpty() || writtenTypes.size() != generatedTypes.size())
            return null;
        if (!methodKey(methodName, writtenTypes).equals(methodKey(methodName, generatedTypes))) return null;
        for (int i = 0; i < writtenTypes.size(); i++) {
            String written = typeText(writtenTypes.get(i).replace("...", "[]"));
            String generated = typeText(generatedTypes.get(i).replace("...", "[]"));
            if (!otherParameterisation(written, generated, builderTypeParameters)) continue;
            boolean single = copyEntryPoints.size() == 1;
            return "@ClassBuilder merged into '" + declaredName + "' finds "
                + signature(methodName, writtenTypes) + " standing in for the generated "
                + signature(methodName, generatedTypes) + ", and " + quotedList(copyEntryPoints)
                + (single ? " passes" : " pass") + " it the slot's " + unqualified(generated)
                + ", which its " + unqualified(written) + " parameter cannot take";
        }
        return null;
    }

    /**
     * Whether two rendered parameter types are distinct concrete
     * parameterisations of one generic type.
     *
     * @param written the author's parameter type
     * @param generated the generated setter's parameter type
     * @param typeParameters the declared builder's own type parameter names
     * @return whether both carry type arguments, neither a wildcard nor a type parameter, and they differ
     */
    private static boolean otherParameterisation(String written, String generated,
                                                 Collection<String> typeParameters) {
        if (written.indexOf('<') < 0 || generated.indexOf('<') < 0) return false;
        if (written.indexOf('?') >= 0 || generated.indexOf('?') >= 0) return false;
        if (namesAny(written, typeParameters) || namesAny(generated, typeParameters)) return false;
        return !sameSimpleType(written, generated);
    }

    /** Whether a rendered type spells one of the names as an identifier of its own. */
    private static boolean namesAny(String type, Collection<String> names) {
        if (names.isEmpty()) return false;
        Matcher identifiers = IDENTIFIER.matcher(type);
        while (identifiers.find()) {
            if (names.contains(identifiers.group())) return true;
        }
        return false;
    }

    /** A method as a diagnostic names it - its name and its unqualified parameter types. */
    private static String signature(String name, List<String> parameterTypes) {
        List<String> types = new ArrayList<>(parameterTypes.size());
        for (String type : parameterTypes) types.add(unqualified(typeText(type)));
        return name + "(" + String.join(", ", types) + ")";
    }

    /** A rendered type with every qualifier removed, so both models print it alike. */
    private static String unqualified(String type) {
        return QUALIFIER.matcher(type).replaceAll("");
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
        return methodKey(name, parameterTypes, Map.of());
    }

    /**
     * Keys a method of a declared builder that declares type parameters, a
     * parameter typed by one of them being keyed by that variable's erasure.
     *
     * <p>javac compares two methods of one class by their erasures, and a type
     * variable erases to its first bound, or {@code Object} unbounded - so an
     * author method taking that erasure is the generated setter taking the
     * variable, and appending the setter beside it is a name clash. Every other
     * parameter is keyed as {@link #methodKey(String, List)} keys it.
     *
     * @param name the method's name
     * @param parameterTypes each parameter's type as either model renders it, in order
     * @param typeVariableErasures each of the declared builder's type parameter names with its erasure,
     *     from {@link #typeVariableErasures}
     * @return the key both halves compare
     */
    public static @NotNull String methodKey(@NotNull String name, @NotNull List<String> parameterTypes,
                                            @NotNull Map<String, String> typeVariableErasures) {
        List<String> erased = new ArrayList<>(parameterTypes.size());
        for (String type : parameterTypes) {
            String text = typeText(type.replace("...", "[]"));
            String bare = withoutDimensions(text);
            String variable = typeVariableErasures.get(bare);
            erased.add((variable == null ? erasedName(bare) : variable) + "[]".repeat(dimensions(text)));
        }
        return name + "(" + String.join(",", erased) + ")";
    }

    /**
     * The erasure of each type parameter a declared builder declares, read from
     * the bounds as written.
     *
     * <p>A parameter erases to the simple name of its first bound with the
     * bound's type arguments dropped, or to {@code Object} when it has none; a
     * first bound naming another of the builder's parameters erases as that one
     * does.
     *
     * @param names the type parameter names, in declaration order
     * @param bounds each parameter's bounds joined as {@link DeclaredBuilderFacts#typeParameterBounds} holds them,
     *     null where a parameter has none
     * @return each name with its erasure's simple name
     */
    public static @NotNull Map<String, String> typeVariableErasures(@NotNull List<String> names,
                                                                    @NotNull List<@Nullable String> bounds) {
        Map<String, String> firstBounds = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String bound = i < bounds.size() ? bounds.get(i) : null;
            firstBounds.put(names.get(i), bound == null || bound.isBlank() ? "Object" : erasedName(firstBound(bound)));
        }
        Map<String, String> out = new HashMap<>();
        for (String name : names) {
            String erasure = firstBounds.get(name);
            for (int hops = 0; hops < names.size() && firstBounds.containsKey(erasure); hops++)
                erasure = firstBounds.get(erasure);
            out.put(name, firstBounds.containsKey(erasure) ? "Object" : erasure);
        }
        return out;
    }

    /** The first of a type parameter's {@code &}-joined bounds, in the spelling {@link #typeText} gives it. */
    private static String firstBound(String bounds) {
        String text = typeText(bounds);
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == '&' && depth == 0) return text.substring(0, i).trim();
        }
        return text;
    }

    /**
     * The methods of {@code java.lang.Object} a generated setter of the same
     * name and parameter types cannot override - the {@code final} ones, and
     * those returning a type no builder is. {@code clone()} returns
     * {@code Object}, which every builder is, and is overridden legally.
     */
    private static final List<InheritedMethod> OBJECT_METHODS = List.of(
        objectMethod("getClass", List.of(), "java.lang.Class<?>", true),
        objectMethod("hashCode", List.of(), "int", false),
        objectMethod("equals", List.of("java.lang.Object"), "boolean", false),
        objectMethod("toString", List.of(), "java.lang.String", false),
        objectMethod("notify", List.of(), "void", true),
        objectMethod("notifyAll", List.of(), "void", true),
        objectMethod("wait", List.of(), "void", true),
        objectMethod("wait", List.of("long"), "void", true),
        objectMethod("wait", List.of("long", "int"), "void", true),
        objectMethod("finalize", List.of(), "void", false));

    /** One of {@link #OBJECT_METHODS}, whose return type no builder is. */
    private static InheritedMethod objectMethod(String name, List<String> parameterTypes, String returnType,
                                                boolean isFinal) {
        return new InheritedMethod(name, parameterTypes, "java.lang.Object", returnType, isFinal, false, false);
    }

    /**
     * Reports an inherited method of the declared builder's supertypes that a
     * generated setter appended into it cannot override.
     *
     * <p>The merge appends a setter wherever the declared builder itself spells
     * nothing under its {@link #methodKey}, and a method the builder inherits
     * under that key is then overridden by it. javac refuses the override where
     * the inherited method is {@code static} or {@code final}, or where the
     * setter's return type - the builder itself - is not one the inherited
     * method's return type accepts: {@code void}, a primitive, or a reference
     * type the builder is not assignable to. It refuses it on the target's
     * line, a line the author never wrote, so the report names the method and
     * the supertype declaring it instead. The first inherited method under the
     * key that blocks the setter is reported; an inherited method taking other
     * parameter types is an overload beside the setter and is left alone.
     *
     * <p>The setter is keyed as the builder's own methods are, a parameter typed
     * by one of the builder's type variables by that variable's erasure - the
     * form the inherited method's erased parameters are in - so a generated
     * {@code value(T)} meets an inherited {@code value(Object)}.
     *
     * @param declaredName the declared builder's simple name
     * @param setterName the appended setter's name
     * @param setterParameterTypes each parameter type of the appended setter as either model renders it, in order
     * @param typeVariableErasures each of the declared builder's type parameter names with its erasure,
     *     from {@link #typeVariableErasures}
     * @param inherited the methods the declared builder inherits, in the order its supertypes are read
     * @return the diagnostic text both halves report, or {@code null} when the setter overrides nothing it cannot
     */
    public static @Nullable String unoverridableInheritedMethod(@NotNull String declaredName,
                                                                @NotNull String setterName,
                                                                @NotNull List<String> setterParameterTypes,
                                                                @NotNull Map<String, String> typeVariableErasures,
                                                                @NotNull List<InheritedMethod> inherited) {
        return unoverridable("@ClassBuilder merged into '" + declaredName + "'", declaredName, setterName,
            setterParameterTypes, typeVariableErasures, inherited);
    }

    /**
     * Reports a method of {@code java.lang.Object} that a setter of a builder
     * the generator writes whole cannot override.
     *
     * <p>A builder with no declared class to merge into has {@code Object} as
     * its only supertype the author did not generate, so the methods a setter
     * can meet and fail to override are {@code Object}'s: a {@code final} one -
     * {@code getClass}, {@code notify}, {@code notifyAll} and the three
     * {@code wait} overloads - or one returning a type the builder is not -
     * {@code hashCode}, {@code toString}, {@code equals(Object)} and
     * {@code finalize}. That list is fixed, so the answer is read from names
     * alone. javac refuses the override on the target's line; the report is
     * made on the slot the setter is generated for.
     *
     * @param builderName the generated builder's simple name
     * @param setterName the setter's name
     * @param setterParameterTypes each parameter type of the setter as either model renders it, in order
     * @param typeVariableErasures each of the builder's type parameter names with its erasure,
     *     from {@link #typeVariableErasures}
     * @return the diagnostic text both halves report, or {@code null} when the setter meets no such method
     */
    public static @Nullable String unoverridableObjectMethod(@NotNull String builderName,
                                                             @NotNull String setterName,
                                                             @NotNull List<String> setterParameterTypes,
                                                             @NotNull Map<String, String> typeVariableErasures) {
        return unoverridable("@ClassBuilder generating '" + builderName + "'", builderName, setterName,
            setterParameterTypes, typeVariableErasures, OBJECT_METHODS);
    }

    /**
     * The sentence for the first of the inherited methods a setter meets under
     * its {@link #methodKey} and cannot override.
     *
     * @param opening how the sentence names the builder, ahead of what it finds
     * @param builderName the builder's simple name, which the setter returns
     * @param setterName the setter's name
     * @param setterParameterTypes each parameter type of the setter, in order
     * @param typeVariableErasures each of the builder's type parameter names with its erasure
     * @param inherited the methods to meet, in the order they are read
     * @return the diagnostic text, or {@code null} when the setter overrides nothing it cannot
     */
    private static @Nullable String unoverridable(String opening, String builderName, String setterName,
                                                  List<String> setterParameterTypes,
                                                  Map<String, String> typeVariableErasures,
                                                  List<InheritedMethod> inherited) {
        String key = methodKey(setterName, setterParameterTypes, typeVariableErasures);
        for (InheritedMethod method : inherited) {
            if (!method.isStatic() && !method.isFinal() && method.acceptsBuilderReturn()) continue;
            if (!methodKey(method.name(), method.parameterTypes()).equals(key)) continue;
            String found = opening + " finds " + signature(setterName, setterParameterTypes) + " inherited from "
                + method.declaringType();
            if (method.isStatic() || method.isFinal()) {
                return found + " declared " + (method.isStatic() ? "static" : "final")
                    + ", so the generated setter of that signature cannot override it";
            }
            return found + " returning " + unqualified(typeText(method.returnType()))
                + ", which the generated setter returning " + builderName + " cannot override";
        }
        return null;
    }

    /**
     * Decides whether the all-args constructor is withheld from a target
     * because the author's own build method survives the merge.
     *
     * <p>The merge keeps an author method in place of every generated member
     * under the same {@link #methodKey}, so the declared builder's own
     * no-argument method of the build method's configured name - after any
     * {@code @BuilderNames(build)} rename - is the {@code build()} callers get.
     * Nothing generated then calls the all-args constructor, and emitting it
     * would remove javac's no-argument default that an author {@code build()}
     * calling {@code new Target()} relies on. {@code @BuilderArgsConstructor}
     * written on the target names that constructor, so it is emitted all the
     * same; {@code @AllArgsConstructor} appends its own.
     *
     * @param buildMethodName the build method's configured name
     * @param authorMethodKeys the {@link #methodKey} of each method the declared builder declares, empty when the
     *     target declares none
     * @param builderArgsConstructorWritten whether {@code @BuilderArgsConstructor} is written on the target
     * @return whether the all-args constructor is withheld
     */
    public static boolean withholdsAllArgsConstructor(@NotNull String buildMethodName,
                                                      @NotNull Collection<String> authorMethodKeys,
                                                      boolean builderArgsConstructorWritten) {
        return !builderArgsConstructorWritten && authorMethodKeys.contains(methodKey(buildMethodName, List.of()));
    }

    /**
     * Decides whether the entry points can instantiate a declared builder.
     *
     * <p>Every entry point calls the builder's constructor with the seeds, in
     * parameter order, so it needs the constructor javac selects for that call
     * among the ones the builder declares - the author's, and any a constructor
     * annotation written on the builder appends. Which one that is, is decided
     * from names alone, which is what both halves can read without a resolve: a
     * seed reaches a parameter of its own type, compared by erasure and simple
     * name as {@link #methodKey} compares one, of its box or its primitive, of a
     * wider primitive, or - a reference seed - of {@code Object} or of a common
     * JDK supertype {@link #LISTED_SUPERTYPES} lists for its type, and among the
     * constructors so reached the one selected is javac's, the earliest phase
     * and in it the most specific. A constructor needing any other conversion
     * is never selected, and where one might be applicable as early as the
     * selected one, or two are ambiguous, none is; with none the entry points
     * are skipped. A class declaring none keeps the implicit default, which
     * takes nothing and so serves only an entry point passing nothing. On a type
     * target there is no seed, a seed being a parameter's alone.
     *
     * <p>A constructor whose throws clause may name a checked exception is not
     * one the entry points can call either: each of them calls it with nothing
     * around the call to handle what it throws. Whether a thrown type is checked
     * is a question of what it resolves to, which neither half asks, so it is
     * answered by name through {@link #throwsNothingChecked} - a clause naming
     * only known unchecked types leaves the selected constructor callable, and
     * any other name does not, the answer that never emits a call javac refuses.
     *
     * <p>An erasure match is not taken where the seed and the parameter are two
     * distinct concrete parameterisations of one generic type - both carrying
     * type arguments, neither a wildcard nor one of the declared builder's own
     * type parameters, and the arguments differing by simple name at some
     * depth - which are never assignable to each other, the rule
     * {@link #setterWithOtherTypeArguments} applies to a covered setter. The
     * rule holds across a listed supertype, the seed read as that supertype:
     * {@code List<String>} reaches {@code Collection<String>} and never
     * {@code Collection<Integer>}.
     *
     * @param declaredConstructors the parameter types of each constructor the builder declares, as
     *     either model renders them
     * @param callableConstructors the parameter types of each of those whose throws clause
     *     {@link #throwsNothingChecked} accepts
     * @param seedTypes the type of each seed the entry points pass, in parameter order
     * @param builderTypeParameters the declared builder's own type parameter names
     * @return whether a constructor the entry points can call exists
     */
    public static boolean instantiable(@NotNull List<List<String>> declaredConstructors,
                                       @NotNull List<List<String>> callableConstructors,
                                       @NotNull List<String> seedTypes,
                                       @NotNull Collection<String> builderTypeParameters) {
        if (declaredConstructors.isEmpty()) return seedTypes.isEmpty();
        List<String> selected = selectedConstructor(declaredConstructors, seedTypes, builderTypeParameters);
        return selected != null && sameKeyAsOneOf(selected, callableConstructors);
    }

    /**
     * Decides whether the entry points can instantiate a declared builder that
     * declares no type parameters of its own, as
     * {@link #instantiable(List, List, List, Collection)} decides it.
     *
     * @param declaredConstructors the parameter types of each constructor the builder declares, as
     *     either model renders them
     * @param callableConstructors the parameter types of each of those whose throws clause
     *     {@link #throwsNothingChecked} accepts
     * @param seedTypes the type of each seed the entry points pass, in parameter order
     * @return whether a constructor the entry points can call exists
     */
    public static boolean instantiable(@NotNull List<List<String>> declaredConstructors,
                                       @NotNull List<List<String>> callableConstructors,
                                       @NotNull List<String> seedTypes) {
        return instantiable(declaredConstructors, callableConstructors, seedTypes, List.of());
    }

    /**
     * Whether a throws clause names only exception types known to be unchecked,
     * which the entry points can call through with nothing to handle them.
     *
     * <p>Read by name, since neither half resolves one: {@link RuntimeException},
     * {@link Error} and the common unchecked subclasses of each in
     * {@code java.lang} and {@code java.util} - {@link IllegalArgumentException},
     * {@link IllegalStateException}, {@link UnsupportedOperationException},
     * {@link NullPointerException}, {@link IndexOutOfBoundsException} and its
     * array and string forms, {@link ArithmeticException},
     * {@link ClassCastException}, {@link NegativeArraySizeException},
     * {@link ArrayStoreException}, {@link SecurityException},
     * {@link NumberFormatException}, {@link AssertionError},
     * {@link ConcurrentModificationException} and
     * {@link NoSuchElementException} - each by
     * its simple name or its qualified one. Any other name is
     * treated as checked, an unchecked type of the author's own among them, so
     * the entry points are skipped wherever the name is unknown - the answer
     * that never emits a call javac refuses.
     *
     * @param thrownTypes each type the throws clause names, as either model renders it
     * @return whether every one is a known unchecked type, and {@code true} for an empty clause
     */
    public static boolean throwsNothingChecked(@NotNull List<String> thrownTypes) {
        for (String thrown : thrownTypes) {
            if (!UNCHECKED_EXCEPTIONS.contains(typeText(thrown))) return false;
        }
        return true;
    }

    /**
     * Whether the entry points are skipped only because the constructor javac
     * selects for what they pass declares a throws clause that may name a
     * checked exception, which decides the note's wording.
     *
     * @param declaredConstructors the parameter types of each constructor the builder declares, as
     *     either model renders them
     * @param callableConstructors the parameter types of each of those whose throws clause
     *     {@link #throwsNothingChecked} accepts
     * @param seedTypes the type of each seed the entry points pass, in parameter order
     * @param builderTypeParameters the declared builder's own type parameter names
     * @return whether a constructor is selected and it is not callable
     */
    public static boolean skippedForAThrowsClause(@NotNull List<List<String>> declaredConstructors,
                                                  @NotNull List<List<String>> callableConstructors,
                                                  @NotNull List<String> seedTypes,
                                                  @NotNull Collection<String> builderTypeParameters) {
        List<String> selected = selectedConstructor(declaredConstructors, seedTypes, builderTypeParameters);
        return selected != null && !sameKeyAsOneOf(selected, callableConstructors);
    }

    /**
     * Whether the entry points into a declared builder that declares no type
     * parameters of its own are skipped only for a throws clause, as
     * {@link #skippedForAThrowsClause(List, List, List, Collection)} decides it.
     *
     * @param declaredConstructors the parameter types of each constructor the builder declares, as
     *     either model renders them
     * @param callableConstructors the parameter types of each of those whose throws clause
     *     {@link #throwsNothingChecked} accepts
     * @param seedTypes the type of each seed the entry points pass, in parameter order
     * @return whether a constructor is selected and it is not callable
     */
    public static boolean skippedForAThrowsClause(@NotNull List<List<String>> declaredConstructors,
                                                  @NotNull List<List<String>> callableConstructors,
                                                  @NotNull List<String> seedTypes) {
        return skippedForAThrowsClause(declaredConstructors, callableConstructors, seedTypes, List.of());
    }

    /**
     * Selects the constructor javac calls with the seeds, as far as names can
     * tell.
     *
     * <p>A seed reaches a parameter of its own type - equal under the erasure
     * {@link #methodKey} keys a parameter under, and not another concrete
     * parameterisation of it - of a wider primitive
     * (JLS 5.1.2), of its box or of its primitive, and, a reference seed, of
     * {@code Object} or {@code java.lang.Object}, or of a supertype
     * {@link #LISTED_SUPERTYPES} lists for its type. javac selects in phases, a
     * constructor reached without boxing before one needing it, and within a
     * phase the most specific, so this does too: the constructor taking the
     * seeds' own types always, otherwise the one of the earliest phase more
     * specific than every other there.
     *
     * <p>Any other conversion is one names cannot vouch for - a supertype the
     * table does not list, a primitive seed boxed into {@code Object}, an
     * unboxing followed by a widening - and a constructor needing one is never
     * selected.
     * Where such a constructor might still be applicable no later than the
     * phase selecting, javac may prefer it, so nothing is selected; nor is
     * anything where two constructors are ambiguous. Selecting nothing skips the
     * entry points with the note, the answer that never emits a call javac
     * refuses.
     *
     * @param constructors the parameter types of each constructor, as either model renders them
     * @param seedTypes the type of each seed, in parameter order
     * @param typeParameters the declared builder's own type parameter names
     * @return the selected constructor's parameter types, or {@code null} when names select none
     */
    private static @Nullable List<String> selectedConstructor(List<List<String>> constructors,
                                                              List<String> seedTypes,
                                                              Collection<String> typeParameters) {
        List<ParameterKey> seeds = keysOf(seedTypes);
        List<Candidate> placed = new ArrayList<>();
        int earliestUnplaced = Integer.MAX_VALUE;
        for (List<String> constructor : constructors) {
            if (constructor.size() != seeds.size()) continue;
            List<ParameterKey> parameters = keysOf(constructor);
            if (sameKeys(parameters, seeds, typeParameters)) return constructor;
            int phase = 1;
            boolean counted = true;
            boolean applicable = true;
            for (int i = 0; i < seeds.size() && applicable; i++) {
                int reach = reach(seeds.get(i), parameters.get(i), typeParameters);
                if (reach == UNREACHABLE) applicable = false;
                else {
                    counted &= reach > 0;
                    phase = Math.max(phase, Math.abs(reach));
                }
            }
            if (!applicable) continue;
            if (counted) placed.add(new Candidate(constructor, parameters, phase));
            else earliestUnplaced = Math.min(earliestUnplaced, phase);
        }
        for (int phase = 1; phase <= 2; phase++) {
            if (earliestUnplaced <= phase) return null;
            List<Candidate> inPhase = new ArrayList<>();
            for (Candidate candidate : placed)
                if (candidate.phase() == phase) inPhase.add(candidate);
            if (inPhase.isEmpty()) continue;
            Candidate chosen = mostSpecific(inPhase);
            return chosen == null ? null : chosen.parameterTypes();
        }
        return null;
    }

    /** A seed and parameter pair no conversion joins. */
    private static final int UNREACHABLE = 0;

    /** The primitive types, by keyword. */
    private static final Set<String> PRIMITIVES =
        Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double");

    /**
     * The package each type {@link #LISTED_SUPERTYPES} names is spelled in,
     * by simple name.
     */
    private static final Map<String, String> LISTED_PACKAGES = listedPackages();

    /**
     * The type argument each listed {@code Comparable} type compares itself
     * to, by simple name - its own type, but for the three {@code java.time}
     * types that compare to a chronology interface.
     */
    private static final Map<String, String> COMPARABLE_TO = comparableTo();

    /**
     * The supertypes the seed match counts beyond {@code Object}, each listed
     * type's simple name mapped to the simple names of its listed supertypes.
     *
     * <p>{@code CharSequence} over {@code String}, {@code StringBuilder} and
     * {@code StringBuffer}; {@code Number} over the boxed numeric types,
     * {@code BigInteger}, {@code BigDecimal}, {@code AtomicInteger} and
     * {@code AtomicLong}; {@code Comparable} over {@code String}, the boxes,
     * {@code BigInteger}, {@code BigDecimal} and the common {@code java.time}
     * types; and the {@code java.util} collection interfaces over their usual
     * implementations and over each other as they extend one another -
     * {@code Collection} and {@code Iterable} over every collection listed.
     * A name counts spelled simply or qualified by the package
     * {@link #LISTED_PACKAGES} holds for it, so an author's own
     * {@code CharSequence} in another package is not taken for the JDK's.
     */
    private static final Map<String, Set<String>> LISTED_SUPERTYPES = listedSupertypes();

    /** Builds {@link #LISTED_PACKAGES}. */
    private static Map<String, String> listedPackages() {
        Map<String, String> out = new HashMap<>();
        for (String name : List.of("String", "StringBuilder", "StringBuffer", "CharSequence", "Number", "Comparable",
            "Iterable", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character", "Boolean"))
            out.put(name, "java.lang");
        for (String name : List.of("BigInteger", "BigDecimal")) out.put(name, "java.math");
        for (String name : List.of("AtomicInteger", "AtomicLong")) out.put(name, "java.util.concurrent.atomic");
        for (String name : List.of("CopyOnWriteArrayList", "ConcurrentHashMap")) out.put(name, "java.util.concurrent");
        for (String name : List.of("ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque",
            "HashMap", "LinkedHashMap", "TreeMap", "Collection", "List", "Set", "SortedSet", "NavigableSet", "Queue",
            "Deque", "Map", "SortedMap", "NavigableMap"))
            out.put(name, "java.util");
        for (String name : List.of("Instant", "Duration", "LocalDate", "LocalTime", "LocalDateTime", "OffsetDateTime",
            "OffsetTime", "ZonedDateTime", "Year", "YearMonth", "MonthDay"))
            out.put(name, "java.time");
        return Map.copyOf(out);
    }

    /** Builds {@link #COMPARABLE_TO}. */
    private static Map<String, String> comparableTo() {
        Map<String, String> out = new HashMap<>();
        for (String name : List.of("String", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character",
            "Boolean", "BigInteger", "BigDecimal", "Instant", "Duration", "LocalTime", "OffsetDateTime", "OffsetTime",
            "Year", "YearMonth", "MonthDay"))
            out.put(name, name);
        out.put("LocalDate", "ChronoLocalDate");
        out.put("LocalDateTime", "ChronoLocalDateTime<?>");
        out.put("ZonedDateTime", "ChronoZonedDateTime<?>");
        return Map.copyOf(out);
    }

    /** Builds {@link #LISTED_SUPERTYPES}. */
    private static Map<String, Set<String>> listedSupertypes() {
        Map<String, Set<String>> out = new HashMap<>();
        for (String name : List.of("String", "StringBuilder", "StringBuffer")) supertype(out, name, "CharSequence");
        for (String name : List.of("Byte", "Short", "Integer", "Long", "Float", "Double", "BigInteger", "BigDecimal",
            "AtomicInteger", "AtomicLong"))
            supertype(out, name, "Number");
        for (String name : COMPARABLE_TO.keySet()) supertype(out, name, "Comparable");

        for (String name : List.of("ArrayList", "LinkedList", "CopyOnWriteArrayList")) supertype(out, name, "List");
        for (String name : List.of("HashSet", "LinkedHashSet", "TreeSet", "SortedSet", "NavigableSet"))
            supertype(out, name, "Set");
        for (String name : List.of("TreeSet", "NavigableSet")) supertype(out, name, "SortedSet");
        supertype(out, "TreeSet", "NavigableSet");
        for (String name : List.of("ArrayDeque", "LinkedList", "Deque")) supertype(out, name, "Queue");
        for (String name : List.of("ArrayDeque", "LinkedList")) supertype(out, name, "Deque");
        for (String name : List.of("ArrayList", "LinkedList", "CopyOnWriteArrayList", "HashSet", "LinkedHashSet",
            "TreeSet", "ArrayDeque", "List", "Set", "SortedSet", "NavigableSet", "Queue", "Deque")) {
            supertype(out, name, "Collection");
            supertype(out, name, "Iterable");
        }
        supertype(out, "Collection", "Iterable");
        for (String name : List.of("HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "SortedMap",
            "NavigableMap"))
            supertype(out, name, "Map");
        for (String name : List.of("TreeMap", "NavigableMap")) supertype(out, name, "SortedMap");
        supertype(out, "TreeMap", "NavigableMap");
        Map<String, Set<String>> frozen = new HashMap<>();
        out.forEach((name, supertypes) -> frozen.put(name, Set.copyOf(supertypes)));
        return Map.copyOf(frozen);
    }

    /** Records one listed supertype of a type. */
    private static void supertype(Map<String, Set<String>> table, String type, String supertype) {
        table.computeIfAbsent(type, name -> new HashSet<>()).add(supertype);
    }

    /**
     * Whether the table lists the one type as a supertype of the other, both
     * spelled as the JDK's.
     *
     * @param type the subtype, a seed or a parameter
     * @param supertype the candidate supertype
     * @return whether {@link #LISTED_SUPERTYPES} holds the pair
     */
    private static boolean listedSupertype(ParameterKey type, ParameterKey supertype) {
        Set<String> supertypes = LISTED_SUPERTYPES.get(type.erased());
        return supertypes != null && supertypes.contains(supertype.erased()) && type.listed() && supertype.listed();
    }

    /**
     * Whether a seed reaching a listed supertype carries type arguments the
     * parameter can take, by the rule {@link #otherParameterisation} applies to
     * two parameterisations of one generic type.
     *
     * <p>The seed is read as the supertype - a collection passing its own
     * arguments up, {@code Comparable} applied to what the seed compares to -
     * and refused only where both it and the parameter carry concrete
     * arguments that differ; a raw side, a wildcard in the parameter, or a type
     * parameter of the builder's own is taken, as it is for one generic type.
     *
     * @param seed the seed's type
     * @param parameter the parameter's type, a listed supertype of the seed's
     * @param typeParameters the declared builder's own type parameter names
     * @return whether the arguments fit
     */
    private static boolean argumentsFit(ParameterKey seed, ParameterKey parameter, Collection<String> typeParameters) {
        String written = parameter.text();
        if (written.indexOf('<') < 0 || written.indexOf('?') >= 0 || namesAny(written, typeParameters)) return true;
        String seen;
        if (parameter.erased().equals("Comparable")) {
            seen = "Comparable<" + COMPARABLE_TO.get(seed.erased()) + ">";
        } else {
            int open = seed.text().indexOf('<');
            if (open < 0) return true;
            seen = parameter.erased() + seed.text().substring(open);
        }
        return namesAny(seen, typeParameters) || sameSimpleType(seen, written);
    }

    /**
     * A parameter or seed type as the selection reads it.
     *
     * @param erased the erased simple name with its array dimensions, as {@link #methodKey} keys it
     * @param qualifier the qualifier the type is written with, empty when it is written by its simple name
     * @param text the whole type in the spelling {@link #typeText} gives it, its type arguments kept
     */
    private record ParameterKey(@NotNull String erased, @NotNull String qualifier, @NotNull String text) {

        /**
         * Whether the type is written unqualified or qualified by
         * {@code java.lang}, which is what lets a box or {@code Object} of that
         * name be taken for the real one.
         */
        boolean javaLang() {
            return qualifier.isEmpty() || qualifier.equals("java.lang");
        }

        /**
         * Whether the type is one {@link #LISTED_SUPERTYPES} names, written
         * unqualified or qualified by its own package.
         */
        boolean listed() {
            String home = LISTED_PACKAGES.get(erased);
            return home != null && (qualifier.isEmpty() || qualifier.equals(home));
        }

        /** Whether this is a primitive type. */
        boolean primitive() {
            return PRIMITIVES.contains(erased);
        }

        /** Whether this is {@code Object}. */
        boolean object() {
            return javaLang() && erased.equals("Object");
        }

        /** The primitive this box stands for, or {@code null} when it is none. */
        @Nullable String unboxed() {
            if (!javaLang()) return null;
            for (String primitive : PRIMITIVES) {
                String box = boxOf(primitive);
                if (box != null && erasedName(box).equals(erased)) return primitive;
            }
            return null;
        }

    }

    /**
     * A constructor the selection can place.
     *
     * @param parameterTypes its parameter types, as written
     * @param keys the same types as the selection reads them
     * @param phase the invocation phase it is applicable in - 1 without boxing, 2 with
     */
    private record Candidate(@NotNull List<String> parameterTypes, @NotNull List<ParameterKey> keys,
                             int phase) {
    }

    /** Each rendered type as the selection reads it, in order. */
    private static List<ParameterKey> keysOf(List<String> types) {
        List<ParameterKey> out = new ArrayList<>(types.size());
        for (String type : types) {
            String text = typeText(type.replace("...", "[]"));
            String bare = withoutDimensions(text);
            int generics = bare.indexOf('<');
            String raw = (generics < 0 ? bare : bare.substring(0, generics)).trim();
            String simple = erasedName(raw);
            String qualifier = raw.equals(simple) ? "" : raw.substring(0, raw.length() - simple.length() - 1);
            out.add(new ParameterKey(simple + "[]".repeat(dimensions(text)), qualifier, text));
        }
        return out;
    }

    /**
     * Whether two key lists name the same types under the erasure, no pair of
     * them being another concrete parameterisation of the other.
     *
     * @param one the one list
     * @param other the other list
     * @param typeParameters the declared builder's own type parameter names
     * @return whether each pair is the same type as far as names can tell
     */
    private static boolean sameKeys(List<ParameterKey> one, List<ParameterKey> other,
                                    Collection<String> typeParameters) {
        if (one.size() != other.size()) return false;
        for (int i = 0; i < one.size(); i++)
            if (!sameType(one.get(i), other.get(i), typeParameters)) return false;
        return true;
    }

    /**
     * Whether two keys are the same type as far as names can tell - equal under
     * the erasure, and not two distinct concrete parameterisations of it, which
     * are never assignable to each other.
     *
     * @param one the one type
     * @param other the other type
     * @param typeParameters the declared builder's own type parameter names
     * @return whether they are the same type
     */
    private static boolean sameType(ParameterKey one, ParameterKey other, Collection<String> typeParameters) {
        return one.erased().equals(other.erased())
            && !otherParameterisation(one.text(), other.text(), typeParameters);
    }

    /**
     * How a seed reaches a parameter.
     *
     * <p>A positive answer is a conversion the selection counts, a negative one
     * a conversion names cannot rule out and do not count, and its magnitude
     * the earliest invocation phase it could apply in - 1 without boxing, 2
     * with.
     *
     * @param seed the seed's type
     * @param parameter the parameter's type
     * @param typeParameters the declared builder's own type parameter names
     * @return the phase, signed by whether the conversion is counted, or {@link #UNREACHABLE}
     */
    private static int reach(ParameterKey seed, ParameterKey parameter, Collection<String> typeParameters) {
        // Two concrete parameterisations of one type are never assignable, so
        // no conversion joins them.
        if (seed.erased().equals(parameter.erased()))
            return sameType(seed, parameter, typeParameters) ? 1 : UNREACHABLE;
        if (seed.primitive()) {
            if (parameter.primitive()) return widens(seed.erased(), parameter.erased()) ? 1 : UNREACHABLE;
            String box = boxOf(seed.erased());
            if (parameter.javaLang() && box != null && erasedName(box).equals(parameter.erased())) return 2;
            // A primitive boxes to its own box alone, which is no array and no other box.
            if (parameter.erased().endsWith("[]") || parameter.unboxed() != null) return UNREACHABLE;
            return -2;
        }
        if (parameter.object()) return 1;
        if (listedSupertype(seed, parameter)) return argumentsFit(seed, parameter, typeParameters) ? 1 : UNREACHABLE;
        String unboxed = seed.unboxed();
        if (parameter.primitive()) {
            if (unboxed == null) return -2;
            if (unboxed.equals(parameter.erased())) return 2;
            return widens(unboxed, parameter.erased()) ? -2 : UNREACHABLE;
        }
        // The boxes are final and unrelated, so no box reaches another.
        if (unboxed != null && parameter.unboxed() != null) return UNREACHABLE;
        return -1;
    }

    /**
     * The one constructor more specific than every other of its phase, a
     * parameter type being more specific than another when it is the same, a
     * primitive widening to it, a reference type beside {@code Object}, or a
     * type beside one {@link #LISTED_SUPERTYPES} lists as its supertype.
     *
     * @param candidates the constructors of one phase
     * @return the most specific, or {@code null} when none is, the call being ambiguous
     */
    private static @Nullable Candidate mostSpecific(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            boolean beatsAll = true;
            for (Candidate other : candidates) {
                if (other == candidate) continue;
                for (int i = 0; i < candidate.keys().size() && beatsAll; i++)
                    beatsAll = subtype(candidate.keys().get(i), other.keys().get(i));
            }
            if (beatsAll) return candidate;
        }
        return null;
    }

    /** Whether the one type is a subtype of the other, as far as names can tell. */
    private static boolean subtype(ParameterKey type, ParameterKey supertype) {
        if (type.erased().equals(supertype.erased())) return true;
        if (type.primitive()) return supertype.primitive() && widens(type.erased(), supertype.erased());
        return supertype.object() || listedSupertype(type, supertype);
    }

    /** Whether a primitive widens to another, per JLS 5.1.2. */
    private static boolean widens(String from, String to) {
        return switch (from) {
            case "byte" -> Set.of("short", "int", "long", "float", "double").contains(to);
            case "short", "char" -> Set.of("int", "long", "float", "double").contains(to);
            case "int" -> Set.of("long", "float", "double").contains(to);
            case "long" -> Set.of("float", "double").contains(to);
            case "float" -> to.equals("double");
            default -> false;
        };
    }

    /** Whether one of the constructors has the given one's method key. */
    private static boolean sameKeyAsOneOf(List<String> constructor, List<List<String>> constructors) {
        String key = methodKey("<init>", constructor);
        for (List<String> other : constructors) {
            if (methodKey("<init>", other).equals(key)) return true;
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
     *     clause that may name a checked exception, from {@link #skippedForAThrowsClause}
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
     * would call declares a throws clause naming an exception
     * {@link #throwsNothingChecked} does not know to be unchecked, which is
     * treated as checked.
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
            + " declares a throws clause naming an exception not known to be unchecked, so " + skipped
            + " - declare one throwing only unchecked exceptions or write " + (single ? "it" : "them");
    }

    /**
     * Renders the note for entry points skipped because the declared builder has
     * no constructor they can call.
     *
     * <p>Worded by what the entry points pass: with no seed the missing
     * constructor is a no-argument one, and with seeds it is one taking those,
     * which on a constructor or factory target is only ever {@code builder(..)}.
     * The seeded note names the conversions {@link #instantiable} counts - a
     * constructor javac would reach only through a supertype the table does not
     * list is not one of them, nor is one of two it could not choose between.
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
        String seeds = seedNames.size() == 1
            ? "the seed " + quotedList(entryPoints) + " passes as its own type, its box or primitive, "
                + "a wider primitive, Object or a listed JDK supertype such as CharSequence, Number, "
                + "Comparable or a java.util collection interface"
            : "the " + seedNames.size() + " seeds " + quotedList(entryPoints) + " passes as their own "
                + "types, their boxes or primitives, wider primitives, Object or listed JDK supertypes such "
                + "as CharSequence, Number, Comparable or a java.util collection interface";
        return "@ClassBuilder merged into '" + declaredName + "' but no single constructor it declares "
            + "takes " + seeds + ", so " + skipped + " - declare a constructor taking ("
            + String.join(", ", seedNames) + ") or write " + pronoun;
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

    /**
     * Renders the diagnostic for a seed the merge appends as a {@code final}
     * field that a constructor appended by a constructor annotation on the
     * declared builder leaves unassigned.
     *
     * <p>The constructor pass runs before the merge, so the constructor it
     * appends takes the builder's fields as they stand then, never the seed's,
     * and assigns nothing else; javac refuses it with its own flow error, on the
     * builder's line, where no author constructor stands to name.
     *
     * @param declaredName the declared builder's simple name
     * @param seedName the seeded slot's name, which the appended field carries
     * @param annotationName the constructor annotation that appends the constructor, with its {@code @}
     * @param parameterTypes the appended constructor's parameter types, as either model renders them
     * @return the diagnostic text
     */
    public static @NotNull String unassignedByAppendedConstructor(@NotNull String declaredName,
                                                                  @NotNull String seedName,
                                                                  @NotNull String annotationName,
                                                                  @NotNull List<String> parameterTypes) {
        return "@ClassBuilder merged into '" + declaredName + "' appends the seed '" + seedName
            + "' as a final field, and the constructor " + signature(declaredName, parameterTypes) + " that "
            + annotationName + " appends leaves it unassigned";
    }

    /** What may already have assigned a seed that a constructor of the declared builder assigns. */
    public enum PriorAssignment {

        /** An instance initializer, which runs before every constructor body. */
        INSTANCE_INITIALIZER("assigns it after an instance initializer already has"),

        /** The constructor this one delegates to through {@code this(..)}, which assigns every seed. */
        DELEGATED_CONSTRUCTOR("assigns it after the constructor it delegates to already has"),

        /** The same constructor, on an earlier path through its body. */
        SAME_CONSTRUCTOR("may assign it more than once");

        private final @NotNull String clause;

        PriorAssignment(@NotNull String clause) {
            this.clause = clause;
        }

    }

    /**
     * Reports a constructor of a declared builder that may assign a seed which
     * is already assigned.
     *
     * <p>The seed field is appended {@code final}, so javac refuses a
     * constructor that may assign it a second time - after an instance
     * initializer, which runs before every constructor body, after the
     * {@code this(..)} call it delegates through, or twice itself. The
     * counterpart of {@link #unassignedSeed}, which reports the one that
     * assigns it nowhere.
     *
     * @param declaredName the declared builder's simple name
     * @param seedName the seeded slot's name, which the appended field carries
     * @param prior what may have assigned it first
     * @return the diagnostic text
     */
    public static @NotNull String reassignedSeed(@NotNull String declaredName, @NotNull String seedName,
                                                 @NotNull PriorAssignment prior) {
        return "@ClassBuilder merged into '" + declaredName + "' appends the seed '" + seedName
            + "' as a final field, and this constructor " + prior.clause;
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

    /**
     * Whether a written parameter type names the target's own builder, which is
     * what makes a one-parameter constructor the author's copy constructor.
     *
     * <p>The builder may be spelled by its simple name or through the target,
     * qualified further by whatever encloses the target and its package. Type
     * arguments are ignored, since a second constructor would clash on the
     * erasure. Another type's builder of the same simple name - an ancestor's
     * {@code Base.Builder} - is not the target's, and a constructor taking it
     * leaves the copy constructor to be generated.
     *
     * @param written the parameter type as written
     * @param targetName the simple name of the target the constructor is declared in
     * @param builderName the simple name of the target's builder
     * @return whether the parameter type is the target's builder
     */
    public static boolean namesOwnBuilder(@NotNull String written, @NotNull String targetName,
                                          @NotNull String builderName) {
        return rawType(written).equals(builderName) || namesType(written, targetName + "." + builderName);
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
     * Whether the trailing pair is bounded as a self-typed builder's has to be.
     *
     * <p>Every link below the declaration binds the pair to itself and its own
     * builder, so the first parameter needs a bound the link is within and the
     * second one the link's builder is within. Both are read as written, by name,
     * since neither half can resolve them. Every type the first one's bound
     * names has to erase to a type each link is within: the target's simple
     * name, however qualified and whatever type arguments a generic target is
     * written with, a type the target's own extends or implements clause names,
     * or {@code Object} - a type the target reaches only further up is not one
     * names can tell from an unrelated one. The second has to carry a bound
     * whose erasure is the builder's simple name and whose type arguments end
     * with the pair's own two names, in order - a generic root's own parameters
     * leading them. An unbounded parameter carries neither. Where the
     * expectation names neither the target nor the builder the bounds are asked
     * for their presence only.
     *
     * @param facts the declared builder as written
     * @param expectation what the role requires
     * @return whether the trailing pair carries the bounds a link below it needs
     */
    private static boolean selfTypeBoundsKept(DeclaredBuilderFacts facts, RoleExpectation expectation) {
        int size = expectation.typeParameterNames().size();
        List<@Nullable String> bounds = facts.typeParameterBounds();
        if (size < 2 || bounds.size() < size || facts.typeParameterNames().size() < size) return false;
        String writtenBuilt = bounds.get(size - 2);
        List<String> builtBounds = boundList(writtenBuilt);
        List<String> builderBounds = boundList(bounds.get(size - 1));
        String targetName = expectation.targetName();
        String builderName = expectation.builderName();
        if (targetName == null || builderName == null) return !builtBounds.isEmpty() && !builderBounds.isEmpty();
        // The bound list leaves Object out, so a bound naming it alone is
        // present as written and has nothing left to compare.
        if (writtenBuilt == null || writtenBuilt.isBlank() || builderBounds.isEmpty()) return false;
        List<String> pair = facts.typeParameterNames().subList(size - 2, size);
        return builtBounds.stream().allMatch(bound -> keepsBuiltType(erasedName(bound), targetName,
                expectation.targetSupertypes()))
            && builderBounds.stream().anyMatch(bound -> appliesToPair(bound, builderName, pair));
    }

    /**
     * Whether one type a self-typed pair's first bound names is a type every
     * link below the target is within, by its erased simple name.
     *
     * @param erased the bound's erased simple name
     * @param targetName the target's simple name
     * @param targetSupertypes the erased simple names the target's own extends and implements clauses name
     * @return whether the bound keeps every link within it
     */
    private static boolean keepsBuiltType(String erased, String targetName, List<String> targetSupertypes) {
        return erased.equals(targetName) || targetSupertypes.contains(erased) || "Object".equals(erased);
    }

    /**
     * Whether a bound names the builder applied to arguments ending with the
     * pair, in order.
     *
     * @param bound the bound, in the spelling {@link #typeText} gives it
     * @param builderName the builder's simple name
     * @param pair the pair's two names, the built type's first
     * @return whether the bound is the builder applied to the pair
     */
    private static boolean appliesToPair(String bound, String builderName, List<String> pair) {
        if (!erasedName(bound).equals(builderName) || bound.indexOf('<') < 0) return false;
        List<String> arguments = typeArguments(bound);
        int count = arguments.size();
        return count >= 2 && arguments.subList(count - 2, count).equals(pair);
    }

}
