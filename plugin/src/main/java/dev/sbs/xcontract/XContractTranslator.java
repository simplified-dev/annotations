package dev.sbs.xcontract;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import dev.sbs.contract.ContractAst;
import dev.sbs.contract.ContractParseException;
import dev.sbs.contract.ContractParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Translates an {@code @XContract} annotation into the source text of an
 * equivalent {@code @org.jetbrains.annotations.Contract} annotation that
 * IntelliJ's data-flow analysis already understands.
 *
 * <p>Translation is best-effort: clauses using features outside the
 * {@code @Contract} grammar (relational comparisons, field access, OR,
 * grouping, {@code this} references, named-parameter references) are
 * dropped silently and the remaining clauses still produce a useful
 * synthetic annotation. {@code pure} and {@code mutates} are always
 * forwarded.
 */
public final class XContractTranslator {

    private XContractTranslator() {}

    /**
     * Builds the source text of an inferred {@code @Contract} annotation for
     * {@code method}, or {@code null} if the {@code @XContract} carries no
     * translatable information.
     */
    public static @Nullable String toJetBrainsContract(@NotNull PsiMethod method, @NotNull PsiAnnotation xContract) {
        String value = stringAttr(xContract, "value");
        boolean pure = boolAttr(xContract, "pure");
        String mutates = stringAttr(xContract, "mutates");

        int paramCount = method.getParameterList().getParametersCount();
        String translatedValue = translateValue(value, paramCount);

        boolean hasValue   = translatedValue != null && !translatedValue.isEmpty();
        boolean hasPure    = pure;
        boolean hasMutates = mutates != null && !mutates.isEmpty();

        if (!hasValue && !hasPure && !hasMutates) return null;

        StringBuilder out = new StringBuilder("@org.jetbrains.annotations.Contract(");
        boolean first = true;

        if (hasValue) {
            out.append("value = \"").append(escape(translatedValue)).append('"');
            first = false;
        }
        if (hasPure) {
            if (!first) out.append(", ");
            out.append("pure = true");
            first = false;
        }
        if (hasMutates) {
            if (!first) out.append(", ");
            out.append("mutates = \"").append(escape(mutates)).append('"');
        }
        out.append(')');
        return out.toString();
    }

    /**
     * Translates the XContract {@code value} string into a {@code @Contract}
     * value string. Returns the empty string if no clause survives translation.
     */
    public static @NotNull String translateValue(@Nullable String xValue, int paramCount) {
        if (xValue == null || xValue.isEmpty() || paramCount < 0) return "";

        List<ContractAst.Clause> clauses;
        try {
            clauses = ContractParser.parse(xValue);
        } catch (ContractParseException ignored) {
            return "";
        }

        List<String> translated = new ArrayList<>();
        for (ContractAst.Clause clause : clauses) {
            String piece = translateClause(clause, paramCount);
            if (piece != null) translated.add(piece);
        }
        return String.join("; ", translated);
    }

    // ─── Clause translation ────────────────────────────────────────────────────

    private static @Nullable String translateClause(@NotNull ContractAst.Clause clause, int paramCount) {
        String ret = translateReturn(clause.returnVal());
        if (ret == null) return null;

        String[] slots = new String[paramCount];
        Arrays.fill(slots, "_");

        if (clause.condition() != null && !applyCondition(clause.condition(), slots)) return null;

        StringBuilder out = new StringBuilder();
        if (paramCount > 0) {
            out.append(String.join(", ", slots));
            out.append(" -> ");
        } else {
            out.append("-> ");
        }
        out.append(ret);
        return out.toString().trim();
    }

    /**
     * Applies a condition to the positional slot array. Returns {@code false}
     * if the condition contains anything outside @Contract's expressiveness
     * (in which case the clause is dropped entirely).
     */
    private static boolean applyCondition(@NotNull ContractAst.Expr expr, @NotNull String[] slots) {
        if (expr instanceof ContractAst.AndExpr and) {
            return applyCondition(and.left(), slots) && applyCondition(and.right(), slots);
        }
        if (expr instanceof ContractAst.CompExpr comp) {
            return applyComparison(comp, slots);
        }
        // OrExpr, NegExpr, ValExpr, and everything else fall outside @Contract's form.
        return false;
    }

    /** Maps {@code paramN == null | !null | true | false} into a positional slot. */
    private static boolean applyComparison(@NotNull ContractAst.CompExpr comp, @NotNull String[] slots) {
        // Normalise: put the ParamRef on the left.
        ContractAst.Value left = comp.left();
        ContractAst.Value right = comp.right();
        ContractAst.CompOp op = comp.op();

        if (!(left instanceof ContractAst.ParamRef leftRef) && right instanceof ContractAst.ParamRef) {
            ContractAst.Value swap = left; left = right; right = swap;
        }
        if (!(left instanceof ContractAst.ParamRef ref) || !ref.fields().isEmpty()) return false;

        int slotIdx = ref.index() - 1;
        if (slotIdx < 0 || slotIdx >= slots.length) return false;

        String constraint = null;
        if (right instanceof ContractAst.NullConst) {
            if (op == ContractAst.CompOp.EQ)  constraint = "null";
            if (op == ContractAst.CompOp.NEQ) constraint = "!null";
        } else if (right instanceof ContractAst.BoolConst b) {
            boolean equals = op == ContractAst.CompOp.EQ;
            boolean notEq  = op == ContractAst.CompOp.NEQ;
            if (equals)      constraint = b.value() ? "true"  : "false";
            else if (notEq)  constraint = b.value() ? "false" : "true";
        }
        if (constraint == null) return false;

        // If this slot already has a constraint, @Contract can only express
        // one constraint per slot - refuse to downgrade.
        if (!"_".equals(slots[slotIdx])) return false;

        slots[slotIdx] = constraint;
        return true;
    }

    private static @Nullable String translateReturn(@NotNull ContractAst.ReturnVal ret) {
        if (ret instanceof ContractAst.TrueRet)    return "true";
        if (ret instanceof ContractAst.FalseRet)   return "false";
        if (ret instanceof ContractAst.NullRet)    return "null";
        if (ret instanceof ContractAst.NotNullRet) return "!null";
        if (ret instanceof ContractAst.FailRet)    return "fail";
        if (ret instanceof ContractAst.ThrowsRet)  return "fail"; // typed exception collapses to @Contract's fail
        if (ret instanceof ContractAst.NewRet)     return "new";
        if (ret instanceof ContractAst.ThisRet)    return "this";
        if (ret instanceof ContractAst.ParamRet p) return "param" + p.index();
        // ParamNameRet and IntRet have no direct @Contract equivalent.
        return null;
    }

    // ─── Annotation attribute helpers ──────────────────────────────────────────

    private static @Nullable String stringAttr(@NotNull PsiAnnotation annotation, @NotNull String name) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(name);
        if (value instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return null;
    }

    private static boolean boolAttr(@NotNull PsiAnnotation annotation, @NotNull String name) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(name);
        return value instanceof PsiLiteralExpression lit && Boolean.TRUE.equals(lit.getValue());
    }

    private static @NotNull String escape(@NotNull String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
