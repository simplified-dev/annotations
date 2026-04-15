package dev.sbs.classbuilder.editor;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;

/**
 * Carries provenance on PSI elements synthesised by
 * {@link ClassBuilderAugmentProvider}. Read by
 * {@link ClassBuilderLineMarkerProvider} to decide which methods and classes
 * deserve a gutter marker.
 */
public final class GeneratedMemberMarker {

    private GeneratedMemberMarker() {
    }

    /** Non-null when the element was produced by {@link ClassBuilderAugmentProvider}. */
    public static final Key<Boolean> GENERATED_BY_CLASSBUILDER =
        Key.create("dev.sbs.classbuilder.generated");

    /** Tags the given element as generated. */
    public static void mark(PsiElement element) {
        element.putUserData(GENERATED_BY_CLASSBUILDER, Boolean.TRUE);
    }

    /** Returns {@code true} when the element carries the generated marker. */
    public static boolean isGenerated(PsiElement element) {
        return Boolean.TRUE.equals(element.getUserData(GENERATED_BY_CLASSBUILDER));
    }

}
