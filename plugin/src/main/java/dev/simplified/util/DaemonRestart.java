package dev.simplified.util;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

/**
 * Version-safe {@link DaemonCodeAnalyzer} restart helper that prefers the
 * reason-carrying overload ({@code restart(PsiFile, Object)}, added in
 * platform 253 / IntelliJ 2025.3) when available, and falls back to the
 * file-only overload ({@code restart(PsiFile)}, deprecated on 253 but still
 * the only file-scoped option on 232-252).
 *
 * <p>Why reflection: our {@code sinceBuild = 232} / compile target 2023.3
 * predates the reason overload, so a direct {@code daemon.restart(file, reason)}
 * call won't link on 2023.3. Reflection picks the method at runtime based on
 * the host IDE's platform level.
 *
 * <p>Both call sites (ResourcePathChangeService, ClassBuilderChangeService)
 * invoke this from PSI change events, so the wrapper is on the hot path but
 * the method lookup is cached in a static field and subsequent calls are
 * cheap.
 */
@SuppressWarnings("all")
public final class DaemonRestart {

    /**
     * {@code restart(PsiFile, Object)} on 253+; {@code null} on older
     * platforms. The old {@code restart(PsiFile)} is guaranteed on 232+, so
     * there's no corresponding lookup for it.
     */
    private static final Method RESTART_WITH_REASON = findRestartWithReason();

    private DaemonRestart() {
    }

    /**
     * Re-analyses {@code file} with the given {@code reason} as a log tag.
     * Routes to the modern overload when the host platform has it, else
     * falls back to the deprecated-on-253 but still present-on-232
     * {@code restart(PsiFile)}.
     */
    public static void restart(@NotNull Project project, @NotNull PsiFile file, @NotNull String reason) {
        DaemonCodeAnalyzer daemon = DaemonCodeAnalyzer.getInstance(project);
        if (RESTART_WITH_REASON != null) {
            try {
                RESTART_WITH_REASON.invoke(daemon, file, reason);
                return;
            } catch (Exception ignored) {
                // Fall through to the deprecated overload; reflection failure
                // shouldn't block daemon restart on a live edit.
            }
        }
        //noinspection deprecation
        daemon.restart(file);
    }

    private static Method findRestartWithReason() {
        try {
            return DaemonCodeAnalyzer.class.getMethod("restart", PsiFile.class, Object.class);
        } catch (NoSuchMethodException notOn232) {
            return null;
        }
    }

}
