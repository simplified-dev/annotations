package dev.simplified.lazy.inspect;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.classbuilder.apt.NamePattern;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import org.jetbrains.annotations.NotNull;

/**
 * Catches misuse of {@code @Lazy} at source-edit time so users see the issue
 * before the annotation processor runs:
 *
 * <ul>
 *   <li>{@code @Lazy} on a static field - the AST mutator cannot install a
 *       per-instance deferred holder on a static slot.</li>
 *   <li>{@code @Lazy} on a record component - records bind the canonical
 *       constructor and accessor, so the storage type can't be rewritten.</li>
 *   <li>{@code @Lazy} on an array field - the storage rewrite has no shape for
 *       one.</li>
 *   <li>{@code @Lazy} on a field with neither an initializer nor a constructor
 *       assignment, on a class with no {@code @ClassBuilder} - no source of
 *       supplier value exists.</li>
 *   <li>{@code @Lazy} combined with the field-only ClassBuilder companions
 *       ({@code @Collector}, {@code @Negate}, {@code @Formattable},
 *       {@code @BuildFlag}, {@code @ObtainVia}) - the companion contracts
 *       assume direct {@code T} storage. {@code @BuilderDefault} and
 *       {@code @BuilderIgnore} are not flagged: they govern the builder's view
 *       of the field, not its storage.</li>
 *   <li>{@code @Lazy(name)} written without the {@code {}} placeholder - the
 *       pattern is applied to one field's name, so a literal is the method name
 *       whatever the field is called.</li>
 *   <li>{@code @Lazy} alongside Lombok {@code @Getter} - the Lazy-generated
 *       getter wins, Lombok's would be a duplicate.</li>
 * </ul>
 */
public class LazyFieldInspection extends LocalInspectionTool {

    private static final String LAZY_FQN = LazyConstants.LAZY_FQN;
    private static final String CLASS_BUILDER_FQN = ClassBuilderConstants.ANNOTATION_FQN;
    private static final String COLLECTOR_FQN = ClassBuilderConstants.COLLECTOR_FQN;
    private static final String NEGATE_FQN = ClassBuilderConstants.NEGATE_FQN;
    private static final String FORMATTABLE_FQN = ClassBuilderConstants.FORMATTABLE_FQN;
    private static final String BUILD_FLAG_FQN = ClassBuilderConstants.BUILD_FLAG_FQN;
    private static final String OBTAIN_VIA_FQN = ClassBuilderConstants.OBTAIN_VIA_FQN;
    private static final String LOMBOK_GETTER_FQN = "lombok.Getter";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                PsiAnnotation lazy = field.getAnnotation(LAZY_FQN);
                if (lazy == null) return;

                if (field instanceof PsiRecordComponent) {
                    holder.registerProblem(lazy,
                        "@Lazy is not supported on record components",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }
                if (field.hasModifierProperty(PsiModifier.STATIC)) {
                    holder.registerProblem(lazy,
                        "@Lazy is not supported on static fields",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                PsiType type = field.getType();
                if (type.getArrayDimensions() > 0) {
                    holder.registerProblem(lazy,
                        "@Lazy is not supported on array fields",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                PsiClass enclosing = field.getContainingClass();
                boolean classBuilderPresent = enclosing != null
                    && enclosing.getAnnotation(CLASS_BUILDER_FQN) != null;
                if (field.getInitializer() == null && !classBuilderPresent
                    && !assignedByAConstructor(enclosing, field.getName())) {
                    holder.registerProblem(lazy,
                        "@Lazy on '" + field.getName() + "' has nothing to defer - give the field "
                            + "an initializer, or assign it in a constructor, either of which "
                            + "becomes the supplier body",
                        ProblemHighlightType.GENERIC_ERROR);
                }

                checkName(holder, lazy);

                checkConflict(holder, field, COLLECTOR_FQN, "@Collector");
                checkConflict(holder, field, NEGATE_FQN, "@Negate");
                checkConflict(holder, field, FORMATTABLE_FQN, "@Formattable");
                // @BuilderDefault and @BuilderIgnore are absent by design: both
                // govern how the builder treats the field, not how it is
                // stored, so neither conflicts with the storage rewrite.
                checkConflict(holder, field, BUILD_FLAG_FQN, "@BuildFlag");
                checkConflict(holder, field, OBTAIN_VIA_FQN, "@ObtainVia");

                PsiAnnotation lombokGetter = field.getAnnotation(LOMBOK_GETTER_FQN);
                if (lombokGetter != null) {
                    holder.registerProblem(lombokGetter,
                        "@Getter is redundant with @Lazy - the lazy-generated getter wins",
                        ProblemHighlightType.WEAK_WARNING);
                }
            }

            /**
             * Whether any constructor on the enclosing class assigns the field.
             *
             * <p>The other place a supplier body comes from, and the reason this
             * is asked at all: the processor accepts a field a constructor
             * assigns, so flagging one here would put a red mark on source that
             * compiles - the failure mode this plugin exists to prevent, in the
             * direction that is hardest to ignore.
             *
             * <p>Matched on the assignment's target rather than by resolving it,
             * so it holds up in a partially-typed file mid-edit.
             */
            private boolean assignedByAConstructor(PsiClass enclosing, String name) {
                if (enclosing == null || name == null) return false;
                for (PsiMethod constructor : enclosing.getConstructors()) {
                    PsiCodeBlock body = constructor.getBody();
                    if (body == null) continue;
                    for (PsiAssignmentExpression assignment :
                        PsiTreeUtil.findChildrenOfType(body, PsiAssignmentExpression.class)) {
                        if (assignsField(assignment, name)) return true;
                    }
                }
                return false;
            }

            /** Whether an assignment's left-hand side names the field, with or without {@code this}. */
            private boolean assignsField(PsiAssignmentExpression assignment, String name) {
                if (!(assignment.getLExpression() instanceof PsiReferenceExpression reference)) {
                    return false;
                }
                if (!name.equals(reference.getReferenceName())) return false;
                PsiExpression qualifier = reference.getQualifierExpression();
                return qualifier == null || qualifier instanceof PsiThisExpression;
            }

            /**
             * Reports a {@code name} pattern the getter cannot be spelled from.
             *
             * <p>The same rule the accessor pair is held to, and for the same
             * reason: the pattern is applied to one field's name, so one
             * without the placeholder is a literal, and a literal is the method
             * name whatever the field is called.
             */
            private void checkName(@NotNull ProblemsHolder holder, @NotNull PsiAnnotation lazy) {
                PsiAnnotationMemberValue value = lazy.findDeclaredAttributeValue("name");
                if (!(value instanceof PsiLiteralExpression literal)) return;
                if (!(literal.getValue() instanceof String pattern)) return;
                if (pattern.isEmpty()) return; // inherits from the style
                String error = NamePattern.patternError(pattern, true);
                if (error != null) {
                    holder.registerProblem(value, "Naming pattern for 'name' " + error,
                        ProblemHighlightType.GENERIC_ERROR);
                }
            }

            private void checkConflict(@NotNull ProblemsHolder holder, @NotNull PsiField field,
                                       @NotNull String otherFqn, @NotNull String displayName) {
                PsiAnnotation other = field.getAnnotation(otherFqn);
                if (other == null) return;
                holder.registerProblem(other,
                    displayName + " is not supported alongside @Lazy",
                    ProblemHighlightType.GENERIC_ERROR);
            }
        };
    }
}
