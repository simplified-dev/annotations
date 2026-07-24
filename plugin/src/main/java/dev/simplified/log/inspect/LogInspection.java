package dev.simplified.log.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import dev.simplified.classbuilder.apt.NamePattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Reports {@code @Log} misuse while the file is being edited, restating what
 * the processor would say at build time:
 *
 * <ul>
 *   <li>An interface, record or annotation type target - ERROR, since none of
 *       them can hold the instance-free field the annotation generates.</li>
 *   <li>{@code name} suppressed - ERROR, since the field is the annotation's
 *       only output.</li>
 *   <li>{@code name} that cannot expand to a legal identifier - ERROR.</li>
 *   <li>log4j2 missing from the module's classpath - ERROR, since the library
 *       names the logger type textually and never supplies it.</li>
 *   <li>A target already declaring a field under the resolved name - WARNING,
 *       since the annotation then generates nothing.</li>
 * </ul>
 */
public final class LogInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass target) {
                super.visitClass(target);
                PsiAnnotation annotation = LogConstants.findLog(target);
                if (annotation == null) return;

                String kind = illegalKind(target);
                if (kind != null) {
                    holder.registerProblem(annotation,
                        "@Log is only supported on classes and enums - '" + target.getName() + "' is " + kind,
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                String pattern = LogConstants.namePattern(annotation);
                if (!NamePattern.emits(pattern)) {
                    holder.registerProblem(annotation,
                        "@Log(name) must not be suppressed - the logger field is the annotation's only output",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }
                String error = NamePattern.patternError(pattern, false);
                if (error != null) {
                    holder.registerProblem(annotation,
                        "@Log(name) '" + pattern + "' " + error,
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                // Mirrors the processor's classpath precondition. The augment
                // provider deliberately contributes an unresolved logger type
                // here, which reddens every call on the field but says nothing
                // about why - this names the missing artifact instead.
                if (JavaPsiFacade.getInstance(target.getProject())
                    .findClass(LogConstants.LOGGER_FQN, target.getResolveScope()) == null) {
                    holder.registerProblem(annotation,
                        "@Log needs log4j2 on this module's compile classpath - '"
                            + LogConstants.LOGGER_FQN + "' does not resolve, so add a dependency "
                            + "on 'org.apache.logging.log4j:log4j-api'",
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                String fieldName = LogConstants.resolvedFieldName(annotation, target);
                if (fieldName == null) return;
                // Only a field the target itself declares collides. An inherited
                // field - a private one above all - leaves the generated field
                // legal, so declared members are read rather than resolved.
                if (hasOwnField(target, fieldName)) {
                    holder.registerProblem(annotation,
                        "@Log generates nothing - '" + target.getName()
                            + "' already declares a field named '" + fieldName + "'",
                        ProblemHighlightType.WARNING);
                }
            }
        };
    }

    private static @Nullable String illegalKind(PsiClass target) {
        if (target.isAnnotationType()) return "an annotation type";
        if (target.isInterface()) return "an interface";
        if (target.isRecord()) return "a record";
        return null;
    }

    private static boolean hasOwnField(PsiClass target, String name) {
        Iterable<PsiField> fields = target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
        for (PsiField f : fields) {
            if (name.equals(f.getName())) return true;
        }
        return false;
    }
}
