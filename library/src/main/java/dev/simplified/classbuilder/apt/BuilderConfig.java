package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;

import java.util.Set;

/**
 * Resolved {@code @ClassBuilder} configuration for a single target type.
 * Shared between the sibling emitter (legacy) and the AST-mutation pipeline.
 *
 * <p>The naming components arrive fully resolved: inherited from the style
 * where unwritten, expanded where they carried a placeholder, and - for the
 * three suppressible entry points on {@link BuilderScheme} - empty where the
 * author opted out. Emitters test emptiness rather than consulting a separate
 * opt-out flag.
 */
public record BuilderConfig(
    BuilderScheme names,
    SetterScheme setters,
    AccessLevel access,
    AccessLevel constructorAccess,
    AccessLevel builderConstructorAccess,
    boolean retainInit,
    boolean generateCopyConstructor,
    boolean generateImpl,
    boolean validate,
    boolean emitContracts,
    boolean emitGenerated,
    boolean mergeDeclaredBuilder,
    String factoryMethod,
    Set<String> excludeSet
) {

    /** Simple name of the generated builder class. */
    public String builderName() {
        return names.type();
    }

    /** Name of the static factory returning a fresh builder, empty when suppressed. */
    public String builderMethodName() {
        return names.builder();
    }

    /** Name of the terminal method returning the constructed instance. */
    public String buildMethodName() {
        return names.build();
    }

    /** Name of the static copy factory, empty when suppressed. */
    public String fromMethodName() {
        return names.from();
    }

    /** Name of the instance seed method, empty when suppressed. */
    public String toBuilderMethodName() {
        return names.toBuilder();
    }

}
