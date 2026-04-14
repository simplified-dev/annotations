package dev.sbs.classbuilder.mutate.compat;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.util.List;

/**
 * Narrow compatibility surface for javac internals that have historically
 * drifted between JDK major versions. Implementations are selected at runtime
 * by {@link JavacCompatFactory} based on {@link Runtime#version()}.
 *
 * <p>Day-one the interface exposes only the few operations needed by the AST
 * mutation pipeline. Currently every supported JDK (17 through 25) uses the
 * single {@code v17} baseline because the javac APIs the pipeline touches
 * have been stable across those versions. The factory stays wired up so a
 * future divergence is a new subclass + one gate, nothing more.
 *
 * @see JavacCompatFactory
 */
public interface JavacCompat {

    /**
     * Appends a single definition (method, field, nested class) to a class
     * declaration's body. Wraps the internal {@code com.sun.tools.javac.util.List}
     * cons-list so callers never handle it directly - that type's API has been
     * the most stable axis we touch.
     *
     * @param target class declaration whose body receives the new definition
     * @param def new declaration to append
     */
    void appendDef(JCClassDecl target, JCTree def);

    /** Returns an empty javac {@link List} parameterised for class members. */
    List<JCTree> emptyDefs();

}
