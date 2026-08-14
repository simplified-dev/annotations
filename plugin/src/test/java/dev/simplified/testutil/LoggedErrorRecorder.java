package dev.simplified.testutil;

import com.intellij.openapi.application.AccessToken;
import com.intellij.testFramework.LoggedErrorProcessor;
import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Test helper that collects {@code Logger.error} reports instead of letting
 * them fail the test where they are raised.
 *
 * <p>The default {@link LoggedErrorProcessor} rethrows, which surfaces a logged
 * error as whatever call happened to be on the stack - useless when the error
 * comes from deep inside the highlighting pass and the assertion wanted is
 * "nothing was logged for this file at all". Recording lets a test run the pass
 * to completion and then assert over everything it raised, with the full text
 * in the failure message.
 *
 * <p>Errors from the bundled JSvg icon pipeline are dropped rather than
 * recorded, for the reason {@link JSvgErrorSuppressor} documents.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * LoggedErrorRecorder recorder = new LoggedErrorRecorder();
 * try (AccessToken ignored = recorder.install()) {
 *     myFixture.doHighlighting();
 * }
 * assertEquals(List.of(), recorder.reports());
 * }</pre>
 */
public final class LoggedErrorRecorder {

    private static final String JSVG_LOGGER_PREFIX = "com.intellij.ui.svg.JSvgDocumentFactory";

    private final List<String> reports = Collections.synchronizedList(new ArrayList<>());

    /**
     * Installs this recorder for a scope.
     *
     * @return the token restoring the previous processor on close
     */
    public AccessToken install() {
        return LoggedErrorProcessor.executeWith(new LoggedErrorProcessor() {
            @Override
            public @NotNull Set<Action> processError(@NotNull String category,
                                                     @NotNull String message,
                                                     @NotNull String[] details,
                                                     Throwable t) {
                if (!isJSvg(category, t)) reports.add(render(category, message, t));
                return EnumSet.noneOf(Action.class);
            }
        });
    }

    /** Every error logged while the recorder was installed, in order. */
    public List<String> reports() {
        return List.copyOf(reports);
    }

    /**
     * The recorded reports mentioning a needle, as one printable block.
     *
     * @param needle text to look for across the category, message and stack trace
     * @return the matching reports joined by blank lines, empty when none match
     */
    public String matching(@NotNull String needle) {
        StringBuilder out = new StringBuilder();
        for (String report : reports) {
            if (!report.contains(needle)) continue;
            if (!out.isEmpty()) out.append("\n\n");
            out.append(report);
        }
        return out.toString();
    }

    private static boolean isJSvg(String category, Throwable t) {
        if (category != null && category.contains(JSVG_LOGGER_PREFIX)) return true;
        if (t == null) return false;
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().contains(JSVG_LOGGER_PREFIX)) return true;
        }
        return false;
    }

    private static String render(String category, String message, Throwable t) {
        StringWriter text = new StringWriter();
        text.append('[').append(category).append("] ").append(message);
        if (t != null) {
            text.append('\n');
            t.printStackTrace(new PrintWriter(text));
        }
        return text.toString();
    }

}
