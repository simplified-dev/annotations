package dev.simplified.classbuilder.mutate;

import dev.simplified.classbuilder.apt.ChainRole;

import javax.lang.model.element.TypeElement;
import java.util.List;

/**
 * The {@code @ClassBuilder}-annotated direct superclass of a SuperBuilder link,
 * together with the type arguments the target passes up to it.
 *
 * <p>The arguments are what makes a generic chain expressible. A concrete link's
 * builder extends {@code Super.Builder<superArgs..., Target, Builder>}, so
 * {@code class StringBox extends Box<String>} has to carry {@code String}
 * through to the extends clause - the superclass's simple name alone cannot
 * reconstruct it.
 *
 * @param simpleName the superclass's simple name
 * @param typeArguments type-display strings for the arguments the target passes
 *        to the superclass, empty when the superclass is not generic
 * @param element the superclass, for reading what its own builder already carries
 * @param role where the superclass itself sits in the chain
 */
record AnnotatedSuper(String simpleName, List<String> typeArguments, TypeElement element,
                      ChainRole role) {
}
