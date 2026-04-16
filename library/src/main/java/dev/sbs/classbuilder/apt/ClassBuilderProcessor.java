package dev.sbs.classbuilder.apt;

import dev.sbs.annotation.AccessLevel;
import dev.sbs.classbuilder.mutate.BuilderMutator;
import dev.sbs.classbuilder.mutate.JavacBridge;
import dev.sbs.classbuilder.mutate.compat.JavacAccessFactory;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Annotation processor that generates a sibling {@code <TypeName>Builder.java}
 * for each class carrying {@code @ClassBuilder}. Records, interfaces, and
 * abstract-class targets are reserved for a later phase.
 */
@SupportedAnnotationTypes("dev.sbs.annotation.ClassBuilder")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class ClassBuilderProcessor extends AbstractProcessor {

    static {
        // Open jdk.compiler/com.sun.tools.javac.* to the unnamed module
        // BEFORE the JVM has a chance to link any field type that imports
        // those packages. The static initializer runs on class load, before
        // instance creation or init() invocation, which means JavacBridge
        // and the rest of the mutate package can subsequently be linked
        // without IllegalAccessError - and consumers do not need to
        // configure --add-exports themselves. JavacAccess holds zero
        // javac-internal references so loading it does not trigger the
        // very access check we are trying to avoid.
        JavacAccessFactory.forRuntime().open();
    }

    private static final String ANNOTATION_FQN = "dev.sbs.annotation.ClassBuilder";

    private final AnnotationLookup lookup = new AnnotationLookup();
    private SourceIntrospector introspector;
    private Optional<JavacBridge> javacBridge = Optional.empty();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.introspector = new SourceIntrospector(processingEnv);
        this.javacBridge = JavacBridge.of(processingEnv);
    }

    /** Exposed for tests. */
    Optional<JavacBridge> javacBridge() {
        return javacBridge;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Messager messager = processingEnv.getMessager();
        TypeElement annotationElement = lookupAnnotationElement();
        if (annotationElement == null) return false;
        for (Element element : roundEnv.getElementsAnnotatedWith(annotationElement)) {
            ElementKind kind = element.getKind();
            if (kind != ElementKind.CLASS && kind != ElementKind.RECORD && kind != ElementKind.INTERFACE) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                    "@ClassBuilder on " + kind + " targets is not yet supported - skipping",
                    element
                );
                continue;
            }
            try {
                if (kind == ElementKind.INTERFACE) {
                    processInterface((TypeElement) element, messager);
                } else {
                    processClass((TypeElement) element, messager);
                }
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate builder for " + element + ": " + e.getMessage(),
                    element
                );
            }
        }
        return false;
    }

    private TypeElement lookupAnnotationElement() {
        return processingEnv.getElementUtils().getTypeElement(ANNOTATION_FQN);
    }

    private void processClass(TypeElement target, Messager messager) {
        BuilderConfig config = extractConfig(target);
        List<FieldSpec> fields = collectFields(target, config);

        if (javacBridge.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder requires javac for AST mutation - current environment is not a "
                    + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).",
                target);
            return;
        }
        BuilderMutator mutator = new BuilderMutator(javacBridge.get(), messager);
        if (!mutator.mutate(target, config, fields)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder could not resolve a source tree for " + target
                    + "; mutation requires the annotated element to have a source declaration.",
                target);
        }
    }

    private void processInterface(TypeElement target, Messager messager) throws IOException {
        BuilderConfig config = extractConfig(target);

        // generateImpl=false means the user takes responsibility for producing
        // the instance build() constructs. That only works if factoryMethod is
        // set so build() has something concrete to call; otherwise the
        // generator has no way to fulfil its contract.
        if (!config.generateImpl() && config.factoryMethod().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder(generateImpl = false) on an interface requires factoryMethod "
                    + "to be set - build() needs an explicit factory to call when no <Name>Impl is generated.",
                target);
            return;
        }

        List<FieldSpec> fields = collectFieldsFromInterface(target, config);
        String packageName = packageOf(target);

        String implName = target.getSimpleName().toString() + "Impl";
        if (config.generateImpl()) {
            // Impl class first
            String implSource = InterfaceImplEmitter.emit(target, packageName, implName, fields);
            String implQn = packageName.isEmpty() ? implName : packageName + "." + implName;
            JavaFileObject implFile = processingEnv.getFiler().createSourceFile(implQn, target);
            try (Writer w = implFile.openWriter()) { w.write(implSource); }
        }

        // Builder second - build() returns the interface, populated via Impl
        // constructor or (when generateImpl=false) the configured factoryMethod.
        BuilderEmitter emitter = new BuilderEmitter(target, packageName, config, fields, messager, false, BuilderEmitter.TargetKind.INTERFACE);
        if (config.generateImpl()) emitter.setInterfaceImplName(implName);
        String source = emitter.emit();
        String qualifiedName = packageName.isEmpty() ? emitter.builderClassName() : packageName + "." + emitter.builderClassName();
        JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, target);
        try (Writer w = file.openWriter()) { w.write(source); }
    }

    private List<FieldSpec> collectFieldsFromInterface(TypeElement target, BuilderConfig config) {
        List<FieldSpec> out = new ArrayList<>();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            if (enclosed.getModifiers().contains(Modifier.STATIC)) continue;
            if (enclosed.getModifiers().contains(Modifier.DEFAULT)) continue;
            javax.lang.model.element.ExecutableElement method = (javax.lang.model.element.ExecutableElement) enclosed;
            if (!method.getParameters().isEmpty()) continue; // only zero-arg abstract accessors become builder fields
            if (method.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID) continue;
            String name = method.getSimpleName().toString();
            if (config.excludeSet().contains(name)) continue;
            FieldSpec spec = FieldSpec.fromInterfaceAccessor(method, lookup);
            if (spec.ignored) continue;
            out.add(spec);
        }
        return out;
    }

    private BuilderConfig extractConfig(TypeElement target) {
        String builderName = lookup.stringAttr(target, ANNOTATION_FQN, "builderName", "Builder");
        String builderMethodName = lookup.stringAttr(target, ANNOTATION_FQN, "builderMethodName", "builder");
        String buildMethodName = lookup.stringAttr(target, ANNOTATION_FQN, "buildMethodName", "build");
        String fromMethodName = lookup.stringAttr(target, ANNOTATION_FQN, "fromMethodName", "from");
        String toBuilderMethodName = lookup.stringAttr(target, ANNOTATION_FQN, "toBuilderMethodName", "mutate");
        String methodPrefix = lookup.stringAttr(target, ANNOTATION_FQN, "methodPrefix", "with");
        AccessLevel access = parseAccess(lookup.stringAttr(target, ANNOTATION_FQN, "access", "PUBLIC"));
        boolean generateBuilder = lookup.booleanAttr(target, ANNOTATION_FQN, "generateBuilder", true);
        boolean generateFrom = lookup.booleanAttr(target, ANNOTATION_FQN, "generateFrom", true);
        boolean generateMutate = lookup.booleanAttr(target, ANNOTATION_FQN, "generateMutate", true);
        boolean generateCopyConstructor = lookup.booleanAttr(target, ANNOTATION_FQN, "generateCopyConstructor", true);
        boolean generateImpl = lookup.booleanAttr(target, ANNOTATION_FQN, "generateImpl", true);
        boolean validate = lookup.booleanAttr(target, ANNOTATION_FQN, "validate", true);
        boolean emitContracts = lookup.booleanAttr(target, ANNOTATION_FQN, "emitContracts", true);
        String factoryMethod = lookup.stringAttr(target, ANNOTATION_FQN, "factoryMethod", "");
        Set<String> excludeSet = new HashSet<>(Arrays.asList(lookup.stringArrayAttr(target, ANNOTATION_FQN, "exclude")));
        return new BuilderConfig(
            builderName, builderMethodName, buildMethodName, fromMethodName, toBuilderMethodName,
            methodPrefix, access, generateBuilder, generateFrom, generateMutate,
            generateCopyConstructor, generateImpl, validate, emitContracts, factoryMethod, excludeSet
        );
    }

    private List<FieldSpec> collectFields(TypeElement target, BuilderConfig config) {
        List<FieldSpec> out = new ArrayList<>();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            if (enclosed.getModifiers().contains(Modifier.STATIC)) continue;
            if (enclosed.getModifiers().contains(Modifier.TRANSIENT)) continue;
            String name = enclosed.getSimpleName().toString();
            if (config.excludeSet().contains(name)) continue;
            FieldSpec spec = FieldSpec.from((VariableElement) enclosed, lookup, introspector);
            if (spec.ignored) continue;
            out.add(spec);
        }
        return out;
    }

    private static String packageOf(TypeElement target) {
        Element cur = target.getEnclosingElement();
        while (cur != null && !(cur instanceof PackageElement)) cur = cur.getEnclosingElement();
        return cur == null ? "" : ((PackageElement) cur).getQualifiedName().toString();
    }

    private static AccessLevel parseAccess(String raw) {
        try {
            return AccessLevel.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return AccessLevel.PUBLIC;
        }
    }

}
