package dev.sbs.xcontract;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiAnnotationOwner;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.xmlb.annotations.OptionTag;
import dev.sbs.contract.ContractAst;
import dev.sbs.contract.ContractParseException;
import dev.sbs.contract.ContractParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspects {@code @XContract} annotations for:
 * <ul>
 *   <li>Syntax errors in the contract string (highlighted at the offending token)</li>
 *   <li>{@code paramN} references whose index exceeds the method's parameter count</li>
 *   <li>Named parameter references that do not match any declared parameter name</li>
 *   <li>Invalid tokens in the {@code mutates} attribute</li>
 * </ul>
 */
public class XContractInspection extends LocalInspectionTool {

    private static final String ANNOTATION_FQN = "dev.sbs.annotation.XContract";

    /**
     * Severity for contract-inheritance warnings (e.g. an override that drops {@code pure=true}
     * or adds mutates targets). Softer than syntax errors by default.
     */
    @OptionTag("INHERITANCE_HIGHLIGHT")
    public @NotNull ProblemHighlightType inheritanceHighlightType = ProblemHighlightType.WARNING;

    /** Whether to validate that overrides honour the super method's contract. */
    @OptionTag("CHECK_INHERITANCE")
    public boolean checkInheritance = true;

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        final ProblemHighlightType inheritanceSeverity = this.inheritanceHighlightType;
        final boolean runInheritanceCheck = this.checkInheritance;

        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                if (!annotation.hasQualifiedName(ANNOTATION_FQN)) return;

