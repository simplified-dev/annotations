package dev.sbs.classbuilder.apt;

import dev.sbs.annotation.AccessLevel;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
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
import java.util.Set;

/**
 * Annotation processor that generates a sibling {@code <TypeName>Builder.java}
 * for each class carrying {@code @ClassBuilder}. Records, interfaces, and
 * abstract-class targets are reserved for a later phase.
 */
@SupportedAnnotationTypes("dev.sbs.annotation.ClassBuilder")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class ClassBuilderProcessor extends AbstractProcessor {

    private static final String ANNOTATION_FQN = "dev.sbs.annotation.ClassBuilder";

    private final AnnotationLookup lookup = new AnnotationLookup();

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

    private void processClass(TypeElement target, Messager messager) throws IOException {
        BuilderEmitter.BuilderConfig config = extractConfig(target);
        List<FieldSpec> fields = collectFields(target, config);
        String packageName = packageOf(target);
        boolean isRecord = target.getKind() == ElementKind.RECORD;

        BuilderEmitter emitter = new BuilderEmitter(target, packageName, config, fields, messager, isRecord, BuilderEmitter.TargetKind.CLASS_OR_RECORD);
        String source = emitter.emit();

        String qualifiedName = packageName.isEmpty()
            ? emitter.builderClassName()
            : packageName + "." + emitter.builderClassName();

        JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, target);
        try (Writer w = file.openWriter()) {
            w.write(source);
        }
    }

    private void processInterface(TypeElement target, Messager messager) throws IOException {
        BuilderEmitter.BuilderConfig config = extractConfig(target);
        List<FieldSpec> fields = collectFieldsFromInterface(target, config);
        String packageName = packageOf(target);

        // Impl class first
        String implName = target.getSimpleName().toString() + "Impl";
        String implSource = InterfaceImplEmitter.emit(target, packageName, implName, fields);
        String implQn = packageName.isEmpty() ? implName : packageName + "." + implName;
        JavaFileObject implFile = processingEnv.getFiler().createSourceFile(implQn, target);
        try (Writer w = implFile.openWriter()) { w.write(implSource); }

        // Builder second - build() returns the interface, new impl instance
        BuilderEmitter emitter = new BuilderEmitter(target, packageName, config, fields, messager, false, BuilderEmitter.TargetKind.INTERFACE);
        emitter.setInterfaceImplName(implName);
        String source = emitter.emit();
        String qualifiedName = packageName.isEmpty() ? emitter.builderClassName() : packageName + "." + emitter.builderClassName();
        JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, target);
        try (Writer w = file.openWriter()) { w.write(source); }
    }

    private List<FieldSpec> collectFieldsFromInterface(TypeElement target, BuilderEmitter.BuilderConfig config) {
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

    private BuilderEmitter.BuilderConfig extractConfig(TypeElement target) {
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
        boolean validate = lookup.booleanAttr(target, ANNOTATION_FQN, "validate", true);
        boolean emitContracts = lookup.booleanAttr(target, ANNOTATION_FQN, "emitContracts", true);
        String factoryMethod = lookup.stringAttr(target, ANNOTATION_FQN, "factoryMethod", "");
        Set<String> excludeSet = new HashSet<>(Arrays.asList(lookup.stringArrayAttr(target, ANNOTATION_FQN, "exclude")));
        return new BuilderEmitter.BuilderConfig(
            builderName, builderMethodName, buildMethodName, fromMethodName, toBuilderMethodName,
            methodPrefix, access, generateBuilder, generateFrom, generateMutate, validate,
            emitContracts, factoryMethod, excludeSet
        );
    }

    private List<FieldSpec> collectFields(TypeElement target, BuilderEmitter.BuilderConfig config) {
        List<FieldSpec> out = new ArrayList<>();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            if (enclosed.getModifiers().contains(Modifier.STATIC)) continue;
            if (enclosed.getModifiers().contains(Modifier.TRANSIENT)) continue;
            String name = enclosed.getSimpleName().toString();
            if (config.excludeSet().contains(name)) continue;
            FieldSpec spec = FieldSpec.from((VariableElement) enclosed, lookup);
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
