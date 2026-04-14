package dev.sbs.classbuilder.editor;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import dev.sbs.classbuilder.inspect.ClassBuilderConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Factory for the synthetic PSI members the augment provider surfaces on a
 * {@code @ClassBuilder}-annotated class:
 * <ul>
 *   <li>the three bootstrap methods {@code static Builder builder()},
 *       {@code static Builder from(T)}, instance {@code Builder mutate()},</li>
 *   <li>the nested {@code Builder} class with one or more setters per
 *       discoverable field and a {@code build()} returning the target.</li>
 * </ul>
 *
 * <p>The synthesised Builder's setter matrix mirrors
 * {@code FieldMutators.setters} so editor autocompletion lines up with what
 * the APT mutator emits: plain, boolean zero-arg/typed pair plus optional
 * {@code @Negate} inverse pair, {@code Optional} nullable-raw/wrapped pair
 * plus optional {@code @Formattable} overload, {@code @Singular}
 * collection/map add/put/clear, array varargs, String {@code @Formattable}
 * overload. Parameter-level annotations (e.g. {@code @PrintFormat}) are not
 * surfaced at the editor layer - they flow through from the compiled class
 * file after the first javac round, at which point real-source inspections
 * pick them up.
 */
final class GeneratedMemberFactory {

    private GeneratedMemberFactory() {
    }

    static List<PsiMethod> bootstrapMethods(PsiClass target, EditorBuilderConfig config) {
        Project project = target.getProject();
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(project);

        String builderFqn = builderTypeFqn(target, config);
        PsiClassType builderType = (PsiClassType) elements.createTypeFromText(builderFqn, target);
        PsiClassType targetType = elements.createType(target);

        PsiMethod builderMethod = buildStaticNoArg(psiManager, target, config.builderMethodName(), builderType);
        PsiMethod fromMethod = buildStaticOneArg(psiManager, target, config.fromMethodName(), builderType, targetType, "instance");
        PsiMethod mutateMethod = buildInstanceNoArg(psiManager, target, config.toBuilderMethodName(), builderType);

        return List.of(builderMethod, fromMethod, mutateMethod);
    }

    private static String builderTypeFqn(PsiClass target, EditorBuilderConfig config) {
        String qualified = target.getQualifiedName();
        String base = qualified != null ? qualified : target.getName();
        return base + "." + config.builderName();
    }

