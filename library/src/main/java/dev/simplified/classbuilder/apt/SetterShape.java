package dev.simplified.classbuilder.apt;

/**
 * The shape of one setter a builder generates for a slot, and the two facts a
 * merge into a declared builder asks of it.
 *
 * <p>Named rather than read off each setter's body because the editor's light
 * setters have none: both halves tag each setter they generate with its shape,
 * and a question about what the setter does is answered here, once. Whether it
 * assigns the slot's field decides whether a {@code final} field of the
 * slot's name can hold the slot, and whether the copy entry points call it
 * decides whether an author method covering it has to take the slot's own
 * type.
 */
public enum SetterShape {

    /** The ordinary setter taking the slot's declared type - a custom container's replace setter among them. */
    PLAIN(true, Assignment.ALWAYS),

    /** The typed setter of a boolean slot. */
    BOOLEAN(true, Assignment.ALWAYS),

    /** The zero-argument setter of a boolean slot, or of its {@code @Negate} stem. */
    FLAG(false, Assignment.ALWAYS),

    /** The typed setter of a boolean slot's {@code @Negate} stem, which stores the inverse. */
    NEGATED(false, Assignment.ALWAYS),

    /**
     * The setter of an {@code Optional} slot taking the inner type, which hands
     * the value on to the setter taking the {@code Optional} and assigns nothing
     * itself.
     */
    OPTIONAL_VALUE(false, Assignment.NEVER),

    /** The setter of an {@code Optional} slot taking the {@code Optional} itself. */
    OPTIONAL(true, Assignment.ALWAYS),

    /** The {@code @Formattable} setter taking a format string and its arguments. */
    FORMAT(false, Assignment.ALWAYS),

    /** The varargs setter of an array slot. */
    ARRAY(true, Assignment.ALWAYS),

    /** The varargs bulk setter of a {@code @Collector} list or set. */
    BULK_VARARGS(false, Assignment.UNLESS_APPENDING),

    /** The {@code Iterable} bulk setter of a {@code @Collector} list or set. */
    BULK_ITERABLE(true, Assignment.UNLESS_APPENDING),

    /** The {@code Map} bulk setter of a {@code @Collector} map. */
    BULK_MAP(true, Assignment.UNLESS_APPENDING),

    /** The singular add of a {@code @Collector} list or set. */
    ADD(false, Assignment.NEVER),

    /** The singular put of a {@code @Collector} map. */
    PUT(false, Assignment.NEVER),

    /** The put-if-absent of a {@code @Collector} map. */
    PUT_IF_ABSENT(false, Assignment.NEVER),

    /** The clear of a {@code @Collector} collection or map. */
    CLEAR(false, Assignment.NEVER),

    /** The singular remove of a {@code @Collector} collection or map. */
    REMOVE(false, Assignment.NEVER),

    /** The setter of a {@code @Lazy} slot taking the value, which it wraps as a constant supplier. */
    LAZY_VALUE(true, Assignment.ALWAYS),

    /** The setter of a {@code @Lazy} slot taking the supplier itself. */
    LAZY_SUPPLIER(false, Assignment.ALWAYS),

    /** The setter an {@code @AssignVia} taking a type of its own adds beside the ordinary one. */
    ASSIGN_VIA(false, Assignment.ALWAYS);

    /** When a shape's body assigns the slot's field rather than calling a method on what it holds. */
    private enum Assignment {

        /** On every slot. */
        ALWAYS,

        /** Only where the slot's bulk setters replace the container rather than append into it. */
        UNLESS_APPENDING,

        /** Never - the setter mutates the container the field holds, or delegates. */
        NEVER

    }

    private final boolean copied;
    private final Assignment assignment;

    SetterShape(boolean copied, Assignment assignment) {
        this.copied = copied;
        this.assignment = assignment;
    }

    /**
     * Whether {@code from(T)} and {@code mutate()} pass the slot's value to this
     * setter.
     *
     * <p>Both call the slot's {@code set}-role name with one argument of the
     * slot's own type, which javac resolves to the one setter of that name
     * taking it - the ordinary setter, the typed boolean, the {@code Optional}
     * form, the array varargs, the {@code Iterable} or {@code Map} bulk form, or
     * a lazy slot's value form. Every other shape is one they never call.
     *
     * @return whether the copy entry points call it
     */
    public boolean copied() {
        return copied;
    }

    /**
     * Whether this setter assigns the slot's field.
     *
     * <p>A singular add, put, put-if-absent, remove and clear call a method on
     * the container the field holds, a bulk setter of an appending slot adds into
     * it, and an {@code Optional} slot's inner-type setter hands the value to the
     * setter taking the {@code Optional}; none of them assigns the field. Every
     * other shape does.
     *
     * @param append whether the slot's bulk setters append rather than replace, per
     *     {@code @Collector(append)}
     * @return whether the setter's body assigns the field
     */
    public boolean assigns(boolean append) {
        return switch (assignment) {
            case ALWAYS -> true;
            case UNLESS_APPENDING -> !append;
            case NEVER -> false;
        };
    }

}
