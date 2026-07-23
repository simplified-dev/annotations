package dev.simplified.cleanup.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * FQNs, the suppressed tool ids and the two annotation readers for the
 * {@code @Cleanup} IDE support, so the suppressor and the inspection agree on
 * what carries the annotation.
 *
 * <p>The two readers are deliberately not one. The inspection resolves, because
 * it reports errors and a same-named annotation from another library must not
 * collect them. The suppressor matches the written name, because it runs on the
 * inspection hot path for every element every other tool reports on, and
 * resolving there is both the expensive answer and the one that re-enters the
 * analysis it was called from.
 */
public final class CleanupConstants {

    public static final @NotNull String CLEANUP_FQN = "dev.simplified.annotations.Cleanup";
    public static final @NotNull String CLEANUP_SHORT_NAME = "Cleanup";

    public static final @NotNull String AUTO_CLOSEABLE_FQN = "java.lang.AutoCloseable";

    /**
     * The resource-leak tools a {@code @Cleanup} declaration answers.
     *
     * <p>These are suppression ids rather than short names - the platform passes
     * {@code LocalInspectionTool.getID()}, which the {@code suppressId} attribute
     * sets and which differs from the short name for both of the tools that are
     * on by default. The short names are kept alongside them so a tool that
     * declares no {@code suppressId} still matches.
     */
    public static final @NotNull Set<String> RESOURCE_TOOL_IDS = Set.of(
        "resource",
        "IOResourceOpenedButNotSafelyClosed",
        "AutoCloseableResource",
        "IOResource"
    );

    private CleanupConstants() {}

    /**
     * The resolved {@code @Cleanup} written on a declaration.
     *
     * @param owner the variable to read
     * @return the annotation, or {@code null} when it carries none
     */
    public static @Nullable PsiAnnotation resolved(@NotNull PsiModifierListOwner owner) {
        return owner.getAnnotation(CLEANUP_FQN);
    }

    /**
     * Whether the declaration carries an annotation spelled {@code Cleanup}.
     *
     * <p>Matched on the reference text rather than by resolving it. The cost is
     * that a same-named annotation from another library is honoured, and the
     * consequence of that is one unreported resource-leak warning - strictly
     * smaller than the cost of resolving an annotation from inside another
     * inspection's callback.
     *
     * @param owner the variable to read
     * @return whether an annotation of that name is written on it
     */
    public static boolean writesCleanup(@Nullable PsiModifierListOwner owner) {
        if (owner == null) return false;
        PsiModifierList modifiers = owner.getModifierList();
        if (modifiers == null) return false;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            var reference = annotation.getNameReferenceElement();
            if (reference == null) continue;
            if (CLEANUP_SHORT_NAME.equals(reference.getReferenceName())) return true;
        }
        return false;
    }

}
