package dev.simplified.silentthrows.apt;

import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.LazyOwnership;
import dev.simplified.shared.javac.compat.JavacAccessFactory;
import dev.simplified.silentthrows.mutate.SilentThrowsMutator;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * JSR 269 processor that drives {@link SilentThrowsMutator}.
 *
 * <p>Shaped after {@code EnumLookupProcessor}: open {@code jdk.compiler} in a
 * static initialiser before any javac internal type is linked, resolve the
 * bridge in {@code init}, and error rather than degrade when the environment is
 * not javac.
 *
 * <p>Annotated members are grouped by their declaring type, because the mutator
 * works a class at a time - the rethrow helper is emitted once per class and the
 * dedupe that guarantees it is a scan of that class's own definitions.
 *
 * <p>A member whose top-level compilation-unit type declares a {@code @Lazy}
 * field is left to {@code ClassBuilderProcessor}, which dispatches the same
 * mutator after its own passes. Processor order within a round is unspecified,
 * and the {@code @Lazy} pass has to see constructor bodies before this one
 * re-parents them one level down.
 */
@SupportedAnnotationTypes("dev.simplified.annotations.SilentThrows")
public class SilentThrowsProcessor extends AbstractProcessor {

    static {
        JavacAccessFactory.forRuntime().open();
    }

    private static final String ANNOTATION_FQN = "dev.simplified.annotations.SilentThrows";

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
            if (!(element instanceof ExecutableElement member)) continue;
            if (isBodyless(member)) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "@SilentThrows has no body to wrap on " + member.getSimpleName()
                        + " - an abstract or native declaration has nothing to catch, and the "
                        + "annotation does not carry to an implementation. Move it onto the "
                        + "declarations that have a body",
                    member);
                continue;
            }
            if (member.getEnclosingElement() instanceof TypeElement owner
                && !deferredToBuilderPipeline(owner)) {
                targets.add(owner);
            }
        }

        for (TypeElement target : targets) {
            try {
                processTarget(target, messager);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to process @SilentThrows on " + target + ": " + e.getMessage(),
                    target);
            }
        }
        return false;
    }

    /**
     * Whether the owner's compilation unit is one the {@code ClassBuilder}
     * pipeline rewrites instead.
     *
     * <p>The question is asked of the <b>top-level</b> type rather than the
     * owner, because that is the granularity the deferring dispatch works at -
     * it walks a round's root elements. Asking it of a nested owner would split
     * one file between two dispatch paths and lose the ordering the split
     * exists to buy.
     *
     * @param owner the type declaring the annotated member
     * @return {@code true} when this processor must stand back
     */
    private boolean deferredToBuilderPipeline(TypeElement owner) {
        if (javacBridge.isEmpty()) return false;
        Element top = owner;
        while (top.getEnclosingElement() instanceof TypeElement enclosing) top = enclosing;
        JCClassDecl tree = javacBridge.get().treeOf((TypeElement) top);
        return tree != null
            && LazyOwnership.declaresLazyField(tree, javacBridge.get().unitOf((TypeElement) top));
    }

    /**
     * Whether the declaration has no body for the wrap to take.
     *
     * <p>Reported as an error rather than tolerated, because such a declaration
     * is inert forever: a method annotation is never inherited, so the
     * annotation cannot reach an implementation later and start doing something.
     * That puts it with the other cases where an annotation whose only job is to
     * generate something is left with nothing to generate.
     *
     * <p>Keyed on the modifiers rather than on a null tree body, so a round that
     * hands over half-parsed source cannot turn this into a second error stacked
     * on the author's real one.
     *
     * @param member the annotated method or constructor
     * @return whether the declaration is abstract or native
     */
    private static boolean isBodyless(ExecutableElement member) {
        Set<Modifier> modifiers = member.getModifiers();
        return modifiers.contains(Modifier.ABSTRACT) || modifiers.contains(Modifier.NATIVE);
    }

    private void processTarget(TypeElement target, Messager messager) {
        if (javacBridge.isEmpty()) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@SilentThrows requires javac for AST mutation - current environment is not a "
                    + "JavacProcessingEnvironment. Run your build under OpenJDK javac (no ecj).",
                target);
            return;
        }

        JCClassDecl tree = javacBridge.get().treeOf(target);
        if (tree == null) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                "@SilentThrows could not resolve a source tree for " + target
                    + "; mutation requires the annotated element to have a source declaration.",
                target);
            return;
        }
        new SilentThrowsMutator(javacBridge.get()).mutate(tree);
    }

}
