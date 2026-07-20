package dev.simplified.classbuilder.mutate;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Names;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.classbuilder.apt.BuilderConfig;
import dev.simplified.classbuilder.apt.FieldSpec;

import javax.lang.model.element.TypeElement;
import java.util.List;
import java.util.Set;

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
    private final ContractAnnotations contracts;
    private final Set<String> instanceDefaults;

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
        this.contracts = new ContractAnnotations(
            bridge.treeMaker(), bridge.names(), this.types, config.emitContracts());
        this.instanceDefaults = InstanceDefaultDetector.detect(targetElement, fields, bridge.elements());
    }

    /**
     * Whether this field's retained initializer reads instance state, and so
     * must be computed in the constructor rather than hoisted into a static
     * provider evaluated when the builder is created.
     *
     * @param fieldName the field to test
     * @return whether the field takes the constructor-computed default path
     */
    public boolean isInstanceDefault(String fieldName) {
        return instanceDefaults.contains(fieldName);
    }

    /** Field names taking the constructor-computed default path. */
    public Set<String> instanceDefaults() {
        return instanceDefaults;
    }

    /**
     * Whether a field can take the constructor-computed path at all. The slot
     * is retyped to {@code Supplier<T>} there, so null distinguishes "never
     * set" from "set to null" without a parallel flag - which rules out shapes
     * whose setters mutate the slot in place or read it as its declared type.
     * {@code @Lazy} fields always qualify: their slot is already
     * {@code Supplier<T>} and they take a single dual-setter shape.
     *
     * @param field the field to test
     * @return whether the constructor-computed path supports this field's shape
     */
    public static boolean supportsInstanceDefault(FieldSpec field) {
        if (field.lazy) return true;
        if (field.isBoolean || field.isOptional || field.isArray || field.formattable) return false;
        return !((field.isListLike || field.isMap) && field.collector);
    }

    public JavacBridge bridge() { return bridge; }
    public TreeMaker make() { return bridge.treeMaker(); }
    public Names names() { return bridge.names(); }
    public JavacTypeFactory types() { return types; }
    public ContractAnnotations contracts() { return contracts; }
    public TypeElement targetElement() { return targetElement; }
    public JCClassDecl target() { return target; }
    public BuilderConfig config() { return config; }
    public List<FieldSpec> fields() { return fields; }
    public String targetSimpleName() { return targetSimpleName; }
    public String builderName() { return builderName; }

    /**
     * Translates the resolved {@link AccessLevel} into the javac modifier
     * bit-field used on generated {@link JCClassDecl} and method modifiers.
     * {@link AccessLevel#PACKAGE} maps to {@code 0} (no modifier keyword,
     * which is how javac represents package-private).
     */
    public long accessFlag() {
        return accessFlagFor(config.access());
    }

    /** Static variant so call sites without a context can reuse the mapping. */
    public static long accessFlagFor(AccessLevel access) {
        return switch (access) {
            case PUBLIC -> Flags.PUBLIC;
            case PROTECTED -> Flags.PROTECTED;
            case PRIVATE -> Flags.PRIVATE;
            case PACKAGE -> 0L;
        };
    }

}
