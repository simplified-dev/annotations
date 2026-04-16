package dev.sbs.classbuilder.mutate.compat;

/**
 * Compatibility shim that opens the {@code jdk.compiler} module's internal
 * {@code com.sun.tools.javac.*} packages to the unnamed module at processor
 * load time, so consumers do not have to configure
 * {@code --add-exports=jdk.compiler/com.sun.tools.javac.*=ALL-UNNAMED} on
 * their javac command line.
 *
 * <p>Same version-gating contract as {@link JavacCompat}: a single baseline
 * implementation covers every supported JDK today (17 through 25) because
 * the reflective {@code Module.implAddOpens} bootstrap has been stable
 * across those releases. Future divergence is absorbed by a new
 * {@code JavacAccessV<N>} subclass plus one gate in
 * {@link JavacAccessFactory}.
 *
 * <p>Implementations must NOT reference any {@code com.sun.tools.javac.*}
 * type directly - even an import would force the JDK module system to
 * resolve the type before opening succeeds, defeating the purpose. The
 * surface here uses only {@code java.lang} / {@code java.lang.reflect}
 * primitives.
 *
 * @see JavacAccessFactory
 */
public interface JavacAccess {

    /**
     * Opens every javac-internal package the AST mutation pipeline needs to
     * the unnamed module. Idempotent and silent on failure - a
     * {@code SecurityManager}, a hardened {@code sun.misc.Unsafe} surface,
     * or running on ecj will all leave the JVM untouched, in which case
     * consumers must fall back to the documented {@code --add-exports} JVM
     * flags. The processor will report a clear error from
     * {@code JavacBridge.of} if access fails after this call.
     */
    void open();

}
