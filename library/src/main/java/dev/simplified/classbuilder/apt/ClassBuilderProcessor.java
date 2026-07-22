package dev.simplified.classbuilder.apt;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.mutate.BuilderMutator;
import dev.simplified.classbuilder.mutate.InterfaceBootstrapMutator;
import dev.simplified.lazy.mutate.LazyFieldMutator;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.apt.SourceIntrospector;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.compat.JavacAccessFactory;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
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
@SupportedAnnotationTypes({
    "dev.simplified.annotations.ClassBuilder",
    "dev.simplified.annotations.Lazy"
})
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

    private static final String ANNOTATION_FQN = "dev.simplified.annotations.ClassBuilder";
    private static final String LAZY_FQN = "dev.simplified.annotations.Lazy";

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
        Set<TypeElement> classBuilderTargets = new java.util.LinkedHashSet<>();
        if (annotationElement != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotationElement)) {
                ElementKind kind = element.getKind();
                if (kind != ElementKind.CLASS && kind != ElementKind.RECORD && kind != ElementKind.INTERFACE) {
                    messager.printMessage(Diagnostic.Kind.WARNING,
                        "@ClassBuilder on " + kind + " targets is not yet supported - skipping",
                        element
                    );
                    continue;
                }
                TypeElement type = (TypeElement) element;
                classBuilderTargets.add(type);
                try {
                    if (kind == ElementKind.INTERFACE) {
                        processInterface(type, messager);
                    } else {
                        processClass(type, messager);
                    }
                } catch (Exception e) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "Failed to generate builder for " + element + ": " + e.getMessage(),
                        element
                    );
                }
            }
        }

        // Standalone @Lazy: classes with @Lazy fields but no @ClassBuilder.
        // Dispatch directly to LazyFieldMutator so the field-type rewrite +
        // getter synthesis still happens.
        TypeElement lazyAnnotation = processingEnv.getElementUtils().getTypeElement(LAZY_FQN);
        if (lazyAnnotation != null) {
            Set<TypeElement> standaloneLazyTargets = new java.util.LinkedHashSet<>();
            for (Element annotated : roundEnv.getElementsAnnotatedWith(lazyAnnotation)) {
                if (annotated.getKind() != ElementKind.FIELD) continue;
                Element enclosing = annotated.getEnclosingElement();
                if (!(enclosing instanceof TypeElement enclosingType)) continue;
                if (classBuilderTargets.contains(enclosingType)) continue;
                if (enclosingType.getKind() == ElementKind.RECORD) continue;
                if (enclosingType.getKind() != ElementKind.CLASS) continue;
                standaloneLazyTargets.add(enclosingType);
            }
            for (TypeElement type : standaloneLazyTargets) {
                try {
                    processStandaloneLazy(type, messager);
                } catch (Exception e) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "Failed to process @Lazy on " + type + ": " + e.getMessage(),
                        type);
                }
            }
        }
        return false;
    }

    private TypeElement lookupAnnotationElement() {
        return processingEnv.getElementUtils().getTypeElement(ANNOTATION_FQN);
    }

    private void processClass(TypeElement target, Messager messager) {
        BuilderConfig config = extractConfig(target);
        validateNaming(target, config, messager);
        List<FieldSpec> fields = collectFields(target, config);

        if (javacBridge.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder requires javac for AST mutation - current environment is not a "
                    + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).",
                target);
            return;
        }
        // The @Lazy pass gets every field, not the builder-visible subset:
        // rewriting a field's storage and synthesising its getter is
        // independent of whether the builder exposes it, so a @BuilderIgnore'd
        // or excluded field must still be processed. Static fields are included
        // too, so @Lazy can report them rather than silently skipping.
        BuilderMutator mutator = new BuilderMutator(javacBridge.get(), messager);
        if (!mutator.mutate(target, config, fields, collectAllFields(target))) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder could not resolve a source tree for " + target
                    + "; mutation requires the annotated element to have a source declaration.",
                target);
        }
    }

    /**
     * Processes a class that has {@code @Lazy} fields but no
     * {@code @ClassBuilder}. Runs {@link LazyFieldMutator} against the
     * target's AST to rewrite field storage + synthesise getters; nothing
     * else fires (no nested builder, no bootstraps).
     */
    private void processStandaloneLazy(TypeElement target, Messager messager) {
        if (javacBridge.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy requires javac for AST mutation - current environment is not a "
                    + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).",
                target);
            return;
        }
        List<FieldSpec> fields = collectAllFields(target);
        var classDecl = javacBridge.get().treeOf(target);
        if (classDecl == null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy could not resolve a source tree for " + target
                    + "; mutation requires the annotated element to have a source declaration.",
                target);
            return;
        }
        javacBridge.get().treeMaker().at(classDecl.pos);
        LazyFieldMutator lazyMutator = new LazyFieldMutator(
            javacBridge.get(), target, classDecl, fields, false, messager);
        lazyMutator.mutate();
    }

    /**
     * Collects every field from the target (including static) without
     * honouring {@code @ClassBuilder.exclude} or {@code @BuilderIgnore}.
     * Used by {@link #processStandaloneLazy} so {@code @Lazy} fields are
     * visible regardless of any other builder-targeted filters - including
     * static ones, which the {@link LazyFieldMutator} needs to see in order
     * to emit the "not supported" error rather than silently dropping them.
     */
    private List<FieldSpec> collectAllFields(TypeElement target) {
        List<FieldSpec> out = new ArrayList<>();
        Messager messager = processingEnv.getMessager();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            rejectUnsupportedLazyCompanions((VariableElement) enclosed, messager);
            // Standalone @Lazy path - no @ClassBuilder, so no retainInit policy.
            out.add(FieldSpec.from((VariableElement) enclosed, lookup, introspector,
                processingEnv.getTypeUtils(), false));
        }
        return out;
    }

    private void processInterface(TypeElement target, Messager messager) throws IOException {
        BuilderConfig config = extractConfig(target);
        validateNaming(target, config, messager);

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

        // Bootstrap methods onto the interface itself, so an interface target is
        // entered the same way a class is - Repo.builder() rather than
        // new RepoBuilder<>(). The builder stays a sibling class; only the entry
        // points move onto the interface body, which static and default methods
        // make possible without shifting interfaces to the mutation path.
        if (javacBridge.isPresent()) {
            JCClassDecl targetTree = javacBridge.get().treeOf(target);
            if (targetTree != null) {
                new InterfaceBootstrapMutator(javacBridge.get(), messager, target, targetTree,
                    config, emitter.builderClassName()).appendAll();
            }
        }
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
            FieldSpec spec = FieldSpec.fromInterfaceAccessor(method, lookup, processingEnv.getTypeUtils());
            if (spec.ignored) continue;
            out.add(spec);
        }
        return out;
    }

    private BuilderConfig extractConfig(TypeElement target) {
        NamingStyle style = parseStyle(lookup.stringAttr(target, ANNOTATION_FQN, "style", "SIMPLIFIED"));
        AccessLevel access = parseAccess(lookup.stringAttr(target, ANNOTATION_FQN, "access", "PUBLIC"));
        AccessLevel constructorAccess =
            parseAccess(lookup.stringAttr(target, ANNOTATION_FQN, "constructorAccess", "PACKAGE"));
        boolean retainInit = lookup.booleanAttr(target, ANNOTATION_FQN, "retainInit", true);
        boolean generateCopyConstructor = lookup.booleanAttr(target, ANNOTATION_FQN, "generateCopyConstructor", true);
        boolean generateImpl = lookup.booleanAttr(target, ANNOTATION_FQN, "generateImpl", true);
        boolean validate = lookup.booleanAttr(target, ANNOTATION_FQN, "validate", true);
        boolean emitContracts = lookup.booleanAttr(target, ANNOTATION_FQN, "emitContracts", true);
        String factoryMethod = lookup.stringAttr(target, ANNOTATION_FQN, "factoryMethod", "");
        Set<String> excludeSet = new HashSet<>(Arrays.asList(lookup.stringArrayAttr(target, ANNOTATION_FQN, "exclude")));
        return new BuilderConfig(
            extractBuilderNames(target, style), extractSetterNames(target, style),
            access, constructorAccess, retainInit,
            generateCopyConstructor, generateImpl, validate, emitContracts, factoryMethod, excludeSet
        );
    }

    /**
     * Companion annotations {@code @Lazy} documents as unsupported. Each assumes
     * direct {@code T} storage, which {@code @Lazy} replaces with
     * {@code Lazy<T>}, so the pairing is not merely redundant - it misbehaves
     * silently. {@code @BuildFlag}, for instance, degrades to a no-op because
     * the validator sees the non-null wrapper rather than the value.
     *
     * <p>{@code @BuilderDefault} and {@code @BuilderIgnore} are deliberately
     * absent: both govern how the builder treats a field rather than how it is
     * stored, so neither actually conflicts with the storage rewrite.
     */
    private static final String[][] LAZY_INCOMPATIBLE = {
        {"dev.simplified.annotations.Collector", "Collector"},
        {"dev.simplified.annotations.Negate", "Negate"},
        {"dev.simplified.annotations.Formattable", "Formattable"},
        {"dev.simplified.annotations.BuildFlag", "BuildFlag"},
        {"dev.simplified.annotations.ObtainVia", "ObtainVia"},
    };

    /**
     * Rejects {@code @Lazy} combined with a companion the annotation documents
     * as unsupported. Runs before any builder-targeted filtering so an ignored
     * field is still reported rather than silently dropped.
     *
     * @param field the field to check
     * @param messager sink for the diagnostic
     */
    private void rejectUnsupportedLazyCompanions(VariableElement field, Messager messager) {
        if (!lookup.hasAnnotation(field, LAZY_FQN)) return;
        for (String[] companion : LAZY_INCOMPATIBLE) {
            if (!lookup.hasAnnotation(field, companion[0])) continue;
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@Lazy cannot be combined with @" + companion[1]
                    + " - the companion assumes direct field storage, which @Lazy replaces with Lazy<T>",
                field
            );
        }
    }

    private List<FieldSpec> collectFields(TypeElement target, BuilderConfig config) {
        List<FieldSpec> out = new ArrayList<>();
        Messager messager = processingEnv.getMessager();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            rejectUnsupportedLazyCompanions((VariableElement) enclosed, messager);
            if (enclosed.getModifiers().contains(Modifier.STATIC)) continue;
            if (enclosed.getModifiers().contains(Modifier.TRANSIENT)) continue;
            String name = enclosed.getSimpleName().toString();
            if (config.excludeSet().contains(name)) continue;
            FieldSpec spec = FieldSpec.from((VariableElement) enclosed, lookup, introspector,
                processingEnv.getTypeUtils(), config.retainInit());
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

    private static NamingStyle parseStyle(String raw) {
        try {
            return NamingStyle.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return NamingStyle.SIMPLIFIED;
        }
    }

    /**
     * Reads the nested {@code setters} attribute. An unwritten attribute leaves
     * every role inheriting from the style, which is exactly what a mirror with
     * no entries produces, so the absent and empty cases need no distinction.
     */
    private SetterScheme extractSetterNames(TypeElement target, NamingStyle style) {
        AnnotationMirror setters =
            lookup.nestedAnnotationValue(lookup.findMirror(target, ANNOTATION_FQN), "setters");
        if (setters == null) return SetterScheme.of(style);
        return SetterScheme.resolve(style,
            lookup.stringAttr(setters, "set", null),
            lookup.stringAttr(setters, "flag", null),
            lookup.stringAttr(setters, "add", null),
            lookup.stringAttr(setters, "put", null),
            lookup.stringAttr(setters, "compute", null),
            lookup.stringAttr(setters, "clear", null));
    }

    /** Reads the nested {@code builder} attribute, on the same inherit-when-unwritten terms. */
    private BuilderScheme extractBuilderNames(TypeElement target, NamingStyle style) {
        String simpleName = target.getSimpleName().toString();
        AnnotationMirror names =
            lookup.nestedAnnotationValue(lookup.findMirror(target, ANNOTATION_FQN), "builder");
        if (names == null) return BuilderScheme.of(style, simpleName);
        return BuilderScheme.resolve(style, simpleName,
            lookup.stringAttr(names, "type", null),
            lookup.stringAttr(names, "builder", null),
            lookup.stringAttr(names, "build", null),
            lookup.stringAttr(names, "from", null),
            lookup.stringAttr(names, "toBuilder", null));
    }

    /**
     * Reports naming patterns that cannot produce a compilable member, at the
     * annotation rather than on generated code the author cannot see. A pattern
     * missing its placeholder would give every field the same method name, and a
     * suppressed {@code set} role would leave the field unassignable.
     *
     * <p>Cross-role name collisions are deliberately not checked: within one
     * field the roles that can share a name differ in arity or parameter type
     * ({@code animated()} against {@code animated(boolean)}, a varargs replace
     * against a single-element add), so the overlap is legal and sometimes
     * intended. A genuine duplicate is javac's own error to raise.
     */
    private void validateNaming(TypeElement target, BuilderConfig config, Messager messager) {
        SetterScheme setters = config.setters();
        String[][] roles = {
            {"set", setters.set()}, {"flag", setters.flag()}, {"add", setters.add()},
            {"put", setters.put()}, {"compute", setters.compute()}, {"clear", setters.clear()}
        };
        for (String[] role : roles) {
            String error = NamePattern.patternError(role[1], true);
            if (error != null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@SetterNames pattern for '" + role[0] + "' " + error, target);
            }
        }
        if (!setters.emitsSet()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@SetterNames cannot suppress the 'set' role - a field would then have no way to "
                    + "be assigned on the builder", target);
        }
        validateBuilderNames(target, messager);
    }

    /**
     * Checks the once-per-target names. The placeholder is optional here, every
     * default being a plain literal, so only malformed text and a suppressed
     * {@code type} or {@code build} are errors.
     */
    private void validateBuilderNames(TypeElement target, Messager messager) {
        AnnotationMirror names =
            lookup.nestedAnnotationValue(lookup.findMirror(target, ANNOTATION_FQN), "builder");
        if (names == null) return;
        for (String attr : new String[] {"type", "builder", "build", "from", "toBuilder"}) {
            String written = lookup.stringAttr(names, attr, null);
            if (written == null) continue;
            String error = NamePattern.patternError(written, false);
            if (error != null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@BuilderNames '" + attr + "' " + error, target);
            }
        }
        for (String attr : new String[] {"type", "build"}) {
            if (!NamePattern.emits(lookup.stringAttr(names, attr, ""))) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@BuilderNames cannot suppress '" + attr + "' - a builder with no class to "
                        + "name, or no way to finish, is not a builder", target);
            }
        }
    }

}
