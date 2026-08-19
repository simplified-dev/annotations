package dev.simplified.shared.inspect;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Silences the inspections that read a field as uninitialized, unread,
 * needlessly a field, or holding a container nothing fills, on the fields a
 * generated member initializes and reads.
 *
 * <p>The inspection-level half of what
 * {@link GeneratedMemberHighlightFilter} does for compiler errors. Same cause -
 * an augmented member takes part in neither definite-assignment analysis nor
 * reference search - and the three tools land on the same declarations from
 * three directions.
 *
 * <p>{@code FieldCanBeLocal} is the one that matters most, because it is the
 * only one here that ships a quick fix: it offers to convert a field the
 * generated accessor is the sole reader of into a local variable, and taking
 * that offer deletes the field the accessor reads. A warning that is merely
 * wrong is noise; a warning whose fix breaks the build is worse than the
 * feature it is reporting on.
 *
 * <p>Suppression is per tool and per field, not blanket per class, so an
 * unannotated field on an annotated class keeps every report. The trade the
 * platform's API forces is that a tool suppressed on a field is suppressed
 * entirely there - {@code NullableProblems} covers more than the
 * not-initialized report this is aimed at. It is accepted rather than worked
 * around: the API offers no message, and the alternative is leaving a
 * guaranteed-wrong warning on every {@code @NotNull final} field in every
 * annotated class.
 *
 * <p>Which element of a declaration a tool reports on is the tool's choice - the
 * field, its name, or the nullability annotation the report is about - so the
 * field is resolved by walking up from whatever was handed in rather than by
 * expecting one of them. The tool set is tested before that walk because this
 * runs for every element every tool in the IDE reports on in a Java file, and a
 * lookup against two small sets of names is what keeps the other tools free.
 *
 * @see GeneratedFieldAccess
 * @see ReportAnchors
 */
public final class GeneratedMemberSuppressor implements InspectionSuppressor {

    /**
     * Tools that report a field as unassigned, and so are answered by the
     * constructor a generated member supplies.
     */
    private static final Set<String> ASSIGNMENT_TOOL_IDS = Set.of(
        "NullableProblems",
        "NotNullFieldNotInitialized"
    );

    /**
     * Tools that report a field as unread, over-scoped, or holding a container
     * nothing fills or empties, and so are answered by the accessor and the
     * constructor a generated member supplies.
     *
     * <p>The three container tools ask who reads or writes a field's
     * <i>contents</i> rather than the field itself, which makes them the same
     * reference-search question the two above them ask and puts them under
     * {@link GeneratedFieldAccess#generatedMemberTouches}. A generated
     * constructor is not the only answer to them: a class carrying nothing but
     * {@code @Getter} still reports a list it fills, because the only reader is
     * an accessor holding no reference into the source tree.
     *
     * <p>They are spelled by suppression id rather than by short name, which is
     * what the platform hands an {@link InspectionSuppressor} - a tool declaring
     * one is known by it in preference to its short name, and the two differ for
     * exactly this family. The tools above declare none, so their short name is
     * their id.
     *
     * <p>{@code FieldMayBeFinal} is deliberately absent. It is a suggestion
     * rather than a wrong claim, and a field a generated setter writes is one
     * the tool already declines to report.
     */
    private static final Set<String> USAGE_TOOL_IDS = Set.of(
        "FieldCanBeLocal",
        "unused",
        "UnusedDeclaration",
        "MismatchedQueryAndUpdateOfCollection",
        "MismatchedReadAndWriteOfArray",
        "MismatchedQueryAndUpdateOfStringBuilder"
    );

    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        boolean assignment = ASSIGNMENT_TOOL_IDS.contains(toolId);
        if (!assignment && !USAGE_TOOL_IDS.contains(toolId)) return false;
        PsiField field = ReportAnchors.declaredField(element);
        if (field == null) return false;
        return assignment
            ? GeneratedFieldAccess.constructorAssigns(field)
            : GeneratedFieldAccess.generatedMemberTouches(field);
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element,
                                                           @NotNull String toolId) {
        // The annotation is the answer to the warning, so it is also the
        // suppression - a comment saying the same thing less precisely is not
        // worth offering.
        return SuppressQuickFix.EMPTY_ARRAY;
    }

}
