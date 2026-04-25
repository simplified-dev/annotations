package dev.simplified.xcontract;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiCall;
import com.intellij.psi.PsiConstantEvaluationHelper;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.util.xmlb.annotations.OptionTag;
import dev.simplified.contract.ContractAst;
import dev.simplified.contract.ContractParseException;
import dev.simplified.contract.ContractParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Flags call sites whose literal arguments deterministically trigger a {@code fail}
 * or {@code throws} clause in the target method's {@code @XContract}.
 *
 * <p>Example: given {@code @XContract("(param1 < 0) -> fail") void at(int i)},
 * a call {@code at(-1)} is warned because {@code -1 < 0} is proven by the literal argument.
 *
 * <p>Only the subset of the contract grammar that can be evaluated against concrete
 * literal arguments is checked. Clauses using {@code this}, field access, {@code instanceof},
 * or non-literal arguments are conservatively skipped - they never produce a warning even if
 * they would violate the contract at runtime.
 */
public class XContractCallInspection extends LocalInspectionTool {

    private static final String XCONTRACT_FQN = "dev.simplified.annotations.XContract";

    /** Whether to flag calls that satisfy a {@code fail} clause. */
    @OptionTag("CHECK_FAIL")
    public boolean checkFailClauses = true;

    /** Whether to flag calls that satisfy a {@code throws TypeName} clause. */
    @OptionTag("CHECK_THROWS")
    public boolean checkThrowsClauses = true;

    /** Severity for a confirmed call-site violation. */
    @OptionTag("VIOLATION_HIGHLIGHT")
    public @NotNull ProblemHighlightType violationHighlightType = ProblemHighlightType.GENERIC_ERROR_OR_WARNING;

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        final boolean doFail = this.checkFailClauses;
        final boolean doThrows = this.checkThrowsClauses;
        final ProblemHighlightType severity = this.violationHighlightType;

