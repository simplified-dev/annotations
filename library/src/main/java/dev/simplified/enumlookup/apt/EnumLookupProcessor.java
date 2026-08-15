package dev.simplified.enumlookup.apt;

import dev.simplified.annotations.KeyField;
import dev.simplified.enumlookup.mutate.EnumLookupMutator;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.compat.JavacAccessFactory;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JSR 269 processor that drives {@link EnumLookupMutator}. Scans enums carrying
 * {@code @EnumLookup}, collects every {@link KeyField}-annotated instance field,
 * and dispatches to the mutator with the resolved spec list.
 *
 * <p>Standalone {@code @KeyField}s (their enclosing type lacks
 * {@code @EnumLookup}) are silently ignored at processing time - the IDE
 * inspection surfaces the user-visible warning.
 */
@SupportedAnnotationTypes({
    "dev.simplified.annotations.EnumLookup",
    "dev.simplified.annotations.KeyField"
})
public class EnumLookupProcessor extends AbstractProcessor {

    static {
        // Mirrors ClassBuilderProcessor - open jdk.compiler before any javac
        // internal types are linked.
        JavacAccessFactory.forRuntime().open();
    }

    private static final String ENUM_LOOKUP_FQN = "dev.simplified.annotations.EnumLookup";

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
        TypeElement annotationElement = processingEnv.getElementUtils().getTypeElement(ENUM_LOOKUP_FQN);
        if (annotationElement == null) return false;

        Set<TypeElement> targets = new LinkedHashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(annotationElement)) {
            if (element.getKind() != ElementKind.ENUM) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "@EnumLookup is only supported on enum types - skipping " + element,
                    element);
                continue;
            }
            targets.add((TypeElement) element);
        }

        for (TypeElement target : targets) {
            try {
                processEnum(target, messager);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to process @EnumLookup on " + target + ": " + e.getMessage(),
                    target);
            }
        }
        return false;
    }

    private void processEnum(TypeElement target, Messager messager) {
        if (javacBridge.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@EnumLookup requires javac for AST mutation - current environment is not a "
                    + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).",
                target);
            return;
        }

        List<EnumKeySpec> keys = collectKeyFields(target, messager);

        EnumLookupMutator mutator = new EnumLookupMutator(javacBridge.get(), messager);
        if (!mutator.mutate(target, keys)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@EnumLookup could not resolve a source tree for " + target
                    + "; mutation requires the annotated element to have a source declaration.",
                target);
        }
    }

    /**
     * Scans the enum's enclosed elements for instance fields carrying
     * {@code @KeyField}. Static fields with {@code @KeyField} are flagged as
     * errors; the IDE inspection mirrors this.
     */
    private List<EnumKeySpec> collectKeyFields(TypeElement target, Messager messager) {
        List<EnumKeySpec> out = new ArrayList<>();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            KeyField annotation = enclosed.getAnnotation(KeyField.class);
            if (annotation == null) continue;
            if (enclosed.getModifiers().contains(Modifier.STATIC)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@KeyField is not allowed on static fields", enclosed);
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            String fieldName = field.getSimpleName().toString();
            TypeMirror type = field.asType();
            boolean isPrimitive = type.getKind().isPrimitive();
            String methodSuffix = resolveMethodSuffix(annotation.methodName(), fieldName);
            out.add(new EnumKeySpec(
                fieldName,
                methodSuffix,
                typeDisplay(type),
                isPrimitive,
                annotation.ignoreCase(),
                annotation.strictKeys(),
                annotation.strictNullKeys()
            ));
        }
        return out;
    }

    /**
     * Renders a field's declared type without the type-use annotations
     * {@link TypeMirror#toString()} keeps.
     *
     * <p>A field written {@code @NotNull String} renders as
     * {@code "@org.jetbrains.annotations.NotNull java.lang.String"}, which is a
     * different string from {@code "java.lang.String"} and the same type. Two
     * things read this display and neither is asking about nullness: the key
     * array's element type, and whether the key is a {@code String} and can
     * therefore fold case. The second is the one that bites - the comparison
     * silently stays exact, so a lookup the author asked to be case-insensitive
     * compiles clean and then does not match.
     *
     * @param type the field's declared type
     * @return the type's own spelling, type-use annotations dropped
     */
    private static String typeDisplay(TypeMirror type) {
        if (type instanceof ArrayType array) return typeDisplay(array.getComponentType()) + "[]";
        if (!(type instanceof DeclaredType declared)) return type.toString();
        String raw = ((TypeElement) declared.asElement()).getQualifiedName().toString();
        List<? extends TypeMirror> arguments = declared.getTypeArguments();
        if (arguments.isEmpty()) return raw;
        StringBuilder out = new StringBuilder(raw).append('<');
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(typeDisplay(arguments.get(i)));
        }
        return out.append('>').toString();
    }

    private static String resolveMethodSuffix(String methodNameAttr, String fieldName) {
        if (methodNameAttr != null && !methodNameAttr.isEmpty()) return methodNameAttr;
        if (fieldName.isEmpty()) return fieldName;
        return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }
}