    private static PsiMethod buildStaticNoArg(PsiManager manager, PsiClass target,
                                              String name, PsiType returnType) {
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .addModifier(PsiModifier.PUBLIC)
            .addModifier(PsiModifier.STATIC)
            .setContainingClass(target);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    private static PsiMethod buildStaticOneArg(PsiManager manager, PsiClass target,
                                               String name, PsiType returnType,
                                               PsiType paramType, String paramName) {
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .addParameter(paramName, paramType)
            .addModifier(PsiModifier.PUBLIC)
            .addModifier(PsiModifier.STATIC)
            .setContainingClass(target);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    private static PsiMethod buildInstanceNoArg(PsiManager manager, PsiClass target,
                                                String name, PsiType returnType) {
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .addModifier(PsiModifier.PUBLIC)
            .setContainingClass(target);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    /**
     * Synthesises the nested {@code Builder} class on the editor side so
     * {@code Target.Builder} references resolve before the first javac round.
     * Every field produces one or more setters per {@link PsiFieldShape};
     * the class also carries a {@code build()} method returning the target.
     */
    static PsiClass synthesizeBuilderClass(PsiClass target, EditorBuilderConfig config) {
        Project project = target.getProject();
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(project);

        LightPsiClassBuilder builder = new LightPsiClassBuilder(target, config.builderName());
        builder.getModifierList().addModifier(PsiModifier.PUBLIC);
        builder.getModifierList().addModifier(PsiModifier.STATIC);
        builder.setContainingClass(target);
        builder.setNavigationElement(target);
        GeneratedMemberMarker.mark(builder);

        // Self-reference type uses createTypeFromText to avoid forcing inner-
        // class resolution while we are mid-synthesis (which would re-enter
        // this augment provider and trip the recursion guard).
        PsiClassType selfType = (PsiClassType) elements.createTypeFromText(
            builderTypeFqn(target, config), target);
        PsiClassType targetType = (PsiClassType) elements.createTypeFromText(
            target.getQualifiedName() != null ? target.getQualifiedName() : target.getName(),
            target);

        // Build every setter first, then attach to the builder in one sweep.
        // Adding methods to LightPsiClassBuilder before they're all built keeps
        // the recursion guard window tight for any type resolution that
        // happens during method construction.
        Set<String> excluded = excludedNames(target);
        List<PsiFieldShape> fields = target.isRecord()
            ? PsiFieldShapeExtractor.fromRecord(target, excluded)
            : PsiFieldShapeExtractor.fromClass(target, excluded);

        List<PsiMethod> setters = new ArrayList<>();
        SetterCtx ctx = new SetterCtx(psiManager, elements, target, builder, selfType, config);
        for (PsiFieldShape field : fields) {
            setters.addAll(settersFor(ctx, field));
        }
        for (PsiMethod setter : setters) builder.addMethod(setter);

        // build()
        LightMethodBuilder build = new LightMethodBuilder(psiManager, "build")
            .setMethodReturnType(targetType)
            .addModifier(PsiModifier.PUBLIC)
            .setContainingClass(builder);
        GeneratedMemberMarker.mark(build);
        build.setNavigationElement(target);
        builder.addMethod(build);

        return builder;
    }

    // ------------------------------------------------------------------
    // Setter dispatch
    // ------------------------------------------------------------------

    /**
     * Mirrors the dispatch in {@code FieldMutators.setters}: picks one or
     * more shape-specific setter methods per field.
     */
    private static List<PsiMethod> settersFor(SetterCtx ctx, PsiFieldShape field) {
        List<PsiMethod> out = new ArrayList<>();
        if (field.isBoolean) {
            out.add(booleanZeroArg(ctx, field, field.name, false));
            out.add(booleanTyped(ctx, field, field.name));
            if (field.negateName != null && !field.negateName.isEmpty()) {
                out.add(booleanZeroArg(ctx, field, field.negateName, true));
                out.add(booleanTyped(ctx, field, field.negateName));
            }
        } else if (field.isOptional) {
            out.add(optionalNullableRaw(ctx, field));
            out.add(optionalWrapped(ctx, field));
            if (field.formattable && field.isOptionalString) {
                out.add(optionalFormattable(ctx, field));
            }
        } else if (field.isArray) {
            out.add(arrayVarargs(ctx, field));
        } else if ((field.isListLike || field.isMap) && field.singularName != null) {
            if (field.isMap) {
                out.add(singularMapReplace(ctx, field));
                out.add(singularMapPut(ctx, field));
            } else {
                out.add(singularCollectionVarargsReplace(ctx, field));
                out.add(singularCollectionIterableReplace(ctx, field));
                out.add(singularCollectionAdd(ctx, field));
            }
            out.add(singularClear(ctx, field));
        } else if (field.isString && field.formattable) {
            out.add(plainSetter(ctx, field));
            out.add(stringFormattable(ctx, field));
        } else {
            out.add(plainSetter(ctx, field));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Plain / boolean / array shapes
    // ------------------------------------------------------------------

    private static PsiMethod plainSetter(SetterCtx ctx, PsiFieldShape field) {
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, field.type);
    }

    private static PsiMethod arrayVarargs(SetterCtx ctx, PsiFieldShape field) {
        // fall back to plain setter if the component type is unknown
        PsiType component = field.arrayComponent != null ? field.arrayComponent : PsiType.NULL;
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, component, true);
    }

    private static PsiMethod booleanZeroArg(SetterCtx ctx, PsiFieldShape field, String methodBase, boolean inverse) {
        return newSetter(ctx, "is" + capitalise(methodBase));
    }

    private static PsiMethod booleanTyped(SetterCtx ctx, PsiFieldShape field, String methodBase) {
        return newSetter(ctx, "is" + capitalise(methodBase))
            .addParameter(methodBase, PsiType.BOOLEAN);
    }

    // ------------------------------------------------------------------
    // Optional shapes
    // ------------------------------------------------------------------

    /** {@code Builder withX(T x)} - nullable-raw overload for an Optional field. */
    private static PsiMethod optionalNullableRaw(SetterCtx ctx, PsiFieldShape field) {
        PsiType inner = field.optionalInner != null
            ? field.optionalInner
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, inner);
    }

    /** {@code Builder withX(Optional<T> x)} - wrapped overload for an Optional field. */
    private static PsiMethod optionalWrapped(SetterCtx ctx, PsiFieldShape field) {
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, field.type);
    }

    // ------------------------------------------------------------------
    // @Formattable shapes
    // ------------------------------------------------------------------

    /** {@code Builder withName(String format, Object... args)} - String @Formattable. */
    private static PsiMethod stringFormattable(SetterCtx ctx, PsiFieldShape field) {
        PsiType stringType = ctx.elements.createTypeFromText("java.lang.String", ctx.target);
        PsiType objectType = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, stringType)
            .addParameter("args", objectType, true);
    }

