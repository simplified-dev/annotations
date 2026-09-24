package dev.simplified.lazy.apt;

/**
 * The diagnostic for {@code @Lazy(access = NONE)}, which both halves report.
 *
 * <p>A lazy field's storage holds the deferred supplier, so the synthesised
 * getter is the only read that yields the declared type, and suppressing it
 * would leave the field unreadable. The processor reports the sentence on the
 * field and generates the getter public beside it; the editor reports it on
 * the written value and contributes the same public getter.
 */
public final class LazyAccess {

    private LazyAccess() { }

    /**
     * The error for {@code access = NONE} on a lazy field.
     *
     * @param fieldName the lazy field's name
     * @return the sentence both halves report
     */
    public static String notExpressible(String fieldName) {
        return "@Lazy(access = NONE) would leave field '" + fieldName + "' unreadable - its storage "
            + "holds the deferred supplier and the synthesised getter is the only read that resolves it";
    }

}
