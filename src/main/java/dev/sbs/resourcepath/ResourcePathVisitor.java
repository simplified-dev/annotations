package dev.sbs.resourcepath;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;
import org.jetbrains.uast.UField;
import org.jetbrains.uast.UastContextKt;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class ResourcePathVisitor {

    private final @NotNull Set<PsiAnnotation> visitedAnnotations = new HashSet<>();
    private final @NotNull Set<PsiElement> inspectedExpressions = new HashSet<>();
    private final @NotNull LocalInspectionTool inspectionTool;
    private final @NotNull ProblemsHolder holder;
    private final @NotNull ProblemHighlightType baseHighlightType;
    private final @NotNull List<String> additionalResourceRoots;

    public ResourcePathVisitor(
        @NotNull LocalInspectionTool inspectionTool,
        @NotNull ProblemsHolder holder,
        @NotNull ProblemHighlightType baseHighlightType
    ) {
        this(inspectionTool, holder, baseHighlightType, Collections.emptyList());
    }

    public ResourcePathVisitor(
        @NotNull LocalInspectionTool inspectionTool,
        @NotNull ProblemsHolder holder,
        @NotNull ProblemHighlightType baseHighlightType,
        @NotNull List<String> additionalResourceRoots
    ) {
        this.inspectionTool = inspectionTool;
        this.holder = holder;
        this.baseHighlightType = baseHighlightType;
        this.additionalResourceRoots = additionalResourceRoots;
    }

    public void inspectMethod(@NotNull PsiMethodCallExpression methodCallExpr) {
        UCallExpression callExpr = UastContextKt.toUElement(methodCallExpr, UCallExpression.class);
        if (callExpr == null) return;
        PsiMethod method = callExpr.resolve();
        if (method == null) return;
        PsiParameter[] parameters = method.getParameterList().getParameters();
        List<UExpression> arguments = callExpr.getValueArguments();

        // If the called method is itself @ResourcePath-annotated, evaluate its return values.
        PsiAnnotation methodAnno = method.getAnnotation(ResourcePathConstants.ANNOTATION_FQN);
        if (methodAnno != null)
            this.inspectArgument(callExpr, methodAnno);

        // Check each argument against its @ResourcePath-annotated parameter.
        for (int i = 0; i < Math.min(arguments.size(), parameters.length); i++)
            this.inspectArgument(arguments.get(i), parameters[i].getAnnotation(ResourcePathConstants.ANNOTATION_FQN));
    }

    public void inspectField(@NotNull PsiField field) {
        PsiAnnotation fieldAnno = field.getAnnotation(ResourcePathConstants.ANNOTATION_FQN);
        if (fieldAnno == null) return; // Early exit: no reason to process unannotated fields

        UField uField = UastContextKt.toUElement(field, UField.class);
        if (uField == null) return;
        UExpression expression = uField.getUastInitializer();
        if (expression == null) return;

        if (expression instanceof PsiEnumConstant enumConst)
            this.inspectEnumArguments(enumConst);
        else
            this.inspectArgument(expression, fieldAnno);
    }

    public void inspectEnumArguments(@NotNull PsiEnumConstant enumConstant) {
        PsiMethod constructor = enumConstant.resolveConstructor();
        if (constructor == null) return;
        PsiExpressionList args = enumConstant.getArgumentList();
        if (args == null) return;
        PsiParameter[] parameters = constructor.getParameterList().getParameters();
        PsiExpression[] arguments = args.getExpressions();

        for (int i = 0; i < Math.min(arguments.length, parameters.length); i++) {
            PsiAnnotation paramAnno = parameters[i].getAnnotation(ResourcePathConstants.ANNOTATION_FQN);
            if (paramAnno == null) continue;
            UExpression uArg = UastContextKt.toUElement(arguments[i], UExpression.class);
            if (uArg == null) continue;
            this.inspectArgument(uArg, paramAnno);
        }
    }

    private void inspectArgument(
        @NotNull UExpression expression,
        @Nullable PsiAnnotation annotation
    ) {
        if (annotation == null) return;

        if (this.visitedAnnotations.add(annotation)) {
            if (!this.validateBaseFolder(annotation))
                return;
        }

        PsiElement source = expression.getSourcePsi();
        if (source == null || !this.inspectedExpressions.add(source)) return;
        Set<String> resolvedValues = StringExpressionEvaluator.evaluate(expression);

        for (String value : resolvedValues) {
            if (value == null || value.isEmpty()) continue;
            String resourcePath = this.resolveFullPath(annotation, value);
            if (this.resourceExists(resourcePath, this.holder.getProject(), false)) continue;
            this.holder.registerProblem(source, "Missing Resource File: " + resourcePath, this.getHighlightType());
        }
    }

    /**
     * Validates that the base folder specified in the given {@code ResourcePath} annotation exists.
     * If the base folder does not exist, registers a problem with the provided {@link ProblemsHolder}.
     */
    private boolean validateBaseFolder(@NotNull PsiAnnotation annotation) {
        String base = ResourcePathConstants.getBase(annotation);

        if (!resourceExists(base, this.holder.getProject(), true)) {
            PsiNameValuePair[] attributes = annotation.getParameterList().getAttributes();

            for (PsiNameValuePair pair : attributes) {
                if (ResourcePathConstants.BASE_ATTR.equals(pair.getName()) && pair.getValue() != null) {
                    this.holder.registerProblem(pair.getValue(), "Invalid Base Directory: " + base, this.baseHighlightType);
                    return false;
                }
            }
        }

        return true;
    }

    private String resolveFullPath(@NotNull PsiAnnotation annotation, @NotNull String value) {
        String base = ResourcePathConstants.getBase(annotation);
        return base.isEmpty() ? value : base + "/" + value;
    }

    /**
     * Checks if the resource exists in the given project's resource directory, searching the
     * project's content source roots plus any user-configured {@link #additionalResourceRoots}.
     */
    private boolean resourceExists(String path, @NotNull Project project, boolean isDirectory) {
        if (path == null || path.trim().isEmpty()) return true;
        String normalizedPath = path.replace('\\', '/');

        // 1. Project content source roots
        for (VirtualFile root : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            VirtualFile candidate = root.findFileByRelativePath(normalizedPath);
            if (candidate != null) return isDirectory == candidate.isDirectory();
        }

        // 2. User-configured additional roots
        String basePath = project.getBasePath();
        for (String extra : this.additionalResourceRoots) {
            if (extra == null || extra.isBlank()) continue;
            String resolved = java.nio.file.Path.of(extra).isAbsolute() || basePath == null
                ? extra
                : basePath + "/" + extra;
            VirtualFile root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(resolved);
            if (root == null) continue;
            VirtualFile candidate = root.findFileByRelativePath(normalizedPath);
            if (candidate != null) return isDirectory == candidate.isDirectory();
        }

        return false;
    }

    /**
     * Determines the appropriate highlight type based on the current inspection profile severity.
     */
    private @NotNull ProblemHighlightType getHighlightType() {
        InspectionProfileImpl profile = InspectionProjectProfileManager.getInstance(this.holder.getProject()).getCurrentProfile();
        InspectionToolWrapper<?, ?> inspectionTool = profile.getInspectionTool(this.inspectionTool.getShortName(), this.holder.getFile());
        if (inspectionTool == null || inspectionTool.getDisplayKey() == null) return ProblemHighlightType.ERROR;
        HighlightSeverity severity = profile.getErrorLevel(inspectionTool.getDisplayKey(), this.holder.getFile()).getSeverity();
        return mapSeverityToHighlightType(severity);
    }

    private @NotNull ProblemHighlightType mapSeverityToHighlightType(@NotNull HighlightSeverity severity) {
        return switch (severity.getName()) {
            case "ERROR" -> ProblemHighlightType.ERROR;
            case "WARNING" -> ProblemHighlightType.WARNING;
            case "GENERIC_SERVER_ERROR_OR_WARNING" -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
            case "INFORMATION", "INFO" -> ProblemHighlightType.INFORMATION;
            default -> ProblemHighlightType.WEAK_WARNING;
        };
    }

}
