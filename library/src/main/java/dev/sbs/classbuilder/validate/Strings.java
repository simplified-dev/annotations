package dev.sbs.classbuilder.validate;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Small string utility used by generated builder setters for {@link Optional}
 * and nullable string fields decorated with {@code @Formattable}.
 *
 * <p>Centralises the "null-tolerant {@link String#format}" behaviour so the
 * generated nullable-format setters can stay one line each.
 */
public final class Strings {

    private Strings() {}

    /**
     * Returns an {@link Optional} containing {@code String.format(format, args)},
     * or {@link Optional#empty()} when the format string is {@code null}.
     *
     * <p>Unlike {@link String#format(String, Object...)}, passing a {@code null}
     * format does not throw - it resolves to an empty result - so this method
     * can be used directly as the right-hand side of an {@code Optional<String>}
     * field assignment.
     *
     * @param format the format string, may be {@code null}
     * @param args the arguments referenced by the format specifiers
     * @return an optional carrying the formatted string, or empty when {@code format} is {@code null}
     */
    public static @NotNull Optional<String> formatNullable(@PrintFormat @Nullable String format, @Nullable Object... args) {
        return Optional.ofNullable(format == null ? null : String.format(format, args));
    }

}
