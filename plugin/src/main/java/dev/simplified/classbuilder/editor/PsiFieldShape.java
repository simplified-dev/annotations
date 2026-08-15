package dev.simplified.classbuilder.editor;

import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import dev.simplified.classbuilder.apt.FieldSpec;
import dev.simplified.classbuilder.apt.SetterScheme;
import org.jetbrains.annotations.Nullable;

/**
 * PSI-side analogue of {@link FieldSpec}: the shape
 * vocabulary the augment provider needs to synthesise setters. FieldSpec
 * itself is tied to {@code javax.lang.model} (APT-only); this mirror is
 * derived from PSI so editor-time synthesis has no APT dependency.
 *
 * <p>Shape coverage mirrors {@code FieldMutators} so editor autocompletion
 * surfaces the same method matrix users will see after the first build:
 * plain, boolean zero-arg/typed pair plus optional {@code @Negate} inverse
 * pair, {@code Optional} nullable-raw/wrapped pair plus optional
 * {@code @Formattable} overload, {@code @Collector} collection/map
 * add/put/clear, array varargs, String {@code @Formattable} overload.
 */
public final class PsiFieldShape {

    public final String name;
    public final PsiType type;

    /**
     * The setter patterns this slot's members are named from - the target's,
     * overridden by a {@code @SetterNames} written on the slot itself. Mirrors
     * {@link FieldSpec#setters}, and for the same reason: with a per-slot
     * override the config's scheme is no longer the answer for every slot, so a
     * synthesiser reaching past this one would put the target's name in
     * completion where the build emits the slot's.
     */
    public final SetterScheme setters;

    public final boolean isBoolean;
    public final boolean isString;
    public final boolean isArray;
    public final PsiType arrayComponent;
    public final boolean isOptional;
    public final PsiType optionalInner;
    public final boolean isOptionalString;

    public final boolean isListLike;
    public final boolean isSet;
    public final boolean isMap;
    /**
     * Recognised as a {@code Collection}/{@code Map} subtype by a supertype
     * walk rather than an exact {@code java.util.*} match (e.g.
     * {@code dev.simplified.collection.ConcurrentList}). Mirrors
     * {@link FieldSpec#isCustomContainer}.
     */
    public final boolean isCustomContainer;
    public final PsiType collectionElement;
    public final PsiType mapKey;
    public final PsiType mapValue;

    // Companion annotations
    public final boolean nullable;
    public final boolean notNull;
    public final boolean formattable;
    public final String negateName;

    /**
     * True when the field carries {@code @Collector} - enables varargs +
     * iterable replace overloads for collection/map fields. Independent of
     * the more granular opt-ins below.
     */
    public final boolean collector;

    /** True when the field carries {@code @Collector(singular = true)}. */
    public final boolean singular;

    /** Name of the single-element add/put method. Null when not resolvable. */
    public final String singularName;

    /** True when the field carries {@code @Collector(clearable = true)}. */
    public final boolean clearable;

    /**
     * True when the field carries {@code @Collector(compute = true)}. Only
     * meaningful for map fields; ignored for non-maps.
     */
    public final boolean compute;

    /**
     * True when the field carries {@code @Collector(append = true)}, so the bulk
     * setters add to the container rather than replace it.
     *
     * <p>Changes no signature, only what the body does, so the editor's
     * synthesised members are the same either way. Carried here because the
     * inspections reason about whether a bulk call discards a declared
     * initializer.
     */
    public final boolean append;

    /** True when the field carries {@code @Collector(removable = true)}. */
    public final boolean removable;

    /**
     * The no-argument method named by {@code @Collector(key)}, called on the
     * map's value type to supply each entry's key, or {@code null} when the put
     * takes a key of its own. Unlike the other collector opt-ins this one moves a
     * signature - the put drops its key parameter - so the editor has to read it.
     */
    public final String keyMethod;

    /**
     * True when the field carries {@code @BuildFlag(nonNull = true)}. Drives the
     * editor-side emission of {@code @NotNull} on the matching setter parameter
     * so IntelliJ's null-flow analysis flags {@code null} arguments immediately,
     * without waiting for a build round.
     */
    public final boolean nonNullByBuildFlag;

