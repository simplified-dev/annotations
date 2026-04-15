package dev.sbs.classbuilder.editor;

import com.intellij.ide.IconProvider;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Replaces the default element icon for every PSI element synthesised by
 * {@link ClassBuilderAugmentProvider} with the project's generated-method
 * icon. Covers both the nested {@code Builder} class and every method it
 * carries (setters, {@code build()}, and the three bootstrap methods on
 * the target itself). Patterned after Lombok's {@code LombokIconProvider}:
 * a marker on the element is the sole eligibility check, so wherever
 * IntelliJ renders an element icon (Structure tool window, completion
 * popup, breadcrumbs, navigation targets), the generated members wear
 * the same {@code /icons/classbuilder_generated.svg} they get in the
 * gutter.
 *
 * <p>Returns {@code null} for any element not produced by this plugin so
 * IntelliJ falls through to the other registered {@link IconProvider}s.
 */
public final class ClassBuilderIconProvider extends IconProvider {

    private static final Icon ICON = IconLoader.getIcon(
        "/icons/classbuilder_generated.svg",
        ClassBuilderIconProvider.class
    );

    @Override
    public @Nullable Icon getIcon(@NotNull PsiElement element, int flags) {
        return GeneratedMemberMarker.isGenerated(element) ? ICON : null;
    }

}
