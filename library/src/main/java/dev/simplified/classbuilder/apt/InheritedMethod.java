package dev.simplified.classbuilder.apt;

import java.util.List;

/**
 * A method a declared builder inherits from one of its supertypes, reduced to
 * what decides whether a generated setter of its signature can override it.
 *
 * <p>Each half reads its supertypes its own way - the processor through the
 * element model, the editor's inspection through resolved PSI - and hands the
 * decision these facts, so neither the rule nor its wording lives on the plugin
 * side.
 *
 * @param name the method's name
 * @param parameterTypes each parameter's type as a member of the declared builder, erased, in either model's spelling
 * @param declaringType the simple name of the supertype that declares it
 * @param returnType the return type as the supertype declares it, in either model's spelling
 * @param isFinal whether the method is declared {@code final}
 * @param acceptsBuilderReturn whether a method returning the declared builder may override it - its
 *     return type, as a member of the builder, is a reference type the builder is assignable to
 * @param isStatic whether the method is declared {@code static}, which no instance method can override
 */
public record InheritedMethod(String name, List<String> parameterTypes, String declaringType, String returnType,
                              boolean isFinal, boolean acceptsBuilderReturn, boolean isStatic) { }
