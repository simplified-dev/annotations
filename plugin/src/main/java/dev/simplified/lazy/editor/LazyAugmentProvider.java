package dev.simplified.lazy.editor;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.TypeAnnotationProvider;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightModifierList;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.IncorrectOperationException;
import dev.simplified.accessor.inspect.AccessorConstants;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.AccessorScheme;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import dev.simplified.lazy.mutate.LazyFieldMutator;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.AnnotatedLightModifierList;
import dev.simplified.shared.psi.GeneratedLightMethod;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import dev.simplified.shared.psi.WrittenAnnotations;
import dev.simplified.shared.psi.WrittenTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Surfaces the memoizing getter that {@link LazyFieldMutator}
 * synthesises at javac time, so autocompletion and goto-symbol resolve
 * {@code getFoo()} on a {@code @Lazy}-annotated field before the first build
 * round.
 *
 * <p>Field-level annotations are propagated to the synthetic method - both as
 * declaration-level annotations on the modifier list and as TYPE_USE
 * annotations on the return type for the JetBrains nullness pair - so hover
 * shows {@code @NotNull String getFoo()} when the source carries
 * {@code @NotNull String foo}.
 *
 * <p>The field's own type is reported as the storage it is rewritten to, which
 * is the whole reason {@link #inferType} is here. javac retypes a {@code @Lazy}
 * field to hold its deferred supplier, so an editor still showing the written
 * type marks a direct read of it green over source javac rejects - the drift
 * this project exists to prevent, pointing the wrong way.
 */
public final class LazyAugmentProvider extends AbstractRecursionSafeAugmentProvider {

    private static final String LAZY_FQN = "dev.simplified.annotations.Lazy";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";
    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";

    /**
     * Annotation FQNs (and short names) that should NOT propagate from the
     * field declaration onto the synthesised getter. {@code @Lazy} itself is
     * field-only; the {@code @ClassBuilder} companions all describe the field
     * contract, not the accessor.
     */
    private static final Set<String> SKIP_ANNOTATIONS = Set.of(
        LAZY_FQN, "Lazy",
        ClassBuilderConstants.COLLECTOR_FQN, "Collector",
        ClassBuilderConstants.NEGATE_FQN, "Negate",
        ClassBuilderConstants.FORMATTABLE_FQN, "Formattable",
        ClassBuilderConstants.BUILDER_DEFAULT_FQN, "BuilderDefault",
        ClassBuilderConstants.BUILDER_IGNORE_FQN, "BuilderIgnore",
        ClassBuilderConstants.BUILD_FLAG_FQN, "BuildFlag",
        ClassBuilderConstants.OBTAIN_VIA_FQN, "ObtainVia"
    );

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element,
                                                                     @NotNull Class<Psi> type,
                                                                     @Nullable String nameHint) {
        if (!(element instanceof PsiClass target)) return Collections.emptyList();
        if (!PsiMethod.class.isAssignableFrom(type)) return Collections.emptyList();
        // Records compile their components into auto-generated accessors via
        // the record contract; @Lazy on record components isn't supported.
        if (target.isRecord()) return Collections.emptyList();
        // Re-entry guard: target.getMethods() inside synthesis triggers all
        // augment providers, including this one. The inner call returns empty
        // so the outer call sees only the user's declared methods - exactly
        // what collision detection needs.
        if (IN_PROGRESS.get().contains(target)) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<Psi> methods = (List<Psi>) cachedGetters(target);
        return methods;
    }

    /**
     * Reports a {@code @Lazy} field as the storage javac rewrites it to.
     *
     * <p>Only the declared type is replaced; the initializer keeps whatever
     * type it was written with, because the processor moves that expression
     * into the supplier rather than assigning it to the field. Returning the
     * storage type for an initializer would mark the author's own initializer
     * red.
     *
     * @param variable the declaration being typed
     * @return the storage type, or {@code null} to leave the written type alone
     */
    @Override
    protected @Nullable PsiType inferType(@NotNull PsiTypeElement variable) {
        PsiElement parent = variable.getParent();
        if (!(parent instanceof PsiField field)) return null;
        if (field.hasModifierProperty(PsiModifier.STATIC)) return null;
        if (!WrittenAnnotations.has(field, LAZY_FQN)) return null;

        // Read from the declaration's text rather than resolved: resolving this
        // element is what called us, so asking it for a type again is a cycle.
        PsiType written = WrittenTypes.of(field);
        if (written == null || written instanceof PsiArrayType) return null;

        PsiType boxed = written instanceof PsiPrimitiveType primitive
            ? primitive.getBoxedType(field)
            : written;
        if (boxed == null) return null;
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(field.getProject());
        try {
            return elements.createTypeFromText(
                "java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<"
                    + boxed.getCanonicalText() + ">>",
                field);
        } catch (IncorrectOperationException e) {
            // An unresolvable written type cannot be spelled back into a
            // storage type, and leaving the field as written is the honest
            // answer while the author is still typing it.
            return null;
        }
    }

    private static List<PsiMethod> cachedGetters(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            List<PsiMethod> methods = synthesizeLazyGetters(target);
            return CachedValueProvider.Result.create(methods, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiMethod> synthesizeLazyGetters(PsiClass target) {
        List<PsiField> lazyFields = collectLazyFields(target);
        if (lazyFields.isEmpty()) return Collections.emptyList();

        IN_PROGRESS.get().add(target);
        Set<String> existingZeroArg;
        try {
            existingZeroArg = collectExistingZeroArgMethodNames(target);
        } finally {
            IN_PROGRESS.get().remove(target);
        }
        PsiManager manager = target.getManager();
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(target.getProject());
        List<PsiMethod> out = new ArrayList<>(lazyFields.size());
        for (PsiField field : lazyFields) {
            // The written type, not the storage it is rewritten to - the getter
            // is what hands the caller the value.
            PsiType fieldType = WrittenTypes.of(field);
            if (fieldType == null) continue;
            // The written type decides this, not the storage: the storage is a
            // supplier and would never read as boolean.
            boolean isBoolean = PsiTypes.booleanType().equals(fieldType);
            AccessorScheme scheme = schemeFor(field);
            String getterName = scheme.readName(field.getName(), isBoolean);
            if (existingZeroArg.contains(getterName)) continue;
            out.add(buildGetter(manager, elements, target, field, getterName, fieldType,
                renamed -> scheme.readName(renamed, isBoolean)));
        }
        return out;
    }

    /**
     * Builds the synthetic {@code public T getFoo()} for a single @Lazy field.
     * Annotations from the source field land on both the modifier list and -
     * for the JetBrains nullness annotations - the return type, so hover
     * surfaces them and DFA flags null-handling violations.
     */
    private static PsiMethod buildGetter(PsiManager manager, PsiElementFactory elements,
                                         PsiClass target, PsiField field, String getterName,
                                         PsiType fieldType, UnaryOperator<String> naming) {
        Map<String, PsiAnnotation> propagated = collectPropagatedAnnotations(elements, target, field);

        PsiType effectiveReturnType = fieldType;
        List<PsiAnnotation> typeUse = new ArrayList<>(2);
        for (Map.Entry<String, PsiAnnotation> entry : propagated.entrySet()) {
            if (NOT_NULL_FQN.equals(entry.getKey()) || NULLABLE_FQN.equals(entry.getKey())) {
                typeUse.add(entry.getValue());
            }
        }
        if (!typeUse.isEmpty()) {
            effectiveReturnType = effectiveReturnType.annotate(TypeAnnotationProvider.Static.create(
                typeUse.toArray(PsiAnnotation.EMPTY_ARRAY)));
        }

        AnnotatedLightModifierList modifiers = new AnnotatedLightModifierList(manager, JavaLanguage.INSTANCE);
        String accessKeyword = readAccessKeyword(field);
        if (!accessKeyword.isEmpty()) modifiers.addModifier(accessKeyword);
        for (Map.Entry<String, PsiAnnotation> entry : propagated.entrySet()) {
            modifiers.add(entry.getKey(), entry.getValue());
        }

        LightMethodBuilder method = new GeneratedLightMethod(manager, JavaLanguage.INSTANCE, getterName,
            new com.intellij.psi.impl.light.LightParameterListBuilder(manager, JavaLanguage.INSTANCE),
            modifiers);
        method.setMethodReturnType(effectiveReturnType);
        method.setContainingClass(target);
        method.setNavigationElement(field);
        GeneratedMemberMarker.mark(method);
        GeneratedMemberMarker.markRename(method, naming);
        return method;
    }

    /**
     * The accessor naming scheme the field's {@code @Lazy} asks for.
     *
     * <p>Its own attributes, not a {@code @Getter}'s: the accessor pass steps
     * over a lazy field so that this one getter is the only one, which leaves
     * the naming of it this annotation's to answer.
     *
     * @param field the annotated field
     * @return the resolved scheme
     */
    private static AccessorScheme schemeFor(PsiField field) {
        PsiAnnotation lazy = WrittenAnnotations.find(field, LAZY_FQN);
        if (lazy == null) return AccessorScheme.of(NamingStyle.SIMPLIFIED);
        return AccessorScheme.resolve(AccessorConstants.style(lazy), AccessorConstants.name(lazy));
    }

    /**
     * Reads field-level annotations and filters out the ones that don't
     * belong on a getter (the @Lazy marker itself + ClassBuilder field-only
     * companions). Returns a map keyed by FQN to keep iteration order
     * deterministic for cache equality.
     *
     * <p>The one place here that has to resolve every annotation it sees
     * rather than gate on a name it already knows: what it propagates is
     * whatever the field carries, so the qualified name is the output, not a
     * test. The skip set is applied to both spellings for that reason.
     */
    private static Map<String, PsiAnnotation> collectPropagatedAnnotations(PsiElementFactory elements,
                                                                          PsiClass target,
                                                                          PsiField field) {
        Map<String, PsiAnnotation> out = new LinkedHashMap<>();
        for (PsiAnnotation a : field.getAnnotations()) {
            String fqn = a.getQualifiedName();
            if (fqn == null) continue;
            if (SKIP_ANNOTATIONS.contains(fqn)) continue;
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (SKIP_ANNOTATIONS.contains(simple)) continue;
            try {
                PsiAnnotation rebuilt = elements.createAnnotationFromText("@" + fqn, target);
                out.put(fqn, rebuilt);
            } catch (Exception ignored) {
                // Skip annotations the IDE can't reconstruct - safer than
                // dragging the whole synthesis to a halt over one bad input.
            }
        }
        return out;
    }

    /**
     * Reads {@code @Lazy.access()} as a PSI modifier keyword. Maps
     * {@link AccessLevel#PACKAGE PACKAGE} to the
     * empty string (no keyword); everything else returns the lowercase
     * Java modifier. Default when unset is {@code "public"}.
     */
    private static String readAccessKeyword(PsiField field) {
        PsiAnnotation lazy = WrittenAnnotations.find(field, LAZY_FQN);
        if (lazy == null) return PsiModifier.PUBLIC;
        PsiAnnotationMemberValue value = lazy.findAttributeValue("access");
        if (value instanceof PsiReferenceExpression ref) {
            String name = ref.getReferenceName();
            if (name != null) {
                return switch (name) {
                    case "PROTECTED" -> PsiModifier.PROTECTED;
                    case "PRIVATE" -> PsiModifier.PRIVATE;
                    case "PACKAGE" -> "";
                    default -> PsiModifier.PUBLIC;
                };
            }
        }
        return PsiModifier.PUBLIC;
    }

    private static List<PsiField> collectLazyFields(PsiClass target) {
        List<PsiField> out = new ArrayList<>();
        for (PsiField field : target.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (WrittenAnnotations.has(field, LAZY_FQN)) out.add(field);
        }
        return out;
    }

    /**
     * Collects the names of all zero-argument methods directly declared on
     * {@code target}. Uses {@link PsiExtensibleClass#getOwnMethods()}
     * rather than {@link PsiClass#getMethods()} - the latter is augment-aware
     * and re-enters every {@link PsiAugmentProvider} (including this one),
     * causing a {@link StackOverflowError} in the synthesis call chain. For
     * collision detection we only care about user-written methods, which is
     * exactly what {@code getOwnMethods()} returns.
     */
    private static Set<String> collectExistingZeroArgMethodNames(PsiClass target) {
        Set<String> out = new java.util.HashSet<>();
        Iterable<PsiMethod> methods = target instanceof com.intellij.psi.impl.source.PsiExtensibleClass ext
            ? ext.getOwnMethods()
            : List.of(target.getMethods());
        for (PsiMethod m : methods) {
            if (m.getParameterList().getParametersCount() == 0) out.add(m.getName());
        }
        return out;
    }

}
