package dev.sbs.classbuilder.editor;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiType;

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
 * {@code @Formattable} overload, {@code @Singular} collection/map
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
    public final boolean formattable;
    public final boolean formattableNullable;
    public final String negateName;
    public final String singularName;

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
        this.formattable = b.formattable;
        this.formattableNullable = b.formattableNullable;
        this.negateName = b.negateName;
        this.singularName = b.singularName;
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
        boolean nullable, formattable, formattableNullable;
        String negateName;
        String singularName;

        PsiFieldShape build() {
            return new PsiFieldShape(this);
        }
    }

}
