package dev.sbs.classbuilder.showcase;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end integration test for the @ClassBuilder runtime. Runs the
 * showcase jar built by :library:showcaseJar in a fresh JVM, parses its
 * gradle-like CASE output, and parameterises over every case so each
 * becomes its own Gradle test row.
 *
 * <p>Expectations: every CASE line must report SUCCESS, the expected ID
 * set must match what the showcase emitted (so a missing or renamed case
 * fails the build), and the trailer must read {@code BUILD SUCCESSFUL}.
 */
@RunWith(Parameterized.class)
public class BuildRuleShowcaseIntegrationTest {

    /**
     * The full set of case IDs the showcase is expected to emit. This
     * set is the coverage contract: adding a case to
     * {@link BuildRuleShowcase} requires adding its id here, and vice
     * versa - the drift is caught by
     * {@link CoverageTest#expectedIdsMatchEmittedIds()}.
     */
    private static final Set<String> EXPECTED_IDS = Set.of(
        "classBuilder.plain",
        "classBuilder.plain.from",
        "classBuilder.plain.mutate",
        "classBuilder.renamed",
        "classBuilder.exclude",
        "classBuilder.access.package",
        "classBuilder.validate.disabled",
        "buildRule.retainInit.literal",
        "buildRule.retainInit.numeric",
        "buildRule.retainInit.object",
        "buildRule.retainInit.fresh",
        "buildRule.retainInit.collection",
        "buildRule.retainInit.factory",
        "buildRule.retainInit.override",
        "buildRule.ignore",
        "buildFlag.nonNull.null",
        "buildFlag.nonNull.value",
        "buildFlag.notEmpty.string.empty",
        "buildFlag.notEmpty.string.value",
        "buildFlag.notEmpty.optional.empty",
        "buildFlag.notEmpty.collection.empty",
        "buildFlag.notEmpty.map.empty",
        "buildFlag.notEmpty.array.empty",
        "buildFlag.pattern.match",
        "buildFlag.pattern.mismatch",
        "buildFlag.pattern.nullSkipped",
        "buildFlag.limit.string.under",
        "buildFlag.limit.string.over",
        "buildFlag.limit.collection.over",
        "buildFlag.limit.optionalNumber.over",
        "buildFlag.group.allMissing",
        "buildFlag.group.onePresent",
        "obtainVia.method",
        "obtainVia.field",
        "obtainVia.isStatic",
        "collector.list.bulk",
        "collector.list.singular",
        "collector.list.clearable",
        "collector.map.put",
        "collector.map.compute",
        "negate.direct",
        "negate.inverse",
        "formattable.string"
    );

    private static Map<String, CaseLine> cases;
    private static List<String> trailer;
    private static int exitCode;

    @BeforeClass
    public static void runShowcase() throws Exception {
        String jarPath = requireProp("showcase.jar");
        String outputDir = requireProp("showcase.output.dir");

        Path jar = Paths.get(jarPath);
        assertTrue("showcase jar not built at " + jar, Files.exists(jar));

        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);
        Path output = dir.resolve("showcase-report.txt");
        Files.deleteIfExists(output);

        String javaBin = System.getProperty("java.home") + "/bin/java"
            + (System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "");

        ProcessBuilder pb = new ProcessBuilder(
            javaBin,
            "-jar", jar.toString(),
            output.toString()
        );
        pb.redirectErrorStream(true);
        pb.inheritIO();

        Process proc = pb.start();
        exitCode = proc.waitFor();

        assertTrue("showcase did not write report: " + output, Files.exists(output));
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        parseReport(lines);
    }

    private static String requireProp(String key) {
        String value = System.getProperty(key);
        if (value == null) fail("system property '" + key + "' not set - is :library:test wired to :showcaseJar?");
        return value;
    }

    private static void parseReport(List<String> lines) {
        Map<String, CaseLine> parsedCases = new LinkedHashMap<>();
        List<String> parsedTrailer = new ArrayList<>();
        boolean pastBlankLine = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                pastBlankLine = true;
                continue;
            }
            if (line.startsWith("CASE")) {
                CaseLine c = CaseLine.parse(line);
                parsedCases.put(c.id, c);
            } else if (pastBlankLine) {
                parsedTrailer.add(line);
            }
        }
        cases = parsedCases;
        trailer = parsedTrailer;
    }

    @AfterClass
    public static void dropRefs() {
        cases = null;
        trailer = null;
    }

    // ------------------------------------------------------------------
    // Parameterised per-case assertion
    // ------------------------------------------------------------------

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> parameters() throws Exception {
        // runShowcase() fires in @BeforeClass on the first invocation, but
        // @Parameters runs before @BeforeClass. Bootstrap the subprocess
        // here so the parameter list reflects what the jar actually emitted.
        if (cases == null) runShowcase();
        List<Object[]> out = new ArrayList<>();
        for (String id : EXPECTED_IDS) out.add(new Object[] { id });
        return out;
    }

    private final String id;

    public BuildRuleShowcaseIntegrationTest(String id) {
        this.id = id;
    }

    @Test
    public void caseReportsSuccess() {
        CaseLine c = cases.get(id);
        assertTrue("case '" + id + "' was not present in the showcase output", c != null);
        assertEquals("case '" + id + "' : " + c.detail, "SUCCESS", c.outcome);
    }

    // ------------------------------------------------------------------
    // Static coverage + trailer assertions - separate test class so they
    // run once instead of per-parameter.
    // ------------------------------------------------------------------

    public static class CoverageTest {
        @BeforeClass
        public static void ensureReport() throws Exception {
            if (cases == null) runShowcase();
        }

        @Test
        public void expectedIdsMatchEmittedIds() {
            Set<String> emitted = cases.keySet();
            Set<String> missing = new java.util.TreeSet<>(EXPECTED_IDS);
            missing.removeAll(emitted);
            Set<String> unexpected = new java.util.TreeSet<>(emitted);
            unexpected.removeAll(EXPECTED_IDS);
            if (!missing.isEmpty() || !unexpected.isEmpty()) {
                fail("case-id drift: missing=" + missing + " unexpected=" + unexpected);
            }
        }

        @Test
        public void trailerSaysBuildSuccessful() {
            assertTrue("expected BUILD SUCCESSFUL trailer, got " + trailer,
                trailer.contains("BUILD SUCCESSFUL"));
        }

        @Test
        public void subprocessExitCodeZero() {
            assertEquals("subprocess exit code", 0, exitCode);
        }
    }

    // ------------------------------------------------------------------
    // Parsed line model
    // ------------------------------------------------------------------

    private static final class CaseLine {
        final String id;
        final String outcome;
        final String detail;

        private CaseLine(String id, String outcome, String detail) {
            this.id = id;
            this.outcome = outcome;
            this.detail = detail;
        }

        /** Parses "CASE <id> | <OUTCOME> | <detail>". */
        static CaseLine parse(String line) {
            String stripped = line.substring("CASE".length()).trim();
            String[] parts = stripped.split("\\|", 3);
            if (parts.length < 2) throw new IllegalArgumentException("malformed CASE line: " + line);
            String id = parts[0].trim();
            String outcome = parts[1].trim();
            String detail = parts.length >= 3 ? parts[2].trim() : "";
            return new CaseLine(id, outcome, detail);
        }
    }

    // Suppress unused warning for IOException import under some toolchains.
    @SuppressWarnings("unused")
    private static IOException unused;

}
