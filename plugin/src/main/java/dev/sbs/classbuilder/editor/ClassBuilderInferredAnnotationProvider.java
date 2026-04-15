package dev.sbs.classbuilder.editor;

import com.intellij.codeInsight.InferredAnnotationProvider;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import dev.sbs.classbuilder.inspect.ClassBuilderConstants;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Delivers attribute-bearing method-level annotations ({@code @XContract} and
 * the derived JetBrains {@code @Contract}) for members synthesised by
 * {@link ClassBuilderAugmentProvider} at query time.
 *
 * <p>{@code LightModifierList.addAnnotation(String)} only accepts zero-
 * attribute annotation FQNs, so attribute-carrying annotations cannot ride on
 * the {@link com.intellij.psi.impl.light.LightMethodBuilder}s the augment
 * provider produces. This provider fills that gap: for every method tagged by
 * {@link GeneratedMemberMarker}, it reconstructs the contract shape from the
 * method's signature and the containing target's {@code @ClassBuilder} config,
 * then hands back freshly-built {@link PsiAnnotation}s.
 *
 * <p>Shape matrix, mirroring
 * {@code dev.sbs.classbuilder.mutate.ContractAnnotations}:
 * <ul>
 *   <li>{@code builder()}, {@code mutate()}, {@code build()} - {@code "-> new"}.</li>
 *   <li>{@code from(T)} - {@code "_ -> new"}, {@code pure = true}.</li>
 *   <li>setter, 0 args - {@code "-> this"}, {@code mutates = "this"}.</li>
 *   <li>setter, 1 arg - {@code "_ -> this"}, {@code mutates = "this"}.</li>
 *   <li>setter, 2 args - {@code "_, _ -> this"}, {@code mutates = "this"}.</li>
 * </ul>
 *
 * <p>Gated on {@code @ClassBuilder(emitContracts = true)} (defaults true). When
 * disabled, this provider returns nothing - matching the library's single-gate
 * behaviour at {@code ContractAnnotations#list}.
 */
public final class ClassBuilderInferredAnnotationProvider implements InferredAnnotationProvider {

    private static final String CONTRACT_FQN = Contract.class.getName();
    private static final String XCONTRACT_FQN = ClassBuilderConstants.XCONTRACT_FQN;

    @Override
    public @Nullable PsiAnnotation findInferredAnnotation(@NotNull PsiModifierListOwner listOwner,
                                                          @NotNull String annotationFQN) {
        if (!CONTRACT_FQN.equals(annotationFQN) && !XCONTRACT_FQN.equals(annotationFQN)) return null;
        ContractShape shape = shapeOf(listOwner);
        if (shape == null) return null;
        return createAnnotation(listOwner, annotationFQN, shape);
    }

    @Override
    public @NotNull List<PsiAnnotation> findInferredAnnotations(@NotNull PsiModifierListOwner listOwner) {
        ContractShape shape = shapeOf(listOwner);
        if (shape == null) return List.of();

        List<PsiAnnotation> out = new ArrayList<>(2);
        PsiAnnotation xContract = createAnnotation(listOwner, XCONTRACT_FQN, shape);
        if (xContract != null) out.add(xContract);
        PsiAnnotation jbContract = createAnnotation(listOwner, CONTRACT_FQN, shape);
        if (jbContract != null) out.add(jbContract);
        return out;
    }

    /**
     * Computes the contract shape for a synthesised method, or {@code null}
     * when the owner is not one of ours, the enclosing target's annotation
     * cannot be resolved, or {@code emitContracts = false}.
     */
    private static @Nullable ContractShape shapeOf(@NotNull PsiModifierListOwner owner) {
        if (!(owner instanceof PsiMethod method)) return null;
        if (!GeneratedMemberMarker.isGenerated(method)) return null;

        PsiClass containing = method.getContainingClass();
        if (containing == null) return null;

        boolean inBuilderClass = GeneratedMemberMarker.isGenerated(containing);
        PsiClass target = inBuilderClass ? containing.getContainingClass() : containing;
        if (target == null) return null;

        PsiAnnotation classBuilder = findClassBuilderAnnotation(target);
        if (classBuilder == null) return null;
        if (!ClassBuilderConstants.booleanAttr(classBuilder, ClassBuilderConstants.ATTR_EMIT_CONTRACTS, true))
            return null;

        GeneratedMemberFactory.EditorBuilderConfig config =
            GeneratedMemberFactory.EditorBuilderConfig.fromAnnotation(classBuilder);

        String name = method.getName();
        int params = method.getParameterList().getParametersCount();

        // build() - inside the synth Builder class.
        if (inBuilderClass && name.equals(config.buildMethodName())) return ContractShape.NEW_NULLARY;

        // Bootstraps - attached directly to the target.
        if (!inBuilderClass) {
            if (name.equals(config.builderMethodName()) && params == 0) return ContractShape.NEW_NULLARY;
            if (name.equals(config.toBuilderMethodName()) && params == 0) return ContractShape.NEW_NULLARY;
            if (name.equals(config.fromMethodName()) && params == 1) return ContractShape.NEW_UNARY_PURE;
            return null;
        }

        // Any other method inside the synth Builder is a setter; shape by arity.
        return switch (params) {
            case 0 -> ContractShape.THIS_NULLARY;
            case 1 -> ContractShape.THIS_UNARY;
            case 2 -> ContractShape.THIS_BINARY;
            default -> null;
        };
    }

    private static @Nullable PsiAnnotation createAnnotation(@NotNull PsiModifierListOwner owner,
                                                            @NotNull String fqn,
                                                            @NotNull ContractShape shape) {
        String text = "@" + fqn + shape.attributes();
        try {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(owner.getProject());
            return factory.createAnnotationFromText(text, owner);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable PsiAnnotation findClassBuilderAnnotation(@NotNull PsiClass target) {
        for (PsiAnnotation a : target.getAnnotations()) {
            if (ClassBuilderConstants.ANNOTATION_FQN.equals(a.getQualifiedName())) return a;
        }
        return null;
    }

    /**
     * Contract shapes emitted by {@link ClassBuilderInferredAnnotationProvider}.
     * Each shape renders to a common attribute suffix used for both
     * {@code @XContract} and {@code @Contract} - their attribute grammars
     * overlap exactly for the shapes we emit.
     */
    enum ContractShape {

        THIS_NULLARY("-> this", false, "this"),
        THIS_UNARY("_ -> this", false, "this"),
        THIS_BINARY("_, _ -> this", false, "this"),
        NEW_NULLARY("-> new", false, null),
        NEW_UNARY_PURE("_ -> new", true, null);

        private final String value;
        private final boolean pure;
        private final @Nullable String mutates;

        ContractShape(String value, boolean pure, @Nullable String mutates) {
            this.value = value;
            this.pure = pure;
            this.mutates = mutates;
        }

        /** Renders the {@code (value = "...", pure = ..., mutates = "...")} suffix. */
        @NotNull String attributes() {
            StringBuilder sb = new StringBuilder("(value = \"").append(value).append("\"");
            if (pure) sb.append(", pure = true");
            if (mutates != null) sb.append(", mutates = \"").append(mutates).append("\"");
            sb.append(")");
            return sb.toString();
        }

    }

}
