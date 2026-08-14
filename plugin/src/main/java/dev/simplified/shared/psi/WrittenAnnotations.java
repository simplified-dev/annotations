package dev.simplified.shared.psi;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.augment.PsiAugmentProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Matches a written annotation against a fully-qualified name without resolving
 * every annotation that is obviously not it.
 *
 * <p>{@link PsiAnnotation#getQualifiedName()} resolves the annotation's name
 * reference, and a resolve started from inside a {@link PsiAugmentProvider}
 * runs while the platform is already holding the class being augmented. The
 * name lookup consults the class's nested types before its imports, the
 * nested-type lookup is augment-aware, and so the resolve can re-enter the
 * provider that started it. What the platform does with that cycle is not a
 * useful answer, so the resolve is worth avoiding wherever it can be.
 *
 * <p>The simple-name test that guards it costs no resolution and rejects
 * nothing the qualified-name test would have accepted:
 * {@code getQualifiedName()} ends with the reference's own name either way -
 * with the resolved class's simple name when the reference resolves, and with
 * the written text when it does not. So a name that differs there can never
 * produce a matching FQN, and the gate only ever removes work.
 *
 * <p>This is the narrow half of the trade. Matching on the reference text
 * <i>alone</i> would also honour a same-named annotation from an unrelated
 * package, which some callers accept deliberately; the methods here do not,
 * because they still confirm the FQN once the cheap test passes.
 */
public final class WrittenAnnotations {

    private WrittenAnnotations() {
    }

    /**
     * Whether an annotation is the one named by a fully-qualified name.
     *
     * @param annotation the written annotation
     * @param fqn the fully-qualified name to match
     * @return whether the annotation spells that name
     */
    public static boolean spells(@NotNull PsiAnnotation annotation, @NotNull String fqn) {
        return namesMatch(annotation, fqn) && fqn.equals(annotation.getQualifiedName());
    }

    /**
     * The first of several names an annotation spells.
     *
     * @param annotation the written annotation
     * @param fqns the fully-qualified names to match, in priority order
     * @return the matched name, or {@code null} when it is none of them
     */
    public static @Nullable String spelledAmong(@NotNull PsiAnnotation annotation,
                                                @NotNull String @NotNull ... fqns) {
        for (String fqn : fqns) {
            if (spells(annotation, fqn)) return fqn;
        }
        return null;
    }

    /**
     * The annotation of a given name written on an owner.
     *
     * @param owner the field, component or type to read
     * @param fqn the fully-qualified name to match
     * @return the annotation, or {@code null} when the owner carries none
     */
    public static @Nullable PsiAnnotation find(@NotNull PsiModifierListOwner owner,
                                               @NotNull String fqn) {
        for (PsiAnnotation annotation : owner.getAnnotations()) {
            if (spells(annotation, fqn)) return annotation;
        }
        return null;
    }

    /**
     * Whether an owner carries the annotation of a given name.
     *
     * @param owner the field, component or type to read
     * @param fqn the fully-qualified name to match
     * @return whether it is written on the owner
     */
    public static boolean has(@NotNull PsiModifierListOwner owner, @NotNull String fqn) {
        return find(owner, fqn) != null;
    }

    private static boolean namesMatch(PsiAnnotation annotation, String fqn) {
        PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
        if (reference == null) return false;
        String written = reference.getReferenceName();
        return written != null && written.equals(fqn.substring(fqn.lastIndexOf('.') + 1));
    }

}
