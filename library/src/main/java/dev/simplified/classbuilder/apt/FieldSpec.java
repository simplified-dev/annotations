package dev.simplified.classbuilder.apt;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Intermediate representation of a single field on a {@code @ClassBuilder}-annotated class.
 * All classification the emitter needs happens once in {@link #from(VariableElement, AnnotationLookup, SourceIntrospector)},
 * so the emitter only reads already-resolved properties.
 */
public final class FieldSpec {

    // Optional<T> is typed so we don't have to reflect on its FQN repeatedly.
    private static final String OPTIONAL_FQN = "java.util.Optional";
    private static final Set<String> LIST_TYPES = Set.of(
        "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
        "java.util.Collection", "java.lang.Iterable"
    );
    private static final Set<String> SET_TYPES = Set.of(
        "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet"
    );
    private static final Set<String> MAP_TYPES = Set.of(
        "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap"
    );

    public final String name;
    public final VariableElement element;
    public final TypeMirror type;
    public final String typeDisplay;

    public final boolean notNull;
    public final boolean nullable;

    public final boolean isBoolean;
    public final boolean isString;
    public final boolean isPrimitive;
    public final boolean isArray;

    public final boolean isOptional;
    public final String optionalInner;              // null unless isOptional

    public final boolean isListLike;                // List, Set, or Collection
    public final boolean isSet;
    public final boolean isMap;
    public final String collectionElement;          // element type for list/set/array
    public final String mapKey, mapValue;

    // Companion annotations
    public final boolean formattable;
    public final String negateName;                 // null if no @Negate
    public final boolean collector;                 // @Collector present - enables varargs + iterable for list/set, gates extras
    public final boolean singular;                  // @Collector(singular = true) - single-element add/put setter
    public final String singularName;               // derived from @Collector.singularMethodName or field-name inflection; null if no @Collector
    public final boolean clearable;                 // @Collector(clearable = true) - clear() method
    public final boolean compute;                   // @Collector(compute = true) - maps only, putIfAbsent(K, Supplier<V>)
    public final boolean ignored;                   // @BuildRule(ignore = true) or listed in @ClassBuilder.exclude
    public final boolean builderDefault;
    public final String sourceInitializer;          // copied source text of the field's declared initializer
    public final Set<String> initializerImports;    // type FQNs referenced by sourceInitializer
    // The javac parse-time tree for the initializer (a JCExpression at
    // runtime). Typed as com.sun.source.tree.Tree so this class stays javac-
    // internal-free. AST-mutation consumers cast + deep-clone with symbols
    // reset before embedding in a synthesised method body.
    public final com.sun.source.tree.Tree sourceInitializerTree;
    public final String obtainViaMethod;            // null if none
    public final String obtainViaField;
    public final boolean obtainViaStatic;

    private FieldSpec(Builder b) {
        this.name = b.name;
        this.element = b.element;
        this.type = b.type;
        this.typeDisplay = b.typeDisplay;
        this.notNull = b.notNull;
        this.nullable = b.nullable;
        this.isBoolean = b.isBoolean;
        this.isString = b.isString;
        this.isPrimitive = b.isPrimitive;
        this.isArray = b.isArray;
        this.isOptional = b.isOptional;
        this.optionalInner = b.optionalInner;
        this.isListLike = b.isListLike;
        this.isSet = b.isSet;
        this.isMap = b.isMap;
        this.collectionElement = b.collectionElement;
        this.mapKey = b.mapKey;
        this.mapValue = b.mapValue;
        this.formattable = b.formattable;
        this.negateName = b.negateName;
        this.collector = b.collector;
        this.singular = b.singular;
        this.singularName = b.singularName;
        this.clearable = b.clearable;
        this.compute = b.compute;
        this.ignored = b.ignored;
        this.builderDefault = b.builderDefault;
        this.sourceInitializer = b.sourceInitializer;
        this.initializerImports = b.initializerImports == null ? Set.of() : b.initializerImports;
        this.sourceInitializerTree = b.sourceInitializerTree;
        this.obtainViaMethod = b.obtainViaMethod;
        this.obtainViaField = b.obtainViaField;
        this.obtainViaStatic = b.obtainViaStatic;
    }

    /** Whether this field uses {@code is*} setters (booleans) vs the configured prefix. */
    boolean usesBooleanPrefix() {
        return isBoolean;
    }

    /**
     * Factory for interface abstract-accessor methods. The method name becomes the
     * field name; the return type becomes the field type. No VariableElement is
     * retained (the underlying element is a method), so {@link #element} is null
     * and callers that need source reporting should fall back to the type element.
     *
     * <p>{@code @BuildRule(retainInit)} and {@code @BuildRule(obtainVia)} do
     * not apply to interface accessors (the annotations target fields only),
     * so no {@link SourceIntrospector} is threaded through this path - only
     * {@code ignore} is honoured here.
     */
    public static FieldSpec fromInterfaceAccessor(ExecutableElement method, AnnotationLookup lookup) {
        Builder b = new Builder();
        b.element = null;
        b.name = method.getSimpleName().toString();
        b.type = method.getReturnType();
        b.typeDisplay = b.type.toString();

        b.notNull = lookup.hasAnnotation(method, "org.jetbrains.annotations.NotNull");
        b.nullable = lookup.hasAnnotation(method, "org.jetbrains.annotations.Nullable");
        classifyType(b);

        b.formattable = lookup.hasAnnotation(method, "dev.simplified.annotations.Formattable");
        b.negateName = lookup.stringAttr(method, "dev.simplified.annotations.Negate", "value", null);
        if (lookup.hasAnnotation(method, "dev.simplified.annotations.Collector")) {
            b.collector = true;
            b.singular = lookup.booleanAttr(method, "dev.simplified.annotations.Collector", "singular", false);
            b.clearable = lookup.booleanAttr(method, "dev.simplified.annotations.Collector", "clearable", false);
            b.compute = lookup.booleanAttr(method, "dev.simplified.annotations.Collector", "compute", false);
            String v = lookup.stringAttr(method, "dev.simplified.annotations.Collector", "singularMethodName", "");
            b.singularName = v.isEmpty() ? defaultSingular(b.name) : v;
        }
        AnnotationMirror rule = lookup.findMirror(method, "dev.simplified.annotations.BuildRule");
        if (rule != null) {
            b.ignored = lookup.booleanAttr(rule, "ignore", false);
        }

        return new FieldSpec(b);
    }

    private static void classifyType(Builder b) {
        TypeKind kind = b.type.getKind();
        b.isPrimitive = kind.isPrimitive();
        b.isBoolean = kind == TypeKind.BOOLEAN;
        b.isArray = kind == TypeKind.ARRAY;

        if (b.isArray) {
            ArrayType array = (ArrayType) b.type;
            b.collectionElement = array.getComponentType().toString();
        } else if (kind == TypeKind.DECLARED) {
            DeclaredType declared = (DeclaredType) b.type;
            String raw = stripTypeArgs(declared.toString());
            List<? extends TypeMirror> args = declared.getTypeArguments();

            if ("java.lang.String".equals(raw)) {
                b.isString = true;
            } else if (OPTIONAL_FQN.equals(raw)) {
                b.isOptional = true;
                b.optionalInner = args.isEmpty() ? "java.lang.Object" : args.get(0).toString();
            } else if (LIST_TYPES.contains(raw)) {
                b.isListLike = true;
                b.collectionElement = args.isEmpty() ? "java.lang.Object" : args.get(0).toString();
            } else if (SET_TYPES.contains(raw)) {
                b.isListLike = true;
                b.isSet = true;
                b.collectionElement = args.isEmpty() ? "java.lang.Object" : args.get(0).toString();
            } else if (MAP_TYPES.contains(raw)) {
                b.isMap = true;
                b.mapKey = args.isEmpty() ? "java.lang.Object" : args.get(0).toString();
                b.mapValue = args.size() < 2 ? "java.lang.Object" : args.get(1).toString();
            }
        }
    }

    public static FieldSpec from(VariableElement element, AnnotationLookup lookup, SourceIntrospector introspector) {
        Builder b = new Builder();
        b.element = element;
        b.name = element.getSimpleName().toString();
        b.type = element.asType();
        b.typeDisplay = element.asType().toString();

        // Nullability
        b.notNull = lookup.hasAnnotation(element, "org.jetbrains.annotations.NotNull");
        b.nullable = lookup.hasAnnotation(element, "org.jetbrains.annotations.Nullable");

        TypeKind kind = b.type.getKind();
        b.isPrimitive = kind.isPrimitive();
        b.isBoolean = kind == TypeKind.BOOLEAN;
        b.isArray = kind == TypeKind.ARRAY;

        if (b.isArray) {
            ArrayType array = (ArrayType) b.type;
            b.collectionElement = array.getComponentType().toString();
        } else if (kind == TypeKind.DECLARED) {
            DeclaredType declared = (DeclaredType) b.type;
            String raw = stripTypeArgs(declared.toString());
            List<? extends TypeMirror> args = declared.getTypeArguments();

            if ("java.lang.String".equals(raw)) {
                b.isString = true;
            } else if (OPTIONAL_FQN.equals(raw)) {
                b.isOptional = true;
                b.optionalInner = args.isEmpty() ? "java.lang.Object" : args.get(0).toString();
            } else if (LIST_TYPES.contains(raw)) {
                b.isListLike = true;
                b.collectionElement = args.isEmpty() ? "java.lang.Object" : args.get(0).toString();
            } else if (SET_TYPES.contains(raw)) {
                b.isListLike = true;
                b.isSet = true;
                b.collectionElement = args.isEmpty() ? "java.lang.Object" : args.get(0).toString();
            } else if (MAP_TYPES.contains(raw)) {
                b.isMap = true;
                b.mapKey = args.isEmpty() ? "java.lang.Object" : args.get(0).toString();
                b.mapValue = args.size() < 2 ? "java.lang.Object" : args.get(1).toString();
            }
        }

        // Companion annotations
        b.formattable = lookup.hasAnnotation(element, "dev.simplified.annotations.Formattable");
        b.negateName = lookup.stringAttr(element, "dev.simplified.annotations.Negate", "value", null);
        if (lookup.hasAnnotation(element, "dev.simplified.annotations.Collector")) {
            b.collector = true;
            b.singular = lookup.booleanAttr(element, "dev.simplified.annotations.Collector", "singular", false);
            b.clearable = lookup.booleanAttr(element, "dev.simplified.annotations.Collector", "clearable", false);
            b.compute = lookup.booleanAttr(element, "dev.simplified.annotations.Collector", "compute", false);
            String v = lookup.stringAttr(element, "dev.simplified.annotations.Collector", "singularMethodName", "");
            b.singularName = v.isEmpty() ? defaultSingular(b.name) : v;
        }
        // @BuildRule is the single entry point for retainInit / ignore /
        // flag / obtainVia. flag() lives in the class-file bytecode too but
        // is only read at runtime by BuildFlagValidator - APT doesn't
        // decompose its nested attributes.
        AnnotationMirror rule = lookup.findMirror(element, "dev.simplified.annotations.BuildRule");
        if (rule != null) {
            b.ignored = lookup.booleanAttr(rule, "ignore", false);
            b.builderDefault = lookup.booleanAttr(rule, "retainInit", false);
            if (b.builderDefault && introspector != null) {
                SourceIntrospector.InitializerInfo info = introspector.readFieldInitializer(element);
                if (info != null) {
                    b.sourceInitializer = info.text();
                    b.initializerImports = new LinkedHashSet<>(info.typeImports());
                    b.sourceInitializerTree = info.tree();
                }
            }
            AnnotationMirror via = lookup.nestedAnnotationValue(rule, "obtainVia");
            if (via != null) {
                String m = lookup.stringAttr(via, "method", "");
                String f = lookup.stringAttr(via, "field", "");
                b.obtainViaMethod = m.isEmpty() ? null : m;
                b.obtainViaField = f.isEmpty() ? null : f;
                b.obtainViaStatic = lookup.booleanAttr(via, "isStatic", false);
            }
        }

        return new FieldSpec(b);
    }

    private static String stripTypeArgs(String typeName) {
        int lt = typeName.indexOf('<');
        return lt < 0 ? typeName : typeName.substring(0, lt);
    }

    private static String defaultSingular(String fieldName) {
        if (fieldName.endsWith("ies") && fieldName.length() > 3) return fieldName.substring(0, fieldName.length() - 3) + "y";
        if (fieldName.endsWith("es") && fieldName.length() > 2) return fieldName.substring(0, fieldName.length() - 2);
        if (fieldName.endsWith("s") && fieldName.length() > 1) return fieldName.substring(0, fieldName.length() - 1);
        return fieldName;
    }

    private static final class Builder {
        String name;
        VariableElement element;
        TypeMirror type;
        String typeDisplay;
        boolean notNull, nullable;
        boolean isBoolean, isString, isPrimitive, isArray;
        boolean isOptional;
        String optionalInner;
        boolean isListLike, isSet, isMap;
        String collectionElement, mapKey, mapValue;
        boolean formattable;
        String negateName;
        boolean collector, singular, clearable, compute;
        String singularName;
        boolean ignored, builderDefault;
        String sourceInitializer;
        Set<String> initializerImports;
        com.sun.source.tree.Tree sourceInitializerTree;
        String obtainViaMethod, obtainViaField;
        boolean obtainViaStatic;
    }

}
