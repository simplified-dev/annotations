package dev.sbs.classbuilder.showcase;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Minimal fluent DSL the showcase main uses to record per-case outcomes.
 * Each {@link #expect(String) expect} call starts a case, the action runs,
 * and the resolver ({@link Case#asSuccess()} / {@link Case#asFailure})
 * records {@code SUCCESS} or {@code FAILURE} against expected behaviour.
 *
 * <p>Output layout (mimics Gradle-style task output):
 * <pre>{@code
 * CASE <id> | SUCCESS | <details>
 * CASE <id> | FAILURE | <expected> vs <actual>
 * ...
 *
 * BUILD SUCCESSFUL
 * <total> cases, <failures> failures
 * }</pre>
 *
 * <p>A non-zero exit code accompanies any BUILD FAILED trailer so callers
 * that only check process exit also catch failures.
 */
public final class ShowcaseReport {

    private final Path output;
    private final List<String> lines = new ArrayList<>();
    private int total = 0;
    private int failed = 0;

    public ShowcaseReport(Path output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    public Case expect(String id) {
        return new Case(id);
    }

    /** Writes the trailer, flushes the file, and {@code System.exit}s. */
    public void finish() {
        boolean ok = failed == 0;
        lines.add("");
        lines.add(ok ? "BUILD SUCCESSFUL" : "BUILD FAILED");
        lines.add(total + " cases, " + failed + " failures");
        try {
            Files.createDirectories(output.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("unable to write showcase report to " + output, e);
        }
        // Also echo the trailer to stdout so Gradle's test-task log shows it.
        System.out.println();
        System.out.println(ok ? "BUILD SUCCESSFUL" : "BUILD FAILED");
        System.out.println(total + " cases, " + failed + " failures");
        System.exit(ok ? 0 : 1);
    }

    private void record(String id, boolean success, String detail) {
        total++;
        if (!success) failed++;
        lines.add(String.format("CASE %-44s | %-7s | %s",
            id, success ? "SUCCESS" : "FAILURE", detail));
    }

    // ------------------------------------------------------------------
    // Fluent API
    // ------------------------------------------------------------------

    /** Runs a case that is expected to succeed; caller supplies the built object. */
    public final class Case {
        private final String id;

        private Case(String id) { this.id = id; }

        /**
         * Runs the supplier; if it throws, the case records FAILURE. The
         * returned {@link Success} exposes optional post-build assertions.
         */
        public Success run(Supplier<Object> action) {
            Object value;
            try {
                value = action.get();
            } catch (Throwable t) {
                record(id, false, "unexpected exception " + t.getClass().getSimpleName()
                    + ": " + String.valueOf(t.getMessage()));
                return new Success(id, null, true);
            }
            return new Success(id, value, false);
        }

        /**
         * Runs the runnable; no return value, pure side-effect. Used for
         * cases whose success is just "did not throw".
         */
        public Success runVoid(Runnable action) {
            try {
                action.run();
            } catch (Throwable t) {
                record(id, false, "unexpected exception " + t.getClass().getSimpleName()
                    + ": " + String.valueOf(t.getMessage()));
                return new Success(id, null, true);
            }
            return new Success(id, null, false);
        }

        /**
         * Runs an action that is expected to throw. The assertion chain on
         * the returned {@link Failure} pins down the exception type and
         * message.
         */
        public Failure runExpectingThrow(Runnable action) {
            try {
                action.run();
            } catch (Throwable t) {
                return new Failure(id, t);
            }
            // Did not throw - record a failure now; the caller's chained
            // assertions will no-op because the outcome is already fixed.
            record(id, false, "expected throw but action returned normally");
            return new Failure(id, null);
        }
    }

    /** Chained assertions on a succeeded case. */
    public final class Success {
        private final String id;
        private final Object value;
        private boolean alreadyFailed;

        private Success(String id, Object value, boolean alreadyFailed) {
            this.id = id;
            this.value = value;
            this.alreadyFailed = alreadyFailed;
        }

        /** Records SUCCESS with the built object's {@code toString}. */
        public Success asSuccess() {
            if (alreadyFailed) return this;
            record(id, true, value == null ? "(void)" : value.toString());
            alreadyFailed = true; // prevent double-record
            return this;
        }

        /** Records SUCCESS with a caller-provided detail string. */
        public Success asSuccess(String detail) {
            if (alreadyFailed) return this;
            record(id, true, detail);
            alreadyFailed = true;
            return this;
        }
    }

    /** Chained assertions on a case that threw. */
    public final class Failure {
        private final String id;
        private final Throwable thrown;

        private Failure(String id, Throwable thrown) {
            this.id = id;
            this.thrown = thrown;
        }

        /**
         * Asserts the thrown exception is of the given type; the case is
         * only recorded successful once {@link #messageEquals} or
         * {@link #messageContains} completes the chain.
         */
        public Failure asFailure(Class<? extends Throwable> expected) {
            if (thrown == null) return this;
            if (!expected.isInstance(thrown)) {
                record(id, false, "expected " + expected.getSimpleName()
                    + " but got " + thrown.getClass().getSimpleName()
                    + ": " + thrown.getMessage());
                return markFailed();
            }
            return this;
        }

        /** Asserts the thrown exception's message equals {@code expected}. */
        public Failure messageEquals(String expected) {
            if (thrown == null) return this;
            if (!expected.equals(thrown.getMessage())) {
                record(id, false, "expected message \"" + expected
                    + "\" but got \"" + thrown.getMessage() + "\"");
                return markFailed();
            }
            record(id, true, "expected-failure " + thrown.getClass().getSimpleName()
                + " \"" + thrown.getMessage() + "\"");
            return markFailed();
        }

        /**
         * Terminator that accepts any message - use when the case only
         * needs to confirm the exception type and a specific message would
         * be brittle (e.g. third-party exception chains).
         */
        public Failure andAnyMessage() {
            if (thrown == null) return this;
            record(id, true, "expected-failure " + thrown.getClass().getSimpleName()
                + " \"" + thrown.getMessage() + "\"");
            return markFailed();
        }

        /** Asserts the thrown exception's message contains {@code needle}. */
        public Failure messageContains(String needle) {
            if (thrown == null) return this;
            String msg = String.valueOf(thrown.getMessage());
            if (!msg.contains(needle)) {
                record(id, false, "expected message to contain \"" + needle
                    + "\" but got \"" + msg + "\"");
                return markFailed();
            }
            record(id, true, "expected-failure " + thrown.getClass().getSimpleName()
                + " \"" + msg + "\"");
            return markFailed();
        }

        private Failure markFailed() {
            // Blank sentinel so repeated calls don't re-record.
            return new Failure(id, null);
        }
    }

}
