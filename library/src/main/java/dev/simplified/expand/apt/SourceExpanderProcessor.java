package dev.simplified.expand.apt;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import dev.simplified.shared.javac.compat.JavacAccessFactory;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Writes an expanded copy of every compilation unit, carrying the members the
 * mutation passes injected into the source text the javadoc tool reads.
 *
 * <p>The javadoc tool runs no annotation processors and cannot be made to, so a
 * link to a generated accessor is a dangling reference in every run that reads
 * source. Pointing a javadoc task at the directory this writes resolves those
 * links against real declarations, and a generated accessor that carries its
 * field's prose renders as a documented member rather than a bare name.
 *
 * <p>Inert unless {@code -Adev.simplified.expandTo=<dir>} is set, so an ordinary
 * build neither reads nor writes anything here.
 *
 * <p>This owns a processor rather than being dispatched from
 * {@code ClassBuilderProcessor} because it only reads, and only after the last
 * round has closed: every pass in every round has finished by then, so it has no
 * ordering relationship with any of them.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions(SourceExpanderProcessor.EXPAND_TO)
public class SourceExpanderProcessor extends AbstractProcessor {

    static {
        // Same bootstrap the mutating processor performs, and for the same
        // reason: the expander links javac-internal types to read the marks and
        // the doc-comment table, and a consumer should not have to configure
        // --add-exports to run it.
        JavacAccessFactory.forRuntime().open();
    }

    /** The processor option naming the directory expanded sources are written to. */
    public static final String EXPAND_TO = "dev.simplified.expandTo";

    /**
     * Name of the file recording what a run wrote, held at the target's root so
     * the next run knows which copies are its own to remove.
     */
    private static final String MANIFEST = ".expanded";

    private final Map<URI, CompilationUnitTree> units = new LinkedHashMap<>();
    private Path target;
    private Trees trees;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        String configured = processingEnv.getOptions().get(EXPAND_TO);
        if (configured == null || configured.isBlank()) return;
        this.target = Path.of(configured);
        this.trees = Trees.instance(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (this.target == null) return false;

        if (!roundEnv.processingOver()) {
            collect(roundEnv);
            return false;
        }

        Set<String> stale = readManifest();
        Set<String> fresh = new LinkedHashSet<>();
        int written = 0;
        for (CompilationUnitTree unit : this.units.values()) {
            String relative = relativePath(unit);
            if (!fresh.add(relative)) {
                this.processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Two compilation units expand to '" + relative + "' - '"
                        + unit.getSourceFile().getName() + "' was not written");
                continue;
            }
            try {
                write(unit, relative);
                written++;
            } catch (IOException | UncheckedIOException e) {
                this.processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not expand '" + unit.getSourceFile().getName() + "': " + e.getMessage());
            }
        }
        stale.removeAll(fresh);
        int removed = removeStale(stale);
        writeManifest(fresh);

        this.processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
            "Expanded " + written + " source files to " + this.target
                + (removed == 0 ? "" : ", removing " + removed + " no longer written"));
        return false;
    }

    /**
     * Records the round's compilation units, keyed by URI so a unit contributing
     * several root elements is held once.
     */
    private void collect(RoundEnvironment roundEnv) {
        for (Element root : roundEnv.getRootElements()) {
            TreePath path = this.trees.getPath(root);
            if (path == null) continue;
            CompilationUnitTree unit = path.getCompilationUnit();
            this.units.putIfAbsent(unit.getSourceFile().toUri(), unit);
        }
    }

    /** Expands one compilation unit and writes it under the target directory. */
    private void write(CompilationUnitTree unit, String relative) throws IOException {
        CharSequence source = unit.getSourceFile().getCharContent(true);
        String expanded = new SourceExpansion(unit, this.trees.getSourcePositions()).expand(source);

        Path destination = this.target.resolve(relative);
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(destination, expanded, StandardCharsets.UTF_8);
    }

    /**
     * The unit's path under the target directory, mirroring its package, with
     * {@code /} as the separator whatever the platform uses.
     *
     * <p>Held as a string rather than a {@link Path} because it is also what the
     * manifest records, and a manifest written on one platform names the same
     * file when it is read back on another.
     */
    private static String relativePath(CompilationUnitTree unit) {
        String simple = fileName(unit);
        var declared = unit.getPackageName();
        if (declared == null) return simple;
        return declared.toString().replace('.', '/') + '/' + simple;
    }

    /**
     * The name to write the unit under, taken from the first type it declares
     * rather than from the file it was read from.
     *
     * <p>Two units can share a file name while declaring different types - a
     * processor minting {@code demo.Helpers} beside an authored
     * {@code Helpers.java} declaring only a package-private type is legal Java -
     * and naming the copy after the file lands both on one path, where the last
     * written wins and the doclet reads whichever that was. A link resolves
     * against the declared type, so the declared type is what the copy is named
     * for. A unit declaring no type at all, which is what {@code package-info}
     * is, keeps the name it was read under.
     *
     * @param unit the compilation unit being expanded
     * @return the file name, always ending in {@code .java}
     */
    private static String fileName(CompilationUnitTree unit) {
        for (Tree declaration : unit.getTypeDecls()) {
            if (declaration instanceof ClassTree type && type.getSimpleName().length() > 0) {
                return type.getSimpleName() + ".java";
            }
        }
        String path = unit.getSourceFile().toUri().getPath();
        if (path == null) return unit.getSourceFile().getName();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * What the previous run wrote, so a copy this one no longer produces can be
     * removed.
     *
     * <p>Recorded rather than inferred, because the expander must never delete a
     * file it did not create: the target is a directory a consumer names, and
     * sweeping it for anything that looks like ours would eventually meet a
     * directory holding something else. An unreadable or absent manifest answers
     * empty, which removes nothing.
     *
     * @return the relative paths the previous run recorded
     */
    private Set<String> readManifest() {
        Path manifest = this.target.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) return new LinkedHashSet<>();
        try {
            return new LinkedHashSet<>(Files.readAllLines(manifest, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new LinkedHashSet<>();
        }
    }

    /** Records what this run wrote, for the next one to compare against. */
    private void writeManifest(Set<String> written) {
        try {
            Files.createDirectories(this.target);
            Files.writeString(this.target.resolve(MANIFEST), String.join("\n", written),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            this.processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "Could not record what was expanded to '" + this.target + "': " + e.getMessage()
                    + " - a copy this build stops producing will be left behind");
        }
    }

    /**
     * Deletes the copies the previous run wrote and this one did not.
     *
     * <p>A renamed or deleted type otherwise leaves its copy in place and the
     * doclet documents a type the build no longer produces. Each path is resolved
     * and checked to be under the target before anything is removed, so an edited
     * manifest cannot reach outside the directory the consumer named.
     *
     * @param stale the relative paths to remove
     * @return how many files were deleted
     */
    private int removeStale(Set<String> stale) {
        Path root = this.target.normalize();
        int removed = 0;
        for (String relative : stale) {
            Path resolved = this.target.resolve(relative).normalize();
            if (!resolved.startsWith(root)) continue;
            try {
                if (Files.deleteIfExists(resolved)) removed++;
            } catch (IOException e) {
                this.processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not remove the stale expansion '" + relative + "': " + e.getMessage());
            }
        }
        return removed;
    }

}
