package dev.simplified.classbuilder.apt;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.accessor.mutate.AccessorMutator;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.annotations.Setter;
import dev.simplified.annotations.SetterNames;
import dev.simplified.args.mutate.ArgsConstructorMutator;
import dev.simplified.classbuilder.mutate.BuilderMutator;
import dev.simplified.classbuilder.mutate.InterfaceBootstrapMutator;
import dev.simplified.cleanup.mutate.CleanupBlockMutator;
import dev.simplified.equality.apt.EqualityConfig;
import dev.simplified.equality.mutate.EqualityMutator;
import dev.simplified.lazy.mutate.LazyFieldMutator;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.apt.MemberSelector;
import dev.simplified.shared.apt.MemberSpec;
import dev.simplified.shared.apt.SourceIntrospector;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.LazyOwnership;
import dev.simplified.shared.javac.compat.JavacAccessFactory;
import dev.simplified.silentthrows.mutate.SilentThrowsMutator;
import dev.simplified.tostring.apt.ToStringConfig;
import dev.simplified.tostring.mutate.ToStringMutator;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
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
    "dev.simplified.annotations.Lazy",
    "dev.simplified.annotations.Getter",
    "dev.simplified.annotations.Setter",
    "dev.simplified.annotations.AllArgsConstructor",
    "dev.simplified.annotations.RequiredArgsConstructor",
    "dev.simplified.annotations.NoArgsConstructor",
    "dev.simplified.annotations.BuilderArgsConstructor",
    "dev.simplified.annotations.EqualsAndHashCode",
    "dev.simplified.annotations.ToString"
})
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
    private static final String GETTER_FQN = "dev.simplified.annotations.Getter";
    private static final String SETTER_FQN = "dev.simplified.annotations.Setter";
    private static final String CLEANUP_FQN = "dev.simplified.annotations.Cleanup";
    private static final String SILENT_THROWS_FQN = "dev.simplified.annotations.SilentThrows";

    /**
     * The constructor annotations, dispatched here rather than from a processor
     * of their own. Ordering against {@code @Lazy} and the builder pass has to
     * be a guarantee, and processor order within a round is unspecified.
     */
    private static final String[] ARGS_FQNS = {
        "dev.simplified.annotations.AllArgsConstructor",
        "dev.simplified.annotations.RequiredArgsConstructor",
        "dev.simplified.annotations.NoArgsConstructor",
        "dev.simplified.annotations.BuilderArgsConstructor"
    };

    private final AnnotationLookup lookup = new AnnotationLookup();
    private SourceIntrospector introspector;
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

        // Constructors first, for every target in the round. Two passes read
        // what this one writes: LazyFieldMutator retypes a @Lazy parameter in
        // whatever constructor it finds, and the builder pass declines to
        // synthesise its own when a written annotation already produced that
        // signature. Neither can be satisfied afterwards.
        processConstructors(roundEnv, messager);

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

        // Accessors last, so the collision snapshot sees every member the
        // builder and @Lazy passes have already injected. Sharing this
        // processor rather than registering a second one is what makes that
        // ordering a guarantee - processor order within a round is otherwise
        // unspecified, and a @Getter racing @Lazy emits a duplicate method.
        processAccessors(roundEnv, messager);

        // The whole-object members after the accessors, for two reasons that
        // point the same way: useAccessors has to see every accessor the
        // accessor pass minted, and the collision snapshot has to be able to
        // tell an author-written equals from one this pipeline produced.
        processEquality(roundEnv, messager);
        processToString(roundEnv, messager);

        // The two body rewrites last of all, after every member injection - a
        // synthesised body carries neither annotation, so running last means
        // neither pass has to re-scan a tree that later grows members. Each has
        // its own processor owning every other tree; these dispatches exist for
        // the trees they have to stand back from, where the @Lazy pass above
        // must have finished first. Their order against each other is free.
        processCleanup(roundEnv, messager);
        processSilentThrows(roundEnv, messager);
        return false;
    }

    /**
     * Runs {@link SilentThrowsMutator} over the round's root elements that
     * declare a {@code @Lazy} field, nested types included.
     *
     * <p>{@code SilentThrowsProcessor} skips exactly those compilation units.
     * The wrap re-parents a member's whole body one level down, so every
     * {@code this.foo = foo} in a constructor sits inside the injected
     * {@code try} - and {@link LazyFieldMutator} walks a body's statements flat,
     * so it would rewrite none of them and javac would then reject the
     * {@code Supplier} assignment against the retyped field.
     *
     * <p>The walk descends into nested types because the mutator does not: each
     * class is its own target with its own rethrow helper.
     *
     * @param roundEnv the round being processed
     * @param messager sink for diagnostics
     */
    private void processSilentThrows(RoundEnvironment roundEnv, Messager messager) {
        if (javacBridge.isEmpty()) return;
        if (processingEnv.getElementUtils().getTypeElement(SILENT_THROWS_FQN) == null) return;
        for (Element root : roundEnv.getRootElements()) {
            if (!(root instanceof TypeElement type)) continue;
            JCClassDecl tree = javacBridge.get().treeOf(type);
            if (tree == null) continue;
            if (!LazyOwnership.declaresLazyField(tree, javacBridge.get().unitOf(type))) continue;
            try {
                mutateSilentThrows(tree);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to process @SilentThrows in " + type + ": " + e.getMessage(), type);
            }
        }
    }

    /** Applies the mutator to the declaration and to every type nested in it. */
    private void mutateSilentThrows(JCClassDecl target) {
        new SilentThrowsMutator(javacBridge.get()).mutate(target);
        for (JCTree def : target.defs) {
            if (def instanceof JCClassDecl nested) mutateSilentThrows(nested);
        }
    }

    /**
     * Runs {@link CleanupBlockMutator} over the round's root elements that
     * declare a {@code @Lazy} field.
     *
     * <p>{@code CleanupProcessor} skips exactly those trees. The two passes are
     * order-free against each other everywhere else, but relocating a
     * constructor's tail into a {@code try} hides the {@code this.foo = foo}
     * assignments {@link LazyFieldMutator} rewrites, which walks a body's
     * statements flat. A type with a {@code @Lazy} field is a type this
     * processor is invoked for, so the deferral always has somewhere to land.
     *
     * @param roundEnv the round being processed
     * @param messager sink for diagnostics
     */
    private void processCleanup(RoundEnvironment roundEnv, Messager messager) {
        if (javacBridge.isEmpty()) return;
        if (processingEnv.getElementUtils().getTypeElement(CLEANUP_FQN) == null) return;
        for (Element root : roundEnv.getRootElements()) {
            if (!(root instanceof TypeElement type)) continue;
            JCClassDecl tree = javacBridge.get().treeOf(type);
            if (tree == null) continue;
            if (!CleanupBlockMutator.declaresLazyField(tree, javacBridge.get().unitOf(type))) continue;
            try {
                new CleanupBlockMutator(javacBridge.get(), messager, type).mutate(tree);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to process @Cleanup in " + type + ": " + e.getMessage(), type);
            }
        }
    }

    /**
     * Runs {@link ArgsConstructorMutator} over every type carrying one of the
     * four constructor annotations.
     *
     * @param roundEnv the round being processed
     * @param messager sink for diagnostics
     */
    private void processConstructors(RoundEnvironment roundEnv, Messager messager) {
        Set<TypeElement> targets = new java.util.LinkedHashSet<>();
        for (String fqn : ARGS_FQNS) {
            TypeElement annotation = processingEnv.getElementUtils().getTypeElement(fqn);
            if (annotation == null) continue;
            for (Element annotated : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (annotated instanceof TypeElement type) targets.add(type);
            }
        }
        if (targets.isEmpty()) return;

        for (TypeElement target : targets) {
            if (javacBridge.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "The constructor annotations require javac for AST mutation - current "
                        + "environment is not a JavacProcessingEnvironment. Run your build under "
                        + "OpenJDK javac (no ecj).",
                    target);
                return;
            }
            try {
                boolean hasClassBuilder = lookup.hasAnnotation(target, ANNOTATION_FQN);
                Set<String> exclude = hasClassBuilder
                    ? new HashSet<>(Arrays.asList(
                        lookup.stringArrayAttr(target, ANNOTATION_FQN, "exclude")))
                    : Set.of();
                if (!new ArgsConstructorMutator(javacBridge.get(), messager)
                    .mutate(target, hasClassBuilder, exclude)) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "A constructor annotation could not resolve a source tree for " + target
                            + "; mutation requires the annotated element to have a source "
                            + "declaration.",
                        target);
                }
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to synthesise a constructor on " + target + ": " + e.getMessage(),
                    target);
            }
        }
    }

    /**
     * Runs {@link AccessorMutator} over every type that carries {@code @Getter}
     * or {@code @Setter}, or declares a field that does.
     */
    private void processAccessors(RoundEnvironment roundEnv, Messager messager) {
        Set<TypeElement> targets = new java.util.LinkedHashSet<>();
        for (String fqn : new String[]{GETTER_FQN, SETTER_FQN}) {
            TypeElement annotation = processingEnv.getElementUtils().getTypeElement(fqn);
            if (annotation == null) continue;
            boolean getter = GETTER_FQN.equals(fqn);
            for (Element annotated : roundEnv.getElementsAnnotatedWith(annotation)) {
                validateAccessorName(annotated, getter, messager);
                Element owner = annotated instanceof TypeElement
                    ? annotated
                    : annotated.getEnclosingElement();
                if (owner instanceof TypeElement type) targets.add(type);
            }
        }
        if (targets.isEmpty()) return;

        for (TypeElement target : targets) {
            ElementKind kind = target.getKind();
            if (kind != ElementKind.CLASS && kind != ElementKind.ENUM) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Getter / @Setter are only supported on classes and enums - "
                        + target.getSimpleName() + " is a " + kind.toString().toLowerCase()
                        + (kind == ElementKind.RECORD
                            ? ", whose components are accessors already" : ""),
                    target);
                continue;
            }
            if (javacBridge.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Getter / @Setter require javac for AST mutation - current environment is "
                        + "not a JavacProcessingEnvironment. Run your build under OpenJDK javac "
                        + "(no ecj).",
                    target);
                return;
            }
            try {
                if (!new AccessorMutator(javacBridge.get(), messager).mutate(target)) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "@Getter / @Setter could not resolve a source tree for " + target
                            + "; mutation requires the annotated element to have a source "
                            + "declaration.",
                        target);
                }
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to process accessors on " + target + ": " + e.getMessage(), target);
            }
        }
    }

    /**
     * Runs {@link EqualityMutator} over every class or record carrying
     * {@code @EqualsAndHashCode}.
     */
    private void processEquality(RoundEnvironment roundEnv, Messager messager) {
        for (TypeElement target : wholeObjectTargets(roundEnv, EqualityConfig.FQN,
            EqualityConfig.LABEL, messager)) {
            if (javacBridge.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR, requiresJavac(EqualityConfig.LABEL),
                    target);
                return;
            }
            try {
                EqualityConfig config = EqualityConfig.from(target);
                List<MemberSpec> members =
                    MemberSelector.select(target, config.policy(), lookup, messager);
                boolean mutated = new EqualityMutator(javacBridge.get(),
                    processingEnv.getTypeUtils(), lookup, messager)
                    .mutate(target, config, members);
                if (!mutated) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        EqualityConfig.LABEL + " could not resolve a source tree for " + target
                            + "; mutation requires the annotated element to have a source "
                            + "declaration.",
                        target);
                }
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate equals/hashCode on " + target + ": " + e.getMessage(),
                    target);
            }
        }
    }

    /** Runs {@link ToStringMutator} over every class or record carrying {@code @ToString}. */
    private void processToString(RoundEnvironment roundEnv, Messager messager) {
        for (TypeElement target : wholeObjectTargets(roundEnv, ToStringConfig.FQN,
            ToStringConfig.LABEL, messager)) {
            if (javacBridge.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR, requiresJavac(ToStringConfig.LABEL),
                    target);
                return;
            }
            try {
                ToStringConfig config = ToStringConfig.from(target);
                List<MemberSpec> members =
                    MemberSelector.select(target, config.policy(), lookup, messager);
                boolean mutated = new ToStringMutator(javacBridge.get(),
                    processingEnv.getTypeUtils(), lookup, messager)
                    .mutate(target, config, members);
                if (!mutated) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        ToStringConfig.LABEL + " could not resolve a source tree for " + target
                            + "; mutation requires the annotated element to have a source "
                            + "declaration.",
                        target);
                }
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate toString on " + target + ": " + e.getMessage(), target);
            }
        }
    }

    /**
     * The classes and records one whole-object annotation reaches, rejecting
     * every other target kind on the way.
     *
     * <p>An interface carrying {@code @ClassBuilder} is deliberately silent
     * here: it has no in-source mutation surface, and the concrete class the
     * builder emits for it is where the members land instead.
     */
    private List<TypeElement> wholeObjectTargets(RoundEnvironment roundEnv, String annotationFqn,
                                                 String label, Messager messager) {
        TypeElement annotation = processingEnv.getElementUtils().getTypeElement(annotationFqn);
        if (annotation == null) return List.of();
        List<TypeElement> targets = new ArrayList<>();
        for (Element annotated : roundEnv.getElementsAnnotatedWith(annotation)) {
            if (!(annotated instanceof TypeElement target)) continue;
            ElementKind kind = target.getKind();
            if (kind == ElementKind.CLASS || kind == ElementKind.RECORD) {
                targets.add(target);
                continue;
            }
            if (kind == ElementKind.INTERFACE) {
                if (lookup.hasAnnotation(target, ANNOTATION_FQN)) continue;
                messager.printMessage(Diagnostic.Kind.ERROR,
                    label + " on the interface " + target.getSimpleName() + ", which declares no "
                        + "state to compare and no body to generate into. Write it on the "
                        + "implementing type, or add @ClassBuilder so an implementation exists "
                        + "for it to reach",
                    target);
                continue;
            }
            messager.printMessage(Diagnostic.Kind.ERROR,
                label + " on the " + kind.toString().toLowerCase() + " " + target.getSimpleName()
                    + (kind == ElementKind.ENUM
                        ? " - an enum constant is already unique, and Enum's own members are "
                            + "final or name it"
                        : " - only classes and records carry the instance state this reads"),
                target);
        }
        return targets;
    }

    private static String requiresJavac(String label) {
        return label + " requires javac for AST mutation - current environment is not a "
            + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).";
    }

    /**
     * Reports a {@code name} pattern on {@code @Getter} or {@code @Setter} that
     * cannot expand into a distinct accessor.
     *
     * <p>Checked on a type-level annotation as well as a field-level one, and
     * the placeholder is mandatory in both. It matters more on the type, where a
     * pattern without it gives every field on the class the same accessor name;
     * on a field it only collides with whatever else claims that name.
     *
     * <p>The suppression sentinel is rejected rather than honoured. These
     * annotations suppress through {@link AccessLevel#NONE}, so nothing on this
     * path reads it as an opt-out and it would be minted verbatim as the method
     * name.
     *
     * @param annotated the type or field carrying the annotation
     * @param getter whether the annotation is {@code @Getter} rather than
     *        {@code @Setter}
     * @param messager the reporting sink
     */
    private void validateAccessorName(Element annotated, boolean getter, Messager messager) {
        Getter get = getter ? annotated.getAnnotation(Getter.class) : null;
        Setter set = getter ? null : annotated.getAnnotation(Setter.class);
        if (get == null && set == null) return;
        String written = getter ? get.name() : set.name();
        // Empty is INHERIT, where the style supplies the pattern.
        if (written.isEmpty()) return;

        String annotation = getter ? "@Getter" : "@Setter";
        if (SetterNames.NONE.equals(written)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                annotation + " naming pattern for 'name' cannot be '" + SetterNames.NONE
                    + "' - write AccessLevel.NONE to generate nothing", annotated);
            return;
        }
        String error = NamePattern.patternError(written, true);
        if (error != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                annotation + " naming pattern for 'name' " + error, annotated);
        }
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
            // Impl class first. Its whole-object members are configured here
            // rather than by a later round, because the class it would mutate
            // already declares all three - see ImplPlan.
            ImplPlan plan = ImplPlan.resolve(target, implName, fields, lookup, messager);
            String implSource = InterfaceImplEmitter.emit(
                target, packageName, implName, fields, config.emitGenerated(), plan);
            String implQn = packageName.isEmpty() ? implName : packageName + "." + implName;
            JavaFileObject implFile = processingEnv.getFiler().createSourceFile(implQn, target);
            try (Writer w = implFile.openWriter()) { w.write(implSource); }
        }
        // A set factoryMethod diverts build() to the author's factory whether or
        // not the Impl was emitted, so the whole-object annotations land on a
        // class no caller holds either way. The Impl is still emitted in that
        // case - the factory may well construct it itself.
        if (!config.generateImpl()) {
            ImplPlan.reportNoImpl(target, "sets @ClassBuilder(generateImpl = false)",
                lookup, messager);
        } else if (!config.factoryMethod().isEmpty()) {
            ImplPlan.reportNoImpl(target,
                "sets @ClassBuilder(factoryMethod = \"" + config.factoryMethod()
                    + "\"), so build() returns what that factory produces rather than " + implName,
                lookup, messager);
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
        boolean emitGenerated = lookup.booleanAttr(target, ANNOTATION_FQN, "emitGenerated", true);
        String factoryMethod = lookup.stringAttr(target, ANNOTATION_FQN, "factoryMethod", "");
        Set<String> excludeSet = new HashSet<>(Arrays.asList(lookup.stringArrayAttr(target, ANNOTATION_FQN, "exclude")));
        return new BuilderConfig(
            extractBuilderNames(target, style), extractSetterNames(target, style),
            access, constructorAccess, retainInit,
            generateCopyConstructor, generateImpl, validate, emitContracts, emitGenerated,
            factoryMethod, excludeSet
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
