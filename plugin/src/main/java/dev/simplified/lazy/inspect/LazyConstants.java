package dev.simplified.lazy.inspect;

import org.jetbrains.annotations.NotNull;

/**
 * FQNs and shared constants for the {@code @Lazy} IDE support. Mirrors the
 * layout of {@code ClassBuilderConstants} and {@code EnumLookupConstants} so
 * each annotation family stays cleanly separated.
 */
public final class LazyConstants {

    public static final @NotNull String LAZY_FQN = "dev.simplified.annotations.Lazy";
    public static final @NotNull String LAZY_SHORT_NAME = "Lazy";

    private LazyConstants() {}
}
