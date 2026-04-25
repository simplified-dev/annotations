package dev.simplified.classbuilder.mutate.compat;

import dev.simplified.classbuilder.mutate.compat.v17.JavacCompatV17;

/**
 * Resolves a {@link JavacCompat} implementation for the running JDK.
 *
 * <p>Every currently supported JDK (17 through 25) uses the
 * {@link JavacCompatV17} baseline - every javac internal the mutation
 * pipeline touches has been stable across those versions. The factory
 * therefore returns the baseline unconditionally. It exists as the single
 * point that handles shim selection, so when a future JDK forces a
 * divergence the fix is a new subclass plus a version gate here - no
 * caller change required.
 *
 * <p>Version-gated static dispatch, deliberately not {@link java.util.ServiceLoader} -
 * fewer moving parts, no META-INF plumbing, no classloader surprises when
 * {@code --add-exports} misconfigurations surface.
 */
public final class JavacCompatFactory {

    private JavacCompatFactory() {
    }

    /**
     * Resolves the compatibility shim for the running JDK.
     *
     * @return a shim tuned for {@link Runtime#version()}; never null
     */
    public static JavacCompat forRuntime() {
        return forFeatureVersion(Runtime.version().feature());
    }

    /**
     * Resolves the compatibility shim for an explicit feature version. Broken
     * out so tests can assert dispatch behaviour without needing to actually
     * run under the corresponding JDK.
     *
     * @param feature JDK feature version (e.g. 17, 21, 25); currently unused
     *                but retained so a future version gate can be added here
     *                without touching callers
     * @return a shim tuned for that feature level; never null
     */
    public static JavacCompat forFeatureVersion(int feature) {
        return new JavacCompatV17();
    }

}