    /**
     * True when the field carries {@code @Lazy}. Drives the dual-setter shape
     * (value form + Supplier form) on the synthesised Builder PSI.
     */
    public final boolean lazy;

    /**
     * True when the slot is a {@code @BuilderSeed} parameter - supplied to
     * {@code builder(...)} and emitting no setter. Only ever set on a slot
     * derived from a constructor or factory parameter, that being the one place
     * the annotation can be written.
     */
    public final boolean seed;

    /**
     * The slot's {@code @AssignVia} transforms, in source order. Mirrors
     * {@link FieldSpec#assignVia}.
     *
     * <p>A direct one changes only what a setter's body does, so it moves no
     * signature and the synthesised surface is the same either way. Every other
     * adds the overload it names, which is why the list is carried at all.
     */
    public final java.util.List<AssignTransform> assignVia;

    /**
     * Source element whose Javadoc the generated setter should surface.
     * Typically the backing field or record component. Null when no Javadoc
     * owner is available (e.g. interface accessor extraction paths).
     */
    public final @Nullable PsiDocCommentOwner docSource;

    /**
     * One {@code @AssignVia} reaching a slot, as the editor needs it - the
     * parameter type the transform declares, and whether that is the slot's own.
     *
     * @param paramType the transform's declared parameter type
     * @param direct whether it is the slot's own type, in which case the
     *     transform shapes the setter the slot already has
     */
    public record AssignTransform(PsiType paramType, boolean direct) { }

    PsiFieldShape(Builder b) {
        this.name = b.name;
        this.type = b.type;
        this.setters = b.setters;
        this.isBoolean = b.isBoolean;
        this.isString = b.isString;
        this.isArray = b.isArray;
        this.arrayComponent = b.arrayComponent;
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
        this.nullable = b.nullable;
        this.notNull = b.notNull;
        this.formattable = b.formattable;
        this.negateName = b.negateName;
        this.collector = b.collector;
        this.singular = b.singular;
        this.singularName = b.singularName;
        this.clearable = b.clearable;
        this.append = b.append;
        this.compute = b.compute;
        this.removable = b.removable;
        this.keyMethod = b.keyMethod;
        this.nonNullByBuildFlag = b.nonNullByBuildFlag;
        this.lazy = b.lazy;
        this.seed = b.seed;
        this.assignVia = b.assignVia == null ? java.util.List.of() : b.assignVia;
        this.docSource = b.docSource;
    }

    /** Classifies a {@link PsiType} into the shape fields used for setter dispatch. */
    static Builder classify(String name, PsiType type) {
        Builder b = new Builder();
        b.name = name;
        b.type = type;
        b.isBoolean = PsiTypes.booleanType().equals(type);

        if (type instanceof PsiArrayType array) {
            b.isArray = true;
            b.arrayComponent = array.getComponentType();
            return b;
        }

        if (!(type instanceof PsiClassType classType)) return b;

        String rawFqn = rawFqn(classType);
        PsiType[] params = classType.getParameters();

        if (isString(rawFqn)) {
            b.isString = true;
        } else if (isOptional(rawFqn)) {
            b.isOptional = true;
            b.optionalInner = params.length == 0 ? null : params[0];
            b.isOptionalString = b.optionalInner instanceof PsiClassType inner
                && isString(rawFqn(inner));
        } else if (isListLike(rawFqn)) {
            b.isListLike = true;
            b.isSet = isSet(rawFqn);
            b.collectionElement = params.length == 0 ? null : params[0];
        } else if (isMap(rawFqn)) {
            b.isMap = true;
            b.mapKey = params.length == 0 ? null : params[0];
            b.mapValue = params.length < 2 ? null : params[1];
        } else {
            classifyCustomContainer(b, classType);
        }
        return b;
    }

