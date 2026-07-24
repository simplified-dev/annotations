package dev.simplified.equality.inspect;

import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads which of a type's own members a hand-written body touches.
 *
 * <p>Shared by the two checks over hand-written pairs, which ask opposite
 * questions of the same reading: one reports the pair whose two halves disagree,
 * the other the pair {@code @EqualsAndHashCode} could replace. A second
 * implementation of "what does this body read" would let those two disagree
 * about the same class, which is the failure both exist to prevent.
 */
final class HandWrittenEquality {

    /**
     * Stands for folding the superclass in, so a body is described by one set.
     * An {@code equals} calling {@code super.equals} is reading state exactly
     * the way a field read is.
     *
     * <p>Not a legal Java identifier, so it can never collide with a member.
     */
    static final String SUPER = "super";

    private HandWrittenEquality() {}

    /**
     * The state a declared member reads, or {@code null} when the body hands the
     * work somewhere this walk cannot follow.
     *
     * <p>An accessor and a direct read of the field behind it are one member.
     * The two spellings routinely differ between the halves of a pair, and every
     * question asked of this reading is about state rather than syntax.
     *
     * @param method the declared method
     * @param target the class declaring it
     * @return the members read, or {@code null} when the body is opaque
     */
    static @Nullable Set<String> reads(@NotNull PsiMethod method, @NotNull PsiClass target) {
        return method.getBody() == null ? null : reads(method.getBody(), target);
    }

    /**
     * The same reading over any fragment, so a guard clause can be checked for
     * touching state before it is accepted as a guard.
     *
     * @param scope the fragment to walk
     * @param target the class whose state is being looked for
     * @return the members read, or {@code null} when the fragment is opaque
     */
    static @Nullable Set<String> reads(@NotNull PsiElement scope, @NotNull PsiClass target) {
        Set<String> members = new LinkedHashSet<>();
        Set<String> assigned = new LinkedHashSet<>();
        boolean[] opaque = {false};

        scope.accept(new PsiRecursiveElementWalkingVisitor() {
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
    static boolean owns(@NotNull PsiClass target, @Nullable PsiClass owner) {
        return owner != null && InheritanceUtil.isInheritorOrSelf(target, owner, true);
    }

    /**
     * Whether a qualifier reads the state of one of the two objects being
     * compared - {@code this}, nothing at all, or the local the parameter was
     * cast into.
     */
    static boolean readsOwnState(@Nullable PsiExpression qualifier) {
        if (qualifier == null || qualifier instanceof PsiThisExpression) return true;
        if (!(qualifier instanceof PsiReferenceExpression ref)) return false;
        PsiElement resolved = ref.resolve();
        return resolved instanceof PsiLocalVariable || resolved instanceof PsiParameter;
    }

    /** An accessor and the field it reads collapse to one name. */
    static @NotNull String normalize(@NotNull String name) {
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

}
