package dev.sbs.classbuilder.mutate;

import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import dev.sbs.classbuilder.mutate.compat.JavacCompat;
import dev.sbs.classbuilder.mutate.compat.JavacCompatFactory;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Gateway to the javac-internal services needed for AST mutation. One instance
 * per annotation-processing round, built by unwrapping the user-facing
 * {@link ProcessingEnvironment} down to a {@link JavacProcessingEnvironment}.
 *
 * <p>If the environment is not a javac (ecj, unknown wrapper, future
 * compilers), {@link #of(ProcessingEnvironment)} returns {@link Optional#empty()}
 * and callers must fall back to sibling-file emission via {@code Filer}.
 *
 * <p>Narrow by design - publishes {@link TreeMaker}, {@link Names},
 * {@link Symtab}, {@link Types}, {@link Log}, {@link JavacElements}, and
 * {@link Trees} as already-resolved services plus a tiny set of helpers. No
 * import mutation, no javadoc copying, no bytecode patching - those are the
 * surfaces that historically broke Lombok on new JDK releases.
 */
public final class JavacBridge {

    private final JavacProcessingEnvironment jpe;
    private final Context context;
    private final TreeMaker treeMaker;
    private final Names names;
    private final Symtab symtab;
    private final Types types;
    private final JavacElements elements;
    private final Log log;
    private final Trees trees;
    private final JavacCompat compat;

    private JavacBridge(JavacProcessingEnvironment jpe) {
        this.jpe = jpe;
        this.context = jpe.getContext();
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
        this.symtab = Symtab.instance(context);
        this.types = Types.instance(context);
        this.elements = JavacElements.instance(context);
        this.log = Log.instance(context);
        this.trees = Trees.instance(jpe);
        this.compat = JavacCompatFactory.forRuntime();
    }

    /**
     * Attempts to build a bridge from the supplied environment. Handles direct
     * javac and common build-system wrappers (Gradle's isolating env) by
     * reflectively reading a {@code delegate} field when the environment is
     * not already a {@link JavacProcessingEnvironment}.
     *
     * @param env processing environment handed to the {@code AbstractProcessor}
     * @return a bridge when javac internals are reachable; empty otherwise
     */
    public static Optional<JavacBridge> of(ProcessingEnvironment env) {
        ProcessingEnvironment unwrapped = unwrap(env);
        if (unwrapped instanceof JavacProcessingEnvironment jpe) return Optional.of(new JavacBridge(jpe));
        return Optional.empty();
    }

    /**
     * Peels off common build-system wrappers. Gradle's
     * {@code IsolatingProcessingEnvironment} and incremental variants all
     * expose a {@code delegate} field of type {@link ProcessingEnvironment};
     * reflection is necessary because those classes are internal to the build
     * tool. Never throws - failure is indistinguishable from "already
     * unwrapped" for the caller.
     */
    private static ProcessingEnvironment unwrap(ProcessingEnvironment env) {
        ProcessingEnvironment current = env;
        for (int hops = 0; hops < 4; hops++) {
            if (current instanceof JavacProcessingEnvironment) return current;
            ProcessingEnvironment next = delegateOf(current);
            if (next == null || next == current) return current;
            current = next;
        }
        return current;
    }

    private static ProcessingEnvironment delegateOf(ProcessingEnvironment env) {
        Class<?> cls = env.getClass();
        while (cls != null) {
            for (Field f : cls.getDeclaredFields()) {
                if (!ProcessingEnvironment.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(env);
                    if (v instanceof ProcessingEnvironment pe) return pe;
                } catch (ReflectiveOperationException ignored) {
                    // next field
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    public JavacProcessingEnvironment processingEnvironment() { return jpe; }
    public Context context() { return context; }
    public TreeMaker treeMaker() { return treeMaker; }
    public Names names() { return names; }
    public Symtab symtab() { return symtab; }
    public Types types() { return types; }
    public JavacElements elements() { return elements; }
    public Log log() { return log; }
    public Trees trees() { return trees; }
    public JavacCompat compat() { return compat; }

    /**
     * Resolves the {@link JCClassDecl} for a given type element. Returns
     * {@code null} when the element has no backing source tree - for example,
     * elements materialised from class files rather than sources.
     *
     * @param element a javac-visible type element
     * @return the class declaration tree, or {@code null} when unavailable
     */
    public JCClassDecl treeOf(TypeElement element) {
        var tree = trees.getTree(element);
        return tree instanceof JCClassDecl cls ? cls : null;
    }

}
