package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.controlFlow.AnalysisCanceledException;
import com.intellij.psi.controlFlow.ControlFlow;
import com.intellij.psi.controlFlow.ControlFlowFactory;
import com.intellij.psi.controlFlow.ControlFlowUtil;
import com.intellij.psi.controlFlow.LocalsOrMyInstanceFieldsControlFlowPolicy;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.SetterScheme;
import dev.simplified.classbuilder.apt.SlotHolding;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The editor's reading of the storage a merged builder holds each slot in, and
 * the diagnostics that follow from it - a declared field mistyped for its slot,
 * and an appended seed field no constructor assigns.
 *
 * <p>The comparison and the sentence are {@link DeclaredBuilderShape#mistypedSlot},
 * the one the processor reports; what lives here is classifying a slot's storage
 * from PSI the way the processor classifies it from the tree. The processor
 * knows one thing this side does not - whether a retained initializer reads
 * instance state, which holds the slot as a supplier or as a scratch container.
 * That is a flow question the editor does not analyse, so a slot whose field
 * carries an initializer and whose storage depends on it is left unjudged: a
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
     * of the slot it shares a name with.
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

        List<Mistyped> out = new ArrayList<>();
        for (PsiField field : ownFields(declared)) {
            PsiTypeElement written = field.getTypeElement();
            if (written == null) continue;
            for (PsiFieldShape slot : slots) {
                if (!slot.name.equals(field.getName())) continue;
                // A parameter carries no initializer and cannot be lazy, so the
                // merge holds it as declared - a field of the enclosing type
                // sharing its name is no part of it.
                SlotHolding holding = executable != null
                    ? SlotHolding.DECLARED
                    : holdingOf(target, slot);
                if (holding == null) continue;
                String declaredType = slot.type.getCanonicalText();
                String storage = holding.isSupplier()
                    ? DeclaredBuilderShape.supplierOf(declaredType)
                    : declaredType;
                String message = DeclaredBuilderShape.mistypedSlot(declaredName, slot.name,
                    written.getText(), storage, holding);
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
     * constructor body. A constructor delegating through {@code this(..)} is left
     * to the one it calls, and a builder with an instance initializer is not
     * judged, the flow of one body not covering it - a missed error rather than a
     * false one. A seed the author declares a field for is the platform's to
     * check, that field being written in source.
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
        for (PsiClassInitializer initializer : declared.getInitializers()) {
            if (!initializer.hasModifierProperty(PsiModifier.STATIC)) return List.of();
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
            if (appended == null) continue;
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
        try {
            ControlFlow flow = ControlFlowFactory.getInstance(constructor.getProject())
                .getControlFlow(body, LocalsOrMyInstanceFieldsControlFlowPolicy.getInstance());
            return ControlFlowUtil.isVariableDefinitelyAssigned(field, flow);
        } catch (AnalysisCanceledException e) {
            return true;
        }
    }

    /**
     * How the merge holds the slot, where that can be read without asking what
     * an initializer reads.
     *
     * <p>A lazy slot is a supplier whatever its initializer says, unless it is
     * also collected - a collected slot whose default reads instance state is a
     * scratch container instead, and which of the two applies is the flow
     * question. A slot with no initializer has no default to compute and is held
     * as declared; one with an initializer may be held as a supplier, and is not
     * classified.
     *
     * @param target the annotated type
     * @param slot the slot to classify
     * @return how the slot is held, or {@code null} when that depends on what its initializer reads
     */
    private static @Nullable SlotHolding holdingOf(PsiClass target, PsiFieldShape slot) {
        boolean initialised = GeneratedMemberFactory.hasInitializer(target, slot.name);
        boolean collected = slot.collector && (slot.isListLike || slot.isMap);
        if (slot.lazy) return collected && initialised ? null : SlotHolding.LAZY;
        return initialised ? null : SlotHolding.DECLARED;
    }

    /** The declared builder's own fields, without anything a provider contributed. */
    private static List<PsiField> ownFields(PsiClass declared) {
        return declared instanceof PsiExtensibleClass extensible
            ? extensible.getOwnFields()
            : List.of(declared.getFields());
    }

}
