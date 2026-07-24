package dev.simplified.shared.psi;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;

/**
 * Carries provenance on PSI elements synthesised by any of the plugin's
 * {@code PsiAugmentProvider}s. Replaces the per-feature markers each annotation
 * family previously maintained; the provenance distinction is "the plugin
 * generated this", not "which feature did", so one shared key suffices.
 */
public final class GeneratedMemberMarker {

    private GeneratedMemberMarker() {
    }

    /** Non-null when the element was produced by one of the plugin's augment providers. */
    public static final Key<Boolean> GENERATED =
        Key.create("dev.simplified.psi.generated");

    /** Tags the given element as generated. */
    public static void mark(PsiElement element) {
        element.putUserData(GENERATED, Boolean.TRUE);
    }

    /** Returns {@code true} when the element carries the generated marker. */
    public static boolean isGenerated(PsiElement element) {
        return Boolean.TRUE.equals(element.getUserData(GENERATED));
    }
}
