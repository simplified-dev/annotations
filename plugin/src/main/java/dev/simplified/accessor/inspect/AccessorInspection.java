package dev.simplified.accessor.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifier;
import dev.simplified.annotations.SetterNames;
import dev.simplified.classbuilder.apt.NamePattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Flags misuse of {@code @Getter} and {@code @Setter} while the file is being
 * edited, rather than at the next build:
 *
 * <ul>
 *   <li>{@code @Setter} on a {@code final} field, which has no assignment to
 *       generate.</li>
 *   <li>{@code @Setter} beside {@code @Lazy}, whose storage is a
 *       {@code Lazy<T>} wrapper a plain assignment cannot type-check
 *       against.</li>
 *   <li>{@code @Getter} beside {@code @Lazy}, which already synthesises the
 *       accessor - redundant rather than wrong.</li>
 *   <li>Either annotation on a record or an interface, neither of which the
 *       processor supports.</li>
 *   <li>A {@code name} pattern that cannot expand to a distinct Java
 *       identifier.</li>
 * </ul>
 *
 * <p>The per-field checks copy the mutator's asymmetry rather than its
 * rejections alone: a rejection arising from a type-level annotation is silent,
 * because fanning out over a class is a blanket request rather than a claim
 * about any one field, and only a field the author singled out is reported. The
 * gating differs per check and is not a single flag - {@code @Setter} beside
 * {@code @Lazy} is reported whatever the annotation's origin, since the
 * alternative is a build that fails with no source line.
 */
public final class AccessorInspection extends LocalInspectionTool {

