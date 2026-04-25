package dev.simplified.resourcepath;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.util.xmlb.annotations.OptionTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flags direct uses of a {@code @ResourcePath(base = "X") String param} inside the enclosing
 * method when the parameter is passed to a known resource-loading call without the base prefix.
 *
 * <p>Offers a quick-fix to wrap the argument as {@code "X/" + param}.
 *
 * <p>Also flags forwarding mismatches: passing a {@code @ResourcePath(base="X")} parameter into
 * another method parameter annotated {@code @ResourcePath(base="Y")} where {@code X != Y}.
 */
public class ResourcePathUsageInspection extends LocalInspectionTool {

    /**
     * Known resource-loading sinks: fully qualified method names -> zero-based argument
     * indexes that receive a resource path.
     */
    private static final Map<String, int[]> METHOD_SINKS = Map.ofEntries(
        Map.entry("java.lang.Class.getResource",                    new int[]{ 0 }),
        Map.entry("java.lang.Class.getResourceAsStream",            new int[]{ 0 }),
        Map.entry("java.lang.ClassLoader.getResource",              new int[]{ 0 }),
        Map.entry("java.lang.ClassLoader.getResourceAsStream",      new int[]{ 0 }),
        Map.entry("java.lang.ClassLoader.getResources",             new int[]{ 0 }),
        Map.entry("java.lang.ClassLoader.resources",                new int[]{ 0 }),
        Map.entry("java.lang.ClassLoader.getSystemResource",        new int[]{ 0 }),
        Map.entry("java.lang.ClassLoader.getSystemResourceAsStream",new int[]{ 0 }),
        Map.entry("java.lang.ClassLoader.getSystemResources",       new int[]{ 0 }),
        Map.entry("java.nio.file.Path.of",                          new int[]{ 0 }),
        Map.entry("java.nio.file.Paths.get",                        new int[]{ 0 })
    );

    /** Constructor sinks: fully qualified class name -> indexes of String arguments. */
    private static final Map<String, int[]> CTOR_SINKS = Map.ofEntries(
        Map.entry("java.io.File",           new int[]{ 0 }),
        Map.entry("java.io.FileInputStream",  new int[]{ 0 }),
        Map.entry("java.io.FileOutputStream", new int[]{ 0 }),
        Map.entry("java.io.FileReader",     new int[]{ 0 }),
        Map.entry("java.io.FileWriter",     new int[]{ 0 }),
        Map.entry("java.io.RandomAccessFile", new int[]{ 0 })
    );

    @OptionTag("CHECK_SINKS")
    public boolean checkSinks = true;

    @OptionTag("CHECK_FORWARDING")
    public boolean checkForwarding = true;

    /** Severity for a sink-check violation (parameter passed raw into a resource-loader). */
    @OptionTag("SINK_HIGHLIGHT")
    public @NotNull ProblemHighlightType sinkHighlightType = ProblemHighlightType.WARNING;

    /** Severity for a forwarding-mismatch violation (base differs across the call boundary). */
    @OptionTag("FORWARD_HIGHLIGHT")
    public @NotNull ProblemHighlightType forwardingHighlightType = ProblemHighlightType.WARNING;

    /** File-pattern exclusions, shared with {@link ResourcePathInspection#excludedFilePatterns}. */
    @OptionTag("EXCLUDED_PATTERNS")
    public @NotNull List<String> excludedFilePatterns = new ArrayList<>();

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (ResourcePathInspection.isFileExcluded(holder.getFile(), this.excludedFilePatterns))
            return PsiElementVisitor.EMPTY_VISITOR;

        final boolean doSinks = this.checkSinks;
        final boolean doForwarding = this.checkForwarding;
        final ProblemHighlightType sinkSeverity = this.sinkHighlightType;
        final ProblemHighlightType forwardSeverity = this.forwardingHighlightType;

        return new JavaElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                PsiMethod target = call.resolveMethod();
                if (target == null) return;

                PsiExpressionList argList = call.getArgumentList();
                PsiExpression[] args = argList.getExpressions();
                PsiParameter[] params = target.getParameterList().getParameters();

                if (doSinks) {
                    String fqn = methodFqn(target);
                    int[] indexes = METHOD_SINKS.get(fqn);
                    if (indexes != null) {
                        for (int idx : indexes) {
                            if (idx < args.length)
                                checkSinkArg(args[idx], holder, sinkSeverity);
                        }
                    }
                }

