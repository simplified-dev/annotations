package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiTypeParameter;
import dev.simplified.classbuilder.apt.SetterScheme;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.shared.psi.WrittenAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walks a {@code @ClassBuilder}-annotated PsiClass (or a record) and derives
 * the {@link PsiFieldShape} list the augment provider uses to synthesise
 * setters. Honours {@code @BuilderIgnore} and the annotation's
 * {@code exclude} attribute, and reads companion annotations
 * ({@code @Collector}, {@code @Negate}, {@code @Formattable},
 * {@code @Nullable}) so the synthesised shape matrix lines up with what
 * the APT mutator emits.
 */
final class PsiFieldShapeExtractor {

    private static final String BUILDER_IGNORE_FQN = ClassBuilderConstants.BUILDER_IGNORE_FQN;
    private static final String BUILD_FLAG_FQN = ClassBuilderConstants.BUILD_FLAG_FQN;
    private static final String COLLECTOR_FQN = ClassBuilderConstants.COLLECTOR_FQN;
    private static final String NEGATE_FQN = ClassBuilderConstants.NEGATE_FQN;
    private static final String FORMATTABLE_FQN = ClassBuilderConstants.FORMATTABLE_FQN;
    private static final String LAZY_FQN = ClassBuilderConstants.LAZY_FQN;
    private static final String BUILDER_SEED_FQN = ClassBuilderConstants.BUILDER_SEED_FQN;
    private static final String SETTER_NAMES_FQN = ClassBuilderConstants.SETTER_NAMES_FQN;
    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";

    private PsiFieldShapeExtractor() {
    }

    /**
     * Extracts shapes from a concrete or abstract target class. Record
     * components are handled separately by {@link #fromRecord}.
     */
    static List<PsiFieldShape> fromClass(PsiClass target, Set<String> excluded, SetterScheme setters) {
        return fromClass(target, excluded, PsiSubstitutor.EMPTY, setters);
    }

    /**
     * Substituting variant. A member synthesised onto the {@code static} nested
     * Builder of a generic target must express field types in the Builder's own
     * type parameters, not the target's - they are distinct
     * {@link PsiTypeParameter}s, so a setter left holding the target's would
     * never be substituted by a {@code Builder<String>} receiver and the editor
     * would reject every call. Applied once here, ahead of classification, so
     * the derived element / key / value types follow.
     *
     * @param target the annotated type
     * @param excluded field names to skip
     * @param substitutor mapping to apply to each declared type
     * @return the extracted shapes
     */
    static List<PsiFieldShape> fromClass(PsiClass target, Set<String> excluded,
                                        PsiSubstitutor substitutor, SetterScheme setters) {
        List<PsiFieldShape> out = new ArrayList<>();
        for (PsiField field : target.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (field.hasModifierProperty(PsiModifier.TRANSIENT)) continue;
            if (excluded.contains(field.getName())) continue;
            if (isIgnored(field)) continue;
            out.add(buildShape(field, field.getName(), substitutor.substitute(field.getType()), setters));
        }
        return out;
    }

    /** Record-component variant; records expose fields via {@link PsiRecordComponent}. */
    static List<PsiFieldShape> fromRecord(PsiClass record, Set<String> excluded, SetterScheme setters) {
        return fromRecord(record, excluded, PsiSubstitutor.EMPTY, setters);
    }

    /** Substituting variant of {@link #fromRecord(PsiClass, Set, SetterScheme)}. */
    static List<PsiFieldShape> fromRecord(PsiClass record, Set<String> excluded,
                                         PsiSubstitutor substitutor, SetterScheme setters) {
        List<PsiFieldShape> out = new ArrayList<>();
        for (PsiRecordComponent c : record.getRecordComponents()) {
            String name = c.getName();
            if (excluded.contains(name)) continue;
            if (isIgnored(c)) continue;
            out.add(buildShape(c, name, substitutor.substitute(c.getType()), setters));
        }
        return out;
    }

    /**
     * Parameter variant, for a {@code @ClassBuilder} written on a constructor or
     * static factory. Every parameter is a slot, in declaration order - there is
     * nothing to exclude, the annotated member requiring all of them.
     *
     * <p>{@code @BuildFlag} is deliberately not read off a parameter, mirroring
     * the processor: the validator resolves the flagged fields of the instance
     * {@code build()} produced, so a constraint lives on those fields and a
     * parameter never carries one to propagate.
     *
     * @param executable the annotated constructor or static factory
     * @param substitutor mapping into the synth Builder's own type parameters
     * @return the extracted shapes, in parameter order
     */
    static List<PsiFieldShape> fromExecutable(PsiMethod executable, PsiSubstitutor substitutor,
                                             SetterScheme setters) {
        List<PsiFieldShape> out = new ArrayList<>();
        for (PsiParameter parameter : executable.getParameterList().getParameters()) {
            String name = parameter.getName();
            PsiFieldShape.Builder b = classify(parameter, name, substitutor.substitute(parameter.getType()), setters);
            b.seed = hasAnnotation(parameter, BUILDER_SEED_FQN);
            out.add(b.build());
        }
        return out;
    }

