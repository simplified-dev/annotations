package dev.simplified.classbuilder.editor;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
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
import com.intellij.psi.TypeAnnotationProvider;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightModifierList;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surfaces the memoizing getter that {@link dev.simplified.classbuilder.mutate.LazyFieldMutator}
 * synthesises at javac time, so autocompletion and goto-symbol resolve
 * {@code getFoo()} on a {@code @Lazy}-annotated field before the first build
 * round.
 *
 * <p>Field-level annotations are propagated to the synthetic method - both as
 * declaration-level annotations on the modifier list and as TYPE_USE
 * annotations on the return type for the JetBrains nullness pair - so hover
 * shows {@code @NotNull String getFoo()} when the source carries
 * {@code @NotNull String foo}.
 */
public final class LazyAugmentProvider extends PsiAugmentProvider {

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
        ClassBuilderConstants.BUILD_RULE_FQN, "BuildRule"
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

        @SuppressWarnings("unchecked")
        List<Psi> methods = (List<Psi>) cachedGetters(target);
        return methods;
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

        Set<String> existingZeroArg = collectExistingZeroArgMethodNames(target);
        PsiManager manager = target.getManager();
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(target.getProject());
        List<PsiMethod> out = new ArrayList<>(lazyFields.size());
        for (PsiField field : lazyFields) {
            PsiType fieldType = field.getType();
            if (fieldType instanceof PsiPrimitiveType) continue;
            String getterName = "get" + capitalise(field.getName());
            if (existingZeroArg.contains(getterName)) continue;
            out.add(buildGetter(manager, elements, target, field, getterName, fieldType));
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
                                         PsiType fieldType) {
        Map<String, PsiAnnotation> propagated = collectPropagatedAnnotations(elements, target, field);

        PsiType effectiveReturnType = fieldType;
        List<PsiAnnotation> typeUse = new ArrayList<>(2);
        for (Map.Entry<String, PsiAnnotation> entry : propagated.entrySet()) {
            if (NOT_NULL_FQN.equals(entry.getKey()) || NULLABLE_FQN.equals(entry.getKey())) {
                typeUse.add(entry.getValue());
            }
        }
        if (!typeUse.isEmpty()) {
            effectiveReturnType = fieldType.annotate(TypeAnnotationProvider.Static.create(
                typeUse.toArray(PsiAnnotation.EMPTY_ARRAY)));
        }

        AnnotatedLightModifierList modifiers = new AnnotatedLightModifierList(manager, JavaLanguage.INSTANCE);
        String accessKeyword = readAccessKeyword(field);
        if (!accessKeyword.isEmpty()) modifiers.addModifier(accessKeyword);
        for (Map.Entry<String, PsiAnnotation> entry : propagated.entrySet()) {
            modifiers.add(entry.getKey(), entry.getValue());
        }

        LightMethodBuilder method = new LightMethodBuilder(manager, JavaLanguage.INSTANCE, getterName,
            new com.intellij.psi.impl.light.LightParameterListBuilder(manager, JavaLanguage.INSTANCE),
            modifiers);
        method.setMethodReturnType(effectiveReturnType);
        method.setContainingClass(target);
        method.setNavigationElement(field);
        GeneratedMemberMarker.mark(method);
        return method;
    }

    /**
     * Reads field-level annotations and filters out the ones that don't
     * belong on a getter (the @Lazy marker itself + ClassBuilder field-only
     * companions). Returns a map keyed by FQN to keep iteration order
     * deterministic for cache equality.
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
     * {@link dev.simplified.annotations.AccessLevel#PACKAGE PACKAGE} to the
     * empty string (no keyword); everything else returns the lowercase
     * Java modifier. Default when unset is {@code "public"}.
     */
    private static String readAccessKeyword(PsiField field) {
        PsiAnnotation lazy = field.getAnnotation(LAZY_FQN);
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
            for (PsiAnnotation a : field.getAnnotations()) {
                if (LAZY_FQN.equals(a.getQualifiedName())) {
                    out.add(field);
                    break;
                }
            }
        }
        return out;
    }

    private static Set<String> collectExistingZeroArgMethodNames(PsiClass target) {
        Set<String> out = new java.util.HashSet<>();
        for (PsiMethod m : target.getMethods()) {
            if (m.getParameterList().getParametersCount() == 0) out.add(m.getName());
        }
        return out;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * {@link LightModifierList} that exposes a caller-supplied annotation set.
     * Mirrors the helper inside {@link GeneratedMemberFactory}; kept private
     * here to avoid expanding the visible surface of that file just for
     * @Lazy.
     */
    private static final class AnnotatedLightModifierList extends LightModifierList {

        private final Map<String, PsiAnnotation> annotations = new LinkedHashMap<>(2);

        AnnotatedLightModifierList(PsiManager manager, com.intellij.lang.Language language) {
            super(manager, language);
        }

        void add(String qualifiedName, PsiAnnotation annotation) {
            annotations.put(qualifiedName, annotation);
        }

        @Override
        public @NotNull PsiAnnotation addAnnotation(@NotNull String qualifiedName) {
            PsiAnnotation annotation = JavaPsiFacade.getElementFactory(getProject())
                .createAnnotationFromText("@" + qualifiedName, null);
            annotations.put(qualifiedName, annotation);
            return annotation;
        }

        @Override
        public @NotNull PsiAnnotation[] getAnnotations() {
            return annotations.isEmpty()
                ? PsiAnnotation.EMPTY_ARRAY
                : annotations.values().toArray(PsiAnnotation.EMPTY_ARRAY);
        }

        @Override
        public @Nullable PsiAnnotation findAnnotation(@NotNull String qualifiedName) {
            return annotations.get(qualifiedName);
        }

        @Override
        public boolean hasAnnotation(@NotNull String qualifiedName) {
            return annotations.containsKey(qualifiedName);
        }

    }

}
