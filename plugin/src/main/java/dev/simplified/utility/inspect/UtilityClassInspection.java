package dev.simplified.utility.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reports {@code @UtilityClass} misuse while the file is being edited, and
 * offers the remedy the processor can only describe.
 *
 * <ul>
 *   <li>An instance field or method under the default
 *       {@code members = REQUIRE_STATIC} - ERROR, with an "add static" fix on
 *       the member itself.</li>
 *   <li>{@code constructorAccess = NONE} - ERROR, since every class has a
 *       constructor.</li>
 *   <li>An author-declared constructor, which defeats the annotation -
 *       WARNING.</li>
 *   <li>A target the annotation cannot be applied to at all - ERROR.</li>
 *   <li>{@code @ClassBuilder} on the same type, which generates instances of a
 *       type declared uninstantiable - ERROR.</li>
 * </ul>
 *
 * <p>The member check is the reason this exists. The default policy is to
 * report rather than rewrite, so annotating an existing class turns every
 * instance member into a compile error at once - and the remedy is purely
 * mechanical, which is exactly the shape a quick fix is for.
 */
public final class UtilityClassInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass target) {
                super.visitClass(target);
                PsiAnnotation annotation =
                    UtilityClassConstants.find(target, UtilityClassConstants.UTILITY_CLASS_FQN);
                if (annotation == null) return;
                if (checkTargetKind(holder, target, annotation)) return;

