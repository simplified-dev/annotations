package dev.sbs.classbuilder.mutate.compat.v17;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.util.List;
import dev.sbs.classbuilder.mutate.compat.JavacCompat;

/**
 * Baseline compatibility implementation covering JDK 17 through 20. Higher
 * versions extend this class and override only what actually diverges.
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
