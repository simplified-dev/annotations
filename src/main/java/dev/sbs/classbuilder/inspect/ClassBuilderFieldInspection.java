package dev.sbs.classbuilder.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Flags misuse of companion field annotations on a {@code @ClassBuilder}
 * target. Examples:
 *
 * <ul>
 *   <li>{@code @Formattable} on a non-String / non-Optional&lt;String&gt; field</li>
 *   <li>{@code @Negate} on a non-{@code boolean} field</li>
 *   <li>{@code @Singular} on a non-{@link java.util.Collection Collection} or
 *       non-{@link java.util.Map Map} field</li>
 *   <li>{@code @BuildFlag(pattern = ...)} on a non-{@link CharSequence} field</li>
 *   <li>{@code @BuildFlag(limit = N)} on a type where the limit is not meaningful</li>
 * </ul>
 */
public class ClassBuilderFieldInspection extends LocalInspectionTool {

    private static final String FORMATTABLE_FQN = "dev.sbs.annotation.Formattable";
    private static final String NEGATE_FQN = "dev.sbs.annotation.Negate";
    private static final String SINGULAR_FQN = "dev.sbs.annotation.Singular";
    private static final String BUILD_FLAG_FQN = "dev.sbs.annotation.BuildFlag";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                PsiType type = field.getType();

                PsiAnnotation formattable = field.getAnnotation(FORMATTABLE_FQN);
                if (formattable != null && !isStringLike(type)) {
                    holder.registerProblem(formattable,
                        "@Formattable requires a String or Optional<String> field",
                        ProblemHighlightType.GENERIC_ERROR);
                }

                PsiAnnotation negate = field.getAnnotation(NEGATE_FQN);
                if (negate != null && !PsiTypes.booleanType().equals(type)) {
                    holder.registerProblem(negate,
                        "@Negate requires a boolean field",
                        ProblemHighlightType.GENERIC_ERROR);
                }

                PsiAnnotation singular = field.getAnnotation(SINGULAR_FQN);
                if (singular != null && !isCollectionOrMap(type)) {
                    holder.registerProblem(singular,
                        "@Singular requires a Collection, List, Set, or Map field",
                        ProblemHighlightType.GENERIC_ERROR);
                }

                PsiAnnotation buildFlag = field.getAnnotation(BUILD_FLAG_FQN);
                if (buildFlag != null) {
                    if (ClassBuilderConstants.stringAttr(buildFlag, "pattern", "").isEmpty() == false
                            && !isCharSequenceLike(type)) {
                        holder.registerProblem(buildFlag,
                            "@BuildFlag(pattern = ...) only applies to CharSequence or Optional<String> fields",
                            ProblemHighlightType.WARNING);
                    }
                    int limit = intAttr(buildFlag, "limit");
                    if (limit >= 0 && !isLimitable(type)) {
                        holder.registerProblem(buildFlag,
                            "@BuildFlag(limit = ...) only applies to CharSequence, Collection, Map, array, "
                                + "or Optional<String>/Optional<Number> fields",
                            ProblemHighlightType.WARNING);
                    }
                }
            }
        };
    }

    private static boolean isStringLike(@NotNull PsiType type) {
        if (type.equalsToText("java.lang.String")) return true;
        if (type instanceof PsiClassType ct && "java.util.Optional".equals(ct.rawType().getCanonicalText())) {
            PsiType[] args = ct.getParameters();
            return args.length > 0 && args[0].equalsToText("java.lang.String");
        }
        return false;
    }

    private static boolean isCharSequenceLike(@NotNull PsiType type) {
        if (type instanceof PsiClassType ct) {
            if (InheritanceUtil.isInheritor(type, "java.lang.CharSequence")) return true;
            if ("java.util.Optional".equals(ct.rawType().getCanonicalText())) {
                PsiType[] args = ct.getParameters();
                return args.length > 0 && args[0].equalsToText("java.lang.String");
            }
        }
        return false;
    }

    private static boolean isCollectionOrMap(@NotNull PsiType type) {
        if (type instanceof PsiPrimitiveType) return false;
        if (type.getArrayDimensions() > 0) return false;
        return InheritanceUtil.isInheritor(type, "java.util.Collection")
            || InheritanceUtil.isInheritor(type, "java.lang.Iterable")
            || InheritanceUtil.isInheritor(type, "java.util.Map");
    }

    private static boolean isLimitable(@NotNull PsiType type) {
        if (type.getArrayDimensions() > 0) return true;
        if (isCharSequenceLike(type)) return true;
        if (InheritanceUtil.isInheritor(type, "java.util.Collection")) return true;
        if (InheritanceUtil.isInheritor(type, "java.util.Map")) return true;
        if (type instanceof PsiClassType ct && "java.util.Optional".equals(ct.rawType().getCanonicalText())) {
            PsiType[] args = ct.getParameters();
            if (args.length == 0) return false;
            return args[0].equalsToText("java.lang.String")
                || InheritanceUtil.isInheritor(args[0], "java.lang.Number");
        }
        return false;
    }

    private static int intAttr(@NotNull PsiAnnotation annotation, @NotNull String attr) {
        var value = annotation.findAttributeValue(attr);
        if (value instanceof com.intellij.psi.PsiLiteralExpression literal && literal.getValue() instanceof Integer i) return i;
        return -1;
    }

}
