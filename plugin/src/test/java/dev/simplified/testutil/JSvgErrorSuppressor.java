package dev.simplified.testutil;

import com.intellij.openapi.application.AccessToken;
import com.intellij.testFramework.LoggedErrorProcessor;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * Test helper that swallows {@code Logger.error} reports originating from
 * IntelliJ Platform 2025.3's bundled JSvg icon pipeline.
 *
 * <p>{@code com.intellij.ui.svg.JSvgDocumentFactoryKt} calls a
 * {@code ParsedElement} constructor whose signature changed in the JSvg
 * version bundled with this platform release; loading any SVG icon during a
 * test logs an {@link IllegalAccessError}, which
 * {@link LoggedErrorProcessor} converts into a test failure. The error is
 * benign for our tests - the icon resolves to a placeholder either way - so
 * the suppressor filters the specific category while keeping every other
 * logged error fatal.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * private AccessToken jsvgSuppressor;
 *
 * @Override protected void setUp() throws Exception {
 *     super.setUp();
 *     jsvgSuppressor = JSvgErrorSuppressor.install();
 * }
 *
 * @Override protected void tearDown() throws Exception {
 *     try {
 *         if (jsvgSuppressor != null) jsvgSuppressor.close();
 *     } finally {
 *         super.tearDown();
 *     }
 * }
 * }</pre>
 */
public final class JSvgErrorSuppressor {

    private static final String JSVG_LOGGER_PREFIX = "com.intellij.ui.svg.JSvgDocumentFactory";

    private JSvgErrorSuppressor() {
    }

    /**
     * Installs a {@link LoggedErrorProcessor} that drops JSvg-originated
     * errors. Returns the {@link AccessToken} returned by IntelliJ's scoped
     * override - call {@link AccessToken#close()} in {@code tearDown} to
     * restore the previous processor.
     */
    public static AccessToken install() {
        return LoggedErrorProcessor.executeWith(new LoggedErrorProcessor() {
            @Override
            public @NotNull Set<Action> processError(@NotNull String category,
                                                     @NotNull String message,
                                                     @NotNull String[] details,
                                                     Throwable t) {
                if (category != null && category.contains(JSVG_LOGGER_PREFIX)) {
                    return EnumSet.noneOf(Action.class);
                }
                if (t != null) {
                    StackTraceElement[] frames = t.getStackTrace();
                    for (StackTraceElement frame : frames) {
                        if (frame.getClassName().contains(JSVG_LOGGER_PREFIX)) {
                            return EnumSet.noneOf(Action.class);
                        }
                    }
                }
                return super.processError(category, message, details, t);
            }
        });
    }
}
