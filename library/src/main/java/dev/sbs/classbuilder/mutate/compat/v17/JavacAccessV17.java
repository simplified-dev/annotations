package dev.sbs.classbuilder.mutate.compat.v17;

import dev.sbs.classbuilder.mutate.compat.JavacAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Baseline {@link JavacAccess} for JDK 17 - 25.
 *
 * <p>Opens every {@code com.sun.tools.javac.*} package the AST-mutation
 * pipeline reaches into via a two-step bootstrap:
 * <ol>
 *   <li>Obtain {@code sun.misc.Unsafe} from the {@code jdk.unsupported}
 *       module (which exports {@code sun.misc} to all callers by default).</li>
 *   <li>Use {@code Unsafe.staticFieldOffset} + {@code Unsafe.getObject} to
 *       acquire the privileged {@code MethodHandles.Lookup.IMPL_LOOKUP} -
 *       this lookup has unrestricted access to every module and can invoke
 *       any method reflectively, bypassing all access-control checks.</li>
 *   <li>Use the privileged lookup to find and invoke
 *       {@code Module.implAddOpens(String)} on the {@code jdk.compiler}
 *       module for each target package.</li>
 * </ol>
 *
 * <p>This mirrors the technique Lombok uses for the same purpose and
 * avoids the fragile {@code AccessibleObject.override} field that was
 * removed or renamed across recent JDK releases. The
 * {@code IMPL_LOOKUP} field has been stable since JDK 9 and is present
 * through JDK 25.
 *
 * <p>This class deliberately holds no references to any
 * {@code com.sun.tools.javac.*} type - not even an import. All javac
 * plumbing lives in the {@code mutate} package and is touched only after
 * the processor has called {@link #open()}.
 */
public class JavacAccessV17 implements JavacAccess {

    /**
     * Every javac-internal package the AST mutation pipeline imports
     * directly, plus the runtime-only set the in-process javac touches
     * during the same compilation pass. Mirrors the union of
     * {@code javacCompileExports} + {@code javacRuntimeExports} in the
     * library's own {@code build.gradle.kts}.
     */
    private static final String[] PACKAGES = {
        "com.sun.tools.javac.api",
        "com.sun.tools.javac.code",
        "com.sun.tools.javac.comp",
        "com.sun.tools.javac.file",
        "com.sun.tools.javac.jvm",
        "com.sun.tools.javac.main",
        "com.sun.tools.javac.model",
        "com.sun.tools.javac.parser",
        "com.sun.tools.javac.processing",
        "com.sun.tools.javac.tree",
        "com.sun.tools.javac.util"
    };

    /** Latch: the bootstrap is JVM-wide; running it twice is a no-op cost. */
    private static volatile boolean opened = false;

    @Override
    public void open() {
        if (opened) return;
        try {
            doOpen();
            opened = true;
        } catch (Throwable t) {
            // Silent fallback path: the consumer can still pass --add-exports
            // explicitly, and JavacBridge.of will surface a clear error if the
            // resulting access still fails. Throwing here would crash the
            // whole annotation-processor classloader before any of our own
            // diagnostics had a chance to render.
        }
    }

    private static void doOpen() throws Throwable {
        // Step 1: obtain sun.misc.Unsafe (jdk.unsupported module exports it)
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);

        // Step 2: steal MethodHandles.Lookup.IMPL_LOOKUP via Unsafe
        // IMPL_LOOKUP is a static final field with MODULE access that javac
        // itself uses for invoking internal methods. By reading it through
        // Unsafe we bypass all module/access checks.
        java.lang.reflect.Method staticFieldOffset = unsafeClass
            .getDeclaredMethod("staticFieldOffset", Field.class);
        java.lang.reflect.Method getObject = unsafeClass
            .getDeclaredMethod("getObject", Object.class, long.class);

        Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        long offset = (long) staticFieldOffset.invoke(unsafe, implLookupField);
        MethodHandles.Lookup implLookup =
            (MethodHandles.Lookup) getObject.invoke(unsafe, MethodHandles.Lookup.class, offset);

        // Step 3: find Module.implAddOpens(String) via the privileged lookup
        // Single-arg overload opens to the EVERYONE pseudo-module so both
        // unnamed and named caller modules get access.
        MethodHandle addOpens = implLookup.findVirtual(
            Module.class,
            "implAddOpens",
            MethodType.methodType(void.class, String.class)
        );

        // Step 4: open each package on jdk.compiler
        Module jdkCompiler = ModuleLayer.boot()
            .findModule("jdk.compiler")
            .orElseThrow(() -> new IllegalStateException("jdk.compiler not in boot layer"));

        for (String pkg : PACKAGES) {
            try {
                addOpens.invoke(jdkCompiler, pkg);
            } catch (Throwable ignored) {
                // A single missing package shouldn't abort the whole bootstrap.
            }
        }
    }

}
