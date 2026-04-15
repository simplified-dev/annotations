package dev.sbs.resourcepath;

import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UDeclaration;
import org.jetbrains.uast.UDeclarationsExpression;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.ULiteralExpression;
import org.jetbrains.uast.UPolyadicExpression;
import org.jetbrains.uast.UQualifiedReferenceExpression;
import org.jetbrains.uast.USimpleNameReferenceExpression;
import org.jetbrains.uast.UVariable;
import org.jetbrains.uast.UastContextKt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursively evaluates a {@link UExpression} to the set of string values it
 * can hold, up to a bounded depth and size.
 *
 * <p>Guards (to prevent IDE freezes/crashes):
 * <ul>
 *   <li>{@link #MAX_DEPTH} - max recursion levels through method bodies and
 *       polyadic concatenations.</li>
 *   <li>{@link #MAX_RESULTS} - cap on result-set cardinality to prevent
 *       cartesian-product explosion in concatenations.</li>
 *   <li>{@code collectReturnStatements} does not descend into nested classes
 *       or lambdas.</li>
 *   <li>Null-safe UAST re-resolution guards against stale-reference crashes.</li>
 * </ul>
 */
final class StringExpressionEvaluator {

    /** Upper bound on total recursion depth - prevents stack overflow on pathological graphs. */
    private static final int MAX_DEPTH = 16;

    /** Cap on number of distinct values a single subexpression can produce. */
    private static final int MAX_RESULTS = 128;

    private StringExpressionEvaluator() {}

    public static @NotNull Set<String> evaluate(@NotNull UExpression expression) {
        return evaluate(expression, new HashSet<>(), new HashMap<>(), 0);
    }

    private static @NotNull Set<String> evaluate(
        @NotNull UExpression expression,
        @NotNull Set<PsiMethod> visitedMethods,
        @NotNull Map<String, Set<String>> intermediateVars,
        int depth
    ) {
        Set<String> result = new HashSet<>();
        if (depth > MAX_DEPTH) return result;

        // Refresh against the current PSI to avoid stale-reference exceptions.
        // getSourcePsi() can be null for synthetic elements - guard it.
        PsiElement source = expression.getSourcePsi();
        if (source != null) {
            UExpression refreshed = UastContextKt.toUElement(source, UExpression.class);
            if (refreshed != null) expression = refreshed;
        }

        if (expression instanceof ULiteralExpression literal && literal.getValue() instanceof String value) {
            result.add(value);
        } else if (expression instanceof UPolyadicExpression polyadic) {
            combineOperandsWithDeps(
                polyadic.getOperands(),
                0,
                "",
                result,
                visitedMethods,
                intermediateVars,
                depth + 1
            );
        } else if (expression instanceof USimpleNameReferenceExpression ref) {
            String name = ref.getIdentifier();

            if (intermediateVars.containsKey(name)) {
                result.addAll(intermediateVars.get(name));
            } else {
                PsiElement resolved = ref.resolve();
                UExpression initExpr = null;

                if (resolved instanceof PsiField field && field.hasModifierProperty(PsiModifier.FINAL) && field.getInitializer() != null)
                    initExpr = UastContextKt.toUElement(field.getInitializer(), UExpression.class);
                else if (resolved instanceof PsiLocalVariable local && local.getInitializer() != null)
                    initExpr = UastContextKt.toUElement(local.getInitializer(), UExpression.class);

                if (initExpr != null)
                    addCapped(result, evaluate(initExpr, visitedMethods, intermediateVars, depth + 1));
            }
        } else if (expression instanceof UCallExpression callExpr) {
            PsiMethod method = callExpr.resolve();

            if (method != null && visitedMethods.add(method)) {
                try {
                    PsiCodeBlock body = method.getBody();

                    if (body != null) {
                        // Bind parameters to evaluated arguments.
                        List<UExpression> args = callExpr.getValueArguments();
                        PsiParameter[] params = method.getParameterList().getParameters();
                        Map<String, Set<String>> paramBindings = new HashMap<>();
                        for (int i = 0; i < Math.min(args.size(), params.length); i++) {
                            Set<String> argEval = evaluate(args.get(i), visitedMethods, intermediateVars, depth + 1);
                            paramBindings.put(params[i].getName(), argEval);
                        }

                        Map<String, Set<String>> localVars = new HashMap<>(paramBindings);

                        // Pre-evaluate top-level local variables.
                        for (PsiStatement statement : body.getStatements()) {
                            if (statement instanceof PsiDeclarationStatement declStmt) {
                                for (PsiElement element : declStmt.getDeclaredElements()) {
                                    if (element instanceof PsiLocalVariable local && local.getInitializer() != null) {
                                        UExpression initExpr = UastContextKt.toUElement(local.getInitializer(), UExpression.class);
                                        if (initExpr != null)
                                            localVars.put(local.getName(), evaluate(initExpr, visitedMethods, localVars, depth + 1));
                                    }
                                }
                            }
                        }

                        // Evaluate return statements (excluding those in nested declarations).
                        for (PsiReturnStatement returnStmt : collectReturnStatements(body)) {
                            PsiExpression returnValue = returnStmt.getReturnValue();
                            if (returnValue == null) continue;
                            UExpression returnExpr = UastContextKt.toUElement(returnValue, UExpression.class);
                            if (returnExpr != null)
                                addCapped(result, evaluate(returnExpr, visitedMethods, localVars, depth + 1));
                            if (result.size() >= MAX_RESULTS) break;
                        }
                    }
                } finally {
                    visitedMethods.remove(method);
                }
            }
        } else if (expression instanceof UQualifiedReferenceExpression qualified) {
            addCapped(result, resolveEnumFieldAccess(qualified, visitedMethods, intermediateVars, depth + 1));
        } else if (expression instanceof UDeclarationsExpression declarations) {
            for (UDeclaration decl : declarations.getDeclarations()) {
                if (decl instanceof UVariable local) {
                    UExpression initExpr = local.getUastInitializer();
                    if (initExpr == null) continue;
                    addCapped(result, evaluate(initExpr, visitedMethods, intermediateVars, depth + 1));
                    if (result.size() >= MAX_RESULTS) break;
                }
            }
        }

        return result;
    }

    /**
     * Collects {@code return} statements that belong to the given method body,
     * excluding returns inside nested classes, anonymous classes, and lambdas.
     */
    private static @NotNull Set<PsiReturnStatement> collectReturnStatements(@NotNull PsiElement element) {
        Set<PsiReturnStatement> results = new HashSet<>();

        element.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
                results.add(statement);
                if (results.size() >= MAX_RESULTS) stopWalking();
            }

            @Override
            public void visitClass(@NotNull PsiClass aClass) {
                // do not descend - inner/nested class returns belong to its own methods
            }

            @Override
            public void visitAnonymousClass(@NotNull PsiAnonymousClass aClass) {
                // do not descend
            }

            @Override
            public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) {
                // do not descend - lambda body returns belong to the lambda, not the enclosing method
            }
        });

        return results;
    }

    private static void combineOperandsWithDeps(
        List<UExpression> operands,
        int index,
        String current,
        @NotNull Set<String> resultValues,
        Set<PsiMethod> visitedMethods,
        Map<String, Set<String>> intermediateVars,
        int depth
    ) {
        if (resultValues.size() >= MAX_RESULTS) return;
        if (index >= operands.size()) {
            resultValues.add(current);
            return;
        }
        if (depth > MAX_DEPTH) return;

        UExpression operand = operands.get(index);
        Set<String> eval = evaluate(operand, visitedMethods, intermediateVars, depth);

        if (eval.isEmpty()) {
            // Unknown operand - skip this branch entirely rather than produce partial paths.
            return;
        }

        for (String val : eval) {
            if (resultValues.size() >= MAX_RESULTS) return;
            combineOperandsWithDeps(
                operands,
                index + 1,
                current + val,
                resultValues,
                visitedMethods,
                intermediateVars,
                depth
            );
        }
    }

    private static @NotNull Set<String> resolveEnumFieldAccess(
        @NotNull UQualifiedReferenceExpression qualifiedExpr,
        @NotNull Set<PsiMethod> visitedMethods,
        @NotNull Map<String, Set<String>> intermediateVars,
        int depth
    ) {
        Set<String> result = new HashSet<>();
        UExpression receiver = qualifiedExpr.getReceiver();
        String selectorName = qualifiedExpr.getResolvedName();

        if (!(receiver instanceof UQualifiedReferenceExpression receiverQualified)) return result;

        PsiElement baseResolved = receiverQualified.resolve();
        if (!(baseResolved instanceof PsiEnumConstant enumConst) || selectorName == null) return result;

        PsiClass enumClass = enumConst.getContainingClass();
        if (enumClass == null) return result;

        PsiField targetField = enumClass.findFieldByName(selectorName, false);
        if (targetField == null) return result;

        PsiMethod constructor = enumConst.resolveConstructor();
        if (constructor == null || enumConst.getArgumentList() == null) return result;

        PsiExpression[] args = enumConst.getArgumentList().getExpressions();
        PsiCodeBlock body = constructor.getBody();
        if (body == null) return result;

        PsiParameter[] ctorParams = constructor.getParameterList().getParameters();

        for (int i = 0; i < Math.min(args.length, ctorParams.length); i++) {
            for (PsiStatement statement : body.getStatements()) {
                if (statement instanceof PsiExpressionStatement exprStmt
                    && exprStmt.getExpression() instanceof PsiAssignmentExpression assignExpr
                    && assignExpr.getLExpression() instanceof PsiReferenceExpression leftRef
                    && assignExpr.getRExpression() instanceof PsiReferenceExpression rightRef
                    && rightRef.resolve() == ctorParams[i]
                    && targetField.getName().equals(leftRef.getReferenceName())) {

                    UExpression argUExpr = UastContextKt.toUElement(args[i], UExpression.class);
                    if (argUExpr != null)
                        addCapped(result, evaluate(argUExpr, visitedMethods, intermediateVars, depth + 1));
                    break;
                }
            }
            if (result.size() >= MAX_RESULTS) break;
        }

        return result;
    }

    /** Adds elements from {@code src} into {@code target}, stopping when the cap is reached. */
    private static void addCapped(@NotNull Set<String> target, @NotNull Set<String> src) {
        for (String s : src) {
            if (target.size() >= MAX_RESULTS) break;
            target.add(s);
        }
    }

}
