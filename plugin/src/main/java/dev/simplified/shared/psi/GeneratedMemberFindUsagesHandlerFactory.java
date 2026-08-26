package dev.simplified.shared.psi;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.JavaFindUsagesHandler;
import com.intellij.find.findUsages.JavaFindUsagesHandlerFactory;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiRecordComponent;
import com.intellij.psi.util.PsiUtilCore;
import dev.simplified.shared.inspect.GeneratedFieldAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Folds the members synthesised from a field into a Find Usages run started on
 * that field.
 *
 * <p>The platform already reaches for this and cannot complete it. Its handler
 * gathers the accessors of a field and then drops every one whose backing field
 * it fails to recover, which it recovers by reading the method body. A
 * synthesised member has no body, so the whole set is dropped and the search
 * runs over the field alone - and the field is written nowhere, because the
 * accessor that reads it exists in no source file. A property called from one
 * end of a project to the other reports nothing found.
 *
 * <p>Provenance answers what a body read cannot: a synthesised member carries
 * {@link GeneratedMemberMarker} and points its navigation element at the field
 * it was minted from, so the pair is matched on identity rather than on
 * analysis. Everything else is {@link JavaFindUsagesHandler}'s - the dialog,
 * the options, records, and any accessor the author wrote out by hand.
 *
 * <p>The other half of the same blind spot is {@link GeneratedFieldAccess},
 * which answers whether a field is touched at all so an unused-field report can
 * be withdrawn. It decides that from the annotations, since a report has to be
 * answered whether or not synthesis ran; this decides which members exist,
 * which only the synthesised list can say.
 */
public final class GeneratedMemberFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        if (!(element instanceof PsiField) && !(element instanceof PsiRecordComponent)) return false;
        // Synthesis resolves types and reads annotations, neither of which an
        // index-less project can answer.
        if (DumbService.isDumb(element.getProject())) return false;
        // Claiming a field nothing was synthesised from would take it away from
        // the Java handler to hand it straight back.
        return !generatedFrom((PsiMember) element).isEmpty();
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(@NotNull PsiElement element,
                                                               boolean forHighlightUsages) {
        JavaFindUsagesHandlerFactory java =
            JavaFindUsagesHandlerFactory.getInstance(element.getProject());
        // Declining passes the element along to the next factory, which is the
        // Java one this handler delegates the rest of its work to anyway.
        return java == null ? null : new Handler(element, java);
    }

    /**
     * Every method synthesised from {@code member}, across the class that
     * declares it and the types nested in that class.
     *
     * <p>The nested walk is not defensive: {@code @ClassBuilder} hangs its
     * setters off the nested {@code Builder} rather than off the annotated
     * type, so stopping at the declaring class would find the accessors and
     * miss every setter call in the project.
     *
     * @param member the field or record component being searched for
     * @return the methods minted from it, empty when it backs none
     */
    private static List<PsiMethod> generatedFrom(@NotNull PsiMember member) {
        PsiClass owner = member.getContainingClass();
        if (owner == null) return List.of();

        List<PsiMethod> out = new ArrayList<>();
        collectFrom(owner, member, out);
        for (PsiClass nested : owner.getInnerClasses()) collectFrom(nested, member, out);
        return out;
    }

    /**
     * Adds the methods of {@code owner} that were synthesised from
     * {@code member}.
     *
     * @param owner the class whose methods to sift
     * @param member the field or record component they would have been minted from
     * @param out the list to add to
     */
    private static void collectFrom(@NotNull PsiClass owner, @NotNull PsiMember member,
                                    @NotNull List<PsiMethod> out) {
        for (PsiMethod method : owner.getMethods()) {
            if (!GeneratedMemberMarker.isGenerated(method)) continue;
            if (method.getNavigationElement() != member) continue;
            out.add(method);
        }
    }

    /**
     * {@link JavaFindUsagesHandler} that searches the synthesised members
     * alongside whatever the Java handler already searches.
     */
    private static final class Handler extends JavaFindUsagesHandler {

        private final JavaFindUsagesHandlerFactory java;

        Handler(@NotNull PsiElement element, @NotNull JavaFindUsagesHandlerFactory java) {
            super(element, java);
            this.java = java;
        }

        @Override
        public PsiElement @NotNull [] getSecondaryElements() {
            PsiElement[] inherited = super.getSecondaryElements();
            if (!(getPsiElement() instanceof PsiMember member)) return inherited;

            List<PsiMethod> generated = generatedFrom(member);
            if (generated.isEmpty()) return inherited;

            Set<PsiElement> out = new LinkedHashSet<>(Arrays.asList(inherited));
            boolean searchBase = java.getFindVariableOptions().isSearchForBaseAccessors;
            for (PsiMethod method : generated) {
                out.add(method);
                // A call written against a supertype resolves to the method
                // declared there, so reaching those call sites means searching
                // that declaration too. Behind the same option the platform
                // puts a written accessor behind, and added to the synthesised
                // method rather than swapped for it, so a call made on the
                // declaring type is still reported when the option is on.
                if (searchBase) Collections.addAll(out, method.findDeepestSuperMethods());
            }
            return PsiUtilCore.toPsiElementArray(out);
        }
    }

}
