package dev.simplified.shared.psi;

import com.intellij.psi.PsiClass;
import com.intellij.psi.augment.PsiAugmentProvider;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Base class for the plugin's {@link PsiAugmentProvider}s that need a
 * thread-local re-entry guard.
 *
 * <p>An augment provider can recurse into itself when its synthesis logic
 * calls augment-aware PSI methods (e.g. {@code PsiClass.getMethods()} or
 * {@code PsiClass.getAnnotations()}) - those go back through the platform's
 * augment chain and re-invoke every provider, including the one whose
 * synthesis is in progress. The shared {@link #IN_PROGRESS} thread-local
 * lets subclasses short-circuit re-entrant calls for the same target, and
 * {@link #withInProgress} wraps a synthesis body so the marker is set for
 * its duration and removed in a {@code finally}.
 *
 * <p>The non-uniform parts of each provider's {@code getAugments} (annotation
 * checks, enum/record guards, dispatch on {@code Class<Psi>}) live in the
 * subclasses; this class only carries the recursion infrastructure.
 */
public abstract class AbstractRecursionSafeAugmentProvider extends PsiAugmentProvider {

    /**
     * Targets currently undergoing synthesis on this thread. Subclasses check
     * {@code IN_PROGRESS.get().contains(target)} at the top of their
     * {@code getAugments} and return an empty list to break the cycle.
     */
    protected static final ThreadLocal<Set<PsiClass>> IN_PROGRESS =
        ThreadLocal.withInitial(HashSet::new);

    /**
     * Whether a target is already undergoing synthesis on this thread.
     *
     * <p>Public because the guard is not only a provider's own business: the
     * helpers a provider calls before it starts synthesising can themselves
     * trigger an augment-aware resolve, and they need the same answer to break
     * the same cycle.
     *
     * @param target the class to test
     * @return whether synthesis for it is already under way here
     */
    public static boolean isInProgress(PsiClass target) {
        return IN_PROGRESS.get().contains(target);
    }

    /**
     * Runs {@code body} with {@code target} marked in-progress on the current
     * thread. Any re-entrant call to a provider extending this class that
     * checks {@link #IN_PROGRESS} for the same target will see the marker and
     * return early.
     */
    public static <T> T withInProgress(PsiClass target, Supplier<T> body) {
        IN_PROGRESS.get().add(target);
        try {
            return body.get();
        } finally {
            IN_PROGRESS.get().remove(target);
        }
    }
}
