package dev.simplified.equality.editor;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.impl.light.LightFieldBuilder;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import dev.simplified.equality.apt.EqualityConfig;
import dev.simplified.shared.psi.AbstractRecursionSafeAugmentProvider;
import dev.simplified.shared.psi.GeneratedLightMethod;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import dev.simplified.tostring.apt.ToStringConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Surfaces the members {@code @EqualsAndHashCode} and {@code @ToString}
 * synthesise at javac time.
 *
 * <p>For three of the four members this provider looks like it is doing
 * nothing, and it very nearly is: {@code equals}, {@code hashCode} and
 * {@code toString} are inherited from {@code Object}, so their absence is never
 * an error and every call site resolves identically with or without the
 * contribution. What it buys is the IDE's own reporting - "Class does not
 * override equals()/hashCode()" and "object used as a HashMap key does not
 * override equals()" both fire on a type whose pair only arrives at build time,
 * and both go quiet once the members are contributed - plus a Ctrl-click that
 * lands on the annotation that asked for the member rather than on
 * {@code Object}'s declaration of it.
 *
 * <p>{@code canEqual} and the {@code $hashCode} memo are the half that is not
 * cosmetic. Both are <b>new</b> names, so without them a subclass overriding the
 * hook, or a hand-written parent calling {@code other.canEqual(this)}, resolves
 * to nothing and the file stays red until the next build.
 *
 * <p>One provider for two annotations because a class writes both far more often
 * than either alone, and the collision rules that decide whether a member is
 * contributed at all are read off the same set of declared signatures.
 *
 * <p><b>A record is skipped outright.</b> The platform already models a record's
 * implicit {@code equals} / {@code hashCode} / {@code toString}, so a second set
 * reads as a duplicate declaration and lights up members the author did not
 * write. Nothing is lost by it: the hook is suppressed on a final root type and
 * the memo is refused on a record body, so neither of the two names that are not
 * inherited ever reaches one.
 */
public final class WholeObjectAugmentProvider extends AbstractRecursionSafeAugmentProvider {

    private static final String EQUALITY_NAME = "EqualsAndHashCode";
    private static final String TO_STRING_NAME = "ToString";
    private static final String OBJECT_FQN = "java.lang.Object";
    private static final String RECORD_FQN = "java.lang.Record";
    private static final String STRING_FQN = "java.lang.String";
    private static final String CACHE_FIELD = "$hashCode";
    private static final String CAN_EQUAL = "canEqual";
    private static final String EQUALS_SIGNATURE = "equals/1";
    private static final String HASH_CODE_SIGNATURE = "hashCode/0";
    private static final String TO_STRING_SIGNATURE = "toString/0";

