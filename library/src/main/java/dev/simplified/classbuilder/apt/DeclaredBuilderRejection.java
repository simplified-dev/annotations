package dev.simplified.classbuilder.apt;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The reason an author-declared nested builder cannot carry the members a role
 * generates.
 *
 * <p>Each constant owns the wording it is reported with, so the processor's
 * {@code Messager} call and the inspection's {@code registerProblem} call render
 * the same bytes. A diagnostic reimplemented in the plugin is the same class of
 * drift as a validator reimplemented there: the editor would go red on a
 * sentence javac never prints, or stay silent on one it does.
 *
 * <p>Every one of these is an error rather than a note, by the rule that decides
 * the boundary: the alternative to reporting here is javac failing inside code
 * the author did not write. Saying it on the declaration they did write is the
 * whole value.
 */
public enum DeclaredBuilderRejection {

    /**
     * A record takes no instance field, an enum no {@code new}, and an
     * interface neither, so only a class can hold the slot fields and the
     * constructor the entry points call.
     */
    NOT_A_CLASS("@ClassBuilder cannot merge into '%s' - it is declared as %s, and only a class can "
        + "hold the builder's fields and the constructor %s() calls"),

    /**
     * An inner class captures the enclosing instance, so no {@code static} entry
     * point can create one.
     */
    NOT_STATIC("@ClassBuilder cannot merge into '%s' - an inner class captures the enclosing "
        + "instance, so %s() has nothing to create it from. Declare it static"),

    /**
     * A self-typed role leaves {@code self()} and {@code build()} abstract, which
     * a concrete class cannot hold.
     */
    NOT_ABSTRACT("@ClassBuilder cannot merge into '%s' - the builder of %s carries an abstract "
        + "self() and build(), so the class holding them has to be abstract too"),

    /**
     * A role whose entry points instantiate the builder cannot have that builder
     * declared abstract.
     */
    ABSTRACT_ON_CONCRETE_ROLE("@ClassBuilder cannot merge into '%s' - it is what %s() "
        + "instantiates, so it cannot be abstract"),

    /**
     * The generated members are written in the builder's own re-declared
     * parameters, which have to be there and in the same order for a generated
     * setter to name the slot's type at all. The second operand says which
     * parameters those are, since a self-typed role's include the pair it
     * declares for itself.
     */
    TYPE_PARAMETERS("@ClassBuilder cannot merge into '%s' - a static nested builder %s, and this "
        + "one declares %s"),

    /**
     * The generated members apply the builder's re-declared parameters to the
     * target and the target's to the builder, so each has to carry the bounds
     * the target writes on it - a looser one fails the generated build method's
     * bound check, a narrower one the generated entry point's.
     */
    TYPE_PARAMETER_BOUNDS("@ClassBuilder cannot merge into '%s' - a static nested builder has to "
        + "bound the target's type parameters as %s, and this one declares %s"),

    /**
     * The trailing pair carries the bounds that make the builder self-typed, and
     * a setter returning the second parameter is unusable without them.
     */
    SELF_TYPE_BOUNDS("@ClassBuilder cannot merge into '%s' - its trailing pair has to be bounded "
        + "as %s for the generated setters to return the caller's own builder type, and this one "
        + "declares %s"),

    /** A link's builder inherits the parent's setters through its extends clause. */
    MISSING_SUPER_TYPE("@ClassBuilder cannot merge into '%s' - the builder of a chained target has "
        + "to extend %s, and this one extends nothing"),

    /** The extends clause names a builder other than the annotated ancestor's. */
    WRONG_SUPER_TYPE("@ClassBuilder cannot merge into '%s' - the builder of a chained target has "
        + "to extend %s, and this one extends %s"),

    /**
     * The extends clause passes the ancestor's builder arguments other than the
     * ones the generated members are typed against - the ancestor's own as the
     * target passes them, then the link and its builder, or the declaration's
     * own trailing pair on a chained abstract.
     */
    SUPER_TYPE_ARGUMENTS("@ClassBuilder cannot merge into '%s' - the builder of a chained target "
        + "has to pass %s the arguments %s, and this one passes %s"),

    /**
     * A build method the author wrote has to be the one the role's callers get,
     * which means returning what the role's build method returns - or, on an
     * abstract root, the root itself, which every link's build method overrides.
     */
    BUILD_RETURN_TYPE("@ClassBuilder cannot merge into '%s' - its build method returns %s where "
        + "this role builds %s, so it cannot stand in for the generated one");

    private final @PrintFormat String template;

    DeclaredBuilderRejection(@PrintFormat String template) {
        this.template = template;
    }

    /**
     * Formats this rejection for report.
     *
     * @param args the values this rejection interpolates, in the order its wording names them
     * @return the diagnostic text the processor and the inspection both report
     */
    public @NotNull String message(@Nullable Object... args) {
        return String.format(template, args);
    }

}
