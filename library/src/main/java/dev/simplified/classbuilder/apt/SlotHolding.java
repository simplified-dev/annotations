package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;

/**
 * The form a builder holds a slot in, and why that form differs from the
 * field's declared type where it does.
 *
 * <p>Named rather than derived at each call site because the two halves read it
 * from different models - the processor from a tree the round is still
 * building, the editor from PSI it must not resolve - and a mistyped-slot
 * diagnostic interpolates the reason. One clause per constant is what keeps the
 * sentence the processor prints and the one the inspection reports the same.
 */
public enum SlotHolding {

    /** Held as the field's declared type. */
    DECLARED(""),

    /**
     * A collected field whose default reads instance state, gathered into a
     * plain {@code java.util} scratch container. No clause: the storage type the
     * sentence prints already names that container, and a supplier clause would
     * contradict it.
     */
    COLLECTED_SCRATCH(""),

    /** A {@code @Lazy} field, held as a supplier of its declared type. */
    LAZY(". A @Lazy field is held in the builder as a supplier of its declared type"),

    /**
     * A slot whose retained initializer reads instance state, held as a supplier
     * of its declared type so the default can be computed where the instance
     * exists.
     */
    INSTANCE_DEFAULT(". A slot whose retained initializer reads instance state is held in the "
        + "builder as a supplier of its declared type");

    private final @NotNull String clause;

    SlotHolding(@NotNull String clause) {
        this.clause = clause;
    }

    /**
     * Classifies a slot from the three facts its storage follows from, in the
     * order the builder's field emitter decides them.
     *
     * <p>A collected instance default is a scratch container even when it is
     * lazy, the setters needing something real to mutate; otherwise a lazy slot
     * and an instance default are each a supplier, and everything else is held
     * as declared.
     *
     * @param lazy whether the field carries {@code @Lazy}
     * @param collected whether the field is a {@code @Collector} list, set or map
     * @param instanceDefault whether its captured initializer reads instance state, per
     *     {@link InstanceDefaults#readsInstanceState}
     * @return how the slot is held
     */
    public static @NotNull SlotHolding of(boolean lazy, boolean collected, boolean instanceDefault) {
        if (instanceDefault && collected) return COLLECTED_SCRATCH;
        if (lazy) return LAZY;
        if (instanceDefault) return INSTANCE_DEFAULT;
        return DECLARED;
    }

    /**
     * The sentence a mistyped-slot diagnostic ends with, explaining why the
     * storage type is not the declared one.
     *
     * @return the trailing clause, empty where the slot is held as its storage type says plainly
     */
    public @NotNull String clause() {
        return clause;
    }

    /**
     * Whether the slot is held as a supplier of its declared type.
     *
     * @return whether the storage type is {@code Supplier<T>}
     */
    public boolean isSupplier() {
        return this == LAZY || this == INSTANCE_DEFAULT;
    }

}
