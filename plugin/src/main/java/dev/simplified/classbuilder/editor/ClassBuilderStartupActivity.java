package dev.simplified.classbuilder.editor;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/**
 * Eagerly instantiates {@link ClassBuilderChangeService} on project open so
 * its PSI listener is registered before the user edits any
 * {@code @ClassBuilder}-annotated file.
 *
 * <p>Mirror of {@code ResourcePathStartupActivity}.
 */
final class ClassBuilderStartupActivity implements ProjectActivity {

    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        project.getService(ClassBuilderChangeService.class);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

}
