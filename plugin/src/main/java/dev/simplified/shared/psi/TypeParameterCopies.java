package dev.simplified.shared.psi;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.impl.light.LightTypeParameterBuilder;
import org.jetbrains.annotations.NotNull;

/**
 * Bounds for a set of light type parameters copied from written ones, spelled
 * against the copies.
 *
 * <p>A generated member re-declaring a type's or a factory's parameters writes
 * each bound as the source declares it, so a bound naming a parameter - itself,
 * as in {@code T extends Comparable<T>}, or another of the list - names the
 * copy it is declared beside. Carrying the source's bound types over unchanged
 * would leave them naming the source's parameters, which no witness for the
 * copy can satisfy.
 */
public final class TypeParameterCopies {

    private TypeParameterCopies() {
    }

    /**
     * Adds each source parameter's bounds to its copy, every source parameter
     * named in them replaced by its copy.
     *
     * @param sources the written type parameters, in declaration order
     * @param copies their copies, one per source in the same order
     */
    public static void copyBounds(PsiTypeParameter @NotNull [] sources,
                                  LightTypeParameterBuilder @NotNull [] copies) {
        if (sources.length == 0) return;
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(copies[0].getProject());
        PsiSubstitutor toCopies = PsiSubstitutor.EMPTY;
        for (int i = 0; i < sources.length && i < copies.length; i++)
            toCopies = toCopies.put(sources[i], elements.createType(copies[i]));
        for (int i = 0; i < sources.length && i < copies.length; i++) {
            for (PsiClassType bound : sources[i].getExtendsListTypes()) {
                PsiType spelled = toCopies.substitute(bound);
                copies[i].getExtendsList().addReference(spelled instanceof PsiClassType classType ? classType : bound);
            }
        }
    }

}
