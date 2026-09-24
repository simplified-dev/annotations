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
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.BuilderConstructorAccess;
import dev.simplified.classbuilder.apt.BuilderScheme;
import dev.simplified.classbuilder.apt.ChainRole;
import dev.simplified.classbuilder.editor.BuilderSite;
import dev.simplified.classbuilder.editor.MergedSlotStorage;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

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
 * holding a type the generated setters cannot assign, or declared {@code final}
 * where a generated setter of its slot is appended, is reported on that field's
 * type, again in the processor's sentence. The slot's storage is classified by
 * {@link MergedSlotStorage}, as the processor classifies it - an initialised
 * slot included, held as a supplier where its kept initializer reads the
 * instance, and a collected one in its {@code java.util} scratch container. An
 * author method covering a generated setter with another parameterisation of
 * the same generic type is reported on its name where {@code from(T)} or
 * {@code mutate()} is emitted to pass it the slot's own type. A generated setter
 * the merge appends that a method the builder inherits keeps from overriding it
 * - a {@code static} or {@code final} one, one returning a type the builder
 * cannot stand in for, or one sharing the setter's erasure without being
 * overridden by it - is reported on the builder's name, the supertypes
 * resolved here as the processor reads them from the element model.
 *
 * <p>On an abstract root or a chained abstract, a declared builder the builders
 * generated below it cannot extend - one declaring constructors none of which
 * takes no parameters - or whose {@code self()} they cannot override because it
 * is {@code final}, one a root's builder inherits included, is reported on the
 * builder's name in the processor's sentence, as is a root's builder inheriting
 * a {@code self()} that returns another type than the pair's builder parameter.
 * A link whose annotated ancestor's builder, declared or generated, is out of
 * its reach is reported on its annotation, as is a link whose ancestor's
 * builder cannot take the extends clause, and a concrete link overriding a
 * {@code final} {@code self()} on a compiled ancestor the processor never
 * judged.
 *
 * <p>On a class or record target and on a constructor or factory target, a
 * {@code builderConstructorAccess} written on the annotation while the declared
 * builder declares a constructor of its own is a warning on that attribute, in
 * the processor's sentence: the author's constructor keeps its own access. On a
 * SuperBuilder chain role or an interface target, a value other than the
 * default is a warning on the attribute whether or not a builder is declared,
 * since no builder there takes it.
 *
 * <p>Only the annotation the processor builds from is judged, which is the one
 * {@link BuilderSite#of} answers: an annotated member the processor refuses -
 * an instance or {@code void} method, or one beside an annotated type - merges
 * nothing, so there is nothing to report about its builder.
 *
 * <p>On a constructor or factory target, a seed the merge appends as a
 * {@code final} field and a constructor of the declared builder leaves
 * unassigned is reported on that constructor, or on the builder's name when it
 * declares none, unless an instance initializer assigns it; and a constructor
 * assigning it where an instance initializer may already have is reported on
 * that constructor. javac refuses each shape in its own words; the platform's
 * definite-assignment check reads only fields written in source.
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
                // Only the annotation the processor builds from is judged. It
                // refuses an instance method, a void one and a member beside an
                // annotated type with an error of its own and merges nothing.
                BuilderSite site = BuilderSite.of(target);
                if (site == null || !Objects.equals(site.executable(), member)) return;

                NamingStyle style = ClassBuilderConstants.namingStyle(annotation);
                BuilderScheme names =
                    ClassBuilderConstants.builderScheme(annotation, style, target.getName());

                // A chain role's builder and an interface's sibling keep a
                // constructor the attribute never reaches, which the processor
                // warns about before it generates anything.
                PsiAnnotationMemberValue access =
                    annotation.findDeclaredAttributeValue(BuilderConstructorAccess.ATTRIBUTE);
                if (!executable && access != null) {
                    boolean interfaceTarget = target.isInterface();
                    String misplaced = BuilderConstructorAccess.misplaced(target.getName(), interfaceTarget,
                        interfaceTarget ? ChainRole.STANDALONE : ClassBuilderConstants.chainRoleOf(target),
                        access instanceof PsiReferenceExpression reference ? reference.getReferenceName() : null);
                    if (misplaced != null) holder.registerProblem(access, misplaced, ProblemHighlightType.WARNING);
                }

                // The ancestor case is reported on the annotation rather than on
                // a declaration, the offending class being one the author did
                // not write and may not own - a link without a declared builder
                // of its own included.
                ClassBuilderConstants.AncestorBlock blocking = ClassBuilderConstants.ancestorBlock(target,
                    names.type(), executable);
                if (blocking != null) {
                    holder.registerProblem(annotation, blocking.message(), ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                // The merge runs on a class or record target, a chain role among
                // them, and on a constructor or factory target, one inside an
                // interface included, into the builder the type declares, and
                // nowhere else - an interface type target's builder is a sibling
                // file - so the shape of a declared builder is only a question
                // there.
                if (target.isInterface() && !executable) return;
                PsiClass declared = ClassBuilderConstants.declaredBuilderOf(target, names.type());
                if (declared == null || declared.getName() == null) return;

                String rejection = ClassBuilderConstants.mergeRejection(target, member, declared, names);
                if (rejection != null) {
                    PsiElement anchor = declared.getNameIdentifier();
                    holder.registerProblem(anchor == null ? declared : anchor, rejection,
                        ProblemHighlightType.GENERIC_ERROR);
                    return;
                }

                // A self-typed role's builder is extended by every link below it,
                // which the processor judges once the shape is accepted and
                // reports on the builder, merging on.
                ChainRole role = executable ? ChainRole.STANDALONE : ClassBuilderConstants.chainRoleOf(target);
                if (role.isSelfTyped()) {
                    PsiElement builderName = declared.getNameIdentifier();
                    for (String message : ClassBuilderConstants.unextendableBuilder(target, declared)) {
                        holder.registerProblem(builderName == null ? declared : builderName, message,
                            ProblemHighlightType.GENERIC_ERROR);
                    }
                }

                // The author's constructor keeps its own access, so the attribute
                // written beside it changes nothing on the roles it reaches.
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

                // The processor judges a covered setter's parameter where it
                // emits the copy entry points that call it, and reports on the
                // class; the author's method is what to act on here.
                for (MergedSlotStorage.CoveringMethod covering
                    : MergedSlotStorage.settersCoveredWithOtherTypeArguments(target, member, declared, annotation)) {
                    PsiElement anchor = covering.method().getNameIdentifier();
                    holder.registerProblem(anchor == null ? covering.method() : anchor,
                        covering.message(), ProblemHighlightType.GENERIC_ERROR);
                }

                // The processor reads the declared builder's supertypes from the
                // element model and reports on the builder each appended setter an
                // inherited method keeps from overriding it. The augment provider
                // stays names-only and keeps contributing the setter.
                for (String message
                    : MergedSlotStorage.unoverridableInheritedMethods(target, member, declared, annotation)) {
                    PsiElement anchor = declared.getNameIdentifier();
                    holder.registerProblem(anchor == null ? declared : anchor, message,
                        ProblemHighlightType.GENERIC_ERROR);
                }

                // A seed is appended final and only the author's constructors can
                // assign it, each exactly once. javac refuses one that does not
                // and one that assigns it after an instance initializer, and the
                // platform's own check never sees a field it did not read from
                // source.
                if (member == null) return;
                for (MergedSlotStorage.MisassignedSeed misassigned
                    : MergedSlotStorage.misassignedSeeds(member, declared)) {
                    holder.registerProblem(misassigned.anchor(), misassigned.message(),
                        ProblemHighlightType.GENERIC_ERROR);
                }
            }
        };
    }

}
