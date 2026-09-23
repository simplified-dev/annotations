package dev.simplified.classbuilder.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.BuilderConstructorAccess;
import dev.simplified.classbuilder.apt.BuilderScheme;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.editor.MergedSlotStorage;
import org.jetbrains.annotations.NotNull;

/**
 * Reports a declared builder the merge cannot append to.
 *
 * <p>The processor refuses these shapes with an error and the editor has had no
 * analogue, so an author whose builder is a record, an enum or an interface, is
 * non-static, carries the wrong parameter list or other bounds on it, is
 * declared abstract where the entry points instantiate it,
 * extends a builder other than the chain ancestor's or passes it the wrong
 * arguments, or spells a build method that cannot stand in for the generated
 * one saw a fully populated class until the build failed. The rejection and its
 * wording both come from the shared decision, so what is red here is red there,
 * in the same sentence.
 *
 * <p>A shape rejection is reported on the declared builder's name identifier -
 * the element the author would act on, and one no other {@code classbuilder}
 * inspection claims. The processor reports on the annotated class, constructor
 * or factory instead, which is where it has an element to report on at all. On
 * a constructor or factory target the builder judged is the one the enclosing
 * type declares, and its type parameters are measured against the factory's own
 * where the factory is static.
 *
 * <p>On a shape the merge accepts, a declared field sharing a slot's name and
 * holding a type the generated setters cannot assign, or declared {@code final},
 * is reported on that field's type, again in the processor's sentence. The slot's storage is classified by
 * {@link MergedSlotStorage}, as the processor classifies it - an initialised
 * slot included, held as a supplier where its kept initializer reads the
 * instance.
 *
 * <p>On a class or record target and on a constructor or factory target, a
 * {@code builderConstructorAccess} written on the annotation while the declared
 * builder declares a constructor of its own is a warning on that attribute, in
 * the processor's sentence: the author's constructor keeps its own access.
 *
 * <p>On a constructor or factory target, a seed the merge appends as a
 * {@code final} field and a constructor of the declared builder leaves
 * unassigned is reported on that constructor, or on the builder's name when it
 * declares none, unless an instance initializer assigns it. javac refuses both
 * shapes in its own words; the platform's definite-assignment check reads only
 * fields written in source.
 */
public class DeclaredBuilderShapeInspection extends LocalInspectionTool {

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

                NamingStyle style = ClassBuilderConstants.namingStyle(annotation);
                BuilderScheme names =
                    ClassBuilderConstants.builderScheme(annotation, style, target.getName());

                // The ancestor case is reported on the annotation rather than on
                // a declaration, the offending class being one the author did
                // not write and may not own.
                PsiClass blocking = ClassBuilderConstants.ancestorBlockingGeneration(target,
                    names.type(), executable);
                if (blocking != null && blocking.getName() != null) {
                    holder.registerProblem(annotation,
                        DeclaredBuilderShape.ancestorDeclaresItsOwnBuilder(target.getName(),
                            blocking.getName()),
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                // The merge runs on a class or record target, a chain role among
                // them, and on a constructor or factory target, into the builder
                // the type declares, and nowhere else - an interface's builder is
                // a sibling file - so the shape of a declared builder is only a
                // question there.
                if (target.isInterface()) return;
                PsiClass declared = ClassBuilderConstants.declaredBuilderOf(target, names.type());
                if (declared == null || declared.getName() == null) return;

                String rejection = ClassBuilderConstants.mergeRejection(target, member, declared, names);
                if (rejection != null) {
                    PsiElement anchor = declared.getNameIdentifier();
                    holder.registerProblem(anchor == null ? declared : anchor, rejection,
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                // The author's constructor keeps its own access, so the attribute
                // written beside it changes nothing on the roles it reaches.
                ChainRole role = executable ? ChainRole.STANDALONE : ClassBuilderConstants.chainRoleOf(target);
                PsiAnnotationMemberValue access =
                    annotation.findDeclaredAttributeValue(BuilderConstructorAccess.ATTRIBUTE);
                if (access != null && BuilderConstructorAccess.appliesTo(role)
                    && ClassBuilderConstants.declaresConstructor(declared)) {
                    holder.registerProblem(access, BuilderConstructorAccess.hasNoEffect(declared.getName()),
                        ProblemHighlightType.WARNING);
                }

                // The processor judges the slot fields only once the shape is
                // accepted, on every role. It keeps merging after reporting one,
                // so this reports and the augment provider keeps contributing.
                for (MergedSlotStorage.Mistyped mistyped
                    : MergedSlotStorage.mistypedFields(target, member, declared, annotation)) {
                    PsiTypeElement anchor = mistyped.field().getTypeElement();
                    holder.registerProblem(anchor == null ? mistyped.field() : anchor,
                        mistyped.message(), ProblemHighlightType.GENERIC_ERROR);
                }

                // A seed is appended final and only the author's constructors can
                // assign it. javac refuses one that does not, and the platform's
                // own check never sees a field it did not read from source.
                if (member == null) return;
                for (MergedSlotStorage.UnassignedSeed unassigned
                    : MergedSlotStorage.unassignedSeeds(member, declared)) {
                    holder.registerProblem(unassigned.anchor(), unassigned.message(),
                        ProblemHighlightType.GENERIC_ERROR);
                }
            }
        };
    }

}