    /** True when the owner carries {@code @BuilderIgnore}. */
    private static boolean isIgnored(PsiModifierListOwner owner) {
        return hasAnnotation(owner, BUILDER_IGNORE_FQN);
    }

    /**
     * Builds a shape from an annotated owner (field or record component),
     * pulling type classification from the type mirror and companion-
     * annotation state from the owner's annotations.
     */
    private static PsiFieldShape buildShape(PsiModifierListOwner owner, String name,
                                            com.intellij.psi.PsiType type, SetterScheme setters) {
        return classify(owner, name, type, setters).build();
    }

    /**
     * Classifies a slot's type and reads its companion annotations, leaving the
     * result open so a caller can add what only its own path knows - the seed
     * marker, which is a parameter's alone.
     */
    private static PsiFieldShape.Builder classify(PsiModifierListOwner owner, String name,
                                                  com.intellij.psi.PsiType type,
                                                  SetterScheme setters) {
        PsiFieldShape.Builder b = PsiFieldShape.classify(name, type);
        b.setters = ClassBuilderConstants.setterOverride(
            findAnnotation(owner, SETTER_NAMES_FQN), setters);
        if (owner instanceof PsiDocCommentOwner docOwner) b.docSource = docOwner;
        // The two JetBrains names, matched off the source text - not
        // NullableNotNullManager, which this used to ask, for two reasons that
        // point the same way.
        //
        // It resolves, and a resolve started from an annotation written inside a
        // class body walks that class's nested types, which is augment-aware and
        // re-enters the provider that asked. The platform can already be
        // resolving that very annotation when it enters us, so the nesting is
        // not ours to unwind and it kills the synthesis outright.
        //
        // And its answer was wider than the processor's. The processor reads
        // exactly org.jetbrains.annotations.NotNull / Nullable, so a field
        // carrying javax.annotation.Nonnull got @NotNull on its setter in the
        // editor and nothing in the class file - a claim the build does not
        // make, which is the drift this pairing exists to prevent.
        b.notNull = WrittenAnnotations.hasOnMember(owner, NOT_NULL_FQN);
        b.nullable = WrittenAnnotations.hasOnMember(owner, NULLABLE_FQN);

        b.formattable = hasAnnotation(owner, FORMATTABLE_FQN);
        b.lazy = hasAnnotation(owner, LAZY_FQN);

        PsiAnnotation negate = findAnnotation(owner, NEGATE_FQN);
        if (negate != null) {
            String v = stringAttr(negate, "value", "");
            if (!v.isEmpty()) b.negateName = v;
        }

        PsiAnnotation collector = findAnnotation(owner, COLLECTOR_FQN);
        if (collector != null) {
            b.collector = true;
            b.singular = booleanAttr(collector, "singular", false);
            b.clearable = booleanAttr(collector, "clearable", false);
            b.append = booleanAttr(collector, "append", false);
            b.compute = booleanAttr(collector, "compute", false);
            String methodName = stringAttr(collector, "singularMethodName", "");
            b.singularName = methodName.isEmpty() ? defaultSingular(name) : methodName;
        }

        // Parity with the APT mutator: a custom (non-java.util) container needs
        // a field initializer to build fresh instances from. Without one the APT
        // emits a plain replace setter (and a NOTE), so suppress the @Collector
        // shape here too rather than advertise bulk methods the build won't
        // generate. Record components have no field initializer, matching the
        // APT, which reads the initializer off the backing field.
        if (b.isCustomContainer && b.collector && !hasFieldInitializer(owner)) {
            b.collector = false;
        }

        PsiAnnotation flag = findAnnotation(owner, BUILD_FLAG_FQN);
        if (flag != null) b.nonNullByBuildFlag = booleanAttr(flag, "nonNull", false);

        return b;
    }

    /** True when the owner is a field carrying a declared initializer. */
    private static boolean hasFieldInitializer(PsiModifierListOwner owner) {
        return owner instanceof PsiField field && field.hasInitializer();
    }

    /** True when the element carries the given FQN annotation. */
    static boolean hasAnnotation(PsiModifierListOwner owner, String fqn) {
        return findAnnotation(owner, fqn) != null;
    }

    /**
     * Every lookup here is on a field, record component or parameter - a
     * declaration inside a class body - so all of them take the resolve-free
     * match for the reason the nullness pair does.
     */
    private static PsiAnnotation findAnnotation(PsiModifierListOwner owner, String fqn) {
        return WrittenAnnotations.findOnMember(owner, fqn);
    }

    private static String stringAttr(PsiAnnotation annotation, String attr, String fallback) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return fallback;
    }

    private static boolean booleanAttr(PsiAnnotation annotation, String attr, boolean fallback) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (value instanceof PsiLiteralExpression lit && lit.getValue() instanceof Boolean b) return b;
        return fallback;
    }

    /** Reads the {@code @ClassBuilder} annotation on {@code target}. */
    static PsiAnnotation classBuilderAnnotation(PsiClass target) {
        return WrittenAnnotations.find(target, ClassBuilderConstants.ANNOTATION_FQN);
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
