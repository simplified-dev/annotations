package dev.sbs.classbuilder.mutate.compat.v25;

import dev.sbs.classbuilder.mutate.compat.v23.JavacCompatV23;

/**
 * Compatibility shim for JDK 25 and above. Empty day-one; exists so a future
 * divergence can be absorbed by overriding a single method here without
 * touching the baseline or other shims.
 */
public class JavacCompatV25 extends JavacCompatV23 {
}
