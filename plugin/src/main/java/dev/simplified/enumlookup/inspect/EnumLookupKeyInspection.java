package dev.simplified.enumlookup.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiConstantEvaluationHelper;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.xmlb.annotations.OptionTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Highlights duplicate or null values supplied to enum constants for keys
 * marked {@code @KeyField(strictKeys = true)} or
 * {@code @KeyField(strictNullKeys = true)}.
 *
 * <p>Value resolution traces each constant's constructor arguments back to
 * the annotated fields via the constructor body's {@code this.<field> = <param>}
 * assignments. Literal arguments and references to {@code static final}
 * constants are resolved via {@link PsiConstantEvaluationHelper}; non-resolvable
 * expressions are silently skipped to avoid false positives.
 *
 * <p>Severities for the two checks are independently configurable in the
 * inspection options panel (both default to {@code ERROR}).
 */
public final class EnumLookupKeyInspection extends LocalInspectionTool {

    private static final Object UNRESOLVED = new Object();

    @OptionTag("DUPLICATE_KEY_HIGHLIGHT")
    public @NotNull ProblemHighlightType duplicateKeyHighlight = ProblemHighlightType.GENERIC_ERROR;

    @OptionTag("NULL_KEY_HIGHLIGHT")
    public @NotNull ProblemHighlightType nullKeyHighlight = ProblemHighlightType.GENERIC_ERROR;

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass clazz) {
                super.visitClass(clazz);
                if (!clazz.isEnum()) return;
                if (!hasEnumLookup(clazz)) return;
                List<KeyFieldRef> strictKeys = collectStrictKeyFields(clazz);
                if (strictKeys.isEmpty()) return;
                analyseConstants(clazz, strictKeys, holder);
            }
        };
    }

    private record KeyFieldRef(PsiField field, String name, boolean strictKeys, boolean strictNullKeys) {
    }

    private static boolean hasEnumLookup(PsiClass clazz) {
        for (PsiAnnotation a : clazz.getAnnotations()) {
            if (EnumLookupConstants.ENUM_LOOKUP_FQN.equals(a.getQualifiedName())) return true;
        }
        return false;
    }

    /** Collects only the fields that have at least one strict flag enabled. */
    private static List<KeyFieldRef> collectStrictKeyFields(PsiClass clazz) {
        List<KeyFieldRef> out = new ArrayList<>();
        Iterable<PsiField> fields = clazz instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(clazz.getFields());
        for (PsiField field : fields) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            PsiAnnotation annotation = findKeyField(field);
            if (annotation == null) continue;
            boolean strictKeys = EnumLookupConstants.booleanAttr(annotation,
                EnumLookupConstants.ATTR_STRICT_KEYS, false);
            boolean strictNullKeys = EnumLookupConstants.booleanAttr(annotation,
                EnumLookupConstants.ATTR_STRICT_NULL_KEYS, false);
            // strictNullKeys is a no-op for primitive-typed fields - skip the
            // null analysis for those even when the attribute is set.
            if (field.getType() instanceof PsiPrimitiveType) strictNullKeys = false;
            if (!strictKeys && !strictNullKeys) continue;
            String name = field.getName();
            if (name == null) continue;
            out.add(new KeyFieldRef(field, name, strictKeys, strictNullKeys));
        }
        return out;
    }

    private static @Nullable PsiAnnotation findKeyField(PsiField field) {
        for (PsiAnnotation a : field.getAnnotations()) {
            if (EnumLookupConstants.KEY_FIELD_FQN.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

    /**
     * For each strict-key field: walk every enum constant, evaluate the
     * argument that maps to the field (via the resolved constructor's
     * {@code this.<field> = <param>} assignments), and register problems.
     */
    private void analyseConstants(PsiClass enumClass, List<KeyFieldRef> keys, ProblemsHolder holder) {
        // Map of fieldName -> (Map of resolved value -> first-seen constant).
        // strictKeys: any second occurrence at the same value is a duplicate.
        Map<String, Map<Object, PsiEnumConstant>> seen = new HashMap<>();
        for (KeyFieldRef k : keys) seen.put(k.name(), new HashMap<>());

        PsiConstantEvaluationHelper helper = JavaPsiFacade.getInstance(enumClass.getProject())
            .getConstantEvaluationHelper();

        for (PsiField field : enumClass.getFields()) {
            if (!(field instanceof PsiEnumConstant constant)) continue;
            PsiMethod ctor = constant.resolveConstructor();
            if (ctor == null) continue;
            Map<String, Integer> fieldToParam = mapFieldToConstructorParam(ctor);
            PsiExpressionList argList = constant.getArgumentList();
            if (argList == null) continue;
            PsiExpression[] args = argList.getExpressions();
            for (KeyFieldRef k : keys) {
                Integer paramIdx = fieldToParam.get(k.name());
                if (paramIdx == null || paramIdx < 0 || paramIdx >= args.length) continue;
                PsiExpression arg = args[paramIdx];
                Object value = resolveValue(arg, helper);
                if (value == UNRESOLVED) continue;

                if (k.strictNullKeys() && value == null) {
                    holder.registerProblem(arg,
                        "Null value not allowed for @KeyField '" + k.name()
                            + "' (annotation has strictNullKeys = true)",
                        nullKeyHighlight);
                }
                if (k.strictKeys()) {
                    Object key = value == null ? NullKey.INSTANCE : value;
                    PsiEnumConstant previous = seen.get(k.name()).putIfAbsent(key, constant);
                    if (previous != null && previous != constant) {
                        String prevName = previous.getName();
                        holder.registerProblem(arg,
                            "Duplicate @KeyField '" + k.name() + "' value "
                                + describe(value) + " - first declared on "
                                + (prevName != null ? prevName : "another constant"),
                            duplicateKeyHighlight);
                    }
                }
            }
        }
    }

    /**
     * Returns a sentinel for null values so they can be used as a map key
     * (HashMap rejects null keys when subsequent put-if-absent runs).
     */
    private enum NullKey { INSTANCE }

    /**
     * Resolves an expression to {@code UNRESOLVED}, {@code null}, or a concrete
     * value. {@code null} is returned only when the expression is statically
     * known to evaluate to null.
     */
    private static Object resolveValue(PsiExpression expr, PsiConstantEvaluationHelper helper) {
        if (expr == null) return UNRESOLVED;
        Object value = helper.computeConstantExpression(expr);
        if (value != null) return value;
        PsiExpression deparen = PsiUtil.skipParenthesizedExprDown(expr);
        if (deparen instanceof PsiLiteralExpression lit && lit.getValue() == null) return null;
        return UNRESOLVED;
    }

    /**
     * Walks a constructor body and maps {@code field-name -> parameter-index}
     * for each {@code this.<field> = <param>} assignment. Field/param
     * mismatches (e.g. {@code this.code = computeCode(slug)}) are skipped
     * silently - we only resolve direct assignments.
     */
    private static Map<String, Integer> mapFieldToConstructorParam(PsiMethod constructor) {
        Map<String, Integer> mapping = new HashMap<>();
        PsiCodeBlock body = constructor.getBody();
        if (body == null) return mapping;
        List<PsiParameter> params = Arrays.asList(constructor.getParameterList().getParameters());
        for (PsiStatement stmt : body.getStatements()) {
            if (!(stmt instanceof PsiExpressionStatement es)) continue;
            if (!(es.getExpression() instanceof PsiAssignmentExpression assign)) continue;
            if (!(assign.getLExpression() instanceof PsiReferenceExpression lhsRef)) continue;
            PsiExpression qualifier = lhsRef.getQualifierExpression();
            if (!(qualifier instanceof PsiThisExpression)) continue;
            String fieldName = lhsRef.getReferenceName();
            if (fieldName == null) continue;
            PsiExpression rhs = assign.getRExpression();
            if (!(rhs instanceof PsiReferenceExpression rhsRef)) continue;
            PsiElement target = rhsRef.resolve();
            if (!(target instanceof PsiParameter param)) continue;
            int idx = params.indexOf(param);
            if (idx >= 0) mapping.put(fieldName, idx);
        }
        return mapping;
    }

    private static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + s + "\"";
        return String.valueOf(value);
    }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(
            OptPane.group(
                "Highlight settings",
                OptPane.dropdown(
                    "duplicateKeyHighlight",
                    "Highlight for duplicate keys",
                    OptPane.option(ProblemHighlightType.GENERIC_ERROR, "Error"),
                    OptPane.option(ProblemHighlightType.WARNING, "Warning"),
                    OptPane.option(ProblemHighlightType.WEAK_WARNING, "Weak Warning"),
                    OptPane.option(ProblemHighlightType.INFORMATION, "Information")
                ),
                OptPane.dropdown(
                    "nullKeyHighlight",
                    "Highlight for null keys",
                    OptPane.option(ProblemHighlightType.GENERIC_ERROR, "Error"),
                    OptPane.option(ProblemHighlightType.WARNING, "Warning"),
                    OptPane.option(ProblemHighlightType.WEAK_WARNING, "Weak Warning"),
                    OptPane.option(ProblemHighlightType.INFORMATION, "Information")
                )
            )
        );
    }
}
