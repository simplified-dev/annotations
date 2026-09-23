package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An author-declared nested builder as written, independent of the model it was
 * read from.
 *
 * <p>Everything here is a name or a flag, so the javac tree and PSI can each
 * fill it from what they have and the decision that reads it runs once for both.
 * Types are as written rather than resolved, because the two models spell an
 * applied type differently and the questions asked of it are about identity:
 * the decision reduces each to the part both models agree on before comparing.
 *
 * @param nestedStatic whether the declared class carries the {@code static} modifier
 * @param nestedAbstract whether the declared class carries the {@code abstract} modifier
 * @param typeParameterNames the declared type parameter names, in declaration order
 * @param typeParameterBounds the first bound written on each type parameter, in the same order, null where none is written
 * @param writtenSuperType the type written in the extends clause with its type arguments removed and its qualifier kept, or null when none is written
 * @param superTypeArguments the type arguments written in the extends clause, in order
 * @param buildMethod the declared build method as written, or null when the class declares none
 */
public record DeclaredBuilderFacts(boolean nestedStatic,
                                   boolean nestedAbstract,
                                   List<String> typeParameterNames,
                                   List<@Nullable String> typeParameterBounds,
                                   @Nullable String writtenSuperType,
                                   List<String> superTypeArguments,
                                   @Nullable DeclaredBuildMethod buildMethod) {

    /**
     * Defensive copies, the two models both handing over lists they still own.
     * The bounds go through {@link Collections#unmodifiableList} rather than
     * {@link List#copyOf}, an unbounded parameter being a null entry.
     */
    public DeclaredBuilderFacts {
        typeParameterNames = List.copyOf(typeParameterNames);
        typeParameterBounds = Collections.unmodifiableList(new ArrayList<>(typeParameterBounds));
        superTypeArguments = List.copyOf(superTypeArguments);
    }

}
