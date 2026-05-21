package dev.simplified.lazy;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Internal runtime backing for fields carrying {@link dev.simplified.annotations.Lazy @Lazy}.
 * Not part of the public API - the annotation processor emits references to this
 * class into consumer bytecode, but consumer source code should never import it.
 * The sibling {@code dev.simplified.util.Lazy} is the public-facing memoizing
 * wrapper for direct use.
 * <p>
 * A thread-safe memoizing wrapper that defers a value's computation until first access
 * and caches the result for all subsequent reads.
 * <p>
 * The supplied {@link Supplier} is invoked at most once. After {@link #get()} returns,
 * the computed value, including {@code null}, is cached in a {@code volatile} field and
 * returned directly on every later call. Concurrent {@link #get()} callers race on a
 * single {@code synchronized} block guarded by double-checked locking, so the
 * initializer runs exactly once even under contention.
 *
 * @param <T> the deferred value type
 * @see Supplier
 * @see dev.simplified.annotations.Lazy
 */
@ApiStatus.Internal
public final class Lazy<T> implements Supplier<T> {

    /** Sentinel marking the cache slot as not-yet-computed; distinguishes uninitialized from a cached {@code null}. */
    private static final @NotNull Object UNINITIALIZED = new Object();

    /** The deferred value computation, invoked at most once on first {@link #get()}. */
    private final @NotNull Supplier<T> initializer;

    /** Either {@link #UNINITIALIZED} or the cached value produced by {@link #initializer}. */
    private volatile @NotNull Object value = UNINITIALIZED;

    /**
     * Constructs a new lazy holder backed by the given initializer.
     *
     * @param initializer the deferred value computation
     */
    private Lazy(@NotNull Supplier<T> initializer) {
        this.initializer = initializer;
    }

    /**
     * Creates a new {@code Lazy} that defers to the given initializer until first access.
     *
     * @param <T> the deferred value type
     * @param initializer the supplier whose result will be memoized
     * @return a new lazy holder wrapping {@code initializer}
     */
    public static <T> @NotNull Lazy<T> of(@NotNull Supplier<T> initializer) {
        return new Lazy<>(initializer);
    }

    /**
     * Returns the memoized value, computing it on the first call and reusing the cached
     * result thereafter.
     * <p>
     * The initializer runs exactly once even under concurrent access. If the initializer
     * throws, the exception propagates and the next call to {@code get()} retries the
     * computation.
     *
     * @return the value produced by the initializer, possibly {@code null}
     */
    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        Object current = this.value;

        if (current != UNINITIALIZED)
            return (T) current;

        synchronized (this) {
            current = this.value;

            if (current == UNINITIALIZED) {
                current = this.initializer.get();
                this.value = current;
            }

            return (T) current;
        }
    }

}