                checkClassBuilderClash(holder, target);
                // The mutator returns the moment it rejects NONE, so it never
                // reaches its own declared-constructor warning. Reporting one
                // here would be a second complaint about a constructor the
                // processor never considered.
                if (!checkConstructorAccess(holder, annotation)) {
                    checkDeclaredConstructor(holder, target, annotation);
                }
                if (UtilityClassConstants.makeStatic(annotation)) return;
                checkInstanceMembers(holder, target);
            }
        };
    }

    /**
     * Rejects the target shapes the processor refuses, reusing its reasons.
     *
     * @param holder the problems holder
     * @param target the annotated type
     * @param annotation the {@code @UtilityClass} annotation
     * @return whether the target was rejected, in which case nothing else is
     *         worth reporting
     */
    private static boolean checkTargetKind(ProblemsHolder holder, PsiClass target,
                                           PsiAnnotation annotation) {
        String reason = kindReason(target);
        if (reason != null) {
            holder.registerProblem(annotation,
                "@UtilityClass is only supported on classes - " + target.getName() + " is " + reason,
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        if (target.hasModifierProperty(PsiModifier.ABSTRACT)) {
            holder.registerProblem(annotation,
                "@UtilityClass cannot be applied to an abstract class - it would be made final",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        // A nested class has to be static all the way out: an inner class carries
        // a reference to its enclosing instance, so a throwing constructor makes
        // it unusable rather than uninstantiable.
        //
        // Walked with getContainingClass(), which is null for a local or
        // anonymous class and so ends the walk there. That is the processor's
        // reach, not a shortfall: a local class is never an element of any
        // round, so the annotation is a silent no-op on one and an error here
        // would be an error on source the build accepts.
        for (PsiClass enclosing = target; enclosing != null;
             enclosing = enclosing.getContainingClass()) {
            if (enclosing.getContainingClass() == null) continue;
            if (enclosing.hasModifierProperty(PsiModifier.STATIC)) continue;
            holder.registerProblem(annotation,
                "@UtilityClass requires a nested target to be static all the way out - "
                    + enclosing.getName() + " is an inner class",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        return false;
    }

    private static @Nullable String kindReason(PsiClass target) {
        if (target.isRecord()) return "a record, whose canonical constructor is its contract";
        if (target.isEnum()) return "an enum, which is already final with a private constructor";
        if (target.isAnnotationType()) return "an annotation type";
        if (target.isInterface()) return "an interface, which cannot be instantiated anyway";
        return null;
    }

    /**
     * {@code @ClassBuilder} builds instances of the type, which
     * {@code @UtilityClass} declares uninstantiable. Reported against the
     * builder, since it is the one whose generated members stop compiling.
     */
    private static void checkClassBuilderClash(ProblemsHolder holder, PsiClass target) {
        PsiAnnotation builder =
            UtilityClassConstants.find(target, UtilityClassConstants.CLASS_BUILDER_FQN);
        if (builder == null) return;
        holder.registerProblem(builder,
            "@ClassBuilder contradicts @UtilityClass - one builds instances of " + target.getName()
                + ", the other makes it uninstantiable. Drop whichever is wrong",
            ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * @param holder the problems holder
     * @param annotation the {@code @UtilityClass} annotation
     * @return whether {@code NONE} was reported, in which case the constructor
     *         the annotation would have retrofitted is no longer in question
     */
    private static boolean checkConstructorAccess(ProblemsHolder holder, PsiAnnotation annotation) {
        if (!UtilityClassConstants.constructorAccessIsNone(annotation)) return false;
        PsiAnnotationMemberValue value = UtilityClassConstants.written(annotation,
            UtilityClassConstants.ATTR_CONSTRUCTOR_ACCESS);
        holder.registerProblem(value == null ? annotation : value,
            "@UtilityClass(constructorAccess = NONE) is not expressible - every class has a "
                + "constructor, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC",
            ProblemHighlightType.GENERIC_ERROR);
        return true;
    }

    /**
     * A constructor the author wrote keeps its body, so the throwing one is
     * never synthesised and the class stays instantiable.
     */
    private static void checkDeclaredConstructor(ProblemsHolder holder, PsiClass target,
                                                 PsiAnnotation annotation) {
        boolean declared = false;
        for (PsiMethod method : UtilityClassConstants.ownMethods(target)) {
            if (method.isConstructor()) declared = true;
        }
        if (!declared) return;
        holder.registerProblem(annotation,
            "@UtilityClass will not synthesise a throwing constructor - " + target.getName()
                + " declares its own. Delete it, or drop the annotation if the class is meant to "
                + "be instantiable", ProblemHighlightType.WARNING);
    }

    /**
     * Reports every instance member the default policy refuses.
     *
     * <p>Registered on the member's name identifier rather than the whole
     * declaration, so the underline is tight and the fix has an unambiguous
     * owner. Nested types are skipped: they are governed by
     * {@code nestedTypes()}, which never reports.
     */
    private static void checkInstanceMembers(ProblemsHolder holder, PsiClass target) {
        for (PsiField field : UtilityClassConstants.ownFields(target)) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            report(holder, field.getNameIdentifier(), "field", field.getName());
        }
        for (PsiMethod method : UtilityClassConstants.ownMethods(target)) {
            if (method.isConstructor()) continue;
            if (method.hasModifierProperty(PsiModifier.STATIC)) continue;
            report(holder, method.getNameIdentifier(), "method", method.getName());
        }
    }

    private static void report(ProblemsHolder holder, @Nullable PsiIdentifier name, String kind,
                               String memberName) {
        if (name == null) return;
        holder.registerProblem(name,
            "@UtilityClass requires every member to be static - " + kind + " '" + memberName
                + "' is not. Add static, or write @UtilityClass(members = MAKE_STATIC) to have it "
                + "added implicitly",
            ProblemHighlightType.GENERIC_ERROR, new AddStaticFix(memberName));
    }

    // ------------------------------------------------------------------
    // Quick fix
    // ------------------------------------------------------------------

    private static final class AddStaticFix implements LocalQuickFix {

        private final @NotNull String memberName;

        AddStaticFix(@NotNull String memberName) {
            this.memberName = memberName;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Add 'static' modifier";
        }

        @Override
        public @NotNull String getName() {
            return "Add 'static' to '" + this.memberName + "'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;
            PsiModifierListOwner owner =
                PsiTreeUtil.getParentOfType(element, PsiModifierListOwner.class);
            if (owner == null) return;
            PsiModifierList modifiers = owner.getModifierList();
            if (modifiers == null) return;
            modifiers.setModifierProperty(PsiModifier.STATIC, true);
        }
    }

}
