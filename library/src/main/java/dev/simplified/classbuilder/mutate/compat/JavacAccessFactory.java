package dev.simplified.classbuilder.mutate.compat;

import dev.simplified.classbuilder.mutate.compat.v17.JavacAccessV17;

/**
 * Resolves a {@link JavacAccess} implementation for the running JDK.
 *
 * <p>Mirror of {@link JavacCompatFactory}: every currently supported JDK
 * (17 through 25) uses the {@link JavacAccessV17} baseline because the
 * {@code sun.misc.Unsafe} + {@code Module.implAddOpens} reflective
 * bootstrap is stable across those releases. The factory exists as the
 * single dispatch point so a future JDK that hardens those internals (or
 * removes the unsafe surface entirely) is absorbed by a new subclass plus
 * one gate, no caller change required.
 *
 * <p>Version-gated static dispatch, deliberately not
 * {@link java.util.ServiceLoader} - this runs from a static initializer
 * before any classpath service-discovery has had a chance to warm up.
 */
public final class JavacAccessFactory {

    private JavacAccessFactory() {
    }

    /**
     * Resolves the access opener for the running JDK.
     *
     * @return an opener tuned for {@link Runtime#version()}; never null
     */
    public static JavacAccess forRuntime() {
        return forFeatureVersion(Runtime.version().feature());
    }

    /**
     * Resolves the access opener for an explicit feature version. Broken
     * out so tests can exercise dispatch without needing to actually run
     * under the corresponding JDK.
     *
     * @param feature JDK feature version (e.g. 17, 21, 25); currently
     *                unused but retained so a future version gate can be
     *                added here without touching callers
     * @return an opener tuned for that feature level; never null
     */
    public static JavacAccess forFeatureVersion(int feature) {
        return new JavacAccessV17();
    }

}
