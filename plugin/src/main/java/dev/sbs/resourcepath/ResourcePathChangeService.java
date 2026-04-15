package dev.sbs.resourcepath;

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
import dev.sbs.util.DaemonRestart;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Listens for {@link PsiAnnotation} changes involving {@code @ResourcePath} and triggers the
 * daemon to re-analyze so affected inspections update immediately.
 *
 * <p>Intentionally narrow: only the three events that can include an annotation node
 * (added/removed/replaced) are observed. No full-file scans, no DumbService guards. If the
 * short-name / FQN fallback misses the event during indexing, IntelliJ's own post-index
 * analysis pass catches up.
 */
@Service(Service.Level.PROJECT)
final class ResourcePathChangeService {

    private final @NotNull Project project;

    public ResourcePathChangeService(@NotNull Project project) {
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
                if (!isResourcePath(annotation)) return;

                PsiFile file = annotation.getContainingFile();
                if (file == null || !file.isValid()) return;

                DaemonRestart.restart(project, file, "ResourcePath annotation changed");
            }
        };
    }

    /**
     * True when the annotation is either the fully-qualified {@code @ResourcePath} (smart mode)
     * or has the matching short name (dumb-mode fallback - may false-positive on like-named
     * annotations from other packages, which just causes an extra harmless daemon restart).
     */
    private static boolean isResourcePath(@NotNull PsiAnnotation annotation) {
        String qname = annotation.getQualifiedName();
        if (ResourcePathConstants.ANNOTATION_FQN.equals(qname)) return true;

        PsiJavaCodeReferenceElement ref = annotation.getNameReferenceElement();
        return ref != null && ResourcePathConstants.ANNOTATION_SHORT_NAME.equals(ref.getReferenceName());
    }

}
