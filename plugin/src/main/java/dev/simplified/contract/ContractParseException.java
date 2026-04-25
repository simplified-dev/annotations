package dev.simplified.contract;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a contract string fails to parse.
 * {@link #getPosition()} and {@link #getTokenLength()} can be used to compute
 * a {@code TextRange} within the annotation literal for IDE highlighting.
 */
public final class ContractParseException extends Exception {

    private final int position;
    private final int tokenLength;

    public ContractParseException(@NotNull String message, int position, int tokenLength) {
        super(message);
        this.position = position;
        this.tokenLength = tokenLength;
    }

    public ContractParseException(@NotNull String message, int position) {
        this(message, position, 1);
    }

    /** Zero-based character offset in the contract string where the error occurred. */
    public int getPosition() {
        return position;
    }

    /** Length of the offending token (used for IDE range highlighting). */
    public int getTokenLength() {
        return tokenLength;
    }

}