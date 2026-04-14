package dev.sbs.classbuilder.mutate;

import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import dev.sbs.classbuilder.apt.BuilderConfig;
import dev.sbs.classbuilder.apt.FieldSpec;

import javax.lang.model.element.TypeElement;
import java.util.List;

/**
 * Per-target value carrier threaded through the mutation pipeline so each
 * factory does not re-resolve javac services or re-read the builder
 * configuration. One instance is built per annotated type.
 */
public final class MutationContext {

    private final JavacBridge bridge;
    private final TypeElement targetElement;
    private final JCClassDecl target;
    private final BuilderConfig config;
    private final List<FieldSpec> fields;
    private final String targetSimpleName;
    private final String builderName;
    private final JavacTypeFactory types;

    public MutationContext(JavacBridge bridge,
                           TypeElement targetElement,
                           JCClassDecl target,
                           BuilderConfig config,
                           List<FieldSpec> fields) {
        this.bridge = bridge;
        this.targetElement = targetElement;
        this.target = target;
        this.config = config;
        this.fields = fields;
        this.targetSimpleName = targetElement.getSimpleName().toString();
        this.builderName = config.builderName();
        this.types = new JavacTypeFactory(bridge.treeMaker(), bridge.names());
    }

    public JavacBridge bridge() { return bridge; }
    public TreeMaker make() { return bridge.treeMaker(); }
    public Names names() { return bridge.names(); }
    public JavacTypeFactory types() { return types; }
    public TypeElement targetElement() { return targetElement; }
    public JCClassDecl target() { return target; }
    public BuilderConfig config() { return config; }
    public List<FieldSpec> fields() { return fields; }
    public String targetSimpleName() { return targetSimpleName; }
    public String builderName() { return builderName; }

}
