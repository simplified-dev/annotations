package dev.simplified.classbuilder.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.InheritanceUtil;
import dev.simplified.annotations.SetterNames;
import dev.simplified.classbuilder.apt.NamePattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;

/**
 * Flags misuse of companion field annotations on a {@code @ClassBuilder}
 * target. Examples:
 *
 * <ul>
 *   <li>{@code @Formattable} on a non-String / non-Optional&lt;String&gt; field</li>
 *   <li>{@code @Negate} on a non-{@code boolean} field</li>
 *   <li>{@code @Collector} on a non-{@link Collection} or
 *       non-{@link Map} field</li>
 *   <li>{@code @BuildFlag(pattern = ...)} on a
 *       non-{@link CharSequence} field</li>
 *   <li>{@code @BuildFlag(limit = N)} on a type where the
 *       limit is not meaningful</li>
 *   <li>a {@code @SetterNames} pattern that cannot expand to a Java
 *       identifier, or that suppresses the setter role</li>
 * </ul>
 */
public class ClassBuilderFieldInspection extends LocalInspectionTool {

    private static final String FORMATTABLE_FQN = "dev.simplified.annotations.Formattable";
    private static final String NEGATE_FQN = "dev.simplified.annotations.Negate";
    private static final String COLLECTOR_FQN = "dev.simplified.annotations.Collector";
    private static final String BUILD_FLAG_FQN = "dev.simplified.annotations.BuildFlag";
    private static final String SETTER_NAMES_FQN = "dev.simplified.annotations.SetterNames";
    private static final String BUILDER_NAMES_FQN = "dev.simplified.annotations.BuilderNames";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                String qualifiedName = annotation.getQualifiedName();
                if (SETTER_NAMES_FQN.equals(qualifiedName)) {
                    // Generated once per field, so a pattern without the
                    // placeholder would name every field's setter the same.
                    for (String role : ClassBuilderConstants.SETTER_ROLES) {
                        checkPattern(holder, annotation, role, true);
                    }
                    checkNotSuppressed(holder, annotation, "set",
                        "a field would then have no way to be assigned on the builder");
                } else if (BUILDER_NAMES_FQN.equals(qualifiedName)) {
                    // Generated exactly once, so every default is a plain
                    // literal and the placeholder is not required.
                    for (String role : ClassBuilderConstants.BUILDER_ROLES) {
                        checkPattern(holder, annotation, role, false);
                    }
                    checkNotSuppressed(holder, annotation, "type",
                        "a builder with no class to name is not a builder");
                    checkNotSuppressed(holder, annotation, "build",
                        "a builder with no way to finish is not a builder");
                }
            }

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

                PsiAnnotation collector = field.getAnnotation(COLLECTOR_FQN);
                if (collector != null && !isCollectionOrMap(type)) {
                    holder.registerProblem(collector,
                        "@Collector requires a Collection, List, Set, or Map field",
                        ProblemHighlightType.GENERIC_ERROR);
                }

                PsiAnnotation flag = field.getAnnotation(BUILD_FLAG_FQN);
                if (flag != null) {
                    if (!ClassBuilderConstants.stringAttr(flag, "pattern", "").isEmpty()
                            && !isCharSequenceLike(type)) {
                        holder.registerProblem(flag,
                            "@BuildFlag(pattern = ...) only applies to CharSequence or Optional<String> fields",
                            ProblemHighlightType.WARNING);
                    }
                    int limit = intAttr(flag, "limit");
                    if (limit >= 0 && !isLimitable(type)) {
                        holder.registerProblem(flag,
                            "@BuildFlag(limit = ...) only applies to CharSequence, Collection, Map, array, "
                                + "or Optional<String>/Optional<Number> fields",
                            ProblemHighlightType.WARNING);
                    }
                }
            }
        };
    }

    /**
     * Reports a naming pattern that cannot expand to a Java identifier,
     * highlighting the attribute value rather than the whole annotation.
     */
    private static void checkPattern(@NotNull ProblemsHolder holder, @NotNull PsiAnnotation annotation,
                                     @NotNull String attr, boolean placeholderRequired) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (!(value instanceof PsiLiteralExpression literal)) return;
        if (!(literal.getValue() instanceof String pattern)) return;
        String error = NamePattern.patternError(pattern, placeholderRequired);
        if (error != null) {
            holder.registerProblem(value, "Naming pattern for '" + attr + "' " + error,
                ProblemHighlightType.GENERIC_ERROR);
        }
    }

    /**
     * Reports a role suppressed with {@code NONE} that the generator cannot do
     * without.
     *
     * @param holder the problems holder
     * @param annotation the naming annotation to read
     * @param attr the attribute that may not be suppressed
     * @param because why the member is mandatory, appended to the message
     */
    private static void checkNotSuppressed(@NotNull ProblemsHolder holder, @NotNull PsiAnnotation annotation,
                                           @NotNull String attr, @NotNull String because) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attr);
        if (!(value instanceof PsiLiteralExpression literal)) return;
        if (!SetterNames.NONE.equals(literal.getValue())) return;
        holder.registerProblem(value,
            "'" + attr + "' cannot be suppressed - " + because,
            ProblemHighlightType.GENERIC_ERROR);
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
