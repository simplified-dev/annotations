package dev.sbs.classbuilder.editor;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFinder;
import com.intellij.psi.impl.file.impl.JavaFileManager;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Bridges {@link com.intellij.psi.JavaPsiFacade#findClass} to our augmented
 * inner classes.
 *
 * <p>{@code JavaPsiFacade.findClass} queries registered {@link PsiElementFinder}
 * extensions and the global class index; neither of those consult
 * {@link com.intellij.psi.augment.PsiAugmentProvider}. So even when our
 * {@link ClassBuilderAugmentProvider} happily returns the synth Builder via
 * {@code Target.getInnerClasses()}, a direct lookup like
 * {@code JavaPsiFacade.findClass("a.Doc.Builder", scope)} returns
 * {@code null}. The Java highlighter relies on that exact lookup when
 * deciding whether a referenced class is accessible at a call site
 * ({@link com.intellij.codeInsight.daemon.impl.analysis.GenericsHighlightUtil}
 * raises {@code "Cannot access X"} when {@code findClass} returns null), so
 * the synth Builder is reported inaccessible from every cross-method call
 * site even though its modifiers are PUBLIC.
 *
 * <p>Mirror of Lombok's {@code LombokElementFinder}: split the FQN into
 * outer + nested, resolve the outer class normally, then route the nested
 * lookup through {@link PsiClass#findInnerClassByName} which IS
 * augment-aware.
 */
public final class ClassBuilderElementFinder extends PsiElementFinder {

    private final JavaFileManager fileManager;

    public ClassBuilderElementFinder(@NotNull Project project) {
        this.fileManager = JavaFileManager.getInstance(project);
    }

    @Override
    public @Nullable PsiClass findClass(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) return null;

        String parentName = qualifiedName.substring(0, lastDot);
        String shortName = qualifiedName.substring(lastDot + 1);
        if (parentName.isEmpty() || shortName.isEmpty()) return null;

        PsiClass parentClass = fileManager.findClass(parentName, scope);
        return parentClass != null ? parentClass.findInnerClassByName(shortName, false) : null;
    }

    @Override
    public PsiClass @NotNull [] findClasses(@NotNull String qualifiedName, @NotNull GlobalSearchScope scope) {
        PsiClass found = findClass(qualifiedName, scope);
        return found != null ? new PsiClass[] {found} : PsiClass.EMPTY_ARRAY;
    }

}
