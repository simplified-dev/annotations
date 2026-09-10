package dev.simplified.classbuilder.apt;

/**
 * A build method an author wrote on a declared builder.
 *
 * @param returnType the erased return type as written
 * @param methodAbstract whether the method carries the {@code abstract} modifier
 */
public record DeclaredBuildMethod(String returnType, boolean methodAbstract) { }
