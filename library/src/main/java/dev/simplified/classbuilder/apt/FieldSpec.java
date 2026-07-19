package dev.simplified.classbuilder.apt;
import dev.simplified.shared.apt.SourceIntrospector;
import dev.simplified.shared.apt.AnnotationLookup;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.HashSet;
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
    // Recognised as a collection/map by a supertype walk rather than an exact
    // java.util.* match (e.g. dev.simplified.collection.ConcurrentList). The
    // builder can't `new` such a type, so its fresh instances come from the
    // field's own captured initializer instead of new ArrayList<>()/etc.
    public final boolean isCustomContainer;
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
    public final boolean lazy;                       // @Lazy: storage rewritten to Lazy<T>, getter synthesised
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
        this.isCustomContainer = b.isCustomContainer;
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
        this.lazy = b.lazy;
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
    public static FieldSpec fromInterfaceAccessor(ExecutableElement method, AnnotationLookup lookup, Types typeUtils) {
        Builder b = new Builder();
        b.element = null;
        b.name = method.getSimpleName().toString();
        b.type = method.getReturnType();
        b.typeDisplay = b.type.toString();

        b.notNull = lookup.hasAnnotation(method, "org.jetbrains.annotations.NotNull");
        b.nullable = lookup.hasAnnotation(method, "org.jetbrains.annotations.Nullable");
        classifyType(b, typeUtils);

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

    private static void classifyType(Builder b, Types typeUtils) {
        TypeKind kind = b.type.getKind();
        b.isPrimitive = kind.isPrimitive();
        b.isBoolean = kind == TypeKind.BOOLEAN;
        b.isArray = kind == TypeKind.ARRAY;

        if (b.isArray) {
            ArrayType array = (ArrayType) b.type;
            b.collectionElement = array.getComponentType().toString();
            return;
        }
        if (kind != TypeKind.DECLARED) return;

        DeclaredType declared = (DeclaredType) b.type;
        String raw = stripTypeArgs(declared.toString());
        List<? extends TypeMirror> args = declared.getTypeArguments();

        if ("java.lang.String".equals(raw)) {
            b.isString = true;
        } else if (OPTIONAL_FQN.equals(raw)) {
            b.isOptional = true;
            b.optionalInner = arg(args, 0);
        } else if (LIST_TYPES.contains(raw)) {
            b.isListLike = true;
            b.collectionElement = arg(args, 0);
        } else if (SET_TYPES.contains(raw)) {
            b.isListLike = true;
            b.isSet = true;
            b.collectionElement = arg(args, 0);
        } else if (MAP_TYPES.contains(raw)) {
            b.isMap = true;
            b.mapKey = arg(args, 0);
            b.mapValue = arg(args, 1);
        } else if (typeUtils != null) {
            // Not a known java.util.* container - walk supertypes to recognise
            // a project-specific Collection/Map subtype. Such a type can't be
            // instantiated with new ArrayList<>()/etc, so it is flagged as a
            // custom container and the mutators build fresh instances from the
            // field's own initializer instead.
            classifyCustomContainer(b, typeUtils, declared);
        }
    }

    /**
     * Recognises {@code declared} as a collection/map when it is a subtype of
     * {@code java.util.Map}, {@code Set}, or {@code Collection}, reading the
     * element/key/value types off the matched java.util supertype (with type
     * arguments substituted through the walk). Sets {@link Builder#isCustomContainer}.
     */
    private static void classifyCustomContainer(Builder b, Types typeUtils, DeclaredType declared) {
        DeclaredType map = findSupertype(typeUtils, declared, "java.util.Map", new HashSet<>());
        if (map != null) {
            b.isMap = true;
            b.isCustomContainer = true;
            List<? extends TypeMirror> a = map.getTypeArguments();
            b.mapKey = arg(a, 0);
            b.mapValue = arg(a, 1);
            return;
        }
        DeclaredType set = findSupertype(typeUtils, declared, "java.util.Set", new HashSet<>());
        if (set != null) {
            b.isListLike = true;
            b.isSet = true;
            b.isCustomContainer = true;
            b.collectionElement = arg(set.getTypeArguments(), 0);
            return;
        }
        DeclaredType coll = findSupertype(typeUtils, declared, "java.util.Collection", new HashSet<>());
        if (coll != null) {
            b.isListLike = true;
            b.isCustomContainer = true;
            b.collectionElement = arg(coll.getTypeArguments(), 0);
        }
    }

    /**
     * Depth-first search for the supertype of {@code type} whose erasure is
     * {@code targetFqn}, returning it with type arguments substituted (so the
     * element types read off it are the concrete ones). {@code seen} guards
     * against re-walking a shared ancestor.
     */
    private static DeclaredType findSupertype(Types typeUtils, TypeMirror type, String targetFqn, Set<String> seen) {
        if (!(type instanceof DeclaredType dt)) return null;
        Element element = dt.asElement();
        if (element instanceof TypeElement te) {
            String qn = te.getQualifiedName().toString();
            if (qn.equals(targetFqn)) return dt;
            if (!seen.add(qn)) return null;
        }
        for (TypeMirror sup : typeUtils.directSupertypes(dt)) {
            DeclaredType found = findSupertype(typeUtils, sup, targetFqn, seen);
            if (found != null) return found;
        }
        return null;
    }

    private static String arg(List<? extends TypeMirror> args, int index) {
        return args.size() <= index ? "java.lang.Object" : args.get(index).toString();
    }

    public static FieldSpec from(VariableElement element, AnnotationLookup lookup, SourceIntrospector introspector, Types typeUtils) {
        Builder b = new Builder();
        b.element = element;
        b.name = element.getSimpleName().toString();
        b.type = element.asType();
        b.typeDisplay = element.asType().toString();

        // Nullability
        b.notNull = lookup.hasAnnotation(element, "org.jetbrains.annotations.NotNull");
        b.nullable = lookup.hasAnnotation(element, "org.jetbrains.annotations.Nullable");

        classifyType(b, typeUtils);

        // Companion annotations
        b.formattable = lookup.hasAnnotation(element, "dev.simplified.annotations.Formattable");
        b.negateName = lookup.stringAttr(element, "dev.simplified.annotations.Negate", "value", null);
        b.lazy = lookup.hasAnnotation(element, "dev.simplified.annotations.Lazy");
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
            AnnotationMirror via = lookup.nestedAnnotationValue(rule, "obtainVia");
            if (via != null) {
                String m = lookup.stringAttr(via, "method", "");
                String f = lookup.stringAttr(via, "field", "");
                b.obtainViaMethod = m.isEmpty() ? null : m;
                b.obtainViaField = f.isEmpty() ? null : f;
                b.obtainViaStatic = lookup.booleanAttr(via, "isStatic", false);
            }
        }

        // Capture the field's declared initializer when it is needed as a
        // builder default - either explicitly via @BuildRule(retainInit), or
        // implicitly because a @Collector on a custom (non-java.util)
        // container has no `new ArrayList<>()`-style fallback and must build
        // fresh instances from the field's own factory.
        boolean needsInitializer = b.builderDefault || (b.collector && b.isCustomContainer);
        if (needsInitializer && introspector != null) {
            SourceIntrospector.InitializerInfo info = introspector.readFieldInitializer(element);
            if (info != null) {
                b.sourceInitializer = info.text();
                b.initializerImports = new LinkedHashSet<>(info.typeImports());
                b.sourceInitializerTree = info.tree();
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
        boolean isListLike, isSet, isMap, isCustomContainer;
        String collectionElement, mapKey, mapValue;
        boolean formattable;
        String negateName;
        boolean collector, singular, clearable, compute;
        String singularName;
        boolean ignored, lazy, builderDefault;
        String sourceInitializer;
        Set<String> initializerImports;
        com.sun.source.tree.Tree sourceInitializerTree;
        String obtainViaMethod, obtainViaField;
        boolean obtainViaStatic;
    }

}
