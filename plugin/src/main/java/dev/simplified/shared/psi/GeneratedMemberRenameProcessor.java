package dev.simplified.shared.psi;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.refactoring.rename.RenameJavaVariableProcessor;
import dev.simplified.classbuilder.editor.ClassBuilderRenames;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renames the members synthesised from a slot along with the slot itself.
 *
 * <p>A generated member is spelled from the name of the field behind it, so
 * renaming the field renames the member - at the next build. Until then every
 * call site in the project names a method that no longer exists, and nothing
 * warned about it, because the rename touched only what it could see in source
 * and the accessor is in none.
 *
 * <p>Renaming the member itself does nothing and needs to do nothing: it has no
 * declaration to rewrite, and the next round mints it under the new name. What
 * has to move is the call sites, and putting the member in the rename map is
 * what moves them.
 *
 * <p>An accessor's new name comes from the naming scheme that spelled the old
 * one, recorded at synthesis; a builder setter's comes from
 * {@link ClassBuilderRenames}. Anything synthesised without either is left
 * alone rather than guessed at.
 */
public final class GeneratedMemberRenameProcessor extends RenameJavaVariableProcessor {

    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        // A light field is somebody's synthesised member, not a slot of its own.
        if (element instanceof LightElement) return false;
        if (!GeneratedMembers.isSlot(element)) return false;
        return !GeneratedMembers.mintedFrom((PsiMember) element).isEmpty();
    }

    @Override
    public void prepareRenaming(@NotNull PsiElement element, @NotNull String newName,
                                @NotNull Map<PsiElement, String> allRenames) {
        // The Java processor's own pass finds the accessors an author wrote and
        // asks about them, which is a question this one does not answer.
        super.prepareRenaming(element, newName, allRenames);
        if (!(element instanceof PsiMember slot)) return;

        List<PsiMethod> unnamed = new ArrayList<>();
        for (PsiMethod member : GeneratedMembers.mintedFrom(slot)) {
            String renamed = GeneratedMemberMarker.renamedTo(member, newName);
            if (renamed == null) {
                unnamed.add(member);
            } else if (!renamed.equals(member.getName())) {
                allRenames.put(member, renamed);
            }
        }
        ClassBuilderRenames.collect(slot, newName, unnamed, allRenames);
    }

}
