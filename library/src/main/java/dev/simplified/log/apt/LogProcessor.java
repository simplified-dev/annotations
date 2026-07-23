package dev.simplified.log.apt;

import dev.simplified.annotations.Log;
import dev.simplified.annotations.SetterNames;
import dev.simplified.classbuilder.apt.NamePattern;
import dev.simplified.log.mutate.LogFieldMutator;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.compat.JavacAccessFactory;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * JSR 269 processor that drives {@link LogFieldMutator}. Resolves the field name
 * from {@code @Log(name)} and dispatches every legal target to the mutator.
 *
 * <p>The field name routes through {@link NamePattern}, the same expander the
 * builder's naming surface uses, so a written pattern behaves identically on
 * both sides of the javac / editor boundary.
 *
 * <p>Emission is refused outright when log4j2 is absent from the target's
 * compile classpath. This library never depends on log4j2 - the generated
 * reference is textual - so the dependency is the consumer's to supply, and
 * saying that plainly beats letting javac fail inside an injected declaration
 * that has no source line to report against.
 */
@SupportedAnnotationTypes("dev.simplified.annotations.Log")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class LogProcessor extends AbstractProcessor {

    static {
        // Mirrors ClassBuilderProcessor - open jdk.compiler before any javac
        // internal types are linked.
        JavacAccessFactory.forRuntime().open();
    }

    private static final String LOG_FQN = "dev.simplified.annotations.Log";

    /** Field name taken when {@code name} is unwritten. */
    private static final String DEFAULT_NAME = "log";

    private Optional<JavacBridge> javacBridge = Optional.empty();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.javacBridge = JavacBridge.of(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Messager messager = processingEnv.getMessager();
        TypeElement annotationElement = processingEnv.getElementUtils().getTypeElement(LOG_FQN);
        if (annotationElement == null) return false;

        Set<TypeElement> targets = new LinkedHashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(annotationElement)) {
            if (!isLegalTarget(element.getKind())) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Log is only supported on classes and enums - '" + element + "' is "
                        + describeKind(element.getKind()),
                    element);
                continue;
            }
            targets.add((TypeElement) element);
        }

        for (TypeElement target : targets) {
            try {
                processTarget(target, messager);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to process @Log on " + target + ": " + e.getMessage(),
                    target);
            }
        }
        return false;
    }

    private void processTarget(TypeElement target, Messager messager) {
        if (javacBridge.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Log requires javac for AST mutation - current environment is not a "
                    + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).",
                target);
            return;
        }

        // Checked here rather than left to javac. The generated field is injected
        // into the AST and carries no source position of its own, so an absent
        // log4j2 surfaces as "cannot find symbol" on a declaration the author
        // cannot open. Reporting on the annotation instead names the artifact.
        if (processingEnv.getElementUtils().getTypeElement(LogFieldMutator.FQN_LOGGER) == null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Log needs log4j2 on the compile classpath - '" + LogFieldMutator.FQN_LOGGER
                    + "' does not resolve, so add a dependency on 'org.apache.logging.log4j:log4j-api'",
                target);
            return;
        }

        Log annotation = target.getAnnotation(Log.class);
        if (annotation == null) return;

        String fieldName = resolveFieldName(target, annotation.name(), messager);
        if (fieldName == null) return;

        LogFieldMutator mutator = new LogFieldMutator(javacBridge.get(), messager);
        if (!mutator.mutate(target, fieldName, annotation.topic(), annotation.emitGenerated())) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Log could not resolve a source tree for " + target
                    + "; mutation requires the annotated element to have a source declaration.",
                target);
        }
    }

    /**
     * Expands the written pattern against the target's simple name.
     *
     * @param target the annotated type, carrying any diagnostic
     * @param written the pattern written on {@code name}
     * @param messager sink for the two rejections
     * @return the field name, or {@code null} when the pattern was rejected
     */
    private static String resolveFieldName(TypeElement target, String written, Messager messager) {
        String pattern = NamePattern.inherit(written, DEFAULT_NAME);
        // Checked ahead of patternError, which reads the sentinel as a valid
        // request to generate nothing.
        if (!NamePattern.emits(pattern)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Log(name) cannot be suppressed with '" + SetterNames.NONE
                    + "' - the logger field is the annotation's only output", target);
            return null;
        }
        String error = NamePattern.patternError(pattern, false);
        if (error != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Log(name) '" + pattern + "' " + error, target);
            return null;
        }
        return NamePattern.expand(pattern, target.getSimpleName().toString());
    }

    /**
     * Whether the kind can carry a generated field. A record's state is its
     * components and an interface field is implicitly {@code public static
     * final}, so neither can hold the {@code private static final} member this
     * annotation exists to produce.
     */
    private static boolean isLegalTarget(ElementKind kind) {
        return kind == ElementKind.CLASS || kind == ElementKind.ENUM;
    }

    /** Renders the rejected kind for the diagnostic, article included. */
    private static String describeKind(ElementKind kind) {
        return switch (kind) {
            case INTERFACE -> "an interface";
            case RECORD -> "a record";
            case ANNOTATION_TYPE -> "an annotation type";
            default -> "a " + kind.name().toLowerCase().replace('_', ' ');
        };
    }

}
