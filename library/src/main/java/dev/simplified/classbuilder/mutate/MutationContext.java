package dev.simplified.classbuilder.mutate;
import dev.simplified.shared.javac.ContractAnnotations;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.classbuilder.apt.BuilderConfig;
import dev.simplified.classbuilder.apt.FieldSpec;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeMirror;
import java.util.HashSet;
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
    private final String selfTypeName;
    private final String selfBuilderName;

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
        // A generic target could itself declare a parameter called T or B, so
        // the SuperBuilder self-type names dodge whatever it uses. Resolved
        // once here because the chain mutator declares them while the
        // self-typed setter emitter returns them, and the two must agree.
        Set<String> taken = new HashSet<>();
        for (TypeParameterElement tp : targetElement.getTypeParameters()) {
            taken.add(tp.getSimpleName().toString());
        }
        this.selfTypeName = freeTypeParamName("T", taken);
        taken.add(this.selfTypeName);
        this.selfBuilderName = freeTypeParamName("B", taken);
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
     * The {@code java.util} interface a collected field's builder slot is typed
     * as while it gathers contributions - {@code List<E>}, {@code Set<E>} or
     * {@code Map<K, V>}, read off the supertype the field's declared type was
     * matched through.
     *
     * <p>The slot is deliberately not the declared type. Nothing needs to build
     * an instance of that type before {@code build()} runs: the builder only has
     * to hold what the caller contributed, and the constructor produces the real
     * container from the field's own initializer. That keeps custom containers
     * working whatever their shape - including an interface, which has no
     * constructor to call - and means the built object always holds exactly what
     * the initializer returns rather than something reconstructed from the
     * declared type.
     *
     * @param field the collected field
     * @return the scratch slot's declared type
     */
    public JCExpression collectedSlotType(FieldSpec field) {
        TreeMaker make = make();
        if (field.isMap) {
            return make.TypeApply(types.qualIdent("java.util.Map"),
                com.sun.tools.javac.util.List.of(
                    types.parseType(field.mapKey), types.parseType(field.mapValue)));
        }
        String fqn = field.isSet ? "java.util.Set" : "java.util.List";
        return make.TypeApply(types.qualIdent(fqn),
            com.sun.tools.javac.util.List.of(types.parseType(field.collectionElement)));
    }

    /** A fresh {@code java.util} container for a collected field's scratch slot. */
    public JCExpression freshCollectedSlot(FieldSpec field) {
        String fqn = field.isMap ? "java.util.LinkedHashMap"
            : field.isSet ? "java.util.LinkedHashSet"
            : "java.util.ArrayList";
        return make().NewClass(null, com.sun.tools.javac.util.List.nil(),
            make().TypeApply(types.qualIdent(fqn), com.sun.tools.javac.util.List.nil()),
            com.sun.tools.javac.util.List.nil(), null);
    }

    /**
     * Whether the field is a {@code @Collector} container - a shape whose
     * setters mutate the builder slot in place rather than assigning it.
     *
     * @param field the field to test
     * @return whether the field takes the collected-container setter family
     */
    public static boolean isCollected(FieldSpec field) {
        return (field.isListLike || field.isMap) && field.collector;
    }

    /**
     * Whether the field is a {@code @Collector} container whose default reads
     * instance state, and so takes the merge path: the slot holds only what the
     * caller contributed, and the constructor folds it onto the instance-
     * computed default.
     *
     * @param field the field to test
     * @return whether the field takes the collected merge path
     */
    public boolean isCollectedInstanceDefault(FieldSpec field) {
        return isInstanceDefault(field.name) && isCollected(field);
    }

    /** The builder-side marker recording that a setter replaced the collection wholesale. */
    public static String replacedMarker(String fieldName) {
        return "$replaced$" + fieldName;
    }

    /** Whether the target declares type parameters of its own. */
    public boolean isGeneric() {
        return !targetElement.getTypeParameters().isEmpty();
    }

    /**
     * Name of the SuperBuilder self-type parameter standing for the built type -
     * {@code T} unless the target declares a parameter by that name.
     *
     * @return the target-type parameter name
     */
    public String selfTypeName() {
        return selfTypeName;
    }

    /**
     * Name of the SuperBuilder self-type parameter standing for the concrete
     * builder - {@code B} unless the target declares a parameter by that name.
     * Read by both the chain mutator, which declares it, and the self-typed
     * setter emitter, which returns it.
     *
     * @return the builder-type parameter name
     */
    public String selfBuilderName() {
        return selfBuilderName;
    }

    /** Appends {@code $} until the name is not one the target already declares. */
    private static String freeTypeParamName(String preferred, Set<String> taken) {
        String candidate = preferred;
        while (taken.contains(candidate)) candidate = candidate + "$";
        return candidate;
    }

    /**
     * The target's type parameters as freshly-built declaration nodes, for
     * re-declaring on the nested {@code Builder} and on the static bootstrap
     * methods. The nested Builder is {@code static}, so it cannot see the
     * enclosing class's type variables and must declare its own copies.
     *
     * <p>Built from the {@link TypeElement} rather than cloned off the target's
     * tree because javac nodes cannot be shared between parents - every call
     * mints new nodes, so callers never need to copy. {@code java.lang.Object}
     * bounds are dropped, being what an unbounded parameter means anyway.
     *
     * @return fresh type-parameter declarations, empty when the target is not generic
     */
    public com.sun.tools.javac.util.List<JCTypeParameter> typeParams() {
        TreeMaker make = make();
        Names names = names();
        ListBuffer<JCTypeParameter> out = new ListBuffer<>();
        for (TypeParameterElement tp : targetElement.getTypeParameters()) {
            ListBuffer<JCExpression> bounds = new ListBuffer<>();
            for (TypeMirror bound : tp.getBounds()) {
                if ("java.lang.Object".equals(bound.toString())) continue;
                bounds.append(types.parseType(bound.toString()));
            }
            out.append(make.TypeParameter(
                names.fromString(tp.getSimpleName().toString()), bounds.toList()));
        }
        return out.toList();
    }

    /**
     * The target's type parameters as freshly-built reference expressions, for
     * applying to a type - {@code Builder<V>}, {@code Target<K, V>}. Mirrors
     * {@link #typeParams()} name-for-name.
     *
     * @return fresh type-argument references, empty when the target is not generic
     */
    public com.sun.tools.javac.util.List<JCExpression> typeArgs() {
        TreeMaker make = make();
        Names names = names();
        ListBuffer<JCExpression> out = new ListBuffer<>();
        for (TypeParameterElement tp : targetElement.getTypeParameters()) {
            out.append(make.Ident(names.fromString(tp.getSimpleName().toString())));
        }
        return out.toList();
    }

    /**
     * A reference to the target type, parameterised when the target is generic:
     * {@code Target} or {@code Target<K, V>}.
     *
     * @return a fresh type reference to the target
     */
    public JCExpression targetType() {
        JCExpression raw = make().Ident(names().fromString(targetSimpleName));
        return isGeneric() ? make().TypeApply(raw, typeArgs()) : raw;
    }

    /**
     * A reference to the nested builder type, parameterised with the target's
     * own type parameters when it is generic: {@code Builder} or
     * {@code Builder<K, V>}.
     *
     * @return a fresh type reference to the nested builder
     */
    public JCExpression builderType() {
        JCExpression raw = make().Ident(names().fromString(builderName));
        return isGeneric() ? make().TypeApply(raw, typeArgs()) : raw;
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