    /**
     * Fallback for a project-specific container the exact-FQN matchers miss:
     * recognise any {@code Collection}/{@code Map} subtype by walking its
     * supertypes, reading the element/key/value types off the matched java.util
     * supertype. Mirrors the {@code Types}-based walk in
     * {@link FieldSpec}. Flagged
     * {@link Builder#isCustomContainer} so the extractor mirrors the APT's
     * initializer requirement for {@code @Collector}.
     */
    private static void classifyCustomContainer(Builder b, PsiClassType classType) {
        if (InheritanceUtil.isInheritor(classType, CommonClassNames.JAVA_UTIL_MAP)) {
            b.isMap = true;
            b.isCustomContainer = true;
            b.mapKey = PsiUtil.substituteTypeParameter(classType, CommonClassNames.JAVA_UTIL_MAP, 0, false);
            b.mapValue = PsiUtil.substituteTypeParameter(classType, CommonClassNames.JAVA_UTIL_MAP, 1, false);
        } else if (InheritanceUtil.isInheritor(classType, CommonClassNames.JAVA_UTIL_SET)) {
            b.isListLike = true;
            b.isSet = true;
            b.isCustomContainer = true;
            b.collectionElement = PsiUtil.substituteTypeParameter(classType, CommonClassNames.JAVA_UTIL_COLLECTION, 0, false);
        } else if (InheritanceUtil.isInheritor(classType, CommonClassNames.JAVA_UTIL_COLLECTION)) {
            b.isListLike = true;
            b.isCustomContainer = true;
            b.collectionElement = PsiUtil.substituteTypeParameter(classType, CommonClassNames.JAVA_UTIL_COLLECTION, 0, false);
        }
    }

    /**
     * Best-effort FQN resolution. Prefers the resolved {@link PsiClass}'s
     * {@code getQualifiedName()}; falls back to stripping generics off the
     * canonical text so classification still works when
     * {@link PsiClassType#resolve()} returns null (common in mock-JDK
     * fixtures). Note the fallback may return the simple name rather than
     * the FQN, so matchers below accept both forms.
     */
    private static String rawFqn(PsiClassType classType) {
        var resolved = classType.resolve();
        if (resolved != null && resolved.getQualifiedName() != null) {
            return resolved.getQualifiedName();
        }
        String canonical = classType.getCanonicalText();
        if (canonical == null) return null;
        int lt = canonical.indexOf('<');
        return lt < 0 ? canonical : canonical.substring(0, lt);
    }

    private static boolean isString(String name) {
        return "java.lang.String".equals(name) || "String".equals(name);
    }

    private static boolean isOptional(String name) {
        return "java.util.Optional".equals(name) || "Optional".equals(name);
    }

    private static boolean isListLike(String name) {
        return "java.util.List".equals(name) || "java.util.ArrayList".equals(name)
            || "java.util.LinkedList".equals(name) || "java.util.Collection".equals(name)
            || "java.lang.Iterable".equals(name)
            || "List".equals(name) || "ArrayList".equals(name)
            || "LinkedList".equals(name) || "Collection".equals(name)
            || "Iterable".equals(name) || isSet(name);
    }

    private static boolean isSet(String name) {
        return "java.util.Set".equals(name) || "java.util.HashSet".equals(name)
            || "java.util.LinkedHashSet".equals(name) || "java.util.TreeSet".equals(name)
            || "Set".equals(name) || "HashSet".equals(name)
            || "LinkedHashSet".equals(name) || "TreeSet".equals(name);
    }

    private static boolean isMap(String name) {
        return "java.util.Map".equals(name) || "java.util.HashMap".equals(name)
            || "java.util.LinkedHashMap".equals(name) || "java.util.TreeMap".equals(name)
            || "Map".equals(name) || "HashMap".equals(name)
            || "LinkedHashMap".equals(name) || "TreeMap".equals(name);
    }

    /** Mutable intermediate populated by {@link PsiFieldShapeExtractor} before freeze. */
    static final class Builder {
        String name;
        PsiType type;
        SetterScheme setters;
        boolean isBoolean, isString, isArray;
        PsiType arrayComponent;
        boolean isOptional, isOptionalString;
        PsiType optionalInner;
        boolean isListLike, isSet, isMap, isCustomContainer;
        PsiType collectionElement, mapKey, mapValue;
        boolean nullable, notNull, formattable;
        String negateName;
        boolean collector, singular, clearable, compute, append, removable;
        String singularName, keyMethod;
        boolean nonNullByBuildFlag;
        boolean lazy;
        boolean seed;
        java.util.List<AssignTransform> assignVia;
        @Nullable PsiDocCommentOwner docSource;

        PsiFieldShape build() {
            return new PsiFieldShape(this);
        }
    }

}
