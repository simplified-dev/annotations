package dev.simplified.classbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One parity case, read off the shared resource root both suites are pointed at.
 *
 * <p>A case is a target source and a list of members each half must agree about -
 * the processor by emitting them or not, the editor by contributing them or not.
 * Writing the claim once is the point: an editor case and a processor case
 * transcribed separately from one intent are two claims that drift, and the
 * divergence between the halves is what this project exists to prevent.
 *
 * <p>The plugin suite carries its own reader of the same resources, the two
 * source sets being unable to share one - the IntelliJ test framework's module
 * layer hides {@code jdk.compiler}, which the apt suite requires.
 */
public final class BuilderParityFixture {

    /**
     * A single member claim.
     *
     * @param owner {@code target} for the annotated type, {@code builder} for its declared nested builder
     * @param member the member's simple name
     * @param present whether both halves must produce it, as opposed to neither
     */
    public record Expectation(String owner, String member, boolean present) { }

    /** The annotated type's simple name, as an owner in an expectation line. */
    public static final String TARGET = "target";

    /** The declared nested builder, as an owner in an expectation line. */
    public static final String BUILDER = "builder";

    private final String caseName;
    private final String qualifiedName;
    private final String builderName;
    private final String source;
    private final List<Expectation> expectations;

    private BuilderParityFixture(String caseName, String qualifiedName, String builderName,
                                 String source, List<Expectation> expectations) {
        this.caseName = caseName;
        this.qualifiedName = qualifiedName;
        this.builderName = builderName;
        this.source = source;
        this.expectations = List.copyOf(expectations);
    }

    /**
     * Reads the named case off the classpath.
     *
     * @param caseName the case's file-name stem under {@code /parity}
     * @return the loaded case
     * @throws IllegalStateException if either half of the case is missing from the classpath
     */
    public static BuilderParityFixture load(String caseName) {
        String source = read(caseName + ".java");
        String members = read(caseName + ".members");
        String qualifiedName = null;
        String builderName = "Builder";
        List<Expectation> expectations = new ArrayList<>();
        for (String raw : members.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 2);
            if (parts.length != 2)
                throw new IllegalStateException("parity/" + caseName + ".members: cannot read '" + raw + "'");
            switch (parts[0]) {
                case "type" -> qualifiedName = parts[1];
                case "builder-name" -> builderName = parts[1];
                case TARGET, BUILDER -> expectations.add(expectation(caseName, parts[0], parts[1]));
                default -> throw new IllegalStateException(
                    "parity/" + caseName + ".members: unknown owner '" + parts[0] + "'");
            }
        }
        if (qualifiedName == null)
            throw new IllegalStateException("parity/" + caseName + ".members: no 'type' line");
        return new BuilderParityFixture(caseName, qualifiedName, builderName, source, expectations);
    }

    private static Expectation expectation(String caseName, String owner, String signed) {
        boolean present = switch (signed.charAt(0)) {
            case '+' -> true;
            case '-' -> false;
            default -> throw new IllegalStateException(
                "parity/" + caseName + ".members: '" + signed + "' starts with neither + nor -");
        };
        return new Expectation(owner, signed.substring(1).trim(), present);
    }

    private static String read(String resource) {
        try (InputStream in = BuilderParityFixture.class.getResourceAsStream("/parity/" + resource)) {
            if (in == null) throw new IllegalStateException("no such parity resource: /parity/" + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read /parity/" + resource, e);
        }
    }

    /** The case's file-name stem under the shared resource root. */
    public String caseName() {
        return caseName;
    }

    /** The annotated type's fully qualified name. */
    public String qualifiedName() {
        return qualifiedName;
    }

    /** The annotated type's simple name. */
    public String simpleName() {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    /** The declared nested builder's simple name. */
    public String builderName() {
        return builderName;
    }

    /** The target source, verbatim. */
    public String source() {
        return source;
    }

    /** The source's path relative to a source root, package directories included. */
    public String path() {
        return qualifiedName.replace('.', '/') + ".java";
    }

    /** Every member claim the case makes. */
    public List<Expectation> expectations() {
        return expectations;
    }

    /**
     * The claims made about one owner.
     *
     * @param owner {@link #TARGET} or {@link #BUILDER}
     * @return that owner's claims, in the order the case writes them
     */
    public List<Expectation> forOwner(String owner) {
        List<Expectation> out = new ArrayList<>();
        for (Expectation expectation : expectations) {
            if (expectation.owner().equals(owner)) out.add(expectation);
        }
        return out;
    }

    /**
     * Reports which claims a set of member names fails.
     *
     * @param owner {@link #TARGET} or {@link #BUILDER}
     * @param names the member names actually found on that owner
     * @return one line per failed claim, empty when every claim holds
     */
    public List<String> mismatches(String owner, List<String> names) {
        List<String> out = new ArrayList<>();
        for (Expectation expectation : forOwner(owner)) {
            boolean found = names.contains(expectation.member());
            if (found == expectation.present()) continue;
            out.add(caseName + ": " + owner + '.' + expectation.member()
                + (expectation.present() ? " is missing" : " is present")
                + ", found " + names);
        }
        return out;
    }

}
