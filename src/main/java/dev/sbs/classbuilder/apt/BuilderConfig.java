package dev.sbs.classbuilder.apt;

import dev.sbs.annotation.AccessLevel;

import java.util.Set;

/**
 * Resolved {@code @ClassBuilder} configuration for a single target type.
 * Shared between the sibling emitter (legacy) and the AST-mutation pipeline.
 */
public record BuilderConfig(
    String builderName,
    String builderMethodName,
    String buildMethodName,
    String fromMethodName,
    String toBuilderMethodName,
    String methodPrefix,
    AccessLevel access,
    boolean generateBuilder,
    boolean generateFrom,
    boolean generateMutate,
    boolean validate,
    boolean emitContracts,
    String factoryMethod,
    Set<String> excludeSet
) {
}
