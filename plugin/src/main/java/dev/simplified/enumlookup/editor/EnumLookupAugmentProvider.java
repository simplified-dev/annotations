package dev.simplified.enumlookup.editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import dev.simplified.enumlookup.inspect.EnumLookupConstants;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.GeneratedLightMethod;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import dev.simplified.shared.psi.WrittenAnnotations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Surfaces the synthesised members from {@code EnumLookupMutator} to the PSI
 * layer so autocompletion, goto-symbol, and type resolution all work before
 * the first javac round.
 *
 * <p>Only triggers on enum {@link PsiClass}es carrying {@code @EnumLookup}.
 * Scans the target's fields for {@code @KeyField} annotations and synthesises:
 * <ul>
 *   <li>{@code private static final E[] CACHED_VALUES} plus one
 *       {@code CACHED_KEYS_<fieldName>} per annotated field</li>
 *   <li>the per-enum methods ({@code size}, two {@code forEach} overloads,
 *       {@code stream}, {@code parallelStream}, {@code ofName}, {@code ofOrdinal},
 *       {@code findByName}, {@code findByOrdinal})</li>
 *   <li>per-{@code @KeyField} {@code of<Name>(T)} / {@code findBy<Name>(T)}
 *       overloads</li>
 * </ul>
 */
public final class EnumLookupAugmentProvider extends AbstractRecursionSafeAugmentProvider {

    private static final String FQN_CONSUMER = "java.util.function.Consumer";
    private static final String FQN_BICONSUMER = "java.util.function.BiConsumer";
    private static final String FQN_STREAM = "java.util.stream.Stream";
    private static final String FQN_OPTIONAL = "java.util.Optional";
    private static final String FQN_STRING = "java.lang.String";

