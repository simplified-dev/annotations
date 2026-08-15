package dev.simplified.utility.apt;

import dev.simplified.annotations.ClassBuilder;
import dev.simplified.annotations.UtilityClass;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.compat.JavacAccessFactory;
import dev.simplified.utility.mutate.UtilityClassMutator;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * JSR 269 processor that drives {@link UtilityClassMutator}.
 *
 * <p>Shaped after {@code EnumLookupProcessor}: open {@code jdk.compiler} in a
 * static initialiser before any javac internal type is linked, resolve the
 * bridge in {@code init}, and error rather than degrade when the environment is
 * not javac.
 */
@SupportedAnnotationTypes("dev.simplified.annotations.UtilityClass")
public class UtilityClassProcessor extends AbstractProcessor {

    static {
        JavacAccessFactory.forRuntime().open();
    }

    private static final String ANNOTATION_FQN = "dev.simplified.annotations.UtilityClass";

    private Optional<JavacBridge> javacBridge = Optional.empty();

    /**
     * Reports the running compiler's latest source version. javac reads this to
     * decide whether a processor will accept the source it is handed, which is a
     * different question from the javac API baseline the mutators compile
     * against - naming a specific release here makes every build above it print
     * one warning per registered processor and changes nothing about which
     * compat layer is chosen.
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.javacBridge = JavacBridge.of(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Messager messager = processingEnv.getMessager();
        TypeElement annotationElement = processingEnv.getElementUtils().getTypeElement(ANNOTATION_FQN);
        if (annotationElement == null) return false;

        Set<TypeElement> targets = new LinkedHashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(annotationElement)) {
            if (!isLegalTarget(element, messager)) continue;
            targets.add((TypeElement) element);
        }

        for (TypeElement target : targets) {
            try {
                processTarget(target, messager);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to process @UtilityClass on " + target + ": " + e.getMessage(),
                    target);
            }
        }
        return false;
    }

    /**
     * Rejects every target shape whose meaning the mutation would not survive.
     *
     * <p>A record, enum, interface or annotation each already fixes its own
     * constructor and finality, so there is nothing to retrofit. A nested class
     * has to be {@code static} all the way out: an inner class carries a
     * reference to its enclosing instance, so a private throwing constructor
     * makes it unusable rather than uninstantiable. {@link ClassBuilder} on the
     * same type asks for instances of a type this annotation declares
     * uninstantiable, which nothing downstream can reconcile - the builder
     * compiles and throws at the first {@code build()}.
     */
    private boolean isLegalTarget(Element element, Messager messager) {
        if (element.getKind() != ElementKind.CLASS) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@UtilityClass is only supported on classes - " + element.getSimpleName()
                    + " is " + describe(element.getKind()),
                element);
            return false;
        }
        TypeElement type = (TypeElement) element;
        if (type.getModifiers().contains(Modifier.ABSTRACT)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@UtilityClass cannot be applied to an abstract class - it would be made final",
                element);
            return false;
        }
        if (type.getAnnotation(ClassBuilder.class) != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder contradicts @UtilityClass - one builds instances of "
                    + type.getSimpleName() + ", the other makes it uninstantiable. Drop whichever "
                    + "is wrong",
                element);
            return false;
        }
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (!(e instanceof TypeElement nested)) continue;
            if (nested.getNestingKind() != NestingKind.MEMBER) continue;
            if (nested.getModifiers().contains(Modifier.STATIC)) continue;
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@UtilityClass requires a nested target to be static all the way out - "
                    + nested.getSimpleName() + " is an inner class",
                element);
            return false;
        }
        return true;
    }

    private static String describe(ElementKind kind) {
        return switch (kind) {
            case RECORD -> "a record, whose canonical constructor is its contract";
            case ENUM -> "an enum, which is already final with a private constructor";
            case INTERFACE -> "an interface, which cannot be instantiated anyway";
            case ANNOTATION_TYPE -> "an annotation type";
            default -> "not a class";
        };
    }

    private void processTarget(TypeElement target, Messager messager) {
        if (javacBridge.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@UtilityClass requires javac for AST mutation - current environment is not a "
                    + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).",
                target);
            return;
        }

        UtilityClass annotation = target.getAnnotation(UtilityClass.class);
        if (annotation == null) return;
        UtilityConfig config = UtilityConfig.from(annotation, target.getSimpleName().toString());

        UtilityClassMutator mutator = new UtilityClassMutator(javacBridge.get(), messager);
        if (!mutator.mutate(target, config)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@UtilityClass could not resolve a source tree for " + target
                    + "; mutation requires the annotated element to have a source declaration.",
                target);
        }
    }

}
