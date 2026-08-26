package dev.simplified.shared.psi;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiRecordComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the members synthesised from a given slot.
 *
 * <p>Reference search and rename both need the same answer - which methods
 * stand in for this field - and neither can read it off the member, a light
 * method having no body to analyse and no name shape that holds across the
 * naming schemes. What it does have is the navigation element the synthesising
 * provider pointed at the slot, so the pair is matched on identity.
 */
public final class GeneratedMembers {

    private GeneratedMembers() {
    }

    /** Whether the element is a slot a generated member could have been minted from. */
    public static boolean isSlot(@Nullable PsiElement element) {
        return element instanceof PsiField || element instanceof PsiRecordComponent;
    }

    /**
     * Every method synthesised from {@code slot}, across the class that declares
     * it and the types nested in that class.
     *
     * <p>The nested walk is not defensive: {@code @ClassBuilder} hangs its
     * setters off the nested {@code Builder} rather than off the annotated type,
     * so stopping at the declaring class would find the accessors and miss every
     * setter in the project.
     *
     * @param slot the field or record component to resolve
     * @return the methods minted from it, empty when it backs none
     */
    public static @NotNull List<PsiMethod> mintedFrom(@NotNull PsiMember slot) {
        PsiClass owner = slot.getContainingClass();
        if (owner == null) return List.of();

        List<PsiMethod> out = new ArrayList<>();
        collectFrom(owner, slot, out);
        for (PsiClass nested : owner.getInnerClasses()) collectFrom(nested, slot, out);
        return out;
    }

    /**
     * Adds the methods of {@code owner} that were synthesised from {@code slot}.
     *
     * @param owner the class whose methods to sift
     * @param slot the field or record component they would have been minted from
     * @param out the list to add to
     */
    private static void collectFrom(@NotNull PsiClass owner, @NotNull PsiMember slot,
                                    @NotNull List<PsiMethod> out) {
        for (PsiMethod method : owner.getMethods()) {
            if (!GeneratedMemberMarker.isGenerated(method)) continue;
            if (method.getNavigationElement() != slot) continue;
            out.add(method);
        }
    }

}
