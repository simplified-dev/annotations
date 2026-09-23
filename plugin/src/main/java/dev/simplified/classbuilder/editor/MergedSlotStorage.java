package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import dev.simplified.classbuilder.apt.DeclaredBuilderShape;
import dev.simplified.classbuilder.apt.SetterScheme;
import dev.simplified.classbuilder.apt.SlotHolding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The editor's reading of the storage a merged builder holds each slot in, and
 * the mistyped-slot diagnostics that follow from it.
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
     * <p>Reads the target's slots with the extractor the builder synthesis uses,
     * so the set of names judged is the set the merge assigns. Not for use from
     * an augment provider: the slot types are rendered through their canonical
     * text, which resolves.
     *
     * @param target the annotated type
     * @param declared the builder it declares
     * @param annotation the target's {@code @ClassBuilder}
     * @return the mistyped fields, in the declared builder's field order
     */
    public static @NotNull List<Mistyped> mistypedFields(@NotNull PsiClass target,
                                                          @NotNull PsiClass declared,
                                                          @NotNull PsiAnnotation annotation) {
        String declaredName = declared.getName();
        if (declaredName == null) return List.of();
        SetterScheme setters =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(annotation).setters();
        Set<String> excluded = GeneratedMemberFactory.excludedNames(target);
        List<PsiFieldShape> slots = target.isRecord()
            ? PsiFieldShapeExtractor.fromRecord(target, excluded, setters)
            : PsiFieldShapeExtractor.fromClass(target, excluded, setters);

        List<Mistyped> out = new ArrayList<>();
        for (PsiField field : ownFields(declared)) {
            PsiTypeElement written = field.getTypeElement();
            if (written == null) continue;
            for (PsiFieldShape slot : slots) {
                if (!slot.name.equals(field.getName())) continue;
                SlotHolding holding = holdingOf(target, slot);
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
