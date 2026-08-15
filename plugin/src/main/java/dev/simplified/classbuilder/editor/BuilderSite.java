package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.WrittenAnnotations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Where a type's {@code @ClassBuilder} is written, and therefore what the
 * builder's slots are.
 *
 * <p>Written on the type, the slots are its fields, record components or
 * abstract accessors. Written on a constructor or static factory, they are that
 * member's parameters - and the builder still nests in the enclosing type and is
 * still entered through it, so everything about the synthesis but the slots and
 * the built type is shared.
 *
 * @param owner the type the builder nests in and is entered through
 * @param executable the annotated constructor or static factory, {@code null}
 *        when the annotation is on the type
 * @param annotation the {@code @ClassBuilder} itself, wherever it is written
 */
record BuilderSite(@NotNull PsiClass owner, @Nullable PsiMethod executable,
                   @NotNull PsiAnnotation annotation) {

    /**
     * Resolves where {@code target}'s builder is declared.
     *
     * <p>The type wins outright when it carries the annotation: the processor
     * rejects a type and a member both carrying one, so the editor has nothing
     * to reconcile and only has to agree about which of the two exists.
     *
     * <p>Reads {@link PsiExtensibleClass#getOwnMethods()} rather than
     * {@code getMethods()}, which is augment-aware and would re-enter the
     * provider asking this question.
     *
     * <p>The member scan matches without resolving - see
     * {@link WrittenAnnotations#findOnMember} - and the whole thing additionally
     * runs under the augment providers' re-entry guard, so a lookup started from
     * anywhere inside synthesis answers {@code null} rather than recursing.
     *
     * @param target the class to read
     * @return the site, or {@code null} when nothing here declares a builder
     */
    static @Nullable BuilderSite of(@NotNull PsiClass target) {
        if (AbstractRecursionSafeAugmentProvider.isInProgress(target)) return null;
        return AbstractRecursionSafeAugmentProvider.withInProgress(target, () -> resolve(target));
    }

    private static @Nullable BuilderSite resolve(PsiClass target) {
        PsiAnnotation onType = WrittenAnnotations.find(target, ClassBuilderConstants.ANNOTATION_FQN);
        if (onType != null) return new BuilderSite(target, null, onType);
        for (PsiMethod own : ownMethods(target)) {
            PsiAnnotation written =
                WrittenAnnotations.findOnMember(own, ClassBuilderConstants.ANNOTATION_FQN);
            if (written == null) continue;
            if (!usable(own)) continue;
            return new BuilderSite(target, own, written);
        }
        return null;
    }

    /**
     * Whether the annotated member can produce a builder at all, on the same
     * terms the processor applies: a factory has to be reachable without an
     * instance, and has to return something for {@code build()} to hand back.
     * A member failing either is one javac rejects, so synthesising for it would
     * put a builder in completion that the build then refuses.
     */
    private static boolean usable(PsiMethod method) {
        if (method.isConstructor()) return true;
        if (!method.hasModifierProperty(PsiModifier.STATIC)) return false;
        PsiType returnType = method.getReturnType();
        return returnType != null && !PsiTypes.voidType().equals(returnType);
    }

    private static List<PsiMethod> ownMethods(PsiClass target) {
        return target instanceof PsiExtensibleClass extensible
            ? extensible.getOwnMethods()
            : List.of(target.getMethods());
    }

    /** Whether the slots are an executable member's parameters. */
    boolean isExecutable() {
        return executable != null;
    }

    /** Whether the slots come from a {@code static} factory rather than a constructor. */
    boolean isStaticFactory() {
        return executable != null && !executable.isConstructor();
    }

    /**
     * The type parameters the generated builder re-declares.
     *
     * <p>A {@code static} factory's own, since it cannot name the enclosing
     * type's; the enclosing type's everywhere else, a constructor running under
     * exactly those.
     *
     * @return the parameters to copy onto the builder
     */
    PsiTypeParameter[] typeParameterSource() {
        return isStaticFactory() ? executable.getTypeParameters() : owner.getTypeParameters();
    }

}
