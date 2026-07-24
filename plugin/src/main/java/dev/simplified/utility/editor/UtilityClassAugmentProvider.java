package dev.simplified.utility.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.augment.PsiAugmentProvider;
import dev.simplified.utility.inspect.UtilityClassConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Contributes {@code static} to the members of a
 * {@code @UtilityClass(members = MAKE_STATIC)} target, so the editor agrees with
 * the class file.
 *
 * <p>The processor adds the modifier by mutating the javac AST, which leaves the
 * PSI holding the modifiers the author wrote. Without this, a class-qualified
 * call to such a member reads as an instance member referenced from a static
 * context - an error on source that builds cleanly, and one indistinguishable
 * from a real one. The reference resolves either way, so the divergence is
 * purely the modifier.
 *
 * <p>{@link #transformModifiers} is the one platform hook that alters an element
 * the platform already built; every other provider in this plugin contributes
 * new members through {@code getAugments} instead, which cannot express this.
 *
 * <p>The selection rule is
 * {@code UtilityClassMutator.applyMemberPolicy}'s, member for member: direct
 * children only, constructors never, and a nested type only under
 * {@code nestedTypes}. A rule that merely resembled it would make the editor
 * disagree with the build in the other direction.
 */
public final class UtilityClassAugmentProvider extends PsiAugmentProvider {

    @Override
    protected @NotNull Set<String> transformModifiers(@NotNull PsiModifierList modifierList,
                                                      @NotNull Set<String> modifiers) {
        // This runs from hasModifierProperty for every modifier list in the
        // project, so every rejection ahead of the annotation read is a cheap
        // syntactic test, and nothing here calls hasModifierProperty itself -
        // the incoming set is the authority on what is already written.
        if (modifiers.contains(PsiModifier.STATIC)) return modifiers;

        PsiElement member = modifierList.getParent();
        if (!(member instanceof PsiMethod || member instanceof PsiField
            || member instanceof PsiClass)) return modifiers;
        if (member instanceof PsiMethod method && method.isConstructor()) return modifiers;

        // getParent() rather than getContainingClass(): the processor walks the
        // target's own definitions, so a local class declared inside a method
        // body is not a member of the target and must not be touched. A member
        // declared directly in a class body has that class as its parent.
        if (!(member.getParent() instanceof PsiClass owner)) return modifiers;

        PsiAnnotation utility = writtenUtilityClass(owner);
        if (utility == null) return modifiers;
        if (!UtilityClassConstants.makeStatic(utility)) return modifiers;
        if (member instanceof PsiClass && !nestedTypes(utility)) return modifiers;

        Set<String> augmented = new HashSet<>(modifiers);
        augmented.add(PsiModifier.STATIC);
        return augmented;
    }

    /**
     * The {@code @UtilityClass} written on a class, matched on its simple name.
     *
     * <p>Resolving it here would re-enter every augment provider registered for
     * the class, several of which read the class's annotations on the way
     * through, and the platform answers that cycle by disabling caching and
     * logging an error. The cost is that an unrelated annotation of the same
     * simple name would be honoured, which only affects what the editor
     * predicts - the processor resolves properly and stays the authority.
     *
     * @param owner the class declaring the member
     * @return the annotation, or {@code null} when the class carries none
     */
    private static @Nullable PsiAnnotation writtenUtilityClass(@NotNull PsiClass owner) {
        PsiModifierList modifiers = owner.getModifierList();
        if (modifiers == null) return null;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            if (reference == null) continue;
            if (UtilityClassConstants.UTILITY_CLASS_SHORT_NAME.equals(reference.getReferenceName())) {
                return annotation;
            }
        }
        return null;
    }

    /** Whether {@code nestedTypes = true} is written, which extends the rewrite to nested types. */
    private static boolean nestedTypes(@NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value =
            UtilityClassConstants.written(annotation, UtilityClassConstants.ATTR_NESTED_TYPES);
        if (value instanceof PsiReferenceExpression reference) {
            return "TRUE".equalsIgnoreCase(reference.getReferenceName());
        }
        return value != null && "true".equals(value.getText());
    }

}
