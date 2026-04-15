package dev.sbs.classbuilder.editor;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTreeChangeAdapter;
import com.intellij.psi.PsiTreeChangeEvent;
import com.intellij.util.messages.MessageBusConnection;
import dev.sbs.classbuilder.inspect.ClassBuilderConstants;
import dev.sbs.util.DaemonRestart;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Listens for {@link PsiAnnotation} changes involving {@code @ClassBuilder} or
 * its companion annotations and triggers the daemon to re-analyze so affected
 * inspections and synthesised members update immediately.
 *
 * <p>The augment provider's {@code CachedValueProvider.Result} already depends
 * on {@link com.intellij.psi.util.PsiModificationTracker#MODIFICATION_COUNT},
 * which invalidates on every PSI edit; this service is the belt-and-braces
 * complement that nudges the daemon to repaint the moment the user types a
 * tracked annotation, so printf / null-flow / {@code @BuildFlag}-derived
 * warnings visibly appear or disappear without a manual keystroke.
 *
 * <p>Intentionally narrow: only the three events that can include an
 * annotation node (added / removed / replaced) are observed. No full-file
 * scans, no DumbService guards. A short-name false positive during dumb mode
 * merely triggers a harmless extra daemon restart.
 *
 * <p>Mirror of {@code ResourcePathChangeService}.
 */
@Service(Service.Level.PROJECT)
final class ClassBuilderChangeService {

    private final @NotNull Project project;

    public ClassBuilderChangeService(@NotNull Project project) {
        this.project = project;
        MessageBusConnection connection = project.getMessageBus().connect();
        PsiManager.getInstance(project).addPsiTreeChangeListener(listener(), connection);
    }

    private @NotNull PsiTreeChangeAdapter listener() {
        return new PsiTreeChangeAdapter() {
            @Override
            public void childAdded(@NotNull PsiTreeChangeEvent event) {
                handle(event.getChild());
            }

            @Override
            public void childRemoved(@NotNull PsiTreeChangeEvent event) {
                handle(event.getOldChild());
            }

            @Override
            public void childReplaced(@NotNull PsiTreeChangeEvent event) {
                handle(event.getOldChild());
                handle(event.getNewChild());
            }

            private void handle(@Nullable PsiElement element) {
                if (!(element instanceof PsiAnnotation annotation)) return;
                if (!isTracked(annotation)) return;

                PsiFile file = annotation.getContainingFile();
                if (file == null || !file.isValid()) return;

                DaemonRestart.restart(project, file, "ClassBuilder annotation changed");
            }
        };
    }

    /**
     * True when the annotation is one of the tracked ClassBuilder / companion
     * annotations, matched by either fully-qualified name (smart mode) or
     * short name (dumb-mode fallback).
     */
    private static boolean isTracked(@NotNull PsiAnnotation annotation) {
        String qname = annotation.getQualifiedName();
        if (qname != null && ClassBuilderConstants.TRACKED_ANNOTATION_FQNS.contains(qname)) return true;

        PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
        if (ref == null) return false;
        String shortName = ref.getReferenceName();
        return shortName != null && ClassBuilderConstants.TRACKED_ANNOTATION_SHORT_NAMES.contains(shortName);
    }

}
