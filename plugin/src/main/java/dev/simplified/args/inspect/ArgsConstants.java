package dev.simplified.args.inspect;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import dev.simplified.args.apt.ArgsMode;
import dev.simplified.args.apt.ArgsSelection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared FQNs, attribute readers and field selection for the constructor
 * annotations' PSI side, so the augment provider, the inferred-annotation
 * provider, the gutter tooltip and the inspection all read one annotation the
 * same way.
 *
 * <p>The selection rule itself is not reimplemented here - it comes from
 * {@link ArgsSelection}, the same class the processor calls. Only fact
 * extraction differs between the two sides, and it has to, since one holds a
 * {@code JCVariableDecl} and the other a {@link PsiField}.
 */
public final class ArgsConstants {

    public static final String ALL_ARGS_FQN = "dev.simplified.annotations.AllArgsConstructor";
    public static final String REQUIRED_ARGS_FQN =
        "dev.simplified.annotations.RequiredArgsConstructor";
    public static final String NO_ARGS_FQN = "dev.simplified.annotations.NoArgsConstructor";
    public static final String BUILDER_ARGS_FQN =
        "dev.simplified.annotations.BuilderArgsConstructor";
    public static final String CLASS_BUILDER_FQN = "dev.simplified.annotations.ClassBuilder";

    private ArgsConstants() {
    }

    /**
     * The annotation FQN a mode is written as.
     *
     * @param mode the selection policy
     * @return the fully-qualified annotation name
     */
    public static String fqnOf(ArgsMode mode) {
        return switch (mode) {
            case ALL -> ALL_ARGS_FQN;
            case REQUIRED -> REQUIRED_ARGS_FQN;
            case NONE -> NO_ARGS_FQN;
            case BUILDER -> BUILDER_ARGS_FQN;
        };
    }

    /**
     * The mode a written annotation asks for.
     *
     * @param annotation any annotation
     * @return the mode, or {@code null} when it is not one of the four
     */
    public static @Nullable ArgsMode modeOf(PsiAnnotation annotation) {
        String fqn = annotation.getQualifiedName();
        if (fqn == null) return null;
        return switch (fqn) {
            case ALL_ARGS_FQN -> ArgsMode.ALL;
            case REQUIRED_ARGS_FQN -> ArgsMode.REQUIRED;
            case NO_ARGS_FQN -> ArgsMode.NONE;
            case BUILDER_ARGS_FQN -> ArgsMode.BUILDER;
            default -> null;
        };
    }

    /** Every constructor annotation written on the class, in a fixed order. */
    public static List<PsiAnnotation> written(PsiClass target) {
        List<PsiAnnotation> out = new ArrayList<>(2);
        for (ArgsMode mode : ArgsMode.values()) {
            PsiAnnotation a = target.getAnnotation(fqnOf(mode));
            if (a != null) out.add(a);
        }
        return out;
    }

    /**
     * The access modifier keyword the annotation asks for.
     *
     * @param annotation one of the four constructor annotations
     * @param mode its mode, which decides the default when nothing is written
     * @return the keyword, the empty string for package-private, or
     *         {@code null} for {@code AccessLevel.NONE}, which generates nothing
     */
    public static @Nullable String accessKeyword(PsiAnnotation annotation, ArgsMode mode) {
        String name = enumConstant(annotation, "access");
        if (name == null) {
            // The two defaults differ: a builder's constructor only has to be
            // reachable from the nested Builder, the others from anywhere.
            return mode == ArgsMode.BUILDER ? "" : PsiModifier.PUBLIC;
        }
        return switch (name) {
            case "PROTECTED" -> PsiModifier.PROTECTED;
            case "PRIVATE" -> PsiModifier.PRIVATE;
            case "PACKAGE" -> "";
            case "NONE" -> null;
            default -> PsiModifier.PUBLIC;
        };
    }

    /** Whether {@code force = true} is written. */
    public static boolean force(PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("force");
        return value != null && "true".equals(value.getText());
    }

    /**
     * Applies a mode to the class's own fields.
     *
     * <p>An {@code enum} constant is a field of the enum type as far as the PSI
     * is concerned, and so is filtered here rather than relied upon to be
     * {@code static}.
     *
     * @param target the class to read
     * @param mode the selection policy
     * @param builderExclude names {@code @ClassBuilder(exclude)} removes
     * @return the selected fields, in declaration order
     */
    public static List<PsiField> select(PsiClass target, ArgsMode mode,
                                        List<String> builderExclude) {
        List<PsiField> out = new ArrayList<>();
        for (PsiField field : ownFields(target)) {
            if (!isCandidate(field)) continue;
            // @Lazy owns the field's storage and its finality, so no mode takes
            // it as a parameter.
            if (hasAnnotation(field, "Lazy")) continue;
            if (!ArgsSelection.selects(mode,
                field.hasModifierProperty(PsiModifier.FINAL),
                field.hasInitializer(),
                field.hasModifierProperty(PsiModifier.TRANSIENT),
                hasAnnotation(field, "BuilderIgnore"),
                builderExclude.contains(field.getName()))) continue;
            out.add(field);
        }
        return out;
    }

    /**
     * Whether the field carries an annotation of this simple name.
     *
     * <p>Matched on the reference text rather than by resolving it, which is
     * not an optimisation. Resolving a field annotation re-enters every augment
     * provider registered for the class, including the builder's, which reads
     * the same annotation on the way through - and the platform answers that
     * cycle by disabling caching and logging an error rather than by returning
     * something useful.
     *
     * <p>The cost is that an unrelated {@code @BuilderIgnore} from another
     * package would be honoured here. That only affects what the editor
     * predicts; the processor resolves properly and remains the authority.
     *
     * @param field the field to test
     * @param simpleName the annotation's simple name
     * @return whether an annotation of that name is written on the field
     */
    private static boolean hasAnnotation(PsiField field, String simpleName) {
        var modifiers = field.getModifierList();
        if (modifiers == null) return false;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            var reference = annotation.getNameReferenceElement();
            if (reference != null && simpleName.equals(reference.getReferenceName())) return true;
        }
        return false;
    }

    /** The {@code final} fields with no initializer, which {@code force} fills. */
    public static List<PsiField> unassignedFinals(PsiClass target) {
        List<PsiField> out = new ArrayList<>();
        for (PsiField field : ownFields(target)) {
            if (!isCandidate(field)) continue;
            if (hasAnnotation(field, "Lazy")) continue;
            if (!field.hasModifierProperty(PsiModifier.FINAL)) continue;
            if (field.hasInitializer()) continue;
            out.add(field);
        }
        return out;
    }

    private static boolean isCandidate(PsiField field) {
        if (field instanceof com.intellij.psi.PsiEnumConstant) return false;
        if (field.hasModifierProperty(PsiModifier.STATIC)) return false;
        String name = field.getName();
        return !name.startsWith("$");
    }

    /**
     * Fields the class itself declares.
     *
     * <p>{@code getOwnFields()} rather than {@code getFields()}: the latter is
     * augment-aware and re-enters every provider, this one's callers included.
     */
    public static Iterable<PsiField> ownFields(PsiClass target) {
        return target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
    }

    private static String enumConstant(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiReferenceExpression ref) return ref.getReferenceName();
        return null;
    }

}
