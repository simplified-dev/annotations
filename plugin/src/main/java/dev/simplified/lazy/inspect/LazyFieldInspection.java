package dev.simplified.lazy.inspect;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.PsiType;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import org.jetbrains.annotations.NotNull;

/**
 * Catches misuse of {@code @Lazy} at source-edit time so users see the issue
 * before the annotation processor runs:
 *
 * <ul>
 *   <li>{@code @Lazy} on a static field - the AST mutator cannot install a
 *       per-instance {@code Lazy<T>} on a static slot.</li>
 *   <li>{@code @Lazy} on a record component - records bind the canonical
 *       constructor and accessor, so the storage type can't be rewritten.</li>
 *   <li>{@code @Lazy} on a primitive field - {@code Lazy<T>} can't be
 *       parameterised with a primitive; suggest the boxed equivalent.</li>
 *   <li>{@code @Lazy} without a field initializer when the enclosing class
 *       has no {@code @ClassBuilder} - no source of supplier value exists.</li>
 *   <li>{@code @Lazy} combined with the field-only ClassBuilder companions
 *       ({@code @Collector}, {@code @Negate}, {@code @Formattable},
 *       {@code @BuildFlag}, {@code @ObtainVia}) - the companion contracts
 *       assume direct {@code T} storage. {@code @BuilderDefault} and
 *       {@code @BuilderIgnore} are not flagged: they govern the builder's view
 *       of the field, not its storage.</li>
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
                if (type instanceof PsiPrimitiveType) {
                    holder.registerProblem(lazy,
                        "@Lazy is not supported on primitive fields - use the boxed equivalent",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }
                if (type.getArrayDimensions() > 0) {
                    holder.registerProblem(lazy,
                        "@Lazy is not supported on array fields",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                PsiClass enclosing = field.getContainingClass();
                boolean classBuilderPresent = enclosing != null
                    && enclosing.getAnnotation(CLASS_BUILDER_FQN) != null;
                if (field.getInitializer() == null && !classBuilderPresent) {
                    holder.registerProblem(lazy,
                        "@Lazy on a field without @ClassBuilder requires an initializer expression",
                        ProblemHighlightType.GENERIC_ERROR);
                }

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
