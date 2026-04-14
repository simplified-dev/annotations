package dev.sbs.classbuilder.mutate.compat.v21;

import dev.sbs.classbuilder.mutate.compat.v17.JavacCompatV17;

/**
 * Compatibility shim for JDK 21 through 22. Empty day-one - the baseline
 * {@link JavacCompatV17} covers everything the mutation pipeline needs on
 * these versions. Exists so a future divergence can be absorbed by overriding
 * a single method here without touching the baseline or other shims.
 */
public class JavacCompatV21 extends JavacCompatV17 {
}
