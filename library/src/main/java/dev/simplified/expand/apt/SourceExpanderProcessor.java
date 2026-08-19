package dev.simplified.expand.apt;

import com.sun.source.tree.CompilationUnitTree;
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

        int written = 0;
        for (CompilationUnitTree unit : this.units.values()) {
            try {
                write(unit);
                written++;
            } catch (IOException | UncheckedIOException e) {
                this.processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not expand '" + unit.getSourceFile().getName() + "': " + e.getMessage());
            }
        }
        this.processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
            "Expanded " + written + " source files to " + this.target);
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
    private void write(CompilationUnitTree unit) throws IOException {
        CharSequence source = unit.getSourceFile().getCharContent(true);
        String expanded = new SourceExpansion(unit, this.trees.getSourcePositions()).expand(source);

        Path destination = this.target.resolve(relativePath(unit));
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, expanded, StandardCharsets.UTF_8);
    }

    /** The unit's path under the target directory, mirroring its package. */
    private static Path relativePath(CompilationUnitTree unit) {
        String name = unit.getSourceFile().toUri().getPath();
        int slash = name.lastIndexOf('/');
        String simple = slash < 0 ? name : name.substring(slash + 1);

        var declared = unit.getPackageName();
        if (declared == null) return Path.of(simple);
        return Path.of(declared.toString().replace('.', '/'), simple);
    }

}
