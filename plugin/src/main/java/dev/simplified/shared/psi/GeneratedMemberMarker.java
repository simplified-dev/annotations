package dev.simplified.shared.psi;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * Carries provenance on PSI elements synthesised by any of the plugin's
 * {@code PsiAugmentProvider}s. Replaces the per-feature markers each annotation
 * family previously maintained; the provenance distinction is "the plugin
 * generated this", not "which feature did", so one shared key suffices.
 *
 * <p>The second key answers a different question than the first - not where a
 * member came from but what it does to the slot behind it - and only the
 * synthesising provider knows the answer. A reader cannot recover it from the
 * member: a light method has no body to analyse, and its name follows whichever
 * naming scheme the target asked for, so the shape a bean setter is recognised
 * by is exactly the shape a fluent or builder setter does not have.
 */
public final class GeneratedMemberMarker {

    private GeneratedMemberMarker() {
    }

    /** Non-null when the element was produced by one of the plugin's augment providers. */
    public static final Key<Boolean> GENERATED =
        Key.create("dev.simplified.psi.generated");

    /** Non-null when the generated member writes the slot it was minted from. */
    public static final Key<Boolean> WRITES =
        Key.create("dev.simplified.psi.generated.writes");

    /**
     * Non-null when the generated member's name can be recomputed for a renamed
     * slot. Holds the derivation the synthesising site used, applied to the
     * slot's new name.
     */
    public static final Key<UnaryOperator<String>> RENAME =
        Key.create("dev.simplified.psi.generated.rename");

    /** Tags the given element as generated. */
    public static void mark(PsiElement element) {
        element.putUserData(GENERATED, Boolean.TRUE);
    }

    /** Tags the given element as generated and as writing the slot behind it. */
    public static void markWrite(PsiElement element) {
        mark(element);
        element.putUserData(WRITES, Boolean.TRUE);
    }

    /** Returns {@code true} when the element carries the generated marker. */
    public static boolean isGenerated(PsiElement element) {
        return Boolean.TRUE.equals(element.getUserData(GENERATED));
    }

    /** Returns {@code true} when the element writes the slot it was minted from. */
    public static boolean isWrite(PsiElement element) {
        return Boolean.TRUE.equals(element.getUserData(WRITES));
    }

    /**
     * Records how to spell this member's name for a renamed slot.
     *
     * <p>The derivation rather than the result, because the site that minted the
     * name is the only place that knows which rule produced it - a naming
     * scheme, a fixed {@code name} attribute, or a companion annotation that
     * pins the name whatever the slot is called.
     *
     * @param element the synthesised member
     * @param naming the slot's new name to this member's new name
     */
    public static void markRename(PsiElement element, UnaryOperator<String> naming) {
        element.putUserData(RENAME, naming);
    }

    /**
     * This member's name for a slot renamed to {@code slotName}.
     *
     * @param element the synthesised member
     * @param slotName the slot's new name
     * @return the member's new name, or {@code null} when the site recorded no
     *     derivation
     */
    public static @Nullable String renamedTo(PsiElement element, String slotName) {
        UnaryOperator<String> naming = element.getUserData(RENAME);
        return naming == null ? null : naming.apply(slotName);
    }
}
