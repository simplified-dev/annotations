package dev.simplified.equality.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiSuperExpression;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.util.InheritanceUtil;
import dev.simplified.annotations.EqualsAndHashCode;
import dev.simplified.equality.inspect.WholeObjectConstants.Signature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * hand-written pair can be replaced by one.
 *
 * <p>No fix is offered, and that is the finding rather than a gap in it. Closing
 * the split means choosing which of the two member sets is the intended value of
 * the object, and nothing in the source says which - a fix that guessed would
 * either change what equality means or silently rewrite a hash the author had a
 * reason for.
 */
public class EqualityConsistencyInspection extends LocalInspectionTool {

    /**
     * Stands for folding the superclass in, so the two members are compared on
     * one set. {@code equals} calling {@code super.equals} while
     * {@code hashCode} skips {@code super.hashCode} is the same divergence as a
     * dropped field, and is one of the shapes actually found in the wild.
     *
     * <p>Not a legal Java identifier, so it can never collide with a member.
     */
    private static final String SUPER = "super";

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

        Set<String> comparedBy = reads(equals, target);
        Set<String> hashedBy = reads(hashCode, target);
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

    // ------------------------------------------------------------------
    // Reading a body
    // ------------------------------------------------------------------

    /**
     * The state one hand-written member reads, or {@code null} when the body
     * delegates somewhere this walk cannot follow.
     *
     * <p>An accessor and a direct read of the field behind it are one member,
     * since the two spellings routinely differ between the pair and the
     * divergence being looked for is about state rather than syntax.
     *
     * @param method the declared {@code equals} or {@code hashCode}
     * @param target the class declaring it
     * @return the members read, or {@code null} when the body is opaque
     */
    private static @Nullable Set<String> reads(@NotNull PsiMethod method, @NotNull PsiClass target) {
        if (method.getBody() == null) return null;
        Set<String> members = new LinkedHashSet<>();
        Set<String> assigned = new LinkedHashSet<>();
        boolean[] opaque = {false};

        method.getBody().accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (opaque[0]) return;
                super.visitElement(element);
                if (element instanceof PsiMethodCallExpression call) {
                    visitCall(call);
                } else if (element instanceof PsiAssignmentExpression assignment) {
                    // A memoized hash writes its own field. That slot is the
                    // cache, not a member, and counting it would report every
                    // cached hashCode ever written.
                    if (assignment.getLExpression() instanceof PsiReferenceExpression ref
                        && ref.resolve() instanceof PsiField field
                        && field.getName() != null) {
                        assigned.add(normalize(field.getName()));
                    }
                } else if (element instanceof PsiReferenceExpression ref
                    && !(ref.getParent() instanceof PsiMethodCallExpression)
                    && ref.resolve() instanceof PsiField field) {
                    if (field.hasModifierProperty(PsiModifier.STATIC)) return;
                    if (!owns(target, field.getContainingClass())) return;
                    if (field.getName() != null) members.add(normalize(field.getName()));
                }
            }

            private void visitCall(@NotNull PsiMethodCallExpression call) {
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                String name = call.getMethodExpression().getReferenceName();
                if (name == null) return;

                if (qualifier instanceof PsiSuperExpression) {
                    // Only the two that fold the supertype's own answer in. A
                    // super call to anything else is an ordinary read.
                    if ("equals".equals(name) || "hashCode".equals(name)) members.add(SUPER);
                    return;
                }
                if (!readsOwnState(qualifier)) return;

                PsiMethod resolved = call.resolveMethod();
                PsiClass owner = resolved == null ? null : resolved.getContainingClass();
                if (owner == null) return;
                // Object's own members are the scaffolding of an equals, never
                // its terms - getClass() above all.
                if (CommonClassNames.JAVA_LANG_OBJECT.equals(owner.getQualifiedName())) return;
                if (!owns(target, owner)) return;

                // A call into the type's own code taking arguments is a
                // delegation whose reads are somewhere else. Following it is out
                // of scope, so the whole body stops being comparable.
                if (!call.getArgumentList().isEmpty()) {
                    opaque[0] = true;
                    return;
                }
                members.add(normalize(name));
            }
        });

        if (opaque[0]) return null;
        members.removeAll(assigned);
        return members;
    }

    /**
     * Whether a member of this owner is the target's own state, so an inherited
     * accessor counts and an unrelated type's does not.
     */
    private static boolean owns(@NotNull PsiClass target, @Nullable PsiClass owner) {
        return owner != null && InheritanceUtil.isInheritorOrSelf(target, owner, true);
    }

    /**
     * Whether a qualifier reads the state of one of the two objects being
     * compared - {@code this}, nothing at all, or the local the parameter was
     * cast into.
     */
    private static boolean readsOwnState(@Nullable PsiExpression qualifier) {
        if (qualifier == null || qualifier instanceof PsiThisExpression) return true;
        if (!(qualifier instanceof PsiReferenceExpression ref)) return false;
        PsiElement resolved = ref.resolve();
        return resolved instanceof PsiLocalVariable || resolved instanceof PsiParameter;
    }

    /** An accessor and the field it reads collapse to one name. */
    private static @NotNull String normalize(@NotNull String name) {
        if (name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))) {
            return decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) {
            return decapitalize(name.substring(2));
        }
        return name;
    }

    private static @NotNull String decapitalize(@NotNull String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
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
            out.append(SUPER.equals(name) ? "the superclass" : "'" + name + "'");
        }
        return out.toString();
    }

}
