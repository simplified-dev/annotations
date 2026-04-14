package dev.sbs.classbuilder.mutate.compat.v17;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.util.List;
import dev.sbs.classbuilder.mutate.compat.JavacCompat;

/**
 * Baseline compatibility implementation covering every JDK currently
 * supported (17 through 25). Future divergence is absorbed by adding a new
 * subclass that overrides only what drifts; the baseline stays put.
 */
public class JavacCompatV17 implements JavacCompat {

    @Override
    public void appendDef(JCClassDecl target, JCTree def) {
        target.defs = target.defs.append(def);
    }

    @Override
    public List<JCTree> emptyDefs() {
        return List.nil();
    }

}
