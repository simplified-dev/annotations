package dev.simplified.resourcepath;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.util.xmlb.annotations.OptionTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Inspection processor that checks string literals annotated with {@code ResourcePath}
 * to verify if they correspond to existing resource paths on the classloader.
 * <p>
 * Supports an optional {@code base} parameter in the annotation to specify a base folder
 * under which the resource path is resolved.
 */
public class ResourcePathInspection extends LocalInspectionTool {

    @OptionTag("HIGHLIGHT_TYPE_BASE")
    public @NotNull ProblemHighlightType baseHighlightType = ProblemHighlightType.ERROR;

    /**
     * Extra paths (project-relative or absolute) treated as resource roots when resolving
     * {@code @ResourcePath} values. Useful for projects that generate resources outside the
     * standard {@code src/main/resources} convention (e.g. {@code build/resources/main},
     * {@code dist}, custom Gradle outputs).
     */
    @OptionTag("ADDITIONAL_RESOURCE_ROOTS")
    public @NotNull List<String> additionalResourceRoots = new ArrayList<>();

    /**
     * Glob patterns for files that should be skipped entirely by the inspection.
     * Examples: {@code **&#47;generated/**}, {@code *Test*.java}, {@code **&#47;vendored/**}.
     */
    @OptionTag("EXCLUDED_PATTERNS")
    public @NotNull List<String> excludedFilePatterns = new ArrayList<>();

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (isFileExcluded(holder.getFile(), this.excludedFilePatterns))
            return PsiElementVisitor.EMPTY_VISITOR;

        ResourcePathVisitor resourcePathVisitor = new ResourcePathVisitor(
            this, holder, this.baseHighlightType, this.additionalResourceRoots);

        // Note: we only hook entry points that can directly carry @ResourcePath (fields, call
        // arguments, enum constants). A bare string-literal visitor was removed because it
        // triggered a project-wide ReferencesSearch on every literal, which caused IDE freezes
        // in large utility files. Incremental re-analysis is handled by ResourcePathChangeService.
        return new JavaElementVisitor() {

            @Override
            public void visitEnumConstant(@NotNull PsiEnumConstant enumConstant) {
                resourcePathVisitor.inspectEnumArguments(enumConstant);
            }

            @Override
            public void visitField(@NotNull PsiField field) {
                resourcePathVisitor.inspectField(field);
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                resourcePathVisitor.inspectMethod(expression);
            }

        };
    }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(
            OptPane.group(
                "Highlight settings",
                OptPane.dropdown(
                    "baseHighlightType",
                    "Highlight for invalid base directory",
                    OptPane.option(ProblemHighlightType.ERROR, "Error"),
                    OptPane.option(ProblemHighlightType.WARNING, "Warning"),
                    OptPane.option(ProblemHighlightType.WEAK_WARNING, "Weak Warning"),
                    OptPane.option(ProblemHighlightType.GENERIC_ERROR_OR_WARNING, "Server Problem"),
                    OptPane.option(ProblemHighlightType.INFORMATION, "Information")
                )
            ),
            OptPane.group(
                "Resource roots",
                OptPane.stringList(
                    "additionalResourceRoots",
                    "Additional resource root paths (project-relative or absolute)"
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

    /** Package-private so ResourcePathUsageInspection can share the logic. */
    static boolean isFileExcluded(@Nullable PsiFile file, @NotNull List<String> patterns) {
        if (file == null || patterns.isEmpty()) return false;
        VirtualFile vf = file.getVirtualFile();
        if (vf == null) return false;
        String path = vf.getPath();

        for (String glob : patterns) {
            if (glob == null || glob.isBlank()) continue;
            Pattern regex = ResourcePathConstants.globToRegex(glob);
            if (regex.matcher(path).find()) return true;
        }
        return false;
    }

}