                if (doForwarding) {
                    for (int i = 0; i < Math.min(args.length, params.length); i++) {
                        checkForwarding(args[i], params[i], holder, forwardSeverity);
                    }
                }
            }

            @Override
            public void visitNewExpression(@NotNull PsiNewExpression newExpr) {
                PsiJavaCodeReferenceElement classRef = newExpr.getClassReference();
                if (classRef == null) return;
                String classFqn = classRef.getQualifiedName();
                if (classFqn == null) return;

                PsiExpressionList argList = newExpr.getArgumentList();
                if (argList == null) return;
                PsiExpression[] args = argList.getExpressions();

                if (doSinks) {
                    int[] indexes = CTOR_SINKS.get(classFqn);
                    if (indexes != null) {
                        for (int idx : indexes) {
                            if (idx < args.length) checkSinkArg(args[idx], holder, sinkSeverity);
                        }
                    }
                }

                if (doForwarding) {
                    PsiMethod ctor = newExpr.resolveConstructor();
                    if (ctor != null) {
                        PsiParameter[] params = ctor.getParameterList().getParameters();
                        for (int i = 0; i < Math.min(args.length, params.length); i++) {
                            checkForwarding(args[i], params[i], holder, forwardSeverity);
                        }
                    }
                }
            }
        };
    }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(
            OptPane.group(
                "Checks",
                OptPane.checkbox("checkSinks",
                    "Warn when a @ResourcePath(base=\"X\") parameter is passed to a resource-loading call without the 'X/' prefix"),
                OptPane.checkbox("checkForwarding",
                    "Warn when a @ResourcePath parameter is forwarded to another @ResourcePath parameter with a mismatched base")
            ),
            OptPane.group(
                "Highlight settings",
                OptPane.dropdown(
                    "sinkHighlightType",
                    "Highlight for sink-check violations",
                    OptPane.option(ProblemHighlightType.ERROR, "Error"),
                    OptPane.option(ProblemHighlightType.WARNING, "Warning"),
                    OptPane.option(ProblemHighlightType.WEAK_WARNING, "Weak Warning"),
                    OptPane.option(ProblemHighlightType.GENERIC_ERROR_OR_WARNING, "Server Problem"),
                    OptPane.option(ProblemHighlightType.INFORMATION, "Information")
                ),
                OptPane.dropdown(
                    "forwardingHighlightType",
                    "Highlight for forwarding-mismatch violations",
                    OptPane.option(ProblemHighlightType.ERROR, "Error"),
                    OptPane.option(ProblemHighlightType.WARNING, "Warning"),
                    OptPane.option(ProblemHighlightType.WEAK_WARNING, "Weak Warning"),
                    OptPane.option(ProblemHighlightType.GENERIC_ERROR_OR_WARNING, "Server Problem"),
                    OptPane.option(ProblemHighlightType.INFORMATION, "Information")
                )
            ),
            OptPane.group(
                "File exclusions",
                OptPane.stringList(
                    "excludedFilePatterns",
                    "Exclude files matching these glob patterns (e.g. **/generated/**)"
                )
            )
        );
    }

    // ─── Sink check ───────────────────────────────────────────────────────────

    private static void checkSinkArg(
        @NotNull PsiExpression arg,
        @NotNull ProblemsHolder holder,
        @NotNull ProblemHighlightType severity
    ) {
        PsiParameter param = asResourcePathParam(arg);
        if (param == null) return;

        String base = ResourcePathConstants.getBase(param.getAnnotation(ResourcePathConstants.ANNOTATION_FQN));
        if (base.isEmpty()) return;

        holder.registerProblem(arg,
            "'" + param.getName() + "' is declared with @ResourcePath(base=\"" + base
            + "\") but passed without the '" + base + "/' prefix",
            severity,
            new PrependBaseFix(base));
    }

    // ─── Forwarding check ─────────────────────────────────────────────────────

    private static void checkForwarding(
        @NotNull PsiExpression arg,
        @NotNull PsiParameter targetParam,
        @NotNull ProblemsHolder holder,
        @NotNull ProblemHighlightType severity
    ) {
        PsiParameter srcParam = asResourcePathParam(arg);
        if (srcParam == null) return;

        PsiAnnotation srcAnno = srcParam.getAnnotation(ResourcePathConstants.ANNOTATION_FQN);
        PsiAnnotation tgtAnno = targetParam.getAnnotation(ResourcePathConstants.ANNOTATION_FQN);
        if (srcAnno == null || tgtAnno == null) return;

        String srcBase = ResourcePathConstants.getBase(srcAnno);
        String tgtBase = ResourcePathConstants.getBase(tgtAnno);

        if (srcBase.equals(tgtBase)) return; // Matching bases - flow is consistent

        String message;
        LocalQuickFix fix;
        if (srcBase.isEmpty()) {
            message = "'" + srcParam.getName() + "' has no base but target expects base \"" + tgtBase + "\"";
            fix = null;
        } else if (tgtBase.isEmpty()) {
            message = "'" + srcParam.getName() + "' has base \"" + srcBase
                + "\" but target expects no base - add the '" + srcBase + "/' prefix";
            fix = new PrependBaseFix(srcBase);
        } else {
            message = "Base mismatch: '" + srcParam.getName() + "' has base \"" + srcBase
                + "\" but target expects base \"" + tgtBase + "\"";
            fix = null;
        }

        if (fix != null)
            holder.registerProblem(arg, message, severity, fix);
        else
            holder.registerProblem(arg, message, severity);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the resolved {@link PsiParameter} if {@code expr} is a direct reference to one, else null. */
    private static @Nullable PsiParameter asResourcePathParam(@NotNull PsiExpression expr) {
        if (!(expr instanceof PsiReferenceExpression ref)) return null;
        PsiElement resolved = ref.resolve();
        if (!(resolved instanceof PsiParameter param)) return null;
        if (param.getAnnotation(ResourcePathConstants.ANNOTATION_FQN) == null) return null;
        return param;
    }

    private static @NotNull String methodFqn(@NotNull PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        String classFqn = owner == null ? "" : owner.getQualifiedName();
        return (classFqn == null ? "" : classFqn + ".") + method.getName();
    }

    // ─── Quick-fix ────────────────────────────────────────────────────────────

    private static final class PrependBaseFix implements LocalQuickFix {
        private final @NotNull String base;

        PrependBaseFix(@NotNull String base) {
            this.base = base;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Prepend '" + this.base + "/' prefix";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (!(element instanceof PsiExpression arg)) return;

            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            String text = "\"" + this.base + "/\" + " + arg.getText();
            PsiExpression replacement = factory.createExpressionFromText(text, arg);
            arg.replace(replacement);
        }
    }

}
