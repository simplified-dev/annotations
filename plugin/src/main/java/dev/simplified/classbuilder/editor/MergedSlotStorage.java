package dev.simplified.classbuilder.editor;

import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiSuperExpression;
import com.intellij.psi.PsiThisExpression;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.controlFlow.AnalysisCanceledException;
import com.intellij.psi.controlFlow.ControlFlow;
import com.intellij.psi.controlFlow.ControlFlowFactory;
import com.intellij.psi.controlFlow.ControlFlowUtil;
import com.intellij.psi.controlFlow.LocalsOrMyInstanceFieldsControlFlowPolicy;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.InstanceDefaults;
import dev.simplified.classbuilder.apt.SetterScheme;
import dev.simplified.classbuilder.apt.SlotHolding;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.shared.psi.WrittenAnnotations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The editor's reading of the storage a merged builder holds each slot in, and
 * the diagnostics that follow from it - a declared field mistyped for its slot,
 * and an appended seed field no constructor assigns.
 *
 * <p>The comparison and the sentence are {@link DeclaredBuilderShape#mistypedSlot},
 * the one the processor reports, and the classification is {@link SlotHolding#of}
 * over the rule {@link InstanceDefaults} states; what lives here is reading the
 * facts both ask of from PSI the way the processor reads them from the tree - the
 * names a field's initializer spells, and the instance members the target
 * declares and inherits. A collected slot whose default reads instance state is
 * held in a scratch container this side does not render, and is left unjudged: a
 * missed error rather than a false one.
 */
public final class MergedSlotStorage {

    private MergedSlotStorage() {
    }

    /**
     * A declared builder field the merge cannot assign, with the diagnostic the
     * processor prints for it.
     *
     * @param field the author's field in the declared builder
     * @param message the diagnostic text
     */
    public record Mistyped(@NotNull PsiField field, @NotNull String message) { }

    /**
     * Reports each field of a declared builder whose type is not the storage type
     * of the slot it shares a name with, or which is declared {@code final} under
     * a slot the generated setter assigns.
     *
     * <p>Reads the slots with the extractor the builder synthesis uses, so the
     * set of names judged is the set the merge assigns - the target's fields or
     * record components, or the annotated member's parameters. Not for use from
     * an augment provider: the slot types are rendered through their canonical
     * text, which resolves.
     *
     * @param target the type the builder nests in
     * @param executable the annotated constructor or static factory, or {@code null} when the
     *     annotation is on the type
     * @param declared the builder it declares
     * @param annotation the {@code @ClassBuilder}, wherever it is written
     * @return the mistyped fields, in the declared builder's field order
     */
    public static @NotNull List<Mistyped> mistypedFields(@NotNull PsiClass target,
                                                          @Nullable PsiMethod executable,
                                                          @NotNull PsiClass declared,
                                                          @NotNull PsiAnnotation annotation) {
        String declaredName = declared.getName();
        if (declaredName == null) return List.of();
        SetterScheme setters =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(annotation).setters();
        List<PsiFieldShape> slots;
        if (executable != null) {
            slots = PsiFieldShapeExtractor.fromExecutable(executable, PsiSubstitutor.EMPTY, setters);
        } else {
            Set<String> excluded = GeneratedMemberFactory.excludedNames(target);
            slots = target.isRecord()
                ? PsiFieldShapeExtractor.fromRecord(target, excluded, setters)
                : PsiFieldShapeExtractor.fromClass(target, excluded, setters);
        }

        boolean retainInit =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(annotation).retainInit();
        List<Mistyped> out = new ArrayList<>();
        for (PsiField field : ownFields(declared)) {
            String written = writtenTypeText(field);
            if (written == null) continue;
            for (PsiFieldShape slot : slots) {
                if (!slot.name.equals(field.getName())) continue;
                // A seed is appended final itself and assigned by the author's
                // constructor alone; every other slot the setter assigns.
                if (field.hasModifierProperty(PsiModifier.FINAL) && !slot.seed) {
                    out.add(new Mistyped(field, DeclaredBuilderShape.finalSlot(declaredName, slot.name)));
                    continue;
                }
                // A parameter carries no initializer and cannot be lazy, so the
                // merge holds it as declared - a field of the enclosing type
                // sharing its name is no part of it.
                SlotHolding holding = executable != null
                    ? SlotHolding.DECLARED
                    : holdingOf(target, slot, retainInit);
                if (holding == null) continue;
                String declaredType = slot.type.getCanonicalText();
                String storage = holding.isSupplier()
                    ? DeclaredBuilderShape.supplierOf(declaredType)
                    : declaredType;
                String message = DeclaredBuilderShape.mistypedSlot(declaredName, slot.name,
                    written, storage, holding);
                if (message != null) out.add(new Mistyped(field, message));
            }
        }
        return out;
    }

    /**
     * A seed the merge appends as a {@code final} field and the declared builder
     * never assigns, with where to report it.
     *
     * @param anchor the constructor that leaves it unassigned, or the builder's
     *     name when it declares no constructor at all
     * @param message the diagnostic text
     */
    public record UnassignedSeed(@NotNull PsiElement anchor, @NotNull String message) { }

    /**
     * Names the annotated member's seeds, each of which {@code builder(..)} takes
     * and passes to the builder's constructor.
     *
     * @param executable the annotated constructor or static factory
     * @return the seeded parameters' names, in parameter order
     */
    public static @NotNull List<String> seedNames(@NotNull PsiMethod executable) {
        List<String> out = new ArrayList<>();
        for (PsiParameter parameter : executable.getParameterList().getParameters()) {
            if (PsiFieldShapeExtractor.hasAnnotation(parameter, ClassBuilderConstants.BUILDER_SEED_FQN))
                out.add(parameter.getName());
        }
        return out;
    }

    /**
     * Reports each seed the merge appends into a declared builder that a
     * constructor there leaves unassigned.
     *
     * <p>The merge appends a seed as a {@code final} field and never appends the
     * constructor that assigns it, so the author's constructors have to. javac
     * refuses one that does not - and the implicit default of a class declaring
     * none - on a line the author wrote; the platform's own check reads only
     * fields written in source, so without this the editor is green over it.
     * Assignment is answered by the platform's definite-assignment flow over each
     * constructor body, and over each instance initializer - one that definitely
     * assigns the seed assigns it before every constructor body runs, so no
     * constructor is then responsible for it. A constructor delegating through
     * {@code this(..)} is left to the one it calls. A seed the author declares a
     * field for is the platform's to check, that field being written in source.
     *
     * <p>Not for use from an augment provider: it reads the builder's fields
     * through the augment-aware lookup, which is what finds the appended ones.
     *
     * @param executable the annotated constructor or static factory
     * @param declared the builder the enclosing type declares
     * @return the unassigned seeds, in parameter order
     */
    public static @NotNull List<UnassignedSeed> unassignedSeeds(@NotNull PsiMethod executable,
                                                                @NotNull PsiClass declared) {
        String declaredName = declared.getName();
        PsiElement nameAnchor = declared.getNameIdentifier();
        if (declaredName == null || nameAnchor == null) return List.of();
        if (!(declared instanceof PsiExtensibleClass extensible)) return List.of();
        List<PsiCodeBlock> initializers = new ArrayList<>();
        for (PsiClassInitializer initializer : declared.getInitializers()) {
            if (!initializer.hasModifierProperty(PsiModifier.STATIC)) initializers.add(initializer.getBody());
        }
        List<PsiMethod> constructors = new ArrayList<>();
        for (PsiMethod own : extensible.getOwnMethods()) {
            if (own.isConstructor()) constructors.add(own);
        }

        List<UnassignedSeed> out = new ArrayList<>();
        for (PsiParameter parameter : executable.getParameterList().getParameters()) {
            if (!PsiFieldShapeExtractor.hasAnnotation(parameter, ClassBuilderConstants.BUILDER_SEED_FQN))
                continue;
            String seed = parameter.getName();
            if (declaresField(extensible, seed)) continue;
            PsiField appended = declared.findFieldByName(seed, false);
            if (appended == null || assignedByAnInitializer(initializers, appended)) continue;
            if (constructors.isEmpty()) {
                out.add(new UnassignedSeed(nameAnchor,
                    DeclaredBuilderShape.unassignedSeed(declaredName, seed, false)));
                continue;
            }
            for (PsiMethod constructor : constructors) {
                if (assigns(constructor, appended)) continue;
                PsiElement anchor = constructor.getNameIdentifier();
                out.add(new UnassignedSeed(anchor == null ? constructor : anchor,
                    DeclaredBuilderShape.unassignedSeed(declaredName, seed, true)));
            }
        }
        return out;
    }

    /** Whether the author wrote a field of that name in the builder. */
    private static boolean declaresField(PsiExtensibleClass declared, String name) {
        for (PsiField own : declared.getOwnFields()) {
            if (name.equals(own.getName())) return true;
        }
        return false;
    }

    /**
     * Whether an instance initializer leaves the field definitely assigned.
     *
     * <p>The initializers run in order before every constructor body, so the
     * field is assigned once any one of them assigns it.
     *
     * @param initializers the bodies of the builder's instance initializers, in declaration order
     * @param field the appended seed field
     * @return whether one of them assigns it
     */
    private static boolean assignedByAnInitializer(List<PsiCodeBlock> initializers, PsiField field) {
        for (PsiCodeBlock body : initializers) {
            if (definitelyAssigns(body, field)) return true;
        }
        return false;
    }

    /**
     * Whether the constructor leaves the field definitely assigned, as javac's
     * flow analysis would find it.
     *
     * @param constructor a constructor the author declared
     * @param field the appended seed field
     * @return whether the constructor assigns it, or hands it to one that does
     */
    private static boolean assigns(PsiMethod constructor, PsiField field) {
        PsiCodeBlock body = constructor.getBody();
        if (body == null) return true;
        PsiStatement[] statements = body.getStatements();
        if (statements.length > 0
            && statements[0] instanceof PsiExpressionStatement first
            && first.getExpression() instanceof PsiMethodCallExpression call
            && "this".equals(call.getMethodExpression().getReferenceName())) {
            return true;
        }
        return definitelyAssigns(body, field);
    }

    /**
     * Whether a block leaves the field definitely assigned, by the platform's
     * definite-assignment flow.
     *
     * @param body the block to analyse
     * @param field the field it may assign
     * @return whether it assigns the field on every path, and {@code true} when the analysis cannot finish
     */
    private static boolean definitelyAssigns(PsiCodeBlock body, PsiField field) {
        try {
            ControlFlow flow = ControlFlowFactory.getInstance(body.getProject())
                .getControlFlow(body, LocalsOrMyInstanceFieldsControlFlowPolicy.getInstance());
            return ControlFlowUtil.isVariableDefinitelyAssigned(field, flow);
        } catch (AnalysisCanceledException e) {
            return true;
        }
    }

    /**
     * How the merge holds a slot of a class or record target, as the processor
     * classifies it.
     *
     * <p>The classification is {@link SlotHolding#of}, asked of the flags the
     * processor asks it of. The instance-default flag is
     * {@link InstanceDefaults#readsInstanceState}, asked of the names the field's
     * initializer spells and of the instance members the target declares and
     * inherits, and only where {@link InstanceDefaults#captures} says the
     * initializer is kept at all. Nothing here resolves a reference, which is
     * what lets the augment provider ask it: the names are read as written and
     * the members off each type's own declarations.
     *
     * @param target the annotated type
     * @param slot the slot to classify
     * @param retainInit the class-wide policy written on {@code @ClassBuilder}
     * @return how the slot is held, or {@code null} for a collected slot whose default reads instance
     *     state, held in a scratch container this side does not render
     */
    static @Nullable SlotHolding holdingOf(@NotNull PsiClass target, @NotNull PsiFieldShape slot,
                                           boolean retainInit) {
        boolean collected = slot.collector && (slot.isListLike || slot.isMap);
        SlotHolding holding = SlotHolding.of(slot.lazy, collected, instanceDefault(target, slot, retainInit));
        return holding == SlotHolding.COLLECTED_SCRATCH ? null : holding;
    }

    /**
     * Whether the slot's field keeps an initializer that reads instance state.
     *
     * @param target the annotated type
     * @param slot the slot to classify
     * @param retainInit the class-wide policy written on {@code @ClassBuilder}
     * @return whether the slot's default is computed on the instance
     */
    private static boolean instanceDefault(PsiClass target, PsiFieldShape slot, boolean retainInit) {
        PsiField field = null;
        for (PsiField own : ownFields(target)) {
            if (slot.name.equals(own.getName())) field = own;
        }
        PsiExpression initializer = field == null ? null : field.getInitializer();
        if (initializer == null) return false;
        PsiAnnotation written = WrittenAnnotations.findOnMember(field, ClassBuilderConstants.BUILDER_DEFAULT_FQN);
        boolean builderDefault = InstanceDefaults.builderDefault(
            written == null ? null : ClassBuilderConstants.booleanAttr(written, "value", true), retainInit);
        if (!InstanceDefaults.captures(builderDefault, slot.collector && slot.isCustomContainer)) return false;
        return InstanceDefaults.readsInstanceState(spelledNames(initializer), instanceMemberNames(target));
    }

    /**
     * Lists every name an initializer spells without a qualifier, and
     * {@code this} for each {@code this} expression, qualified or not - the
     * names the processor lists from the tree.
     *
     * @param initializer the field's initializer
     * @return the names, in source order
     */
    private static List<String> spelledNames(PsiExpression initializer) {
        List<String> out = new ArrayList<>();
        initializer.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitReferenceElement(@NotNull PsiJavaCodeReferenceElement reference) {
                String name = reference.getReferenceName();
                if (reference.getQualifier() == null && name != null) out.add(name);
                super.visitReferenceElement(reference);
            }

            @Override
            public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
                visitReferenceElement(expression);
            }

            @Override
            public void visitThisExpression(@NotNull PsiThisExpression expression) {
                out.add("this");
                super.visitThisExpression(expression);
            }

            @Override
            public void visitSuperExpression(@NotNull PsiSuperExpression expression) {
                // A qualified super is a select on the tree, whose name the
                // processor does not list.
                if (expression.getQualifier() == null) out.add("super");
                super.visitSuperExpression(expression);
            }
        });
        return out;
    }

    /**
     * Names every non-static field and method the target declares, and every
     * one its supertypes declare that it inherits, as the processor's element
     * model lists them.
     *
     * @param target the annotated type
     * @return the member names
     */
    private static Set<String> instanceMemberNames(PsiClass target) {
        Set<String> names = new HashSet<>();
        collectInstanceMembers(target, true, names, new HashSet<>());
        return names;
    }

    /**
     * Adds a type's instance members to the set, then its supertypes'.
     *
     * @param type the type to read
     * @param own whether it is the target itself, whose private members count too
     * @param names the set being filled
     * @param visited the types already read
     */
    private static void collectInstanceMembers(PsiClass type, boolean own, Set<String> names,
                                               Set<PsiClass> visited) {
        if (!visited.add(type)) return;
        for (PsiField field : ownFields(type)) {
            if (instanceMember(field, own)) names.add(field.getName());
        }
        for (PsiMethod method : GeneratedMemberFactory.ownMethods(type)) {
            if (!method.isConstructor() && instanceMember(method, own)) names.add(method.getName());
        }
        PsiClass superClass = type.getSuperClass();
        if (superClass != null) collectInstanceMembers(superClass, false, names, visited);
        for (PsiClass implemented : type.getInterfaces()) collectInstanceMembers(implemented, false, names, visited);
    }

    /** Whether the member is one the target sees on an instance - its own, or an inherited non-private one. */
    private static boolean instanceMember(PsiMember member, boolean own) {
        if (member.hasModifierProperty(PsiModifier.STATIC)) return false;
        return own || !member.hasModifierProperty(PsiModifier.PRIVATE);
    }

    /**
     * A variable's type as written, with the brackets a C-style declaration puts
     * after its name.
     *
     * <p>javac folds {@code String tags[]} into the declared type, so the
     * processor reads {@code String[]}; the type element PSI keeps in front of
     * the name covers {@code String} alone. The trailing dimensions are counted
     * off the variable's type, which PSI builds from the same brackets without
     * resolving anything.
     *
     * @param variable the field or parameter
     * @return the type as the processor reads it, or {@code null} when none is written
     */
    static @Nullable String writtenTypeText(@NotNull PsiVariable variable) {
        PsiTypeElement written = variable.getTypeElement();
        if (written == null) return null;
        int trailing = variable.getType().getArrayDimensions() - written.getType().getArrayDimensions();
        return written.getText() + "[]".repeat(Math.max(0, trailing));
    }

    /** The class's own fields, without anything a provider contributed. */
    private static List<PsiField> ownFields(PsiClass declared) {
        return declared instanceof PsiExtensibleClass extensible
            ? extensible.getOwnFields()
            : List.of(declared.getFields());
    }

}
