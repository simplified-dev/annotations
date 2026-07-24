package dev.simplified.equality.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.equality.inspect.WholeObjectConstants.Signature;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reports a hand-written {@code equals} and {@code hashCode} pair that read
 * different state.
 *
 * <p>The two directions are different findings and are reported differently.
 * A {@code hashCode} reading <b>less</b> than {@code equals} is legal - equal
 * objects still agree on the smaller set, so their hashes still agree - and only
 * costs collisions. A {@code hashCode} reading something {@code equals} ignores
 * is a <b>contract break</b>: two objects the relation calls equal differ in
 * that member and hash apart, so one of them goes missing from every hash
 * container.
 *
 * <p>Both are also the one shape {@link EqualsAndHashCode} structurally cannot
 * adopt. It drives both members from a single selection and a single
 * {@code callSuper}, so a split member set has no spelling in the annotation -
 * which makes this check the inventory of what has to be decided before a
 * hand-written pair can be replaced by one. A pair whose halves <i>do</i> agree
 * is the other half of that question, and belongs to
 * {@link ReplaceableEqualityInspection}.
 *
 * <p>No fix is offered, and that is the finding rather than a gap in it. Closing
 * the split means choosing which of the two member sets is the intended value of
 * the object, and nothing in the source says which - a fix that guessed would
 * either change what equality means or silently rewrite a hash the author had a
 * reason for.
 */
public class EqualityConsistencyInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass target) {
                super.visitClass(target);
                check(holder, target);
            }
        };
    }

    private static void check(@NotNull ProblemsHolder holder, @NotNull PsiClass target) {
        if (target.isInterface() || target.isAnnotationType()) return;
        // A target carrying the annotation and declaring the pair is already an
        // error EqualsAndHashCodeInspection reports, in its own words. Saying it
        // twice in two vocabularies would read as two problems.
        if (target.getAnnotation(WholeObjectConstants.EQUALS_AND_HASH_CODE_FQN) != null) return;

        PsiMethod equals = WholeObjectConstants.declares(target, Signature.EQUALS);
        PsiMethod hashCode = WholeObjectConstants.declares(target, Signature.HASH_CODE);
        if (equals == null || hashCode == null) return;

        Set<String> comparedBy = HandWrittenEquality.reads(equals, target);
        Set<String> hashedBy = HandWrittenEquality.reads(hashCode, target);
        // An empty set is "this body did not tell us", never "this body reads
        // nothing". A pair that delegates to a helper, or is written against
        // state this walk cannot see, has to be left alone - comparing an
        // unknown against a known set would report every one of them.
        if (comparedBy == null || hashedBy == null) return;
        if (comparedBy.isEmpty() || hashedBy.isEmpty()) return;
        if (comparedBy.equals(hashedBy)) return;

        PsiElement anchor = hashCode.getNameIdentifier();
        if (anchor == null) return;

        List<String> hashedOnly = missing(hashedBy, comparedBy);
        List<String> comparedOnly = missing(comparedBy, hashedBy);

        // Reported first and at the higher severity because it is the half that
        // is actually broken: the other half is merely a weaker hash.
        if (!hashedOnly.isEmpty()) {
            holder.registerProblem(anchor,
                "hashCode reads " + render(hashedOnly) + ", which equals ignores - two objects "
                    + "this equals calls equal can hash apart, which breaks the hashCode contract "
                    + "and loses one of them in every hash container. @EqualsAndHashCode drives "
                    + "both members from one selection, so adopting it here closes this as well",
                ProblemHighlightType.WARNING);
            return;
        }

        holder.registerProblem(anchor,
            "equals compares " + render(comparedOnly) + ", which hashCode ignores - legal, since "
                + "equal objects still hash alike, but the hash is looser than the relation. "
                + "@EqualsAndHashCode drives both members from one selection and cannot express "
                + "the split, so this is the decision to make before adopting it here",
            ProblemHighlightType.WEAK_WARNING);
    }

    private static @NotNull List<String> missing(@NotNull Set<String> from,
                                                 @NotNull Set<String> other) {
        List<String> out = new ArrayList<>();
        for (String name : from) {
            if (!other.contains(name)) out.add(name);
        }
        return out;
    }

    /** The diverging members, with the super fold named as the thing it is. */
    private static @NotNull String render(@NotNull List<String> names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out.append(i == names.size() - 1 ? " and " : ", ");
            String name = names.get(i);
            out.append(HandWrittenEquality.SUPER.equals(name) ? "the superclass" : "'" + name + "'");
        }
        return out.toString();
    }

}
