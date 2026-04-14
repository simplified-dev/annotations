package dev.sbs.classbuilder.mutate.compat.v23;

import dev.sbs.classbuilder.mutate.compat.v21.JavacCompatV21;

/**
 * Compatibility shim for JDK 23 through 24. Empty day-one; exists so a future
 * divergence can be absorbed by overriding a single method here without
 * touching the baseline or other shims.
 */
public class JavacCompatV23 extends JavacCompatV21 {
}