    private static final String NAME_ATTR = "name";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (!isAccessor(annotation)) return;
                checkName(holder, annotation);
            }

            @Override
            public void visitClass(@NotNull PsiClass target) {
                super.visitClass(target);
                if (isSupportedKind(target)) return;
                checkTargetKind(holder, target, target.getAnnotation(AccessorConstants.GETTER_FQN));
                checkTargetKind(holder, target, target.getAnnotation(AccessorConstants.SETTER_FQN));
            }

            @Override
            public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                // An enum's constants are fields of the enum type; they are not
                // state and never grow an accessor.
                if (field instanceof PsiEnumConstant) return;
                PsiClass owner = field.getContainingClass();
                if (owner == null) return;
                if (!isSupportedKind(owner)) {
                    checkTargetKind(holder, owner,
                        field.getAnnotation(AccessorConstants.GETTER_FQN));
                    checkTargetKind(holder, owner,
                        field.getAnnotation(AccessorConstants.SETTER_FQN));
                    return;
                }
                String fieldName = field.getName();
                if (fieldName.startsWith("$")) return;
                checkRead(holder, field, owner, fieldName);
                checkWrite(holder, field, owner, fieldName);
            }
        };
    }

    /**
     * Whether an annotation is one of the two, screened on its written simple
     * name before the reference is resolved. Every annotation in the file
     * reaches this hook, and only two of them can ever match.
     */
    private static boolean isAccessor(PsiAnnotation annotation) {
        PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
        if (reference == null) return false;
        String simpleName = reference.getReferenceName();
        if (!"Getter".equals(simpleName) && !"Setter".equals(simpleName)) return false;
        String fqn = annotation.getQualifiedName();
        return AccessorConstants.GETTER_FQN.equals(fqn) || AccessorConstants.SETTER_FQN.equals(fqn);
    }

    /** Whether the processor synthesises accessors onto this kind of target. */
    private static boolean isSupportedKind(PsiClass target) {
        return !target.isRecord() && !target.isInterface() && !target.isAnnotationType();
    }

    private static void checkTargetKind(ProblemsHolder holder, PsiClass target,
                                        @Nullable PsiAnnotation annotation) {
        if (annotation == null) return;
        String reason = target.isRecord()
            ? "a record, whose components are accessors already"
            : target.isAnnotationType() ? "an annotation type" : "an interface";
        holder.registerProblem(annotation,
            "@Getter / @Setter are only supported on classes and enums - " + target.getName()
                + " is " + reason,
            ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * Reports a {@code @Getter} the field's {@code @Lazy} has already answered.
     * Silent under a type-level annotation, where the author never named the
     * field, and never an error: the accessor the author wanted exists either
     * way.
     */
    private static void checkRead(ProblemsHolder holder, PsiField field, PsiClass owner,
                                  String fieldName) {
        PsiAnnotation fieldLevel = field.getAnnotation(AccessorConstants.GETTER_FQN);
        if (!applies(field, owner, fieldLevel, AccessorConstants.GETTER_FQN)) return;
        if (fieldLevel == null) return;
        if (field.getAnnotation(AccessorConstants.LAZY_FQN) == null) return;
        holder.registerProblem(fieldLevel,
            "@Getter on '" + fieldName + "' is redundant - @Lazy already synthesises its accessor",
            ProblemHighlightType.WEAK_WARNING);
    }

    /**
     * Reports the two shapes a write accessor cannot take. The {@code @Lazy}
     * clash is reported wherever the annotation was written, since javac would
     * otherwise reject the generated assignment with no line to click on; the
     * {@code final} rejection is reported only against a field the author
     * singled out.
     */
    private static void checkWrite(ProblemsHolder holder, PsiField field, PsiClass owner,
                                   String fieldName) {
        PsiAnnotation fieldLevel = field.getAnnotation(AccessorConstants.SETTER_FQN);
        if (!applies(field, owner, fieldLevel, AccessorConstants.SETTER_FQN)) return;
        PsiElement anchor = fieldLevel != null ? fieldLevel : field.getNameIdentifier();

        if (field.getAnnotation(AccessorConstants.LAZY_FQN) != null) {
            holder.registerProblem(anchor,
                "@Setter cannot be combined with @Lazy on '" + fieldName + "' - the field's "
                    + "storage is a Lazy wrapper, so a plain assignment does not type-check",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        if (fieldLevel == null) return;
        if (!field.hasModifierProperty(PsiModifier.FINAL)) return;
        holder.registerProblem(fieldLevel,
            "@Setter cannot be applied to final field '" + fieldName + "'",
            ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * Whether an accessor is generated for this field at all, resolving the
     * field-level annotation against the enclosing type's the way the mutator
     * does.
     *
     * @param field the field being visited
     * @param owner its enclosing type
     * @param fieldLevel the annotation written on the field, or {@code null}
     * @param fqn the annotation being resolved
     * @return whether the annotation reaches this field with an access level
     *         that emits
     */
    private static boolean applies(PsiField field, PsiClass owner, @Nullable PsiAnnotation fieldLevel,
                                   String fqn) {
        PsiAnnotation typeLevel = owner.getAnnotation(fqn);
        PsiAnnotation effective = fieldLevel != null ? fieldLevel : typeLevel;
        if (effective == null) return false;
        if (fieldLevel == null && AccessorConstants.excludes(typeLevel, field.getName())) return false;
        return AccessorConstants.generates(effective);
    }

    /**
     * Reports a {@code name} pattern the accessor scheme would expand into a
     * name the author cannot have meant, highlighting the attribute value
     * rather than the whole annotation.
     *
     * <p>Validated on a type-level annotation as well as a field-level one. The
     * placeholder is required in both places and matters more on the type: a
     * pattern without it gives every field on the class the same method name,
     * where a field-level one only collides with whatever else claims that
     * name.
     */
    private static void checkName(ProblemsHolder holder, PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(NAME_ATTR);
        if (!(value instanceof PsiLiteralExpression literal)) return;
        if (!(literal.getValue() instanceof String pattern)) return;
        if (pattern.isEmpty()) return; // INHERIT - the style supplies the pattern
        if (SetterNames.NONE.equals(pattern)) {
            // The accessor annotations suppress through AccessLevel.NONE, so
            // the sentinel is never read as one here and would be minted
            // verbatim as the method name.
            holder.registerProblem(value,
                "Naming pattern for 'name' cannot be '" + SetterNames.NONE + "' - write "
                    + "AccessLevel.NONE to generate nothing",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        String error = NamePattern.patternError(pattern, true);
        if (error != null) {
            holder.registerProblem(value, "Naming pattern for 'name' " + error,
                ProblemHighlightType.GENERIC_ERROR);
        }
    }

}