    /** {@code Builder withDescription(String format, Object... args)} - Optional<String> @Formattable. */
    private static PsiMethod optionalFormattable(SetterCtx ctx, PsiFieldShape field) {
        PsiType stringType = ctx.elements.createTypeFromText("java.lang.String", ctx.target);
        PsiType objectType = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, stringType)
            .addParameter("args", objectType, true);
    }

    // ------------------------------------------------------------------
    // @Singular shapes
    // ------------------------------------------------------------------

    /** {@code Builder withEntries(T... entries)} - replace-collection varargs form. */
    private static PsiMethod singularCollectionVarargsReplace(SetterCtx ctx, PsiFieldShape field) {
        PsiType element = field.collectionElement != null
            ? field.collectionElement
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, element, true);
    }

    /** {@code Builder withEntries(Iterable<T> entries)} - replace-collection iterable form. */
    private static PsiMethod singularCollectionIterableReplace(SetterCtx ctx, PsiFieldShape field) {
        String elementText = field.collectionElement != null
            ? field.collectionElement.getCanonicalText()
            : "java.lang.Object";
        PsiType iterableType = ctx.elements.createTypeFromText(
            "java.lang.Iterable<" + elementText + ">", ctx.target);
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, iterableType);
    }

    /** {@code Builder addEntry(T entry)} - append one element to the existing collection. */
    private static PsiMethod singularCollectionAdd(SetterCtx ctx, PsiFieldShape field) {
        String prefix = ctx.config.methodPrefix().isEmpty() ? "add" : ctx.config.methodPrefix();
        String name = prefix + capitalise(field.singularName);
        PsiType element = field.collectionElement != null
            ? field.collectionElement
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        return newSetter(ctx, name)
            .addParameter(field.singularName, element);
    }

    /** {@code Builder withEntries(Map<K, V> entries)} - replace with fresh LinkedHashMap. */
    private static PsiMethod singularMapReplace(SetterCtx ctx, PsiFieldShape field) {
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(field.name, field.type);
    }

    /** {@code Builder putEntry(K key, V value)} - put a single entry into the existing map. */
    private static PsiMethod singularMapPut(SetterCtx ctx, PsiFieldShape field) {
        String name = "put" + capitalise(field.singularName);
        PsiType keyType = field.mapKey != null
            ? field.mapKey
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        PsiType valueType = field.mapValue != null
            ? field.mapValue
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        return newSetter(ctx, name)
            .addParameter("key", keyType)
            .addParameter("value", valueType);
    }

    /** {@code Builder clearEntries()} - empties the underlying collection or map. */
    private static PsiMethod singularClear(SetterCtx ctx, PsiFieldShape field) {
        String name = "clear" + capitalise(field.name);
        return newSetter(ctx, name);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Shared half-built setter: public, returns the nested Builder self-type,
     * lives on the synthesised builder class, navigates back to the target.
     * Callers chain {@code addParameter} calls then hand the builder back;
     * {@code LightMethodBuilder} doubles as the resulting {@link PsiMethod}.
     */
    private static LightMethodBuilder newSetter(SetterCtx ctx, String name) {
        LightMethodBuilder m = new LightMethodBuilder(ctx.manager, name)
            .setMethodReturnType(ctx.selfType)
            .addModifier(PsiModifier.PUBLIC)
            .setContainingClass(ctx.builder);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(ctx.target);
        return m;
    }

    private static String methodName(SetterCtx ctx, String fieldName, boolean forceBoolean) {
        String prefix = forceBoolean ? "is" : ctx.config.methodPrefix();
        if (prefix.isEmpty()) return fieldName;
        return prefix + capitalise(fieldName);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Set<String> excludedNames(PsiClass target) {
        Set<String> out = new HashSet<>();
        PsiAnnotation annotation = PsiFieldShapeExtractor.classBuilderAnnotation(target);
        if (annotation == null) return out;
        PsiAnnotationMemberValue value = annotation.findAttributeValue("exclude");
        if (value instanceof PsiArrayInitializerMemberValue arr) {
            for (PsiAnnotationMemberValue elem : arr.getInitializers()) {
                if (elem instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
                    out.add(s);
                }
            }
        } else if (value instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
            out.add(s);
        }
        return out;
    }

    /** Immutable per-synthesis context passed to every setter-building helper. */
    private record SetterCtx(PsiManager manager, PsiElementFactory elements, PsiClass target,
                             PsiClass builder, PsiClassType selfType,
                             EditorBuilderConfig config) {
    }

    /**
     * Resolved builder configuration from the editor's PSI view of the
     * annotation. Kept small - the augment provider only needs the names.
     */
    record EditorBuilderConfig(String builderName, String builderMethodName,
                               String fromMethodName, String toBuilderMethodName,
                               String methodPrefix) {
        static EditorBuilderConfig fromAnnotation(PsiAnnotation annotation) {
            String builderName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_BUILDER_NAME, ClassBuilderConstants.DEFAULT_BUILDER_NAME);
            String builderMethodName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_BUILDER_METHOD_NAME, ClassBuilderConstants.DEFAULT_BUILDER_METHOD);
            String fromMethodName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_FROM_METHOD_NAME, ClassBuilderConstants.DEFAULT_FROM_METHOD);
            String toBuilderMethodName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_TO_BUILDER_METHOD_NAME, ClassBuilderConstants.DEFAULT_TO_BUILDER_METHOD);
            String methodPrefix = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_METHOD_PREFIX, ClassBuilderConstants.DEFAULT_METHOD_PREFIX);
            return new EditorBuilderConfig(builderName, builderMethodName, fromMethodName,
                toBuilderMethodName, methodPrefix);
        }
    }

}
