package dev.simplified.classbuilder.apt;
import dev.simplified.shared.apt.AnnotationLookup;
import dev.simplified.shared.apt.SourceIntrospector;
import dev.simplified.shared.apt.TypeNames;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
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
 * All classification the emitter needs happens once in
 * {@link #from(VariableElement, AnnotationLookup, SourceIntrospector, Types, boolean, SetterScheme)},
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

    /**
     * The setter patterns this slot's members are named from - the target's,
     * overridden by a {@code @SetterNames} written on the slot itself.
     *
     * <p>Resolved once here rather than read from the config at each emitter,
     * because a per-slot override means "the config's scheme" is no longer the
     * answer for every slot and an emitter reaching past this one would silently
     * mint the target's name instead.
     */
    public final SetterScheme setters;

    public final boolean notNull;
    public final boolean nullable;

    public final boolean isBoolean;
    public final boolean isString;
    public final boolean isPrimitive;
    public final boolean isArray;
    // The field carries the `final` modifier. A final field whose initializer is
    // retained as a builder default (@BuilderDefault) must have that
    // initializer stripped to a blank final, or the builder-called constructor
    // cannot assign it ("cannot assign a value to final variable").
    public final boolean isFinal;

    public final boolean isOptional;
    public final String optionalInner;              // null unless isOptional
    // Whether optionalInner is java.lang.String, decided from the TypeMirror
    // rather than by comparing optionalInner itself. That string is a display
    // form: it renders any type annotation on the argument inline, so
    // Optional<@NotNull String> does not equal "java.lang.String" and the
    // @Formattable overload silently went missing on exactly the fields that
    // carry a nullness annotation.
    public final boolean isOptionalString;

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
    public final boolean append;                    // @Collector(append = true) - bulk setters add rather than replace
    public final boolean removable;                 // @Collector(removable = true) - single-element remove
    /**
     * The no-argument method named by {@code @Collector(key)}, called on the map's
     * value type to supply each entry's key, or {@code null} when the put takes a
     * key of its own.
     */
    public final String keyMethod;
    public final boolean ignored;                   // @BuilderIgnore or listed in @ClassBuilder.exclude
    public final boolean lazy;                       // @Lazy: storage rewritten to a deferred holder, getter synthesised
    /**
     * {@code @BuilderSeed} on a constructor or factory parameter - the slot is
     * supplied to {@code builder(...)} and emits no setter. Always false on the
     * field and interface-accessor paths, where the annotation cannot be
     * written.
     */
    public final boolean seed;
    public final boolean builderDefault;
    /** True only when the field itself carried {@code @BuilderDefault}, not when it inherited the class policy. */
    public final boolean builderDefaultExplicit;
    /**
     * The static method named by {@code @BuilderDefault(provider)}, or
     * {@code null} when none is written. Supplies the slot's default where there
     * is no initializer to retain, which is every record component.
     */
    public final String defaultProvider;
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
    /**
     * The {@code @AssignVia} transforms written on the slot, in source order.
     * Empty when it carries none.
     */
    public final java.util.List<AssignTransform> assignVia;
    /**
     * The {@code @BuildFlag} mirror to copy onto a generated field, or
     * {@code null} when the accessor carries none. Populated only by
     * {@link #fromInterfaceAccessor}: on a class or record the annotation is
     * already written on the field the validator reads, and nothing re-emits
     * that declaration, so there is nothing to carry.
     */
    public final AnnotationMirror buildFlag;

    private FieldSpec(Builder b) {
        this.name = b.name;
        this.element = b.element;
        this.type = b.type;
        this.typeDisplay = b.typeDisplay;
        this.setters = b.setters;
        this.notNull = b.notNull;
        this.nullable = b.nullable;
        this.isBoolean = b.isBoolean;
        this.isString = b.isString;
        this.isPrimitive = b.isPrimitive;
        this.isArray = b.isArray;
        this.isFinal = b.isFinal;
        this.isOptional = b.isOptional;
        this.optionalInner = b.optionalInner;
        this.isOptionalString = b.isOptionalString;
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
        this.append = b.append;
        this.removable = b.removable;
        this.keyMethod = b.keyMethod;
        this.compute = b.compute;
        this.ignored = b.ignored;
        this.lazy = b.lazy;
        this.seed = b.seed;
        this.builderDefault = b.builderDefault;
        this.builderDefaultExplicit = b.builderDefaultExplicit;
        this.defaultProvider = b.defaultProvider;
        this.sourceInitializer = b.sourceInitializer;
        this.initializerImports = b.initializerImports == null ? Set.of() : b.initializerImports;
        this.sourceInitializerTree = b.sourceInitializerTree;
        this.obtainViaMethod = b.obtainViaMethod;
        this.obtainViaField = b.obtainViaField;
        this.obtainViaStatic = b.obtainViaStatic;
        this.assignVia = b.assignVia == null ? java.util.List.of() : b.assignVia;
        this.buildFlag = b.buildFlag;
    }

    /**
     * One {@code @AssignVia} reaching a slot - the static method a setter's
     * argument passes through on the way in.
     *
     * @param method the named method
     * @param paramDisplay the parameter type it declares, rendered for
     *     re-parsing, or {@code null} when the name resolves to no single
     *     one-argument method
     * @param direct whether that parameter type is the slot's own, which is what
     *     tells shaping the ordinary setter from adding an overload beside it
     */
    public record AssignTransform(String method, String paramDisplay, boolean direct) {

        /** Whether this transform names a method the emitters can call. */
        public boolean resolved() {
            return paramDisplay != null;
        }

    }

    /**
     * The transform shaping the slot's ordinary value-taking setter, or
     * {@code null} when every declared one takes a type of its own and therefore
     * adds an overload instead.
     *
     * @return the direct transform's method name, or {@code null}
     */
    public String directAssign() {
        for (AssignTransform transform : assignVia) {
            if (transform.direct() && transform.resolved()) return transform.method();
        }
        return null;
    }

    /** Whether this field uses {@code is*} setters (booleans) vs the configured prefix. */
    boolean usesBooleanPrefix() {
        return isBoolean;
    }

    /**
     * Whether the slot has a default to seed from - a captured initializer, or a
     * method named by {@code @BuilderDefault(provider)}.
     *
     * <p>One reading for both, because everything downstream of the seeding
     * treats them identically: the value is fetched through the same
     * {@code $default$} provider, copied before a {@code @Collector} container's
     * setters can mutate it, and discarded by a wholesale replace.
     *
     * @return whether anything seeds this slot
     */
    public boolean hasDefault() {
        return defaultProvider != null || (sourceInitializer != null && !sourceInitializer.isEmpty());
    }

    /**
     * Factory for interface abstract-accessor methods. The method name becomes the
     * field name; the return type becomes the field type. No VariableElement is
     * retained (the underlying element is a method), so {@link #element} is null
     * and callers that need source reporting should fall back to the type element.
     *
     * <p>{@code @BuilderDefault} and {@code @ObtainVia} do not apply to
     * interface accessors (the annotations target fields only), so no
     * {@link SourceIntrospector} is threaded through this path - only
     * {@code @BuilderIgnore} is honoured here.
     *
     * <p>{@code @BuildFlag} does apply, and is kept as its raw mirror rather
     * than as parsed attributes: it is not read here at all, only copied onto
     * the generated {@code <Name>Impl} field, so preserving exactly what the
     * author wrote beats round-tripping it through five typed accessors.
     */
    public static FieldSpec fromInterfaceAccessor(ExecutableElement method, AnnotationLookup lookup,
                                                  Types typeUtils, SetterScheme setters) {
        Builder b = new Builder();
        b.element = null;
        b.name = method.getSimpleName().toString();
        b.type = method.getReturnType();
        b.typeDisplay = b.type.toString();
        b.setters = resolveSetters(method, lookup, setters);

        b.notNull = lookup.hasAnnotation(method, "org.jetbrains.annotations.NotNull");
        b.nullable = lookup.hasAnnotation(method, "org.jetbrains.annotations.Nullable");
        classifyType(b, typeUtils);

        readSetterCompanions(b, method, lookup);
        b.ignored = lookup.hasAnnotation(method, "dev.simplified.annotations.BuilderIgnore");
        b.buildFlag = lookup.findMirror(method, "dev.simplified.annotations.BuildFlag");

        return new FieldSpec(b);
    }

    /**
     * Factory for a parameter of a {@code @ClassBuilder}-annotated constructor
     * or static factory. The parameter name becomes the slot name and its
     * declared type the slot type, so the setter matrix reads exactly as it
     * would off a field of the same shape.
     *
     * <p>Only the companions that shape a setter apply - {@code @Collector},
     * {@code @Negate}, {@code @Formattable} - plus {@code @BuilderSeed}, which
     * withdraws the setter entirely. {@code @BuilderDefault},
     * {@code @BuilderIgnore} and {@code @ObtainVia} have nothing to act on: a
     * parameter carries no initializer to retain, every parameter has to be
     * passed, and there is no instance to read a slot back off. No
     * {@link SourceIntrospector} is threaded through for the same reason.
     *
     * <p>{@code @BuildFlag} is not read here either, and that is where the
     * constraint lives rather than where it is missing: the validator resolves
     * the flagged fields of the instance {@code build()} produced, so the
     * annotation belongs on those fields and is found there whichever member
     * constructed them.
     *
     * @param parameter the declared parameter
     * @param lookup the annotation reader
     * @param typeUtils type utilities, for the custom-container supertype walk
     * @return the slot IR for this parameter
     */
    public static FieldSpec fromParameter(VariableElement parameter, AnnotationLookup lookup,
                                          Types typeUtils, SetterScheme setters) {
        Builder b = new Builder();
        b.element = parameter;
        b.name = parameter.getSimpleName().toString();
        b.type = parameter.asType();
        b.typeDisplay = b.type.toString();
        b.isFinal = parameter.getModifiers().contains(Modifier.FINAL);
        b.setters = resolveSetters(parameter, lookup, setters);

        b.notNull = lookup.hasAnnotation(parameter, "org.jetbrains.annotations.NotNull");
        b.nullable = lookup.hasAnnotation(parameter, "org.jetbrains.annotations.Nullable");
        classifyType(b, typeUtils);

        readSetterCompanions(b, parameter, lookup);
        b.assignVia = readAssignVia(b, parameter, lookup, typeUtils);
        b.seed = lookup.hasAnnotation(parameter, "dev.simplified.annotations.BuilderSeed");

        return new FieldSpec(b);
    }

    /**
     * Reads the three companions that shape a setter - {@code @Formattable},
     * {@code @Negate} and {@code @Collector} - off whichever element declares
     * the slot. One reading for all three factories, so a slot derived from a
     * parameter cannot come out with a different setter matrix from a field of
     * the same shape.
     */
    /**
     * Resolves the slot's setter patterns: the target's, overridden by a
     * {@code @SetterNames} written on the slot itself.
     *
     * @param owner the field, component or parameter declaring the slot
     * @param lookup the annotation reader
     * @param base the target's resolved scheme
     * @return the scheme this slot's members are named from
     */
    private static SetterScheme resolveSetters(Element owner, AnnotationLookup lookup,
                                               SetterScheme base) {
        AnnotationMirror written =
            lookup.findMirror(owner, "dev.simplified.annotations.SetterNames");
        if (written == null) return base;
        return SetterScheme.override(base,
            lookup.stringAttr(written, "set", null),
            lookup.stringAttr(written, "flag", null),
            lookup.stringAttr(written, "add", null),
            lookup.stringAttr(written, "put", null),
            lookup.stringAttr(written, "compute", null),
            lookup.stringAttr(written, "clear", null),
            lookup.stringAttr(written, "remove", null));
    }

    /**
     * Reads the slot's {@code @AssignVia} transforms and resolves each named
     * method against the type declaring the slot.
     *
     * <p>A name that matches no single one-argument method is kept rather than
     * dropped, carrying a null parameter type: the emitters skip it and the
     * processor reports it at the annotation, which is where an author can see
     * it. Dropping it here would leave a written annotation doing nothing.
     *
     * @param b the slot under construction, already carrying its type
     * @param owner the field, component or parameter declaring the slot
     * @param lookup the annotation reader
     * @param typeUtils type utilities, for the erasure comparison
     * @return the declared transforms, in source order
     */
    private static java.util.List<AssignTransform> readAssignVia(Builder b, Element owner,
                                                                 AnnotationLookup lookup,
                                                                 Types typeUtils) {
        java.util.List<AnnotationMirror> written = lookup.repeatedMirrors(owner,
            "dev.simplified.annotations.AssignVia", "dev.simplified.annotations.AssignVia.List");
        if (written.isEmpty()) return java.util.List.of();

        TypeElement declaring = enclosingType(owner);
        java.util.List<AssignTransform> out = new java.util.ArrayList<>(written.size());
        for (AnnotationMirror mirror : written) {
            String method = lookup.stringAttr(mirror, "method", "");
            ExecutableElement resolved = declaring == null ? null : soleUnaryMethod(declaring, method);
            if (resolved == null) {
                out.add(new AssignTransform(method, null, false));
                continue;
            }
            TypeMirror param = resolved.getParameters().get(0).asType();
            out.add(new AssignTransform(method, param.toString(), sameErasure(typeUtils, param, b.type)));
        }
        return out;
    }

    /** The type declaring a field, record component or parameter. */
    private static TypeElement enclosingType(Element owner) {
        Element enclosing = owner.getEnclosingElement();
        while (enclosing != null && !(enclosing instanceof TypeElement)) {
            enclosing = enclosing.getEnclosingElement();
        }
        return (TypeElement) enclosing;
    }

    /**
     * The type's one single-argument method of that name, or {@code null} when
     * it declares none or several. Overload resolution is deliberately not
     * attempted: a name that could mean two methods is reported rather than
     * guessed at.
     */
    private static ExecutableElement soleUnaryMethod(TypeElement target, String name) {
        ExecutableElement found = null;
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            if (!enclosed.getSimpleName().contentEquals(name)) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (method.getParameters().size() != 1) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    /**
     * Whether a transform's parameter type erases to the slot's own, which is
     * what decides that it shapes the ordinary setter rather than adding an
     * overload beside it.
     *
     * <p>Erasure is the comparison because a duplicate method signature is what
     * the answer has to prevent, and Java signatures collide on erasures. It
     * also settles the two shapes a declared type routinely takes that a
     * sameness test does not relate to the slot's - a type-use nullness
     * annotation, and a {@code static} method's own type variable standing where
     * the type's would be.
     */
    private static boolean sameErasure(Types typeUtils, TypeMirror param, TypeMirror slot) {
        if (typeUtils == null) return String.valueOf(param).equals(String.valueOf(slot));
        return typeUtils.isSameType(typeUtils.erasure(param), typeUtils.erasure(slot));
    }

    private static void readSetterCompanions(Builder b, Element owner, AnnotationLookup lookup) {
        b.formattable = lookup.hasAnnotation(owner, "dev.simplified.annotations.Formattable");
        b.negateName = lookup.stringAttr(owner, "dev.simplified.annotations.Negate", "value", null);
        if (!lookup.hasAnnotation(owner, "dev.simplified.annotations.Collector")) return;
        b.collector = true;
        b.singular = lookup.booleanAttr(owner, "dev.simplified.annotations.Collector", "singular", false);
        b.clearable = lookup.booleanAttr(owner, "dev.simplified.annotations.Collector", "clearable", false);
        b.compute = lookup.booleanAttr(owner, "dev.simplified.annotations.Collector", "compute", false);
        b.append = lookup.booleanAttr(owner, "dev.simplified.annotations.Collector", "append", false);
        b.removable = lookup.booleanAttr(owner, "dev.simplified.annotations.Collector", "removable", false);
        String key = lookup.stringAttr(owner, "dev.simplified.annotations.Collector", "key", "");
        b.keyMethod = key.isEmpty() ? null : key;
        String written = lookup.stringAttr(owner, "dev.simplified.annotations.Collector",
            "singularMethodName", "");
        b.singularName = written.isEmpty() ? NamePattern.singularSubject(b.name) : written;
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
        // The FQN comes off the element, never off the rendered type - see
        // TypeNames for why every comparison below silently missed on the
        // near-universal annotated shape. Optional lost its dual setters and
        // its empty default outright; a List or Map survived only by falling
        // through to the supertype walk, which then labelled a plain
        // java.util type a custom container.
        String raw = TypeNames.fqn(declared);
        List<? extends TypeMirror> args = declared.getTypeArguments();

        if ("java.lang.String".equals(raw)) {
            b.isString = true;
        } else if (OPTIONAL_FQN.equals(raw)) {
            b.isOptional = true;
            b.optionalInner = arg(args, 0);
            b.isOptionalString = args.size() == 1 && TypeNames.is(args.get(0), "java.lang.String");
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


    public static FieldSpec from(VariableElement element, AnnotationLookup lookup, SourceIntrospector introspector,
                                 Types typeUtils, boolean classRetainInit, SetterScheme setters) {
        Builder b = new Builder();
        b.element = element;
        b.name = element.getSimpleName().toString();
        b.type = element.asType();
        b.typeDisplay = element.asType().toString();
        b.isFinal = element.getModifiers().contains(Modifier.FINAL);
        b.setters = resolveSetters(element, lookup, setters);

        // Nullability
        b.notNull = lookup.hasAnnotation(element, "org.jetbrains.annotations.NotNull");
        b.nullable = lookup.hasAnnotation(element, "org.jetbrains.annotations.Nullable");

        classifyType(b, typeUtils);

        // Companion annotations
        readSetterCompanions(b, element, lookup);
        b.assignVia = readAssignVia(b, element, lookup, typeUtils);
        b.lazy = lookup.hasAnnotation(element, "dev.simplified.annotations.Lazy");
        b.ignored = lookup.hasAnnotation(element, "dev.simplified.annotations.BuilderIgnore");

        // @BuilderDefault overrides the class-level retainInit policy. Presence
        // of the annotation is the signal: written bare it means "retain" (its
        // own value() default), written @BuilderDefault(false) it opts out, and
        // absent it inherits whatever the class declared. Track the explicit
        // case separately - a missing initializer is only an error when this
        // field asked for retention by name, not when it merely inherited the
        // class-wide policy and has nothing to retain.
        AnnotationMirror declaredDefault =
            lookup.findMirror(element, "dev.simplified.annotations.BuilderDefault");
        if (declaredDefault != null) {
            b.builderDefault = lookup.booleanAttr(declaredDefault, "value", true);
            b.builderDefaultExplicit = b.builderDefault;
            String provider = lookup.stringAttr(declaredDefault, "provider", "");
            b.defaultProvider = provider.isEmpty() ? null : provider;
        } else {
            b.builderDefault = classRetainInit;
        }

        AnnotationMirror via = lookup.findMirror(element, "dev.simplified.annotations.ObtainVia");
        if (via != null) {
            String m = lookup.stringAttr(via, "method", "");
            String f = lookup.stringAttr(via, "field", "");
            b.obtainViaMethod = m.isEmpty() ? null : m;
            b.obtainViaField = f.isEmpty() ? null : f;
            b.obtainViaStatic = lookup.booleanAttr(via, "isStatic", false);
        }

        // Capture the field's declared initializer when it is needed as a
        // builder default - either via retainInit, or implicitly because a
        // @Collector on a custom (non-java.util) container has no
        // `new ArrayList<>()`-style fallback and must build fresh instances
        // from the field's own factory.
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

    private static final class Builder {
        String name;
        VariableElement element;
        TypeMirror type;
        String typeDisplay;
        SetterScheme setters;
        boolean notNull, nullable;
        boolean isBoolean, isString, isPrimitive, isArray, isFinal;
        boolean isOptional;
        String optionalInner;
        boolean isOptionalString;
        boolean isListLike, isSet, isMap, isCustomContainer;
        String collectionElement, mapKey, mapValue;
        boolean formattable;
        String negateName;
        boolean collector, singular, clearable, compute, append, removable;
        String keyMethod;
        String singularName;
        boolean ignored, lazy, seed, builderDefault, builderDefaultExplicit;
        String sourceInitializer, defaultProvider;
        Set<String> initializerImports;
        com.sun.source.tree.Tree sourceInitializerTree;
        String obtainViaMethod, obtainViaField;
        boolean obtainViaStatic;
        java.util.List<AssignTransform> assignVia;
        AnnotationMirror buildFlag;
    }

}
