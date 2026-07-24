package dev.simplified.equality.inspect;

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
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.equality.inspect.WholeObjectConstants.Policy;
import dev.simplified.equality.inspect.WholeObjectConstants.Selected;
import dev.simplified.equality.inspect.WholeObjectConstants.Signature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports {@code @ToString} misuse while the file is being edited.
 *
 * <p>Pure error-mirroring: every check restates a diagnostic the processor
 * already emits, so what the inspection adds is timing and position rather than
 * the message. It carries the one asymmetry with the equality pair as well -
 * a target that already declares {@code toString} keeps the written member and
 * is reported as a prompt rather than a rejection, because a diagnostic method
 * the author wrote is not a relation anything else depends on.
 */
public final class ToStringInspection extends LocalInspectionTool {

    private static final @NotNull String FQN = WholeObjectConstants.TO_STRING_FQN;
    private static final @NotNull String EXCLUDE_FQN = WholeObjectConstants.TO_STRING_EXCLUDE_FQN;
    private static final @NotNull String INCLUDE_FQN = WholeObjectConstants.TO_STRING_INCLUDE_FQN;
    private static final @NotNull Policy POLICY = WholeObjectConstants.TO_STRING_POLICY;
    private static final @NotNull String LABEL = POLICY.label();

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass target) {
                super.visitClass(target);
                PsiAnnotation annotation = WholeObjectConstants.find(target, FQN);
                if (annotation == null) return;
                if (checkTargetKind(holder, target, annotation)) return;
                if (checkCollision(holder, target, annotation)) return;
                if (checkFinalInSupertype(holder, target, annotation)) return;

                checkCallSuper(holder, target, annotation);
                checkNarrowing(holder, target, annotation);
            }

            @Override
            public void visitField(@NotNull PsiField field) {
                super.visitField(field);
                if (field instanceof PsiEnumConstant) return;
                checkMarkers(holder, field, field.getName(), field.getContainingClass());
            }

            @Override
            public void visitRecordComponent(@NotNull PsiRecordComponent component) {
                super.visitRecordComponent(component);
                checkMarkers(holder, component, component.getName(),
                    component.getContainingClass());
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);
                checkIncludedMethod(holder, method);
            }
        };
    }

    // ------------------------------------------------------------------
    // Refusals the processor also makes
    // ------------------------------------------------------------------

    /**
     * Rejects the target kinds that carry no instance state to print.
     *
     * <p>An interface carrying {@code @ClassBuilder} is deliberately silent: the
     * concrete class the builder emits for it already renders its own accessors,
     * in the shape this annotation's default style produces.
     *
     * @param holder the problems holder
     * @param target the annotated type
     * @param annotation the {@code @ToString} annotation
     * @return whether nothing further is worth reporting
     */
    private static boolean checkTargetKind(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                           @NotNull PsiAnnotation annotation) {
        if (target.isAnnotationType()) {
            holder.registerProblem(annotation,
                LABEL + " on the annotation type " + target.getName()
                    + " - only classes and records carry the instance state this reads",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        if (target.isInterface()) {
            if (WholeObjectConstants.has(target, WholeObjectConstants.CLASS_BUILDER_FQN)) return true;
            holder.registerProblem(annotation,
                LABEL + " on the interface " + target.getName() + ", which declares no state to "
                    + "print and no body to generate into. Write it on the implementing type, or "
                    + "add @ClassBuilder so an implementation exists for it to reach",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        if (target.isEnum()) {
            holder.registerProblem(annotation,
                LABEL + " on the enum " + target.getName() + " - only classes and records carry the "
                    + "instance state this reads",
                ProblemHighlightType.GENERIC_ERROR);
            return true;
        }
        return false;
    }

    /**
     * Reports a {@code toString} the author already wrote, which is kept.
     *
     * <p>Deliberately not the error the equality pair reports for the same
     * shape. A written {@code toString} is a diagnostic rather than a contract,
     * so the annotation becoming a no-op costs nothing beyond the reader's
     * expectation - which is exactly what a prompt is for.
     */
    private static boolean checkCollision(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                          @NotNull PsiAnnotation annotation) {
        if (WholeObjectConstants.declares(target, Signature.TO_STRING) == null) return false;
        holder.registerProblem(annotation,
            LABEL + " on " + target.getName() + ", which already declares toString - the written "
                + "member is kept and the annotation generates nothing",
            ProblemHighlightType.WEAK_WARNING);
        return true;
    }

    /** Reports a supertype declaring {@code toString} final, which no override can compile past. */
    private static boolean checkFinalInSupertype(@NotNull ProblemsHolder holder,
                                                 @NotNull PsiClass target,
                                                 @NotNull PsiAnnotation annotation) {
        PsiClass owner =
            WholeObjectConstants.finalSupertypeMember(target, Signature.TO_STRING);
        if (owner == null) return false;
        holder.registerProblem(annotation,
            LABEL + " cannot generate toString on " + target.getName() + " - "
                + owner.getQualifiedName() + " declares it final",
            ProblemHighlightType.GENERIC_ERROR);
        return true;
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /**
     * Reports {@code callSuper = YES} on a type whose superclass supplies
     * nothing to call - the inherited implementation prints a type name and an
     * identity hash, which is not state worth prefixing the dump with.
     */
    private static void checkCallSuper(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                       @NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value =
            WholeObjectConstants.written(annotation, WholeObjectConstants.ATTR_CALL_SUPER);
        if (value == null) return;
        if (!"YES".equals(WholeObjectConstants.enumAttr(annotation,
            WholeObjectConstants.ATTR_CALL_SUPER, "AUTO"))) return;
        if (WholeObjectConstants.callableSuperclass(target) != null) return;
        holder.registerProblem(value,
            LABEL + "(callSuper = YES) on " + target.getName() + ", whose superclass supplies no "
                + "implementation to call - the inherited one is identity-based and would defeat "
                + "the generated member",
            ProblemHighlightType.GENERIC_ERROR);
    }

    /**
     * Reports the two narrowing attributes contradicting each other or naming a
     * member the selection never reaches.
     *
     * <p>The unmatched-name report is the check earning those attributes their
     * keep: a rename leaves the string behind, and the member it used to name
     * silently rejoins or leaves the dump with nothing failing.
     */
    private static void checkNarrowing(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                       @NotNull PsiAnnotation annotation) {
        List<String> of = WholeObjectConstants.names(annotation, WholeObjectConstants.ATTR_OF);
        List<String> exclude =
            WholeObjectConstants.names(annotation, WholeObjectConstants.ATTR_EXCLUDE);
        if (!of.isEmpty() && !exclude.isEmpty()) {
            holder.registerProblem(annotation,
                LABEL + " sets both 'of' and 'exclude' - they are two spellings of one choice and "
                    + "cannot both apply",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        if (of.isEmpty() && exclude.isEmpty()) return;
        List<String> selectable = new ArrayList<>();
        for (Selected member : WholeObjectConstants.candidates(target, POLICY)) {
            selectable.add(member.name());
        }
        reportUnmatched(holder, target, annotation, WholeObjectConstants.ATTR_OF, selectable);
        reportUnmatched(holder, target, annotation, WholeObjectConstants.ATTR_EXCLUDE, selectable);
    }

    private static void reportUnmatched(@NotNull ProblemsHolder holder, @NotNull PsiClass target,
                                        @NotNull PsiAnnotation annotation, @NotNull String attribute,
                                        @NotNull List<String> selectable) {
        for (PsiAnnotationMemberValue entry : WholeObjectConstants.entries(annotation, attribute)) {
            String name = WholeObjectConstants.stringValue(entry);
            if (name == null || selectable.contains(name)) continue;
            PsiMethod candidate = WholeObjectConstants.includableCandidate(target, name);
            String because = candidate == null
                ? "which is not a member this selection reaches"
                : "which is a method rather than a field - mark it @"
                    + WholeObjectConstants.simpleName(POLICY.includeFqn())
                    + " to make it a member";
            holder.registerProblem(entry,
                LABEL + "(" + attribute + ") names '" + name + "', " + because,
                ProblemHighlightType.GENERIC_ERROR);
        }
    }

    // ------------------------------------------------------------------
    // Members
    // ------------------------------------------------------------------

    /**
     * Reports the marker pair on a field or a record component.
     *
     * @param holder the problems holder
     * @param member the field or component carrying the markers
     * @param name its name
     * @param owner the type declaring it
     */
    private static void checkMarkers(@NotNull ProblemsHolder holder,
                                     @NotNull PsiModifierListOwner member, @Nullable String name,
                                     @Nullable PsiClass owner) {
        if (name == null || owner == null) return;
        PsiAnnotation exclude = WholeObjectConstants.find(member, EXCLUDE_FQN);
        PsiAnnotation include = WholeObjectConstants.find(member, INCLUDE_FQN);
        if (exclude == null && include == null) {
            checkBuilderIgnore(holder, member, name, owner, null);
            return;
        }
        if (exclude != null && include != null) {
            holder.registerProblem(include,
                "'" + name + "' carries both the include and exclude markers for " + LABEL
                    + " - keep one",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        if (!WholeObjectConstants.has(owner, FQN)) {
            reportInertMarker(holder, exclude != null ? exclude : include, owner);
            return;
        }
        if (include != null && WholeObjectConstants.has(member, WholeObjectConstants.LAZY_FQN)) {
            holder.registerProblem(include,
                "'" + name + "' is @Lazy, so " + LABEL + " cannot read it directly - the field's "
                    + "storage is the wrapper, and forcing the value from inside a dump is a side "
                    + "effect a debugger should not cause. Declare a zero-arg method carrying the "
                    + "include marker if the memoized value belongs here",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        checkBuilderIgnore(holder, member, name, owner, exclude);
    }

    /**
     * Reports the marker on a method, which the include marker can promote to a
     * printed term only where it holds a per-instance value to render.
     */
    private static void checkIncludedMethod(@NotNull ProblemsHolder holder,
                                            @NotNull PsiMethod method) {
        PsiAnnotation include = WholeObjectConstants.find(method, INCLUDE_FQN);
        PsiAnnotation exclude = WholeObjectConstants.find(method, EXCLUDE_FQN);
        if (include == null && exclude == null) return;
        PsiClass owner = method.getContainingClass();
        if (owner == null) return;
        if (include != null && exclude != null) {
            holder.registerProblem(include,
                "'" + method.getName() + "' carries both the include and exclude markers for "
                    + LABEL + " - keep one",
                ProblemHighlightType.GENERIC_ERROR);
            return;
        }
        if (!WholeObjectConstants.has(owner, FQN)) {
            reportInertMarker(holder, include != null ? include : exclude, owner);
            return;
        }
        if (include == null || WholeObjectConstants.includable(method)) return;
        String reason;
        if (method.hasModifierProperty(PsiModifier.STATIC)) {
            reason = "is static, so it holds no per-instance value to contribute to " + LABEL;
        } else if (!method.getParameterList().isEmpty()) {
            reason = "takes parameters, so " + LABEL + " has nothing to pass it - only a zero-arg "
                + "method can be included";
        } else {
            reason = "returns void, so it produces no value for " + LABEL;
        }
        holder.registerProblem(include, "'" + method.getName() + "' " + reason,
            ProblemHighlightType.GENERIC_ERROR);
    }

    private static void reportInertMarker(@NotNull ProblemsHolder holder,
                                          @NotNull PsiAnnotation marker, @NotNull PsiClass owner) {
        String written = marker.getNameReferenceElement() == null
            ? "The marker"
            : "@" + marker.getNameReferenceElement().getReferenceName();
        holder.registerProblem(marker,
            written + " is never read - " + owner.getName() + " carries no " + LABEL
                + " for it to narrow",
            ProblemHighlightType.WARNING);
    }

    /**
     * Reports a field the builder skips but the dump still prints.
     *
     * <p>The two selections are deliberately independent - {@code @BuilderIgnore}
     * says the caller does not supply the field, not that it is uninteresting -
     * so this is a prompt rather than a rejection.
     *
     * <p>The prompt is only true of a member the dump <b>does</b> print, which is
     * why the resolved selection is consulted rather than the annotation's
     * presence. A {@code @Lazy} field is already dropped, and so is a static, a
     * {@code $} name and anything either narrowing attribute removes - on all of
     * which the report would name a reconciliation that has already happened and
     * offer a marker that changes nothing. The walk is paid only once the ignore
     * marker is found.
     */
    private static void checkBuilderIgnore(@NotNull ProblemsHolder holder,
                                           @NotNull PsiModifierListOwner member,
                                           @NotNull String name, @NotNull PsiClass owner,
                                           @Nullable PsiAnnotation exclude) {
        if (exclude != null) return;
        PsiAnnotation type = WholeObjectConstants.find(owner, FQN);
        if (type == null) return;
        PsiAnnotation ignore =
            WholeObjectConstants.find(member, WholeObjectConstants.BUILDER_IGNORE_FQN);
        if (ignore == null) return;
        if (!WholeObjectConstants.reaches(owner, type, POLICY, name)) return;
        holder.registerProblem(ignore,
            "@BuilderIgnore does not take '" + name + "' out of " + LABEL + " - the builder's field "
                + "set is not the printed set, and a field the caller does not supply is still "
                + "state a dump wants to show. Write @ToStringExclude as well if it should be left "
                + "out",
            ProblemHighlightType.WEAK_WARNING, excludeFixes(type, name));
    }

    /**
     * The exclude-marker fix, offered only where writing the marker would leave
     * one statement about the member rather than two.
     *
     * <p>A member the type-level {@code of} or {@code exclude} already names is
     * spoken for there, and the marker takes it out of the selection those
     * attributes are matched against - so the offered fix would trade a prompt
     * for the hard error that reports a name the selection can no longer reach.
     * Editing the list instead is not a local fix and not a safe one: dropping
     * the last name from {@code of} widens the dump to every field, which is the
     * opposite of what it was written to say. The report stands without a fix,
     * and which of the two statements to keep is the author's to choose.
     */
    private static @NotNull LocalQuickFix[] excludeFixes(@NotNull PsiAnnotation type,
                                                         @NotNull String name) {
        return WholeObjectConstants.namedByNarrowing(type, name)
            ? new LocalQuickFix[0]
            : new LocalQuickFix[]{new AddExcludeFix(name)};
    }

    // ------------------------------------------------------------------
    // Quick fix
    // ------------------------------------------------------------------

    /** Writes the exclude marker onto the member the report was about. */
    private static final class AddExcludeFix implements LocalQuickFix {

        private final @NotNull String memberName;

        AddExcludeFix(@NotNull String memberName) {
            this.memberName = memberName;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Exclude the member with @ToStringExclude";
        }

        @Override
        public @NotNull String getName() {
            return "Exclude '" + this.memberName + "' with @ToStringExclude";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiModifierListOwner member = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(),
                PsiModifierListOwner.class, false);
            if (member == null) return;
            PsiModifierList modifiers = member.getModifierList();
            if (modifiers == null) return;
            if (WholeObjectConstants.find(member, EXCLUDE_FQN) != null) return;
            JavaCodeStyleManager.getInstance(project)
                .shortenClassReferences(modifiers.addAnnotation(EXCLUDE_FQN));
        }
    }

}
