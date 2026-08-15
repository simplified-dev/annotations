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
import dev.simplified.classbuilder.mutate.ExecutableBuilderMutator;
import dev.simplified.classbuilder.mutate.InterfaceBootstrapMutator;
import dev.simplified.cleanup.mutate.CleanupBlockMutator;
import dev.simplified.equality.apt.EqualityConfig;
import dev.simplified.equality.mutate.EqualityMutator;
import dev.simplified.lazy.mutate.LazyFieldMutator;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.apt.MemberSelector;
import dev.simplified.shared.apt.MemberSpec;
import dev.simplified.shared.apt.SourceIntrospector;
import dev.simplified.shared.apt.TypeNames;
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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
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
        List<ExecutableElement> executableTargets = new ArrayList<>();
        if (annotationElement != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotationElement)) {
                ElementKind kind = element.getKind();
                if (kind == ElementKind.CONSTRUCTOR || kind == ElementKind.METHOD) {
                    executableTargets.add((ExecutableElement) element);
                    continue;
                }
                if (kind != ElementKind.CLASS && kind != ElementKind.RECORD && kind != ElementKind.INTERFACE) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "@ClassBuilder on a " + kind.toString().toLowerCase()
                            + " has nothing to derive a builder from - write it on the type, on a "
                            + "constructor, or on a static factory method",
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
            processExecutables(executableTargets, classBuilderTargets, messager);
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
     * <p>Checked on a type-level annotation as well as a field-level one, but
     * the placeholder is mandatory only on the type. There it fans out over
     * every field, so a pattern without one gives them all the same accessor
     * name - which is a defect with no legitimate reading. On a single field the
     * pattern expands exactly once, so a placeholder-free literal is simply the
     * accessor's name, and that is the only way to spell an accessor that does
     * not contain its field's name at all.
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
        // Mandatory on a type, where the pattern fans out; optional on a field,
        // where it expands once and a literal is just the accessor's name.
        boolean fansOut = annotated instanceof TypeElement;
        String error = NamePattern.patternError(written, fansOut);
        if (error != null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                annotation + " naming pattern for 'name' " + error, annotated);
        }
    }

    private TypeElement lookupAnnotationElement() {
        return processingEnv.getElementUtils().getTypeElement(ANNOTATION_FQN);
    }

    /**
     * Runs {@link ExecutableBuilderMutator} over every annotated constructor and
     * static factory in the round, after rejecting the shapes that cannot carry
     * a builder.
     *
     * <p>Deferred to a pass of its own rather than handled inline with the type
     * targets, because two of the rejections are about a <em>pair</em> of
     * annotations: an enclosing type that is itself a target, and a second
     * annotated member beside the first. Each would have the builder class and
     * the entry point emitted twice onto one type, which javac reports as a
     * duplicate on generated code the author cannot see.
     *
     * @param targets the annotated executables, in round order
     * @param typeTargets the types annotated in this round
     * @param messager sink for diagnostics
     */
    private void processExecutables(List<ExecutableElement> targets,
                                    Set<TypeElement> typeTargets, Messager messager) {
        Set<TypeElement> claimed = new java.util.LinkedHashSet<>();
        for (ExecutableElement executable : targets) {
            if (!(executable.getEnclosingElement() instanceof TypeElement enclosing)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@ClassBuilder needs a type to nest the builder in, and this member is not "
                        + "declared directly in one",
                    executable);
                continue;
            }
            if (!validExecutableTarget(executable, enclosing, typeTargets, claimed, messager)) continue;
            claimed.add(enclosing);
            try {
                processExecutable(enclosing, executable, messager);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate builder for " + enclosing.getSimpleName() + "."
                        + executable.getSimpleName() + ": " + e.getMessage(),
                    executable
                );
            }
        }
    }

    /**
     * Reports every reason an annotated executable cannot produce a builder,
     * each at the declaration that causes it.
     *
     * @param executable the annotated member
     * @param enclosing the type it is declared in
     * @param typeTargets the types annotated in this round
     * @param claimed the types an earlier annotated member already took
     * @param messager sink for diagnostics
     * @return whether the member is usable
     */
    private boolean validExecutableTarget(ExecutableElement executable, TypeElement enclosing,
                                          Set<TypeElement> typeTargets, Set<TypeElement> claimed,
                                          Messager messager) {
        boolean method = executable.getKind() == ElementKind.METHOD;
        if (method && !executable.getModifiers().contains(Modifier.STATIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder on an instance method has no receiver to call it on - builder() is "
                    + "static, so the factory it builds through must be static too",
                executable);
            return false;
        }
        if (method && executable.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder on a void method has nothing for build() to return",
                executable);
            return false;
        }
        if (typeTargets.contains(enclosing) || lookup.hasAnnotation(enclosing, ANNOTATION_FQN)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder is on " + enclosing.getSimpleName() + " as well as on this member - "
                    + "one type carries one builder, so keep whichever set of slots is wanted and "
                    + "drop the other annotation",
                executable);
            return false;
        }
        if (claimed.contains(enclosing)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder is already on another member of " + enclosing.getSimpleName()
                    + " - one type carries one builder",
                executable);
            return false;
        }
        for (Element enclosed : enclosing.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            if (!lookup.hasAnnotation(enclosed, LAZY_FQN)) continue;
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder on a member of " + enclosing.getSimpleName() + ", whose field '"
                    + enclosed.getSimpleName() + "' is @Lazy - that rewrites the field's storage "
                    + "and every constructor parameter feeding it, so the slots this builder passes "
                    + "would no longer match. Move @ClassBuilder onto the type",
                executable);
            return false;
        }
        return true;
    }

    /**
     * Generates the builder for one annotated constructor or static factory.
     *
     * <p>The attributes that describe a field set have nothing to name here and
     * are reported rather than silently ignored: {@code exclude} names fields,
     * and every parameter is a slot the annotated member requires; and
     * {@code factoryMethod} redirects what {@code build()} calls, which the
     * annotated member already decides.
     */
    private void processExecutable(TypeElement enclosing, ExecutableElement executable,
                                   Messager messager) {
        BuilderConfig config = extractConfig(executable, enclosing.getSimpleName().toString());
        validateNaming(executable, config, messager);
        if (!config.excludeSet().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder(exclude) names fields, and this builder's slots are "
                    + executable.getSimpleName() + "'s parameters - every one of which it requires",
                executable);
        }
        if (!config.factoryMethod().isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder(factoryMethod) redirects what build() calls, and the annotated "
                    + "member is already what it calls",
                executable);
        }

        List<FieldSpec> slots = new ArrayList<>();
        for (VariableElement parameter : executable.getParameters()) {
            FieldSpec slot = FieldSpec.fromParameter(parameter, lookup,
                processingEnv.getTypeUtils(), config.setters());
            if (slot.seed) rejectSeedCompanions(parameter, messager);
            slots.add(slot);
        }
        validateSlotNaming(slots, config.setters(), executable, messager);
        validateAssignVia(enclosing, slots, executable, messager);

        if (javacBridge.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder requires javac for AST mutation - current environment is not a "
                    + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).",
                executable);
            return;
        }
        if (!new ExecutableBuilderMutator(javacBridge.get(), messager)
            .mutate(enclosing, executable, config, slots)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@ClassBuilder could not resolve a source tree for " + enclosing
                    + "; mutation requires the annotated element to have a source declaration.",
                executable);
        }
    }

    /**
     * Companion annotations a {@code @BuilderSeed} parameter cannot carry. Each
     * shapes a setter, and a seed emits none - so the pairing does nothing at
     * all, which is worth a diagnostic rather than a surprise at the call site.
     */
    private static final String[][] SEED_INCOMPATIBLE = {
        {"dev.simplified.annotations.Collector", "Collector"},
        {"dev.simplified.annotations.Negate", "Negate"},
        {"dev.simplified.annotations.Formattable", "Formattable"},
        {"dev.simplified.annotations.SetterNames", "SetterNames"},
    };

    /**
     * Rejects {@code @BuilderSeed} combined with a companion that only shapes a
     * setter.
     *
     * @param parameter the seeded parameter
     * @param messager sink for the diagnostic
     */
    private void rejectSeedCompanions(VariableElement parameter, Messager messager) {
        for (String[] companion : SEED_INCOMPATIBLE) {
            if (!lookup.hasAnnotation(parameter, companion[0])) continue;
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@BuilderSeed cannot be combined with @" + companion[1]
                    + " - a seeded slot is supplied to builder(...) and emits no setter for the "
                    + "companion to shape",
                parameter
            );
        }
    }

    private void processClass(TypeElement target, Messager messager) {
        BuilderConfig config = extractConfig(target);
        validateNaming(target, config, messager);
        List<FieldSpec> fields = collectFields(target, config);
        validateSlotNaming(fields, config.setters(), target, messager);
        validateDefaultProviders(target, fields, messager);
        validateAssignVia(target, fields, target, messager);

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
     * Reports every way a {@code @BuilderDefault(provider)} cannot supply the
     * slot it is written on, at the annotation rather than inside the generated
     * body that would have called it.
     *
     * <p>Being checkable at the declaration is the whole argument for naming a
     * method instead of carrying a source string: a missing, non-static or
     * wrongly-typed provider is an error on a line the author wrote.
     *
     * @param target the annotated type
     * @param fields the builder-visible fields
     * @param messager sink for diagnostics
     */
    private void validateDefaultProviders(TypeElement target, List<FieldSpec> fields,
                                          Messager messager) {
        for (FieldSpec field : fields) {
            if (field.defaultProvider == null) continue;
            Element site = field.element != null ? field.element : target;
            if (!field.builderDefault) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@BuilderDefault(value = false) asks for no default at all, and provider = '"
                        + field.defaultProvider + "' supplies one - keep whichever was meant",
                    site);
                continue;
            }
            ExecutableElement provider = findNullaryMethod(target, field.defaultProvider);
            if (provider == null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@BuilderDefault(provider = '" + field.defaultProvider + "') names no "
                        + "no-argument method on " + target.getSimpleName(),
                    site);
                continue;
            }
            if (!provider.getModifiers().contains(Modifier.STATIC)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@BuilderDefault(provider = '" + field.defaultProvider + "') names an instance "
                        + "method - the default is read when the builder is created, before any "
                        + target.getSimpleName() + " exists to read it from",
                    site);
                continue;
            }
            if (!suppliesType(provider.getReturnType(), field.type)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@BuilderDefault(provider = '" + field.defaultProvider + "') returns "
                        + provider.getReturnType() + ", which does not supply '" + field.name
                        + "' of type " + field.typeDisplay,
                    site);
            }
        }
    }

    /**
     * Reports every way an {@code @AssignVia} cannot route a setter's argument
     * into the slot it is written on, at the annotation rather than inside the
     * generated setter that would have called it.
     *
     * @param declaring the type declaring both the slots and the named methods
     * @param slots the builder's slots
     * @param fallbackSite where to report when a slot has no element of its own
     * @param messager sink for diagnostics
     */
    private void validateAssignVia(TypeElement declaring, List<FieldSpec> slots,
                                   Element fallbackSite, Messager messager) {
        var types = processingEnv.getTypeUtils();
        for (FieldSpec slot : slots) {
            if (slot.assignVia.isEmpty()) continue;
            Element site = slot.element != null ? slot.element : fallbackSite;
            // Read off the resolved list rather than asked of the parameter, so
            // the container javac wraps a repeated annotation in is covered too.
            if (slot.seed) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@BuilderSeed cannot be combined with @AssignVia - a seeded slot is supplied "
                        + "to builder(...) and emits no setter for a transform to route",
                    site);
                continue;
            }
            if (slot.lazy) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@AssignVia cannot be combined with @Lazy - that slot holds a Supplier<"
                        + slot.typeDisplay + "> rather than the value itself, so there is nothing "
                        + "for a transform to take",
                    site);
                continue;
            }
            if (slot.collector) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@AssignVia cannot be combined with @Collector - those setters copy element "
                        + "by element into the container rather than assigning it, so there is no "
                        + "single value to route through a transform",
                    site);
                continue;
            }
            // Every parameter type an arity-one setter already takes for this
            // slot. A transform landing on one of them is a duplicate method in
            // generated code, which javac would report on a line nobody wrote.
            java.util.List<TypeMirror> taken = new ArrayList<>();
            if (slot.isOptional) taken.add(optionalInnerOf(slot));
            for (FieldSpec.AssignTransform transform : slot.assignVia) {
                TypeMirror param = validateTransform(declaring, slot, transform, site, messager);
                if (param == null) continue;
                if (transform.direct()) continue;
                if (erasureAmong(types, param, taken)) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "@AssignVia(method = '" + transform.method() + "') takes " + param
                            + ", which is already the argument of a setter '" + slot.name
                            + "' emits - give it a parameter type of its own",
                        site);
                    continue;
                }
                taken.add(param);
            }
        }
    }

    /**
     * Checks one transform and returns the parameter type its setter would take,
     * or {@code null} when it was rejected.
     */
    private TypeMirror validateTransform(TypeElement declaring, FieldSpec slot,
                                         FieldSpec.AssignTransform transform,
                                         Element site, Messager messager) {
        String name = transform.method();
        java.util.List<ExecutableElement> candidates = unaryMethods(declaring, name);
        if (candidates.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@AssignVia(method = '" + name + "') names no single-argument method on "
                    + declaring.getSimpleName(),
                site);
            return null;
        }
        if (candidates.size() > 1) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@AssignVia(method = '" + name + "') names " + candidates.size()
                    + " single-argument methods on " + declaring.getSimpleName()
                    + " - one transform is one method, so give the intended one its own name",
                site);
            return null;
        }
        ExecutableElement method = candidates.getFirst();
        if (!method.getModifiers().contains(Modifier.STATIC)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@AssignVia(method = '" + name + "') names an instance method - the setter runs "
                    + "on the builder, before any " + declaring.getSimpleName() + " exists to "
                    + "call it on",
                site);
            return null;
        }
        TypeMirror param = method.getParameters().getFirst().asType();
        if (!suppliesType(method.getReturnType(), slot.type)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@AssignVia(method = '" + name + "') returns " + method.getReturnType()
                    + ", which does not supply '" + slot.name + "' of type " + slot.typeDisplay,
                site);
            return null;
        }
        if (transform.direct() && !suppliesType(slot.type, param)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@AssignVia(method = '" + name + "') takes " + param + ", which cannot accept '"
                    + slot.name + "' of type " + slot.typeDisplay + " - a transform over the "
                    + "slot's own type is what the ordinary setter hands its argument to",
                site);
            return null;
        }
        return param;
    }

    /** Every single-argument method of that name the type declares. */
    private static java.util.List<ExecutableElement> unaryMethods(TypeElement target, String name) {
        java.util.List<ExecutableElement> out = new ArrayList<>();
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            if (!enclosed.getSimpleName().contentEquals(name)) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (method.getParameters().size() == 1) out.add(method);
        }
        return out;
    }

    /** Whether a type erases to the same as any already spoken for. */
    private static boolean erasureAmong(javax.lang.model.util.Types types, TypeMirror candidate,
                                        java.util.List<TypeMirror> taken) {
        for (TypeMirror other : taken) {
            if (other == null) continue;
            if (types.isSameType(types.erasure(candidate), types.erasure(other))) return true;
        }
        return false;
    }

    /** The type argument of an {@code Optional} slot, which its raw setter takes. */
    private static TypeMirror optionalInnerOf(FieldSpec slot) {
        if (!(slot.type instanceof DeclaredType declared)) return null;
        var args = declared.getTypeArguments();
        return args.isEmpty() ? null : args.getFirst();
    }

    /** The target's own no-argument method of that name, or {@code null}. */
    private static ExecutableElement findNullaryMethod(TypeElement target, String name) {
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            if (!enclosed.getSimpleName().contentEquals(name)) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (method.getParameters().isEmpty()) return method;
        }
        return null;
    }

    /**
     * Whether a provider's return type can seed a slot of the given type.
     *
     * <p>Where either side mentions a type variable the comparison drops to
     * erasures, and it has to. A generic target's provider declares its own
     * parameters - a {@code static} method cannot name the class's - so
     * {@code static <T> List<T> none()} seeding a {@code List<V>} component is
     * two distinct variables that no assignability test relates, while the call
     * javac ends up attributing infers one from the other and is perfectly
     * legal. The erasure comparison still catches the mistake worth catching
     * here, a provider of an unrelated kind, and javac catches the rest on the
     * generated call.
     */
    private boolean suppliesType(TypeMirror provided, TypeMirror slot) {
        var types = processingEnv.getTypeUtils();
        if (TypeNames.mentionsTypeVariable(provided) || TypeNames.mentionsTypeVariable(slot)) {
            return types.isAssignable(types.erasure(provided), types.erasure(slot));
        }
        return types.isAssignable(provided, slot);
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
            // Standalone @Lazy path - no @ClassBuilder, so no retainInit policy
            // and no builder to name setters for either.
            out.add(FieldSpec.from((VariableElement) enclosed, lookup, introspector,
                processingEnv.getTypeUtils(), false, SetterScheme.of(NamingStyle.SIMPLIFIED)));
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
        validateSlotNaming(fields, config.setters(), target, messager);
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
            FieldSpec spec = FieldSpec.fromInterfaceAccessor(method, lookup,
                processingEnv.getTypeUtils(), config.setters());
            if (spec.ignored) continue;
            out.add(spec);
        }
        return out;
    }

    /**
     * Resolves the configuration written on a type target, whose own simple name
     * is what the builder-class name expands against.
     */
    private BuilderConfig extractConfig(TypeElement target) {
        return extractConfig(target, target.getSimpleName().toString());
    }

    /**
     * Resolves the configuration written on any target.
     *
     * @param target the annotated element - a type, a constructor, or a static
     *        factory
     * @param nameSubject the simple name a {@code @BuilderNames} pattern expands
     *        its placeholder against, always the enclosing type's
     * @return the resolved configuration
     */
    private BuilderConfig extractConfig(Element target, String nameSubject) {
        NamingStyle style = parseStyle(lookup.stringAttr(target, ANNOTATION_FQN, "style", "SIMPLIFIED"));
        AccessLevel access = parseAccess(lookup.stringAttr(target, ANNOTATION_FQN, "access", "PUBLIC"));
        AccessLevel constructorAccess =
            parseAccess(lookup.stringAttr(target, ANNOTATION_FQN, "constructorAccess", "PACKAGE"));
        AccessLevel builderConstructorAccess =
            parseAccess(lookup.stringAttr(target, ANNOTATION_FQN, "builderConstructorAccess", "PACKAGE"));
        boolean retainInit = lookup.booleanAttr(target, ANNOTATION_FQN, "retainInit", true);
        boolean generateCopyConstructor = lookup.booleanAttr(target, ANNOTATION_FQN, "generateCopyConstructor", true);
        boolean generateImpl = lookup.booleanAttr(target, ANNOTATION_FQN, "generateImpl", true);
        boolean validate = lookup.booleanAttr(target, ANNOTATION_FQN, "validate", true);
        boolean emitContracts = lookup.booleanAttr(target, ANNOTATION_FQN, "emitContracts", true);
        boolean emitGenerated = lookup.booleanAttr(target, ANNOTATION_FQN, "emitGenerated", true);
        String factoryMethod = lookup.stringAttr(target, ANNOTATION_FQN, "factoryMethod", "");
        Set<String> excludeSet = new HashSet<>(Arrays.asList(lookup.stringArrayAttr(target, ANNOTATION_FQN, "exclude")));
        return new BuilderConfig(
            extractBuilderNames(target, style, nameSubject), extractSetterNames(target, style),
            access, constructorAccess, builderConstructorAccess, retainInit,
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
                processingEnv.getTypeUtils(), config.retainInit(), config.setters());
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
    private SetterScheme extractSetterNames(Element target, NamingStyle style) {
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
    private BuilderScheme extractBuilderNames(Element target, NamingStyle style, String simpleName) {
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
    private void validateNaming(Element target, BuilderConfig config, Messager messager) {
        validateSetterScheme(config.setters(), target, true, messager);
        validateBuilderNames(target, messager);
    }

    /**
     * Checks each slot's resolved patterns, which a {@code @SetterNames} written
     * on the slot may differ from the target's.
     *
     * <p>Reported at the slot rather than at the type, because that is where the
     * override was written. A slot inheriting the target's scheme unchanged is
     * skipped - the same defect would otherwise be reported once per field.
     *
     * <p>The placeholder is optional here, and mandatory on the target, for the
     * reason it is on {@code @Getter} and {@code @Setter}: a target's pattern
     * fans out over every slot, so a literal would give them all the same method
     * name, while a slot's expands exactly once and a literal is simply that
     * setter's name. Any role a slot inherited was already checked at the
     * target, so nothing is let through by asking less of it here.
     *
     * @param slots the builder-visible slots
     * @param base the target's own resolved scheme, already reported on
     * @param target the annotated element, for a slot with no element of its own
     * @param messager sink for diagnostics
     */
    private void validateSlotNaming(List<FieldSpec> slots, SetterScheme base, Element target,
                                    Messager messager) {
        for (FieldSpec slot : slots) {
            if (slot.setters.equals(base)) continue;
            validateSetterScheme(slot.setters, slot.element != null ? slot.element : target,
                false, messager);
        }
    }

    /** Reports every pattern in a resolved scheme that cannot mint a member. */
    private void validateSetterScheme(SetterScheme setters, Element site, boolean fansOut,
                                      Messager messager) {
        String[][] roles = {
            {"set", setters.set()}, {"flag", setters.flag()}, {"add", setters.add()},
            {"put", setters.put()}, {"compute", setters.compute()}, {"clear", setters.clear()}
        };
        for (String[] role : roles) {
            String error = NamePattern.patternError(role[1], fansOut);
            if (error != null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@SetterNames pattern for '" + role[0] + "' " + error, site);
            }
        }
        if (!setters.emitsSet()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@SetterNames cannot suppress the 'set' role - a field would then have no way to "
                    + "be assigned on the builder", site);
        }
    }

    /**
     * Checks the once-per-target names. The placeholder is optional here, every
     * default being a plain literal, so only malformed text and a suppressed
     * {@code type} or {@code build} are errors.
     */
    private void validateBuilderNames(Element target, Messager messager) {
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