    @Override
    protected @NotNull <Psi extends PsiElement> List<Psi> getAugments(@NotNull PsiElement element,
                                                                     @NotNull Class<Psi> type,
                                                                     @Nullable String nameHint) {
        if (!(element instanceof PsiClass target)) return Collections.emptyList();
        // Everything the processor refuses as a target, plus records, whose
        // implicit members the platform already contributes.
        if (target.isRecord() || target.isInterface() || target.isEnum()) return Collections.emptyList();
        // Re-entry guard: reading the target's own members during synthesis
        // re-triggers every augment provider, this one included.
        if (IN_PROGRESS.get().contains(target)) return Collections.emptyList();

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

    // ------------------------------------------------------------------
    // Annotation lookup
    // ------------------------------------------------------------------

    /**
     * The {@code @EqualsAndHashCode} written on a type, or {@code null}.
     *
     * @param target the type to read
     * @return the annotation, or null when the type does not carry it
     */
    public static @Nullable PsiAnnotation equalityAnnotation(@NotNull PsiClass target) {
        return annotation(target, EQUALITY_NAME, EqualityConfig.FQN);
    }

    /**
     * The {@code @ToString} written on a type, or {@code null}.
     *
     * @param target the type to read
     * @return the annotation, or null when the type does not carry it
     */
    public static @Nullable PsiAnnotation toStringAnnotation(@NotNull PsiClass target) {
        return annotation(target, TO_STRING_NAME, ToStringConfig.FQN);
    }

    /**
     * The annotation of a given name written on a type.
     *
     * <p>The written simple name is matched before the reference is resolved,
     * and the ordering is the point rather than the speed. Resolving an
     * annotation re-enters every augment provider registered for the class, this
     * one among them, and the platform answers that cycle by disabling caching
     * and logging an error - so the resolve has to be reachable only from a type
     * that already spells the name.
     */
    private static @Nullable PsiAnnotation annotation(PsiClass target, String simpleName,
                                                      String fqn) {
        PsiModifierList modifiers = target.getModifierList();
        if (modifiers == null) return null;
        for (PsiAnnotation annotation : modifiers.getAnnotations()) {
            PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            if (reference == null) continue;
            if (!simpleName.equals(reference.getReferenceName())) continue;
            if (fqn.equals(annotation.getQualifiedName())) return annotation;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Synthesis
    // ------------------------------------------------------------------

    private static List<PsiMethod> cachedMethods(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            List<PsiMethod> methods = synthesizeMethods(target);
            return CachedValueProvider.Result.create(methods,
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static List<PsiField> cachedFields(PsiClass target) {
        return CachedValuesManager.getCachedValue(target, () -> {
            List<PsiField> fields = synthesizeFields(target);
            return CachedValueProvider.Result.create(fields,
                PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    /**
     * The members the two annotations contribute, skipping any the class
     * declares itself.
     *
     * <p>The equality pair is all-or-nothing on <b>either</b> half being
     * written, which mirrors the processor: a target declaring one of them is an
     * error there and produces neither member, so contributing the other here
     * would promise something javac never emits.
     */
    private static List<PsiMethod> synthesizeMethods(PsiClass target) {
        PsiAnnotation equality = equalityAnnotation(target);
        PsiAnnotation toString = toStringAnnotation(target);
        if (equality == null && toString == null) return Collections.emptyList();

        PsiManager manager = target.getManager();
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(target.getProject());
        List<PsiMethod> out = new ArrayList<>(4);

        IN_PROGRESS.get().add(target);
        try {
            Set<String> declared = declaredSignatures(target);
            PsiType object = elements.createTypeFromText(OBJECT_FQN, target);

            if (equality != null && !declared.contains(EQUALS_SIGNATURE)
                && !declared.contains(HASH_CODE_SIGNATURE)) {
                out.add(method(manager, target, equality, "equals", PsiTypes.booleanType(),
                    PsiModifier.PUBLIC, "o", object));
                out.add(method(manager, target, equality, "hashCode", PsiTypes.intType(),
                    PsiModifier.PUBLIC, null, null));
                if (emitsCanEqual(target, equality, declared)) {
                    out.add(method(manager, target, equality, CAN_EQUAL, PsiTypes.booleanType(),
                        PsiModifier.PROTECTED, "other", object));
                }
            }
            if (toString != null && !declared.contains(TO_STRING_SIGNATURE)) {
                out.add(method(manager, target, toString, "toString",
                    elements.createTypeFromText(STRING_FQN, target), PsiModifier.PUBLIC, null, null));
            }
        } finally {
            IN_PROGRESS.get().remove(target);
        }
        return out;
    }

    /**
     * The hash memo, contributed only where the generated {@code hashCode} that
     * reads it is itself contributed.
     */
    private static List<PsiField> synthesizeFields(PsiClass target) {
        PsiAnnotation equality = equalityAnnotation(target);
        if (equality == null || !booleanAttr(equality, "cacheHashCode")) {
            return Collections.emptyList();
        }

        IN_PROGRESS.get().add(target);
        try {
            Set<String> declared = declaredSignatures(target);
            if (declared.contains(EQUALS_SIGNATURE) || declared.contains(HASH_CODE_SIGNATURE)) {
                return Collections.emptyList();
            }
            if (declaresField(target, CACHE_FIELD)) return Collections.emptyList();
            return List.of(cacheField(target, equality));
        } finally {
            IN_PROGRESS.get().remove(target);
        }
    }

    /**
     * Whether the {@code canEqual} hook is contributed.
     *
     * <p>The processor's rule, restated: only under
     * {@code identity = INSTANCE_OF_CANEQUAL}, and never on a type that is both
     * {@code final} and a direct subclass of {@code Object}, where no subclass
     * exists to override it.
     */
    private static boolean emitsCanEqual(PsiClass target, PsiAnnotation equality,
                                         Set<String> declared) {
        if (!"INSTANCE_OF_CANEQUAL".equals(enumConstant(equality, "identity"))) return false;
        if (declared.contains(CAN_EQUAL + "/1")) return false;
        return !target.hasModifierProperty(PsiModifier.FINAL) || callableSuperclass(target) != null;
    }

    /**
     * The direct superclass a generated member could call, or {@code null}.
     *
     * <p>{@code java.lang.Record} counts as none for the same reason
     * {@code java.lang.Object} does: it declares the pair abstract, so there is
     * nothing there to call.
     *
     * @param target the type to walk up from
     * @return the superclass, or null when there is none worth calling
     */
    public static @Nullable PsiClass callableSuperclass(@NotNull PsiClass target) {
        PsiClass superClass = target.getSuperClass();
        if (superClass == null) return null;
        String fqn = superClass.getQualifiedName();
        if (OBJECT_FQN.equals(fqn) || RECORD_FQN.equals(fqn)) return null;
        return superClass;
    }

    // ------------------------------------------------------------------
    // Light members
    // ------------------------------------------------------------------

    /**
     * One synthesised member, taking its navigation element from the annotation
     * that asked for it.
     *
     * <p>Navigating to the annotation rather than to the target is the whole
     * payoff on {@code equals} / {@code hashCode} / {@code toString}: the
     * declaration a reader would otherwise reach is {@code Object}'s, which says
     * nothing about why this type has its own.
     */
    private static PsiMethod method(PsiManager manager, PsiClass target, PsiAnnotation source,
                                    String name, PsiType returnType, String access,
                                    @Nullable String paramName, @Nullable PsiType paramType) {
        LightMethodBuilder method = new GeneratedLightMethod(manager, name)
            .setMethodReturnType(returnType)
            .setContainingClass(target);
        method.addModifier(access);
        if (paramName != null && paramType != null) method.addParameter(paramName, paramType);
        method.setNavigationElement(source);
        GeneratedMemberMarker.mark(method);
        return method;
    }

    /**
     * The hash memo field.
     *
     * <p>{@code transient} here is not decoration - it is what the processor
     * emits, and a memo that survives serialization is a hash computed in
     * another JVM.
     */
    private static PsiField cacheField(PsiClass target, PsiAnnotation source) {
        LightFieldBuilder field =
            new LightFieldBuilder(target.getManager(), CACHE_FIELD, PsiTypes.intType());
        field.setModifiers(PsiModifier.PRIVATE, PsiModifier.TRANSIENT);
        field.setContainingClass(target);
        field.setNavigationElement(source);
        GeneratedMemberMarker.mark(field);
        return field;
    }

    // ------------------------------------------------------------------
    // The target's own declarations
    // ------------------------------------------------------------------

    /**
     * Method name plus argument count for everything the class declares.
     *
     * <p>{@code getOwnMethods()} rather than {@code getMethods()}: the latter is
     * augment-aware and re-enters every provider, this one included, which is a
     * {@link StackOverflowError} in the synthesis call chain. Collision only
     * cares about members the author wrote, which is exactly what the own
     * variant returns.
     *
     * <p>A one-argument {@code equals} is only counted when its parameter is
     * {@code Object}, which is the mutator's own rule. A typed convenience
     * overload {@code equals(Vec)} overrides nothing, so javac still generates
     * the pair for such a type - and reading it as a written {@code equals} would
     * withhold both contributions from a class that ends up with both, leaving
     * the platform's "does not override equals()/hashCode()" report standing over
     * a build that succeeds.
     */
    private static Set<String> declaredSignatures(PsiClass target) {
        Set<String> out = new HashSet<>();
        Iterable<PsiMethod> methods = target instanceof PsiExtensibleClass ext
            ? ext.getOwnMethods()
            : List.of(target.getMethods());
        for (PsiMethod method : methods) {
            PsiParameter[] parameters = method.getParameterList().getParameters();
            if ("equals".equals(method.getName()) && parameters.length == 1
                && !takesObject(parameters[0])) continue;
            out.add(method.getName() + "/" + parameters.length);
        }
        return out;
    }

    /**
     * Whether a parameter is spelled {@code Object}.
     *
     * <p>Matched on the written name rather than on the resolved type, which is
     * what the mutator does on its own side and for the same reason: this runs
     * over source that is being typed, and a moment where {@code java.lang}
     * has not resolved yet would otherwise read the author's own
     * {@code equals(Object)} as an overload and contribute a second one beside
     * it.
     */
    private static boolean takesObject(PsiParameter parameter) {
        PsiType type = parameter.getType();
        if (type.equalsToText(OBJECT_FQN)) return true;
        return type instanceof PsiClassType classType && "Object".equals(classType.getClassName());
    }

    private static boolean declaresField(PsiClass target, String name) {
        Iterable<PsiField> fields = target instanceof PsiExtensibleClass ext
            ? ext.getOwnFields()
            : List.of(target.getFields());
        for (PsiField field : fields) {
            if (name.equals(field.getName())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Attribute readers
    // ------------------------------------------------------------------

    private static @Nullable String enumConstant(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        if (value instanceof PsiReferenceExpression reference) return reference.getReferenceName();
        return null;
    }

    private static boolean booleanAttr(PsiAnnotation annotation, String attribute) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(attribute);
        return value != null && "true".equals(value.getText());
    }

}
