package dev.sbs.classbuilder.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiRecordComponent;
import dev.sbs.classbuilder.inspect.ClassBuilderConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walks a {@code @ClassBuilder}-annotated PsiClass (or a record) and derives
 * the {@link PsiFieldShape} list the augment provider uses to synthesise
 * setters. Honours {@code @BuilderIgnore} and the annotation's
 * {@code exclude} attribute, and reads companion annotations
 * ({@code @Singular}, {@code @Negate}, {@code @Formattable},
 * {@code @Nullable}) so the synthesised shape matrix lines up with what
 * the APT mutator emits.
 */
final class PsiFieldShapeExtractor {

    private static final String BUILDER_IGNORE_FQN = "dev.sbs.annotation.BuilderIgnore";
    private static final String SINGULAR_FQN = "dev.sbs.annotation.Singular";
    private static final String NEGATE_FQN = "dev.sbs.annotation.Negate";
    private static final String FORMATTABLE_FQN = "dev.sbs.annotation.Formattable";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";

    private PsiFieldShapeExtractor() {
    }

    /**
     * Extracts shapes from a concrete or abstract target class. Record
     * components are handled separately by {@link #fromRecord}.
     */
    static List<PsiFieldShape> fromClass(PsiClass target, Set<String> excluded) {
        List<PsiFieldShape> out = new ArrayList<>();
        for (PsiField field : target.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (field.hasModifierProperty(PsiModifier.TRANSIENT)) continue;
            if (excluded.contains(field.getName())) continue;
            if (hasAnnotation(field, BUILDER_IGNORE_FQN)) continue;
            out.add(buildShape(field, field.getName(), field.getType()));
        }
        return out;
    }

    /** Record-component variant; records expose fields via {@link PsiRecordComponent}. */
    static List<PsiFieldShape> fromRecord(PsiClass record, Set<String> excluded) {
        List<PsiFieldShape> out = new ArrayList<>();
        for (PsiRecordComponent c : record.getRecordComponents()) {
            String name = c.getName();
            if (excluded.contains(name)) continue;
            if (hasAnnotation(c, BUILDER_IGNORE_FQN)) continue;
            out.add(buildShape(c, name, c.getType()));
        }
        return out;
    }

    /**
     * Builds a shape from an annotated owner (field or record component),
     * pulling type classification from the type mirror and companion-
     * annotation state from the owner's annotations.
     */
    private static PsiFieldShape buildShape(PsiModifierListOwner owner, String name, com.intellij.psi.PsiType type) {
        PsiFieldShape.Builder b = PsiFieldShape.classify(name, type);
        b.nullable = hasAnnotation(owner, NULLABLE_FQN);

        PsiAnnotation formattable = findAnnotation(owner, FORMATTABLE_FQN);
        if (formattable != null) {
            b.formattable = true;
            b.formattableNullable = booleanAttr(formattable, "nullable", false);
        }

        PsiAnnotation negate = findAnnotation(owner, NEGATE_FQN);
        if (negate != null) {
            String v = stringAttr(negate, "value", "");
            if (!v.isEmpty()) b.negateName = v;
        }

        PsiAnnotation singular = findAnnotation(owner, SINGULAR_FQN);
        if (singular != null) {
            String v = stringAttr(singular, "value", "");
            b.singularName = v.isEmpty() ? defaultSingular(name) : v;
        }
        return b.build();
    }

    /** True when the element carries the given FQN annotation. */
    static boolean hasAnnotation(PsiModifierListOwner owner, String fqn) {
        return findAnnotation(owner, fqn) != null;
    }

    private static PsiAnnotation findAnnotation(PsiModifierListOwner owner, String fqn) {
        for (PsiAnnotation a : owner.getAnnotations()) {
            if (fqn.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

    private static String stringAttr(PsiAnnotation annotation, String attr, String fallback) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attr);
        if (value instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return fallback;
    }

    private static boolean booleanAttr(PsiAnnotation annotation, String attr, boolean fallback) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue(attr);
        if (value instanceof PsiLiteralExpression lit && lit.getValue() instanceof Boolean b) return b;
        return fallback;
    }

    /** Reads the {@code @ClassBuilder} annotation on {@code target}. */
    static PsiAnnotation classBuilderAnnotation(PsiClass target) {
        for (PsiAnnotation a : target.getAnnotations()) {
            if (ClassBuilderConstants.ANNOTATION_FQN.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

    /**
     * Mirrors {@code FieldSpec.defaultSingular}: peels a trailing plural
     * inflection so {@code entries} becomes {@code entry}, {@code boxes}
     * becomes {@code box}, and {@code tags} becomes {@code tag}.
     */
    private static String defaultSingular(String fieldName) {
        if (fieldName.endsWith("ies") && fieldName.length() > 3)
            return fieldName.substring(0, fieldName.length() - 3) + "y";
        if (fieldName.endsWith("es") && fieldName.length() > 2)
            return fieldName.substring(0, fieldName.length() - 2);
        if (fieldName.endsWith("s") && fieldName.length() > 1)
            return fieldName.substring(0, fieldName.length() - 1);
        return fieldName;
    }

}
