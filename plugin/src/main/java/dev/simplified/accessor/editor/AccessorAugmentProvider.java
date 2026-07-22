package dev.simplified.accessor.editor;

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
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.TypeAnnotationProvider;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightParameter;
import com.intellij.psi.impl.light.LightParameterListBuilder;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import dev.simplified.accessor.inspect.AccessorConstants;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.AccessorScheme;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.AnnotatedLightModifierList;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Surfaces the accessors {@code @Getter} and {@code @Setter} synthesise at
 * javac time, so a call resolves before the first build round.
 *
 * <p>This provider is not a convenience. Four module groups contain files whose
 * only implementation of an inherited abstract method is a generated accessor;
 * without it those files carry a permanent "must implement abstract method"
 * error, and since the IDE never runs javac, "until the next build" is not a
 * mitigation.
 *
 * <p>Names come from {@link AccessorScheme}, the same record the processor
 * reads, so the two cannot drift. The whole reason the naming trio is free of
 * javac and PSI references is to make that sharing possible.
 */
public final class AccessorAugmentProvider extends AbstractRecursionSafeAugmentProvider {

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element,
                                                                     @NotNull Class<Psi> type,
                                                                     @Nullable String nameHint) {
        if (!(element instanceof PsiClass target)) return Collections.emptyList();
        if (!PsiMethod.class.isAssignableFrom(type)) return Collections.emptyList();
        // A record's components are accessors already, and an interface has no
        // fields - both are rejected by the processor too.
        if (target.isRecord() || target.isInterface()) return Collections.emptyList();
        // Re-entry guard: reading the target's methods during synthesis
        // re-triggers every augment provider, this one included.
        if (IN_PROGRESS.get().contains(target)) return Collections.emptyList();

        @SuppressWarnings("unchecked")
        List<Psi> methods = (List<Psi>) cachedAccessors(target);
        return methods;
    }

    private static List<PsiMethod> cachedAccessors(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            List<PsiMethod> methods = synthesize(target);
            return CachedValueProvider.Result.create(methods, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiMethod> synthesize(PsiClass target) {
        PsiAnnotation typeGetter = target.getAnnotation(AccessorConstants.GETTER_FQN);
        PsiAnnotation typeSetter = target.getAnnotation(AccessorConstants.SETTER_FQN);

        List<PsiField> fields = new ArrayList<>();
        for (PsiField field : ownFields(target)) {
            if (typeGetter != null || typeSetter != null
                || field.getAnnotation(AccessorConstants.GETTER_FQN) != null
                || field.getAnnotation(AccessorConstants.SETTER_FQN) != null) {
                fields.add(field);
            }
        }
        if (fields.isEmpty()) return Collections.emptyList();

        IN_PROGRESS.get().add(target);
        Set<String> declared;
        try {
            declared = declaredSignatures(target);
        } finally {
            IN_PROGRESS.get().remove(target);
        }

        PsiManager manager = target.getManager();
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(target.getProject());
        List<PsiMethod> out = new ArrayList<>();

        for (PsiField field : fields) {
            addAccessor(out, declared, manager, elements, target, field, typeGetter, true);
            addAccessor(out, declared, manager, elements, target, field, typeSetter, false);
        }
        return out;
    }

    private static void addAccessor(List<PsiMethod> out, Set<String> declared, PsiManager manager,
                                    PsiElementFactory elements, PsiClass target, PsiField field,
                                    PsiAnnotation typeLevel, boolean read) {
        String fqn = read ? AccessorConstants.GETTER_FQN : AccessorConstants.SETTER_FQN;
        PsiAnnotation fieldLevel = field.getAnnotation(fqn);
        PsiAnnotation effective = fieldLevel != null ? fieldLevel : typeLevel;
        if (effective == null) return;

        String fieldName = field.getName();
        if (fieldLevel == null && AccessorConstants.excludes(typeLevel, fieldName)) return;

        String access = AccessorConstants.accessKeyword(effective);
        if (access == null) return; // AccessLevel.NONE

        boolean isFinal = field.hasModifierProperty(PsiModifier.FINAL);
        if (!read && isFinal) return;
        // @Lazy synthesises its own accessor, which LazyAugmentProvider already
        // contributes; a second one here would be a duplicate in the PSI too.
        if (field.getAnnotation(AccessorConstants.LAZY_FQN) != null) return;

        NamingStyle style = AccessorConstants.style(effective);
        AccessorScheme scheme = AccessorScheme.resolve(style, AccessorConstants.name(effective));
        boolean isBoolean = PsiTypes.booleanType().equals(field.getType());
        String methodName = read
            ? scheme.readName(fieldName, isBoolean)
            : scheme.writeName(fieldName);

        String signature = methodName + "/" + (read ? 0 : 1);
        if (!declared.add(signature)) return;

        out.add(read
            ? buildGetter(manager, elements, target, field, methodName, access)
            : buildSetter(manager, elements, target, field, methodName, access));
    }

    private static PsiMethod buildGetter(PsiManager manager, PsiElementFactory elements,
                                         PsiClass target, PsiField field, String methodName,
                                         String access) {
        PsiType returnType = annotate(field.getType(), nullness(elements, target, field));
        AnnotatedLightModifierList modifiers = modifiers(manager, field, access, elements, target);

        LightMethodBuilder method = new LightMethodBuilder(manager, JavaLanguage.INSTANCE, methodName,
            new LightParameterListBuilder(manager, JavaLanguage.INSTANCE), modifiers);
        method.setMethodReturnType(returnType);
        method.setContainingClass(target);
        method.setNavigationElement(field);
        GeneratedMemberMarker.mark(method);
        return method;
    }

    private static PsiMethod buildSetter(PsiManager manager, PsiElementFactory elements,
                                         PsiClass target, PsiField field, String methodName,
                                         String access) {
        AnnotatedLightModifierList modifiers = modifiers(manager, field, access, elements, target);

        LightParameterListBuilder params =
            new LightParameterListBuilder(manager, JavaLanguage.INSTANCE);
        // Nullness rides the parameter here, not the return type - the inverse
        // of the getter, and the reason the two shapes are built separately.
        PsiType paramType = annotate(field.getType(), nullness(elements, target, field));
        params.addParameter(new LightParameter(
            field.getName(), paramType, target, JavaLanguage.INSTANCE));

        LightMethodBuilder method = new LightMethodBuilder(manager, JavaLanguage.INSTANCE, methodName,
            params, modifiers);
        method.setMethodReturnType(PsiTypes.voidType());
        method.setContainingClass(target);
        method.setNavigationElement(field);
        GeneratedMemberMarker.mark(method);
        return method;
    }

    private static AnnotatedLightModifierList modifiers(PsiManager manager, PsiField field,
                                                        String access, PsiElementFactory elements,
                                                        PsiClass target) {
        AnnotatedLightModifierList modifiers =
            new AnnotatedLightModifierList(manager, JavaLanguage.INSTANCE);
        if (!access.isEmpty()) modifiers.addModifier(access);
        if (field.hasModifierProperty(PsiModifier.STATIC)) modifiers.addModifier(PsiModifier.STATIC);
        for (PsiAnnotation a : nullness(elements, target, field)) {
            String fqn = a.getQualifiedName();
            if (fqn != null) modifiers.add(fqn, a);
        }
        return modifiers;
    }

    /**
     * Nullness annotations rebuilt against the target, as an <b>allowlist</b>.
     *
     * <p>A denylist is the wrong shape here: it cannot enumerate Hibernate,
     * Jackson and Spring, and one leaked annotation changes what a persistence
     * provider does with the member.
     */
    private static List<PsiAnnotation> nullness(PsiElementFactory elements, PsiClass target,
                                                PsiField field) {
        List<PsiAnnotation> out = new ArrayList<>(2);
        for (PsiAnnotation a : field.getAnnotations()) {
            String fqn = a.getQualifiedName();
            if (fqn == null) continue;
            if (!AccessorConstants.NOT_NULL_FQN.equals(fqn)
                && !AccessorConstants.NULLABLE_FQN.equals(fqn)) continue;
            try {
                out.add(elements.createAnnotationFromText("@" + fqn, target));
            } catch (Exception ignored) {
                // An annotation the IDE cannot reconstruct is skipped rather
                // than halting synthesis for the whole class.
            }
        }
        return out;
    }

    private static PsiType annotate(PsiType type, List<PsiAnnotation> typeUse) {
        if (typeUse.isEmpty()) return type;
        return type.annotate(TypeAnnotationProvider.Static.create(
            typeUse.toArray(PsiAnnotation.EMPTY_ARRAY)));
    }

    /**
     * Fields the class itself declares. Inherited fields are excluded: a
     * subclass of an annotated parent would otherwise contribute a second copy
     * of every inherited accessor.
     */
    private static Iterable<PsiField> ownFields(PsiClass target) {
        return target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
    }

    /**
     * Method name plus argument count for everything the class declares.
     *
     * <p>{@code getOwnMethods()} rather than {@code getMethods()}: the latter
     * is augment-aware and re-enters every provider, this one included, which
     * is a {@link StackOverflowError} in the synthesis call chain. Collision
     * only cares about members the author wrote, which is exactly what the own
     * variant returns.
     */
    private static Set<String> declaredSignatures(PsiClass target) {
        Set<String> out = new HashSet<>();
        Iterable<PsiMethod> methods = target instanceof PsiExtensibleClass ext
            ? ext.getOwnMethods()
            : List.of(target.getMethods());
        for (PsiMethod m : methods) {
            out.add(m.getName() + "/" + m.getParameterList().getParametersCount());
        }
        return out;
    }

}
