package dev.simplified.classbuilder.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.BuilderScheme;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.editor.BuilderSite;
import dev.simplified.classbuilder.editor.MergedSlotStorage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Reports the entry points a {@code @ClassBuilder} skips because the builder the
 * target declares has no constructor they can call.
 *
 * <p>Every entry point instantiates the builder with the seeds, in parameter
 * order, and a declared builder's constructors are the author's, so one
 * declaring no constructor taking the seeds' types in that order leaves
 * {@code builder()}, {@code from(T)} and {@code mutate()} nothing to call, and
 * so does one whose constructor taking them declares a throws clause. The
 * processor skips them with a note and the
 * augment provider withholds them from completion through the same
 * {@link ClassBuilderConstants#withholdsEntryPointsOnly} decision; this is the
 * account on screen of why they are missing, reported as a weak warning on the
 * annotation in the note's own text, {@link DeclaredBuilderShape#entryPointsSkipped}.
 *
 * <p>It is silent wherever the processor prints no note: on an annotated member
 * the processor refuses, which merges nothing; on an interface type target,
 * whose entry points call its sibling builder; on an abstract type target, which has
 * no entry points of its own; beside a declared shape the merge refuses or an
 * ancestor that blocks the builder, both errors of
 * {@link DeclaredBuilderShapeInspection}; and where every entry point the path
 * emits is named {@code NONE}.
 */
public class DeclaredBuilderSkipsEntryPointsInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (!ClassBuilderConstants.ANNOTATION_FQN.equals(annotation.getQualifiedName())) return;

                PsiModifierListOwner owner =
                    PsiTreeUtil.getParentOfType(annotation, PsiModifierListOwner.class);
                PsiMethod member = owner instanceof PsiMethod method ? method : null;
                boolean executable = member != null;
                PsiClass target = owner instanceof PsiClass cls
                    ? cls
                    : member != null ? member.getContainingClass() : null;
                if (target == null || target.getName() == null) return;
                // The processor refuses an instance method, a void one and a
                // member beside an annotated type before any merge, so only the
                // annotation it builds from can have entry points skipped.
                BuilderSite site = BuilderSite.of(target);
                if (site == null || !Objects.equals(site.executable(), member)) return;

                // An abstract type target emits no entry points of its own. An
                // executable one emits its builder(..) whatever encloses it.
                if (!executable && target.hasModifierProperty(PsiModifier.ABSTRACT)) return;

                NamingStyle style = ClassBuilderConstants.namingStyle(annotation);
                BuilderScheme names =
                    ClassBuilderConstants.builderScheme(annotation, style, target.getName());
                List<String> seeds = member == null ? List.of() : MergedSlotStorage.seedNames(member);
                List<String> seedTypes = member == null ? List.of() : MergedSlotStorage.seedTypes(member);
                if (!ClassBuilderConstants.withholdsEntryPointsOnly(target, names.type(), executable, seedTypes))
                    return;

                // A refused shape and a blocking ancestor stop the processor ahead
                // of the entry points, with an error and no note.
                if (ClassBuilderConstants.ancestorBlockingGeneration(target, names.type(), executable) != null)
                    return;
                PsiClass declared = ClassBuilderConstants.declaredBuilderOf(target, names.type());
                if (declared == null || declared.getName() == null) return;
                if (ClassBuilderConstants.mergeRejection(target, member, declared, names) != null) return;

                String note = DeclaredBuilderShape.entryPointsSkipped(declared.getName(), names,
                    executable, seeds, ClassBuilderConstants.skippedForAThrowsClause(declared, seedTypes));
                if (note != null) holder.registerProblem(annotation, note, ProblemHighlightType.WEAK_WARNING);
            }
        };
    }

}