                PsiMethod method = resolveMethod(annotation);
                validateValue(annotation, method, holder);
                validateMutates(annotation, method, holder);
                if (runInheritanceCheck)
                    validateInheritance(annotation, method, holder, inheritanceSeverity);
            }
        };
    }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(
            OptPane.group(
                "Contract inheritance",
                OptPane.checkbox("checkInheritance", "Warn when an override weakens the super method's contract"),
                OptPane.dropdown(
                    "inheritanceHighlightType",
                    "Highlight for contract inheritance violations",
                    OptPane.option(ProblemHighlightType.ERROR, "Error"),
                    OptPane.option(ProblemHighlightType.WARNING, "Warning"),
                    OptPane.option(ProblemHighlightType.WEAK_WARNING, "Weak Warning"),
                    OptPane.option(ProblemHighlightType.GENERIC_ERROR_OR_WARNING, "Server Problem"),
                    OptPane.option(ProblemHighlightType.INFORMATION, "Information")
                )
            )
        );
    }

    // ─── value() validation ───────────────────────────────────────────────────

    private static void validateValue(@NotNull PsiAnnotation annotation, @Nullable PsiMethod method, @NotNull ProblemsHolder holder) {
        PsiAnnotationMemberValue attr = annotation.findDeclaredAttributeValue("value");
        if (!(attr instanceof PsiLiteralExpression literal)) return;
        if (!(literal.getValue() instanceof String spec) || spec.isEmpty()) return;

        List<ContractAst.Clause> clauses;
        try {
            clauses = ContractParser.parse(spec);
        } catch (ContractParseException e) {
            reportParseError(holder, literal, e);
            return;
        }

        if (method == null) return;
        int paramCount = method.getParameterList().getParametersCount();
        List<String> paramNames = paramNames(method);

        for (ContractAst.Clause clause : clauses) {
            validateClause(clause, paramCount, paramNames, literal, holder);
        }
    }

    private static void reportParseError(
        @NotNull ProblemsHolder holder,
        @NotNull PsiLiteralExpression literal,
        @NotNull ContractParseException e
    ) {
        // The literal's text includes surrounding quotes (position 0 = opening quote).
        // Contract string positions are 0-based within the content, so offset by +1.
        int quoteOffset = 1;
        int errorStart  = e.getPosition() + quoteOffset;
        int errorEnd    = Math.min(errorStart + Math.max(e.getTokenLength(), 1), literal.getTextLength() - 1);

        if (errorStart < literal.getTextLength() && errorStart < errorEnd) {
            holder.registerProblem(literal, TextRange.create(errorStart, errorEnd),
                "Contract syntax error: " + e.getMessage());
        } else {
            holder.registerProblem(literal, "Contract syntax error: " + e.getMessage());
        }
    }

    private static void validateClause(
        @NotNull ContractAst.Clause clause,
        int paramCount,
        @NotNull List<String> paramNames,
        @NotNull PsiLiteralExpression literal,
        @NotNull ProblemsHolder holder
    ) {
        if (clause.condition() != null) validateExpr(clause.condition(), paramCount, paramNames, literal, holder);
        validateReturn(clause.returnVal(), paramCount, paramNames, literal, holder);
    }

    private static void validateExpr(
        @NotNull ContractAst.Expr expr,
        int paramCount,
        @NotNull List<String> paramNames,
        @NotNull PsiLiteralExpression literal,
        @NotNull ProblemsHolder holder
    ) {
        if (expr instanceof ContractAst.OrExpr or) {
            validateExpr(or.left(),  paramCount, paramNames, literal, holder);
            validateExpr(or.right(), paramCount, paramNames, literal, holder);
        } else if (expr instanceof ContractAst.AndExpr and) {
            validateExpr(and.left(),  paramCount, paramNames, literal, holder);
            validateExpr(and.right(), paramCount, paramNames, literal, holder);
        } else if (expr instanceof ContractAst.CompExpr comp) {
            validateValue(comp.left(),  paramCount, paramNames, literal, holder);
            validateValue(comp.right(), paramCount, paramNames, literal, holder);
        } else if (expr instanceof ContractAst.NegExpr neg) {
            validateValue(neg.value(), paramCount, paramNames, literal, holder);
        } else if (expr instanceof ContractAst.ValExpr val) {
            validateValue(val.value(), paramCount, paramNames, literal, holder);
        } else if (expr instanceof ContractAst.InstanceOfExpr inst) {
            validateValue(inst.value(), paramCount, paramNames, literal, holder);
            validateTypeName(inst.typeName(), literal, holder);
        }
    }

    private static void validateValue(
        @NotNull ContractAst.Value value,
        int paramCount,
        @NotNull List<String> paramNames,
        @NotNull PsiLiteralExpression literal,
        @NotNull ProblemsHolder holder
    ) {
        if (value instanceof ContractAst.ParamRef ref && ref.index() > paramCount) {
            holder.registerProblem(literal,
                "Contract references 'param" + ref.index() + "' but the method only has "
                + paramCount + " parameter" + (paramCount == 1 ? "" : "s"));
        } else if (value instanceof ContractAst.ParamNameRef named && !paramNames.contains(named.name())) {
            String available = paramNames.isEmpty() ? "(none)" : String.join(", ", paramNames);
            holder.registerProblem(literal,
                "Contract references unknown parameter name '" + named.name() + "' - available: " + available);
        }
    }

    private static void validateReturn(
        @NotNull ContractAst.ReturnVal ret,
        int paramCount,
        @NotNull List<String> paramNames,
        @NotNull PsiLiteralExpression literal,
        @NotNull ProblemsHolder holder
    ) {
        if (ret instanceof ContractAst.ParamRet ref && ref.index() > paramCount) {
            holder.registerProblem(literal,
                "Contract return references 'param" + ref.index() + "' but the method only has "
                + paramCount + " parameter" + (paramCount == 1 ? "" : "s"));
        } else if (ret instanceof ContractAst.ParamNameRet named && !paramNames.contains(named.name())) {
            String available = paramNames.isEmpty() ? "(none)" : String.join(", ", paramNames);
            holder.registerProblem(literal,
                "Contract return references unknown parameter name '" + named.name()
                + "' - available: " + available);
        } else if (ret instanceof ContractAst.ThrowsRet throwsRet) {
            validateTypeName(throwsRet.typeName(), literal, holder);
        }
    }

    /**
     * Validates that a type name referenced in the contract resolves to a class on the project
     * classpath. Supports both unqualified names (resolved via a project-wide short-name search)
     * and fully qualified names.
     */
    private static void validateTypeName(
        @NotNull String typeName,
        @NotNull PsiLiteralExpression literal,
        @NotNull ProblemsHolder holder
    ) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(holder.getProject());

        if (typeName.indexOf('.') >= 0) {
            // Fully qualified - must resolve exactly
            if (JavaPsiFacade.getInstance(holder.getProject()).findClass(typeName, scope) == null) {
                holder.registerProblem(literal,
                    "Contract references unknown type '" + typeName + "'");
            }
            return;
        }

        // Unqualified - look up by short name
        PsiClass[] candidates = PsiShortNamesCache.getInstance(holder.getProject()).getClassesByName(typeName, scope);
        if (candidates.length == 0) {
            holder.registerProblem(literal,
                "Contract references unknown type '" + typeName + "'");
        }
    }

    // ─── mutates() validation ─────────────────────────────────────────────────

    private static void validateMutates(@NotNull PsiAnnotation annotation, @Nullable PsiMethod method, @NotNull ProblemsHolder holder) {
        PsiAnnotationMemberValue attr = annotation.findDeclaredAttributeValue("mutates");
        if (!(attr instanceof PsiLiteralExpression literal)) return;
        if (!(literal.getValue() instanceof String raw) || raw.isEmpty()) return;

        int paramCount = method == null ? -1 : method.getParameterList().getParametersCount();

        int cursor = 0; // index into raw
        for (String rawToken : raw.split(",", -1)) {
            String token = rawToken.trim();
            int tokenStart = cursor + indexOfNonWhitespace(rawToken);
            int tokenLen   = token.length();
            cursor += rawToken.length() + 1; // +1 for the consumed comma

            if (token.isEmpty()) continue;

            if ("this".equals(token) || "io".equals(token)) continue;

            if (token.startsWith("param")) {
                String numPart = token.substring("param".length());
                try {
                    int idx = Integer.parseInt(numPart);
                    if (idx < 1) {
                        reportMutatesError(holder, literal, tokenStart, tokenLen,
                            "Parameter index must be >= 1, got '" + token + "'");
                    } else if (paramCount >= 0 && idx > paramCount) {
                        reportMutatesError(holder, literal, tokenStart, tokenLen,
                            "'mutates' references 'param" + idx + "' but the method only has "
                            + paramCount + " parameter" + (paramCount == 1 ? "" : "s"));
                    }
                    continue;
                } catch (NumberFormatException ignored) {
                    // fall through to generic error
                }
            }

            reportMutatesError(holder, literal, tokenStart, tokenLen,
                "Invalid 'mutates' token '" + token + "' - expected: this, io, or paramN");
        }
    }

    private static int indexOfNonWhitespace(@NotNull String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return i;
        }
        return 0;
    }

    private static void reportMutatesError(
        @NotNull ProblemsHolder holder,
        @NotNull PsiLiteralExpression literal,
        int tokenStart,
        int tokenLen,
        @NotNull String message
    ) {
        int quoteOffset = 1;
        int start = tokenStart + quoteOffset;
        int end   = Math.min(start + Math.max(tokenLen, 1), literal.getTextLength() - 1);

        if (start < literal.getTextLength() && start < end) {
            holder.registerProblem(literal, TextRange.create(start, end), message);
        } else {
            holder.registerProblem(literal, message);
        }
    }

    // ─── Inheritance check ────────────────────────────────────────────────────

    private static final String JETBRAINS_CONTRACT_FQN = "org.jetbrains.annotations.Contract";

    /**
     * Warns when an overriding method weakens the contract declared by a super method:
     * <ul>
     *   <li>{@code pure = true} on a super - override must not declare {@code pure = false}.</li>
     *   <li>{@code mutates} on the override must be a subset of the super's mutates
     *       (an override may not mutate more than its parent promised).</li>
     * </ul>
     */
    private static void validateInheritance(
        @NotNull PsiAnnotation annotation,
        @Nullable PsiMethod method,
        @NotNull ProblemsHolder holder,
        @NotNull ProblemHighlightType severity
    ) {
        if (method == null) return;

        PsiMethod[] supers = method.findSuperMethods();
        if (supers.length == 0) return;

        boolean overridePure = boolAttr(annotation, "pure", false);
        java.util.Set<String> overrideMutates = parseMutatesSet(stringAttr(annotation, "mutates"));

        for (PsiMethod sup : supers) {
            PsiAnnotation superAnno = findContractAnnotation(sup);
            if (superAnno == null) continue;

            boolean superPure = boolAttr(superAnno, "pure", false);
            java.util.Set<String> superMutates = parseMutatesSet(stringAttr(superAnno, "mutates"));

            if (superPure && !overridePure) {
                String superName = sup.getContainingClass() != null
                    ? sup.getContainingClass().getName() + "." + sup.getName()
                    : sup.getName();
                holder.registerProblem(annotation,
                    "Override weakens contract: super '" + superName + "' is pure but this override is not",
                    severity);
            }

            // An override may have tighter (fewer) mutates, never more than the super.
            for (String token : overrideMutates) {
                if (!superMutates.contains(token) && !superMutates.isEmpty()) {
                    String superName = sup.getContainingClass() != null
                        ? sup.getContainingClass().getName() + "." + sup.getName()
                        : sup.getName();
                    holder.registerProblem(annotation,
                        "Override mutates '" + token + "' which super '" + superName + "' does not permit",
                        severity);
                }
            }
        }
    }

    private static @Nullable PsiAnnotation findContractAnnotation(@NotNull PsiMethod method) {
        PsiAnnotation x = method.getAnnotation(ANNOTATION_FQN);
        if (x != null) return x;
        return method.getAnnotation(JETBRAINS_CONTRACT_FQN);
    }

    private static @NotNull java.util.Set<String> parseMutatesSet(@Nullable String raw) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static boolean boolAttr(@NotNull PsiAnnotation annotation, @NotNull String name, boolean fallback) {
        PsiAnnotationMemberValue v = annotation.findDeclaredAttributeValue(name);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof Boolean b) return b;
        return fallback;
    }

    private static @Nullable String stringAttr(@NotNull PsiAnnotation annotation, @NotNull String name) {
        PsiAnnotationMemberValue v = annotation.findDeclaredAttributeValue(name);
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
        return null;
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    /** Returns the enclosing method/constructor, or {@code null} if not determinable. */
    private static @Nullable PsiMethod resolveMethod(@NotNull PsiAnnotation annotation) {
        PsiAnnotationOwner owner = annotation.getOwner();
        if (!(owner instanceof PsiModifierList modList)) return null;

        PsiElement parent = modList.getParent();
        return parent instanceof PsiMethod method ? method : null;
    }

    private static @NotNull List<String> paramNames(@NotNull PsiMethod method) {
        PsiParameter[] params = method.getParameterList().getParameters();
        List<String> names = new ArrayList<>(params.length);
        for (PsiParameter p : params) {
            String n = p.getName();
            if (n != null) names.add(n);
        }
        return names;
    }

}
