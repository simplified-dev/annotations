package dev.sbs.classbuilder.mutate.compat;

import dev.sbs.classbuilder.mutate.compat.v17.JavacCompatV17;
import dev.sbs.classbuilder.mutate.compat.v21.JavacCompatV21;
import dev.sbs.classbuilder.mutate.compat.v23.JavacCompatV23;
import dev.sbs.classbuilder.mutate.compat.v25.JavacCompatV25;

/**
 * Selects a {@link JavacCompat} implementation matching the current JDK's
 * feature version. New JDK support is a two-line change: add a new subclass
 * under {@code compat/v<N>/}, then add one conditional here.
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
     * @param feature JDK feature version (e.g. 17, 21, 25)
     * @return a shim tuned for that feature level; never null
     */
    public static JavacCompat forFeatureVersion(int feature) {
        if (feature >= 25) return new JavacCompatV25();
        if (feature >= 23) return new JavacCompatV23();
        if (feature >= 21) return new JavacCompatV21();
        return new JavacCompatV17();
    }

}
