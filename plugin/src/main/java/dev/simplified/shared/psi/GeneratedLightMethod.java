package dev.simplified.shared.psi;

import com.intellij.lang.Language;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.impl.light.LightMethodBuilder;
import org.jetbrains.annotations.NotNull;

/**
 * {@link LightMethodBuilder} that accepts a rename by ignoring it.
 *
 * <p>Renaming a slot renames the members spelled from it, and the platform
 * carries that out in two steps: rewrite the references, then set the name on
 * the declaration. Only the first step applies here. There is no declaration to
 * set a name on - the member is minted fresh from the slot on the next round,
 * under whatever the slot is called by then - and the platform's own answer to
 * being asked is to throw, which aborts a refactoring that had already done the
 * part that mattered.
 *
 * <p>So the request is accepted and dropped. The references move, the next
 * synthesis produces the new name, and the two agree without either being told
 * about the other.
 */
public class GeneratedLightMethod extends LightMethodBuilder {

    public GeneratedLightMethod(@NotNull PsiManager manager, @NotNull String name) {
        super(manager, name);
    }

    public GeneratedLightMethod(@NotNull PsiManager manager, @NotNull Language language,
                                @NotNull String name) {
        super(manager, language, name);
    }

    public GeneratedLightMethod(@NotNull PsiManager manager, @NotNull Language language,
                                @NotNull String name, PsiParameterList parameterList,
                                PsiModifierList modifierList) {
        super(manager, language, name, parameterList, modifierList);
    }

    @Override
    public PsiElement setName(@NotNull String name) {
        return this;
    }

}