        return new JavaElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                check(call, call.resolveMethod(), holder, doFail, doThrows, severity);
            }

            @Override
            public void visitNewExpression(@NotNull PsiNewExpression newExpr) {
                check(newExpr, newExpr.resolveConstructor(), holder, doFail, doThrows, severity);
            }
        };
    }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(
            OptPane.group(
                "Clauses to check",
                OptPane.checkbox("checkFailClauses",   "Warn on 'fail' clause violations"),
                OptPane.checkbox("checkThrowsClauses", "Warn on 'throws' clause violations")
            ),
            OptPane.group(
                "Highlight settings",
                OptPane.dropdown(
                    "violationHighlightType",
                    "Highlight for confirmed call-site violations",
                    OptPane.option(ProblemHighlightType.ERROR, "Error"),
                    OptPane.option(ProblemHighlightType.WARNING, "Warning"),
                    OptPane.option(ProblemHighlightType.WEAK_WARNING, "Weak Warning"),
                    OptPane.option(ProblemHighlightType.GENERIC_ERROR_OR_WARNING, "Server Problem"),
                    OptPane.option(ProblemHighlightType.INFORMATION, "Information")
                )
            )
        );
    }

    private static void check(
        @NotNull PsiCall call,
        @Nullable PsiMethod target,
        @NotNull ProblemsHolder holder,
        boolean checkFail,
        boolean checkThrows,
        @NotNull ProblemHighlightType severity
    ) {
        if (target == null) return;

        PsiAnnotation annotation = findXContract(target);
        if (annotation == null) return;

        String spec = stringAttr(annotation, "value");
        if (spec == null || spec.isEmpty()) return;

        List<ContractAst.Clause> clauses;
        try {
            clauses = ContractParser.parse(spec);
        } catch (ContractParseException e) {
            return; // Syntax errors surface via XContractInspection
        }

        PsiExpressionList argList = call.getArgumentList();
        if (argList == null) return;
        PsiExpression[] args = argList.getExpressions();
        PsiParameter[] params = target.getParameterList().getParameters();

        PsiConstantEvaluationHelper helper = JavaPsiFacade.getInstance(call.getProject()).getConstantEvaluationHelper();

        for (ContractAst.Clause clause : clauses) {
            if (!shouldCheck(clause.returnVal(), checkFail, checkThrows)) continue;
            if (clause.condition() == null) continue;
            if (evalCondition(clause.condition(), args, params, helper) != Truth.TRUE) continue;

            PsiElement anchor = argList.getTextLength() > 0 ? argList : call;
            holder.registerProblem(anchor,
                "Contract violation: this call will " + describeReturn(clause.returnVal())
                + " (matches clause '" + renderClause(clause) + "')",
                severity);
            return; // One warning per call site is plenty
        }
    }

    private static boolean shouldCheck(@NotNull ContractAst.ReturnVal ret, boolean checkFail, boolean checkThrows) {
        if (ret instanceof ContractAst.FailRet)   return checkFail;
        if (ret instanceof ContractAst.ThrowsRet) return checkThrows;
        return false;
    }

    // ─── Condition evaluation ─────────────────────────────────────────────────

    /**
     * Three-valued logic: we only warn on TRUE (definite violation). UNKNOWN means we
     * cannot prove the clause fires, so we stay silent.
     */
    private enum Truth { TRUE, FALSE, UNKNOWN }

    private static Truth evalCondition(
        @NotNull ContractAst.Expr expr,
        @NotNull PsiExpression[] args,
        @NotNull PsiParameter[] params,
        @NotNull PsiConstantEvaluationHelper helper
    ) {
        if (expr instanceof ContractAst.AndExpr and) {
            Truth l = evalCondition(and.left(),  args, params, helper);
            Truth r = evalCondition(and.right(), args, params, helper);
            if (l == Truth.FALSE || r == Truth.FALSE) return Truth.FALSE;
            if (l == Truth.TRUE && r == Truth.TRUE)   return Truth.TRUE;
            return Truth.UNKNOWN;
        }
        if (expr instanceof ContractAst.OrExpr or) {
            Truth l = evalCondition(or.left(),  args, params, helper);
            Truth r = evalCondition(or.right(), args, params, helper);
            if (l == Truth.TRUE || r == Truth.TRUE)   return Truth.TRUE;
            if (l == Truth.FALSE && r == Truth.FALSE) return Truth.FALSE;
            return Truth.UNKNOWN;
        }
        if (expr instanceof ContractAst.CompExpr comp) {
            return evalCompare(comp, args, params, helper);
        }
        if (expr instanceof ContractAst.NegExpr neg) {
            Object v = resolveValue(neg.value(), args, params, helper);
            if (v instanceof Boolean b) return b ? Truth.FALSE : Truth.TRUE;
            if (v == ContractKnown.NULL) return Truth.FALSE; // !null with null -> false
            return Truth.UNKNOWN;
        }
        if (expr instanceof ContractAst.ValExpr val) {
            Object v = resolveValue(val.value(), args, params, helper);
            if (v instanceof Boolean b) return b ? Truth.TRUE : Truth.FALSE;
            return Truth.UNKNOWN;
        }
        // InstanceOfExpr and anything else: we can't reliably prove at a call site.
        return Truth.UNKNOWN;
    }

    private static Truth evalCompare(
        @NotNull ContractAst.CompExpr comp,
        @NotNull PsiExpression[] args,
        @NotNull PsiParameter[] params,
        @NotNull PsiConstantEvaluationHelper helper
    ) {
        Object left  = resolveValue(comp.left(),  args, params, helper);
        Object right = resolveValue(comp.right(), args, params, helper);
        if (left == null || right == null) return Truth.UNKNOWN;

        // Null comparisons
        boolean leftIsNull  = left  == ContractKnown.NULL;
        boolean rightIsNull = right == ContractKnown.NULL;
        if (leftIsNull || rightIsNull) {
            if (comp.op() == ContractAst.CompOp.EQ)  return (leftIsNull && rightIsNull) ? Truth.TRUE : Truth.UNKNOWN;
            if (comp.op() == ContractAst.CompOp.NEQ) return (leftIsNull && rightIsNull) ? Truth.FALSE : Truth.UNKNOWN;
            return Truth.UNKNOWN;
        }

        // Integer comparisons
        if (left instanceof Number ln && right instanceof Number rn) {
            long a = ln.longValue(), b = rn.longValue();
            return switch (comp.op()) {
                case LT  -> a <  b ? Truth.TRUE : Truth.FALSE;
                case GT  -> a >  b ? Truth.TRUE : Truth.FALSE;
                case LTE -> a <= b ? Truth.TRUE : Truth.FALSE;
                case GTE -> a >= b ? Truth.TRUE : Truth.FALSE;
                case EQ  -> a == b ? Truth.TRUE : Truth.FALSE;
                case NEQ -> a != b ? Truth.TRUE : Truth.FALSE;
            };
        }

        // Boolean comparisons
        if (left instanceof Boolean lb && right instanceof Boolean rb) {
            return switch (comp.op()) {
                case EQ  -> lb.equals(rb) ? Truth.TRUE : Truth.FALSE;
                case NEQ -> lb.equals(rb) ? Truth.FALSE : Truth.TRUE;
                default  -> Truth.UNKNOWN;
            };
        }

        return Truth.UNKNOWN;
    }

    /**
     * Resolves a contract-AST {@link ContractAst.Value} to a constant object, or {@code null}
     * if not resolvable. Returns {@link ContractKnown#NULL} for the null constant.
     */
    private static @Nullable Object resolveValue(
        @NotNull ContractAst.Value value,
        @NotNull PsiExpression[] args,
        @NotNull PsiParameter[] params,
        @NotNull PsiConstantEvaluationHelper helper
    ) {
        if (value instanceof ContractAst.NullConst) return ContractKnown.NULL;
        if (value instanceof ContractAst.BoolConst b) return b.value();
        if (value instanceof ContractAst.IntConst i) return i.value();
        if (value instanceof ContractAst.ParamRef ref && ref.fields().isEmpty()) {
            int idx = ref.index() - 1;
            if (idx < 0 || idx >= args.length) return null;
            return constant(args[idx], helper);
        }
        if (value instanceof ContractAst.ParamNameRef named && named.fields().isEmpty()) {
            int idx = indexOfName(named.name(), params);
            if (idx < 0 || idx >= args.length) return null;
            return constant(args[idx], helper);
        }
        // ThisRef, field access, etc. - not resolvable at the call site
        return null;
    }

    private static @Nullable Object constant(@NotNull PsiExpression expr, @NotNull PsiConstantEvaluationHelper helper) {
        // Null literal is represented as a PsiLiteralExpression with value==null; distinguish.
        if (expr instanceof PsiLiteralExpression lit && lit.getValue() == null && "null".equals(lit.getText())) {
            return ContractKnown.NULL;
        }
        return helper.computeConstantExpression(expr);
    }

    private static int indexOfName(@NotNull String name, @NotNull PsiParameter[] params) {
        for (int i = 0; i < params.length; i++) {
            if (name.equals(params[i].getName())) return i;
        }
        return -1;
    }

    /** Sentinel for the null literal - distinct from Java null (which means "unknown"). */
    private enum ContractKnown { NULL }

    // ─── Pretty printing ──────────────────────────────────────────────────────

    private static @NotNull String describeReturn(@NotNull ContractAst.ReturnVal ret) {
        if (ret instanceof ContractAst.FailRet)       return "fail";
        if (ret instanceof ContractAst.ThrowsRet t)   return "throw " + t.typeName();
        return ret.toString();
    }

    private static @NotNull String renderClause(@NotNull ContractAst.Clause clause) {
        StringBuilder sb = new StringBuilder();
        if (clause.condition() != null) {
            sb.append('(').append(renderExpr(clause.condition())).append(')');
        } else {
            sb.append("()");
        }
        sb.append(" -> ").append(describeReturn(clause.returnVal()));
        return sb.toString();
    }

    private static @NotNull String renderExpr(@NotNull ContractAst.Expr expr) {
        if (expr instanceof ContractAst.OrExpr or)
            return renderExpr(or.left()) + " || " + renderExpr(or.right());
        if (expr instanceof ContractAst.AndExpr and)
            return renderExpr(and.left()) + " && " + renderExpr(and.right());
        if (expr instanceof ContractAst.CompExpr comp)
            return renderValue(comp.left()) + " " + comp.op().symbol + " " + renderValue(comp.right());
        if (expr instanceof ContractAst.NegExpr neg)
            return "!" + renderValue(neg.value());
        if (expr instanceof ContractAst.ValExpr val)
            return renderValue(val.value());
        if (expr instanceof ContractAst.InstanceOfExpr inst)
            return renderValue(inst.value()) + " instanceof " + inst.typeName();
        return expr.toString();
    }

    private static @NotNull String renderValue(@NotNull ContractAst.Value v) {
        if (v instanceof ContractAst.NullConst) return "null";
        if (v instanceof ContractAst.BoolConst b) return String.valueOf(b.value());
        if (v instanceof ContractAst.IntConst i) return String.valueOf(i.value());
        if (v instanceof ContractAst.ParamRef p) return "param" + p.index();
        if (v instanceof ContractAst.ParamNameRef n) return n.name();
        if (v instanceof ContractAst.ThisRef) return "this";
        return v.toString();
    }

    // ─── Annotation lookup ────────────────────────────────────────────────────

    /** Finds @XContract on the method or any of its super declarations. */
    private static @Nullable PsiAnnotation findXContract(@NotNull PsiMethod method) {
        PsiAnnotation direct = method.getAnnotation(XCONTRACT_FQN);
        if (direct != null) return direct;
        for (PsiMethod sup : method.findSuperMethods()) {
            PsiAnnotation inherited = sup.getAnnotation(XCONTRACT_FQN);
            if (inherited != null) return inherited;
        }
        return null;
    }

    private static @Nullable String stringAttr(@NotNull PsiAnnotation annotation, @NotNull String name) {
        PsiAnnotationMemberValue v = annotation.findDeclaredAttributeValue(name);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return null;
    }

}
