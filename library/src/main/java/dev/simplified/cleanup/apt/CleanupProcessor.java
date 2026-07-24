package dev.simplified.cleanup.apt;

import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import dev.simplified.cleanup.mutate.CleanupBlockMutator;
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
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Optional;
import java.util.Set;

/**
 * JSR 269 processor that drives {@link CleanupBlockMutator}.
 *
 * <p>Discovery here is unlike every other processor in this library.
 * {@code RoundEnvironment.getElementsAnnotatedWith} cannot see a local-variable
 * annotation at all: javac builds the round's annotations-present set with an
 * element scan over root elements, and a local variable is not an enclosed
 * element of anything. A processor declaring the annotation as its supported
 * type would simply never be invoked. So this one claims {@code "*"} and finds
 * its work by walking each root element's tree.
 *
 * <p>It stays a processor of its own rather than widening the shared one, so a
 * consumer using only {@code @ClassBuilder} sees no change in what triggers.
 * The price of {@code "*"} is that it is invoked for every round of every
 * compilation with this jar on the processor path, so it bails on the cheapest
 * sound signal available - the annotation not being resolvable at all - and then
 * lets the tree walk be the filter. An import-list pre-filter would be cheaper
 * and wrong: a fully-qualified {@code @dev.simplified.annotations.Cleanup} needs
 * no import, and missing one is a resource that is silently never closed.
 *
 * <p>Trees that declare a {@code @Lazy} field are left to
 * {@code ClassBuilderProcessor}, which dispatches the same mutator after its own
 * passes. Processor order within a round is unspecified, and the {@code @Lazy}
 * pass has to see constructor bodies before this one relocates their tails.
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class CleanupProcessor extends AbstractProcessor {

    static {
        JavacAccessFactory.forRuntime().open();
    }

    private static final String ANNOTATION_FQN = "dev.simplified.annotations.Cleanup";

    private Optional<JavacBridge> javacBridge = Optional.empty();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.javacBridge = JavacBridge.of(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;
        // Not resolvable means no source in this compilation can name it, in
        // either spelling - the fully-qualified form needs the type on the
        // compile classpath just as the imported one does.
        if (processingEnv.getElementUtils().getTypeElement(ANNOTATION_FQN) == null) return false;
        // Silent under a non-javac compiler. This processor is invoked for every
        // compilation that has the jar on its processor path, so an error here
        // would break builds that use none of these annotations; the sibling
        // processors already say so loudly for anything that does.
        if (javacBridge.isEmpty()) return false;

        Messager messager = processingEnv.getMessager();
        for (Element root : roundEnv.getRootElements()) {
            if (!(root instanceof TypeElement type)) continue;
            JCClassDecl tree = javacBridge.get().treeOf(type);
            if (tree == null) continue;
            if (CleanupBlockMutator.declaresLazyField(tree, javacBridge.get().unitOf(type))) continue;
            try {
                new CleanupBlockMutator(javacBridge.get(), messager, type).mutate(tree);
            } catch (Exception e) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                    "Failed to process @Cleanup in " + type + ": " + e.getMessage(), type);
            }
        }
        return false;
    }

}
