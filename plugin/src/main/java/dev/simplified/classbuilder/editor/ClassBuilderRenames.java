package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiType;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import dev.simplified.shared.psi.WrittenTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Names the builder setters of a renamed slot.
 *
 * <p>A setter is spelled from the slot it fills, so renaming the slot renames
 * every setter that fills it - and the call sites in the project, which name a
 * method that will not exist under that name once the build runs.
 *
 * <p>The new names come from asking the setter dispatch for the same slot under
 * the new name and pairing its answer with the current one by position. Both
 * lists come out of one generator, so a role added later is carried without
 * anything here changing, and the roles a companion annotation pins - a
 * {@code @Negate} flag, a {@code @Collector} singular name - come back
 * unchanged and drop out on their own.
 */
public final class ClassBuilderRenames {

    private ClassBuilderRenames() {
    }

    /**
     * Adds the builder setters minted from {@code slot} to {@code out}, each
     * against the name it takes once the slot is called {@code newName}.
     *
     * @param slot the field or record component being renamed
     * @param newName the name it is being renamed to
     * @param minted the members synthesised from the slot
     * @param out the rename map to add to
     */
    public static void collect(@NotNull PsiMember slot, @NotNull String newName,
                               @NotNull List<PsiMethod> minted,
                               @NotNull Map<PsiElement, String> out) {
        if (minted.isEmpty()) return;
        PsiClass target = slot.getContainingClass();
        if (target == null) return;

        PsiAnnotation annotation = target.getAnnotation(ClassBuilderConstants.ANNOTATION_FQN);
        if (annotation == null) return;
        PsiClass builder = synthesisedBuilder(target);
        if (builder == null) return;
        PsiType type = declaredType(slot);
        if (type == null) return;

        String currentName = slot.getName();
        if (currentName == null) return;

        GeneratedMemberFactory.EditorBuilderConfig config =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(annotation);
        List<String> current = GeneratedMemberFactory.setterNames(target, builder, config,
            PsiFieldShapeExtractor.buildShape(slot, currentName, type, config.setters()));
        List<String> renamed = GeneratedMemberFactory.setterNames(target, builder, config,
            PsiFieldShapeExtractor.buildShape(slot, newName, type, config.setters()));
        if (current.size() != renamed.size()) return;

        for (PsiMethod setter : minted) {
            int role = current.indexOf(setter.getName());
            if (role < 0) continue;
            String name = renamed.get(role);
            if (!name.equals(setter.getName())) out.put(setter, name);
        }
    }

    /**
     * The builder synthesised onto the target.
     *
     * @param target the annotated type
     * @return the nested builder, or {@code null} when none was synthesised
     */
    private static @Nullable PsiClass synthesisedBuilder(@NotNull PsiClass target) {
        for (PsiClass nested : target.getInnerClasses()) {
            if (GeneratedMemberMarker.isGenerated(nested)) return nested;
        }
        return null;
    }

    /**
     * The type the slot's setters are shaped around.
     *
     * <p>The written type on a field, not its storage: a {@code @Lazy} field
     * holds a supplier and the builder's slot is shaped around the value a
     * caller passes.
     *
     * @param slot the field or record component
     * @return the type, or {@code null} when it does not resolve
     */
    private static @Nullable PsiType declaredType(@NotNull PsiMember slot) {
        if (slot instanceof PsiField field) return WrittenTypes.of(field);
        if (slot instanceof PsiRecordComponent component) return component.getType();
        return null;
    }

}
