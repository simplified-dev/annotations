package dev.simplified.args.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiType;
import dev.simplified.args.apt.ArgsMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reports the constructor annotations' misuse while the file is being edited,
 * rather than at the next build.
 *
 * <p>Every check here mirrors a diagnostic the processor already emits. The
 * value is not the message but its timing and its position: the processor can
 * only point at the type, while this can underline the offending attribute.
 */
public final class ArgsConstructorInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass target) {
                List<PsiAnnotation> written = ArgsConstants.written(target);
                if (written.isEmpty()) return;
                checkTargetKind(holder, target, written);
                if (!isSupportedKind(target)) return;

                Map<String, ArgsMode> emitted = new LinkedHashMap<>();
                for (PsiAnnotation annotation : written) {
                    ArgsMode mode = ArgsConstants.modeOf(annotation);
                    if (mode == null) continue;
                    checkAccess(holder, annotation, mode);
                    if (mode == ArgsMode.BUILDER) {
                        checkBuilderArgs(holder, target, annotation);
                        continue;
                    }
                    if (mode == ArgsMode.NONE) checkNoArgs(holder, target, annotation);
                    checkCollision(holder, target, annotation, mode, emitted);
                }
            }
        };
    }

    private static boolean isSupportedKind(PsiClass target) {
        return !target.isRecord() && !target.isInterface() && !target.isAnnotationType();
    }

    private static void checkTargetKind(ProblemsHolder holder, PsiClass target,
                                        List<PsiAnnotation> written) {
        if (isSupportedKind(target)) return;
        String reason = target.isRecord()
            ? "a record, whose canonical constructor is its contract"
            : target.isInterface() ? "an interface, which has no constructor" : "an annotation type";
        for (PsiAnnotation annotation : written) {
            holder.registerProblem(annotation,
                "Only supported on classes and enums - " + target.getName() + " is " + reason,
                ProblemHighlightType.GENERIC_ERROR);
        }
    }

    /**
     * {@code AccessLevel.NONE} is the absence of a member, so an annotation
     * whose whole job is to generate one has nothing left to do.
     */
    private static void checkAccess(ProblemsHolder holder, PsiAnnotation annotation, ArgsMode mode) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("access");
        if (value == null) return;
        if (ArgsConstants.accessKeyword(annotation, mode) != null) return;
        holder.registerProblem(value,
            mode.annotationName() + "(access = NONE) generates nothing - delete the annotation "
                + "instead", ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * {@code @BuilderArgsConstructor} names the field set of a generated
     * builder, so without one it names nothing.
     */
    private static void checkBuilderArgs(ProblemsHolder holder, PsiClass target,
                                         PsiAnnotation annotation) {
        if (target.getAnnotation(ArgsConstants.CLASS_BUILDER_FQN) != null) return;
        holder.registerProblem(annotation,
            "@BuilderArgsConstructor names the field set of a generated builder - " + target.getName()
                + " carries no @ClassBuilder. Write @AllArgsConstructor for every field instead",
            ProblemHighlightType.GENERIC_ERROR);
    }

    private static void checkNoArgs(ProblemsHolder holder, PsiClass target,
                                    PsiAnnotation annotation) {
        List<PsiField> unassigned = ArgsConstants.unassignedFinals(target);
        boolean force = ArgsConstants.force(annotation);
        if (unassigned.isEmpty()) {
            if (!force) return;
            PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("force");
            holder.registerProblem(value == null ? annotation : value,
                "force = true has nothing to assign - " + target.getName() + " declares no final "
                    + "field without an initializer", ProblemHighlightType.WARNING);
            return;
        }
        if (force) return;
        holder.registerProblem(annotation,
            "@NoArgsConstructor would leave final "
                + (unassigned.size() == 1 ? "field " : "fields ")
                + unassigned.stream().map(f -> "'" + f.getName() + "'")
                    .collect(Collectors.joining(", "))
                + " unassigned - give an initializer, or write force = true to accept the JVM zero "
                + "value", ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * Two annotations resolving to one signature. Reported against both, since
     * neither is more wrong than the other and javac would otherwise complain
     * about a duplicate constructor with nothing to click on.
     */
    private static void checkCollision(ProblemsHolder holder, PsiClass target,
                                       PsiAnnotation annotation, ArgsMode mode,
                                       Map<String, ArgsMode> emitted) {
        List<String> types = new ArrayList<>();
        for (PsiField field : ArgsConstants.select(target, mode, List.of())) {
            PsiType type = field.getType();
            types.add(type.getCanonicalText());
        }
        String signature = String.join(", ", types);
        ArgsMode clash = emitted.putIfAbsent(signature, mode);
        if (clash == null) return;
        holder.registerProblem(annotation,
            clash.annotationName() + " and " + mode.annotationName() + " both generate "
                + target.getName() + "(" + signature + ") - only one of them can",
            ProblemHighlightType.GENERIC_ERROR);
    }

}
