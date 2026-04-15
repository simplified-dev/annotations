package dev.sbs.classbuilder.editor;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.Nullable;

/**
 * PSI-side analogue of {@link dev.sbs.classbuilder.apt.FieldSpec}: the shape
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
     * True when the field carries {@code @BuildFlag(nonNull = true)}. Drives the
     * editor-side emission of {@code @NotNull} on the matching setter parameter
     * so IntelliJ's null-flow analysis flags {@code null} arguments immediately,
     * without waiting for a build round.
     */
    public final boolean nonNullByBuildFlag;

    /**
     * Source element whose Javadoc the generated setter should surface.
     * Typically the backing field or record component. Null when no Javadoc
     * owner is available (e.g. interface accessor extraction paths).
     */
    public final @Nullable PsiDocCommentOwner docSource;

    PsiFieldShape(Builder b) {
        this.name = b.name;
        this.type = b.type;
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
        this.compute = b.compute;
        this.nonNullByBuildFlag = b.nonNullByBuildFlag;
        this.docSource = b.docSource;
    }

    /** Classifies a {@link PsiType} into the shape fields used for setter dispatch. */
    static Builder classify(String name, PsiType type) {
        Builder b = new Builder();
        b.name = name;
        b.type = type;
        b.isBoolean = PsiType.BOOLEAN.equals(type);

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
        }
        return b;
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
        boolean isBoolean, isString, isArray;
        PsiType arrayComponent;
        boolean isOptional, isOptionalString;
        PsiType optionalInner;
        boolean isListLike, isSet, isMap;
        PsiType collectionElement, mapKey, mapValue;
        boolean nullable, notNull, formattable;
        String negateName;
        boolean collector, singular, clearable, compute;
        String singularName;
        boolean nonNullByBuildFlag;
        @Nullable PsiDocCommentOwner docSource;

        PsiFieldShape build() {
            return new PsiFieldShape(this);
        }
    }

}
