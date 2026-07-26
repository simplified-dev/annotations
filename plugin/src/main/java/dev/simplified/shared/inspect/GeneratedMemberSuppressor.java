package dev.simplified.shared.inspect;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIdentifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Silences the inspections that read a field as uninitialized, unread, or
 * needlessly a field, on the fields a generated member initializes and reads.
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
 * @see GeneratedFieldAccess
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
     * Tools that report a field as unread or over-scoped, and so are answered by
     * the accessor a generated member supplies.
     *
     * <p>{@code FieldMayBeFinal} is deliberately absent. It is a suggestion
     * rather than a wrong claim, and a field a generated setter writes is one
     * the tool already declines to report.
     */
    private static final Set<String> USAGE_TOOL_IDS = Set.of(
        "FieldCanBeLocal",
        "unused",
        "UnusedDeclaration"
    );

    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        boolean assignment = ASSIGNMENT_TOOL_IDS.contains(toolId);
        if (!assignment && !USAGE_TOOL_IDS.contains(toolId)) return false;
        PsiField field = enclosingField(element);
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

    /**
     * The field a report belongs to.
     *
     * <p>Bounded at one step deliberately. Every tool here reports on a field
     * declaration or its name identifier, so a climb would only ever find a
     * field the report is not about, and this runs for every element every other
     * tool reports on in a Java file.
     *
     * @param element the element an inspection is reporting on
     * @return the field, or {@code null} when the report is about something else
     */
    private static @Nullable PsiField enclosingField(@NotNull PsiElement element) {
        if (element instanceof PsiField field) return field;
        if (element instanceof PsiIdentifier && element.getParent() instanceof PsiField field)
            return field;
        return null;
    }

}