    private static final String CACHED_VALUES = EnumLookupConstants.CACHED_VALUES;
    private static final String CACHED_KEYS_PREFIX = EnumLookupConstants.CACHED_KEYS_PREFIX;

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element,
                                                                     @NotNull Class<Psi> type,
                                                                     @Nullable String nameHint) {
        if (!(element instanceof PsiClass target)) return Collections.emptyList();
        if (!target.isEnum()) return Collections.emptyList();
        if (!hasEnumLookup(target)) return Collections.emptyList();

        if (PsiMethod.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            List<Psi> methods = (List<Psi>) cachedMethods(target);
            return methods;
        }
        if (PsiField.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            List<Psi> fields = (List<Psi>) cachedFields(target);
            return fields;
        }
        return Collections.emptyList();
    }

    private static boolean hasEnumLookup(PsiClass target) {
        return WrittenAnnotations.has(target, EnumLookupConstants.ENUM_LOOKUP_FQN);
    }

    /**
     * Returns the {@code (fieldName, methodSuffix, fieldType)} tuples for
     * every instance field on {@code target} carrying {@code @KeyField}.
     * Uses {@link PsiExtensibleClass#getOwnFields()} to read only declared
     * fields - calling {@link PsiClass#getFields()} would re-enter our augment
     * provider and recurse.
     */
    private static List<KeyFieldInfo> collectKeyFields(PsiClass target) {
        List<KeyFieldInfo> out = new ArrayList<>();
        Iterable<PsiField> fields = target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
        for (PsiField field : fields) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            PsiAnnotation annotation = findKeyFieldAnnotation(field);
            if (annotation == null) continue;
            String fieldName = field.getName();
            String methodSuffix = resolveMethodSuffix(annotation, fieldName);
            out.add(new KeyFieldInfo(fieldName, methodSuffix, field.getType()));
        }
        return out;
    }

    private static @Nullable PsiAnnotation findKeyFieldAnnotation(PsiField field) {
        return WrittenAnnotations.find(field, EnumLookupConstants.KEY_FIELD_FQN);
    }

    private static String resolveMethodSuffix(PsiAnnotation annotation, String fieldName) {
        String override = EnumLookupConstants.stringAttr(annotation, EnumLookupConstants.ATTR_METHOD_NAME, "");
        if (!override.isEmpty()) return override;
        if (fieldName.isEmpty()) return fieldName;
        return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private record KeyFieldInfo(String fieldName, String methodSuffix, PsiType fieldType) {
    }

    // ------------------------------------------------------------------
    // Synthesis - cached on the target's PSI modification count.
    // ------------------------------------------------------------------

    private static List<PsiMethod> cachedMethods(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            List<PsiMethod> methods = buildMethods(target);
            return CachedValueProvider.Result.create(methods, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiField> cachedFields(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            List<PsiField> fields = buildFields(target);
            return CachedValueProvider.Result.create(fields, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiField> buildFields(PsiClass target) {
        Project project = target.getProject();
        PsiManager manager = PsiManager.getInstance(project);
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

        List<PsiField> out = new ArrayList<>();

        if (!hasOwnField(target, CACHED_VALUES)) {
            PsiType arrayOfTarget = factory.createType(target).createArrayType();
            out.add(buildField(manager, target, CACHED_VALUES, arrayOfTarget));
        }
        for (KeyFieldInfo info : collectKeyFields(target)) {
            String fieldName = CACHED_KEYS_PREFIX + info.fieldName();
            if (hasOwnField(target, fieldName)) continue;
            PsiType keyArrayType = info.fieldType().createArrayType();
            out.add(buildField(manager, target, fieldName, keyArrayType));
        }
        return out;
    }

    private static PsiField buildField(PsiManager manager, PsiClass target, String name, PsiType type) {
        LightFieldBuilder field = new LightFieldBuilder(manager, name, type);
        field.setModifiers(PsiModifier.PRIVATE, PsiModifier.STATIC, PsiModifier.FINAL);
        field.setContainingClass(target);
        field.setNavigationElement(target);
        GeneratedMemberMarker.mark(field);
        return field;
    }

    private static List<PsiMethod> buildMethods(PsiClass target) {
        Project project = target.getProject();
        PsiManager manager = PsiManager.getInstance(project);
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

        String enumName = target.getName();
        if (enumName == null) return Collections.emptyList();

        PsiClassType enumType = factory.createType(target);
        PsiType stringType = factory.createTypeFromText(FQN_STRING, target);
        PsiType consumerType = factory.createTypeFromText(
            FQN_CONSUMER + "<? super " + enumName + ">", target);
        PsiType biConsumerType = factory.createTypeFromText(
            FQN_BICONSUMER + "<java.lang.Integer, ? super " + enumName + ">", target);
        PsiType streamType = factory.createTypeFromText(
            FQN_STREAM + "<" + enumName + ">", target);
        PsiType optionalType = factory.createTypeFromText(
            FQN_OPTIONAL + "<" + enumName + ">", target);

        List<PsiMethod> out = new ArrayList<>();
        out.add(staticMethod(manager, target, "size", PsiTypes.intType()));
        out.add(staticMethodOneParam(manager, target, "forEach", PsiTypes.voidType(), "action", consumerType));
        out.add(staticMethodOneParam(manager, target, "forEach", PsiTypes.voidType(), "action", biConsumerType));
        out.add(staticMethod(manager, target, "stream", streamType));
        out.add(staticMethod(manager, target, "parallelStream", streamType));
        out.add(staticMethodOneParam(manager, target, "ofName", enumType, "name", stringType));
        out.add(staticMethodOneParam(manager, target, "ofOrdinal", enumType, "ordinal", PsiTypes.intType()));
        out.add(staticMethodOneParam(manager, target, "findByName", optionalType, "name", stringType));
        out.add(staticMethodOneParam(manager, target, "findByOrdinal", optionalType, "ordinal", PsiTypes.intType()));

        for (KeyFieldInfo info : collectKeyFields(target)) {
            String ofName = "of" + info.methodSuffix();
            String findName = "findBy" + info.methodSuffix();
            out.add(staticMethodOneParam(manager, target, ofName, enumType, "key", info.fieldType()));
            out.add(staticMethodOneParam(manager, target, findName, optionalType, "key", info.fieldType()));
        }
        return out;
    }

    private static PsiMethod staticMethod(PsiManager manager, PsiClass target,
                                          String name, PsiType returnType) {
        LightMethodBuilder method = new GeneratedLightMethod(manager, name)
            .setMethodReturnType(returnType)
            .addModifiers(PsiModifier.PUBLIC, PsiModifier.STATIC)
            .setContainingClass(target);
        method.setNavigationElement(target);
        GeneratedMemberMarker.mark(method);
        return method;
    }

    private static PsiMethod staticMethodOneParam(PsiManager manager, PsiClass target,
                                                  String name, PsiType returnType,
                                                  String paramName, PsiType paramType) {
        LightMethodBuilder method = new GeneratedLightMethod(manager, name)
            .setMethodReturnType(returnType)
            .addModifiers(PsiModifier.PUBLIC, PsiModifier.STATIC)
            .setContainingClass(target)
            .addParameter(paramName, paramType);
        method.setNavigationElement(target);
        GeneratedMemberMarker.mark(method);
        return method;
    }

    private static boolean hasOwnField(PsiClass target, String name) {
        Iterable<PsiField> fields = target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
        for (PsiField f : fields) {
            if (name.equals(f.getName())) return true;
        }
        return false;
    }
}
