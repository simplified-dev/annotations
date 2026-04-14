package dev.sbs.classbuilder.apt;

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
 * All classification the emitter needs happens once in {@link #from(VariableElement, AnnotationLookup)},
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
    public final boolean formattableNullable;
    public final String negateName;                 // null if no @Negate
    public final String singularName;               // null if no @Singular; filled from @Singular.value or defaulted
    public final boolean ignored;                   // @BuilderIgnore or listed in @ClassBuilder.exclude
    public final boolean builderDefault;
    public final String sourceInitializer;          // copied source text of the field's declared initializer
    public final Set<String> initializerImports;    // type FQNs referenced by sourceInitializer
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
        this.formattableNullable = b.formattableNullable;
        this.negateName = b.negateName;
        this.singularName = b.singularName;
        this.ignored = b.ignored;
        this.builderDefault = b.builderDefault;
        this.sourceInitializer = b.sourceInitializer;
        this.initializerImports = b.initializerImports == null ? Set.of() : b.initializerImports;
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
     * <p>{@code @BuilderDefault} / {@code @ObtainVia} do not apply to interface
     * accessors (the annotations target fields only), so no {@link SourceIntrospector}
     * is threaded through this path.
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

        b.formattable = lookup.hasAnnotation(method, "dev.sbs.annotation.Formattable");
        b.formattableNullable = b.formattable
            && lookup.booleanAttr(method, "dev.sbs.annotation.Formattable", "nullable", false);
        b.negateName = lookup.stringAttr(method, "dev.sbs.annotation.Negate", "value", null);
        if (lookup.hasAnnotation(method, "dev.sbs.annotation.Singular")) {
            String v = lookup.stringAttr(method, "dev.sbs.annotation.Singular", "value", "");
            b.singularName = v.isEmpty() ? defaultSingular(b.name) : v;
        }
        b.ignored = lookup.hasAnnotation(method, "dev.sbs.annotation.BuilderIgnore");

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
        b.formattable = lookup.hasAnnotation(element, "dev.sbs.annotation.Formattable");
        b.formattableNullable = b.formattable
            && lookup.booleanAttr(element, "dev.sbs.annotation.Formattable", "nullable", false);
        b.negateName = lookup.stringAttr(element, "dev.sbs.annotation.Negate", "value", null);
        if (lookup.hasAnnotation(element, "dev.sbs.annotation.Singular")) {
            String v = lookup.stringAttr(element, "dev.sbs.annotation.Singular", "value", "");
            b.singularName = v.isEmpty() ? defaultSingular(b.name) : v;
        }
        b.ignored = lookup.hasAnnotation(element, "dev.sbs.annotation.BuilderIgnore");
        b.builderDefault = lookup.hasAnnotation(element, "dev.sbs.annotation.BuilderDefault");
        if (b.builderDefault && introspector != null) {
            SourceIntrospector.InitializerInfo info = introspector.readFieldInitializer(element);
            if (info != null) {
                b.sourceInitializer = info.text();
                b.initializerImports = new LinkedHashSet<>(info.typeImports());
            }
        }
        if (lookup.hasAnnotation(element, "dev.sbs.annotation.ObtainVia")) {
            b.obtainViaMethod = lookup.stringAttr(element, "dev.sbs.annotation.ObtainVia", "method", "");
            b.obtainViaField = lookup.stringAttr(element, "dev.sbs.annotation.ObtainVia", "field", "");
            b.obtainViaStatic = lookup.booleanAttr(element, "dev.sbs.annotation.ObtainVia", "isStatic", false);
            if (b.obtainViaMethod.isEmpty()) b.obtainViaMethod = null;
            if (b.obtainViaField.isEmpty()) b.obtainViaField = null;
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
        boolean formattable, formattableNullable;
        String negateName;
        String singularName;
        boolean ignored, builderDefault;
        String sourceInitializer;
        Set<String> initializerImports;
        String obtainViaMethod, obtainViaField;
        boolean obtainViaStatic;
    }

}
