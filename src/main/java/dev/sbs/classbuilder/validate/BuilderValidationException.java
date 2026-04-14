package dev.sbs.classbuilder.validate;

import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when a builder field violates its {@code @BuildFlag} constraints.
 */
public class BuilderValidationException extends RuntimeException {

    /**
     * Constructs a new {@code BuilderValidationException} with the given cause.
     *
     * @param cause the underlying cause
     */
    public BuilderValidationException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code BuilderValidationException} with the given message.
     *
     * @param message the detail message
     */
    public BuilderValidationException(@NotNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@code BuilderValidationException} with the given cause and message.
     *
     * @param cause the underlying cause
     * @param message the detail message
     */
    public BuilderValidationException(@NotNull Throwable cause, @NotNull String message) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code BuilderValidationException} with a formatted message.
     *
     * @param message the format string
     * @param args the arguments referenced by the format specifiers
     */
    public BuilderValidationException(@PrintFormat @NotNull String message, @Nullable Object... args) {
        super(String.format(message, args));
    }

    /**
     * Constructs a new {@code BuilderValidationException} with the given cause and a formatted message.
     *
     * @param cause the underlying cause
     * @param message the format string
     * @param args the arguments referenced by the format specifiers
     */
    public BuilderValidationException(@NotNull Throwable cause, @PrintFormat @NotNull String message, @Nullable Object... args) {
        super(String.format(message, args), cause);
    }

}
