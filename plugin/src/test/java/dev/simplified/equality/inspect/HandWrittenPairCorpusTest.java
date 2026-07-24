package dev.simplified.equality.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the two hand-written-pair checks over a real source tree and writes an
 * inventory of what each class would be told.
 *
 * <p>A measurement rather than a regression test, and inert unless pointed at a
 * corpus: it skips when the directory is absent, so it costs a normal run
 * nothing and cannot fail a build on somebody else's checkout.
 *
 * <p><b>The reading under-reports and never over-reports</b>, which is what
 * makes it usable at all. The fixture has no classpath beyond the JDK, so an
 * accessor inherited from a type in another module does not resolve, and an
 * unresolved read is simply not counted. That can turn a split pair into an
 * apparently matched one, or drop a term and make a replaceable pair
 * unrecognisable - both of which end in silence. Nothing it does report is an
 * artefact of the missing classpath; what it stays silent about may be.
 */
public class HandWrittenPairCorpusTest extends LightJavaCodeInsightFixtureTestCase {

    /** Where the sources live. Absent means there is nothing to measure. */
    private static final String CORPUS_PROPERTY = "corpus.dir";

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;",
        Pattern.MULTILINE);

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections(new EqualityConsistencyInspection(),
            new ReplaceableEqualityInspection());
        WholeObjectTestSources.install(myFixture);
        installAccessorStubs();
    }

    /**
     * {@code @Getter} and what it reads, which the shared stubs deliberately
     * leave out.
     *
     * <p>Without these the annotation does not resolve, so
     * {@code AccessorAugmentProvider} never contributes the accessors, and every
     * {@code this.getX()} in a real {@code equals} reads as an unresolved call
     * that the member walk skips. The result is a corpus where nothing is
     * reported and the silence looks like a verdict. Added here rather than to
     * the shared stubs, which several other tests depend on not generating
     * accessors.
     */
    private void installAccessorStubs() {
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Getter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Getter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
                boolean emitContracts() default true;
                boolean emitGenerated() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Setter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Setter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
                boolean emitContracts() default true;
                boolean emitGenerated() default true;
            }
            """);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    public void testCorpusInventory() throws IOException {
        String configured = System.getProperty(CORPUS_PROPERTY);
        if (configured == null || configured.isBlank()) return;
        Path root = Paths.get(configured);
        if (!Files.isDirectory(root)) return;

        List<Path> sources = candidates(root);
        Map<String, List<String>> byVerdict = new LinkedHashMap<>();
        for (Path source : sources) {
            String verdict = verdictFor(source);
            byVerdict.computeIfAbsent(verdict, key -> new ArrayList<>())
                .add(root.relativize(source).toString().replace('\\', '/'));
        }

        StringBuilder report = new StringBuilder();
        report.append("Hand-written equals/hashCode inventory over ").append(root).append('\n');
        report.append(sources.size()).append(" classes declaring both members\n\n");
        for (Map.Entry<String, List<String>> entry : byVerdict.entrySet()) {
            report.append(entry.getKey()).append("  (").append(entry.getValue().size()).append(")\n");
            for (String name : entry.getValue()) report.append("    ").append(name).append('\n');
            report.append('\n');
        }

        Path out = Paths.get("build", "corpus-report.txt").toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("written to " + out);
    }

    /** Every source declaring both members, which is the only shape either check looks at. */
    private static @NotNull List<Path> candidates(@NotNull Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".java")) continue;
                if (path.toString().replace('\\', '/').contains("/build/")) continue;
                String text = Files.readString(path, StandardCharsets.UTF_8);
                if (!text.contains("boolean equals(Object") || !text.contains("int hashCode()")) continue;
                out.add(path);
            }
        }
        out.sort(Path::compareTo);
        return out;
    }

    private @NotNull String verdictFor(@NotNull Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        Matcher matcher = PACKAGE.matcher(text);
        String directory = matcher.find() ? matcher.group(1).replace('.', '/') + "/" : "";
        String path = "corpus/" + directory + source.getFileName();

        PsiFile file = myFixture.addFileToProject(path, text);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());

        String verdict = "SILENT - neither check could read it";
        for (HighlightInfo info : myFixture.doHighlighting()) {
            String described = describe(info);
            if (described != null) return described;
        }
        return verdict;
    }

    private static @Nullable String describe(@NotNull HighlightInfo info) {
        String description = info.getDescription();
        if (description == null) return null;
        if (!HighlightSeverity.WARNING.equals(info.getSeverity())
            && !HighlightSeverity.WEAK_WARNING.equals(info.getSeverity())) {
            return null;
        }
        if (description.contains("by content where this equals")) {
            return "REPLACEABLE - but equality changes on an array member";
        }
        if (description.contains("generates this pair")) return "REPLACEABLE";
        if (description.contains("which equals ignores")) {
            return "SPLIT - hashCode contract break";
        }
        if (description.contains("which hashCode ignores")) {
            return "SPLIT - hash looser than the relation";
        }
        return null;
    }

}
