package dev.sbs.classbuilder.editor;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightModifierList;
import com.intellij.psi.impl.light.LightParameter;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * overload.
 *
 * <p>Parameter-level annotations are emitted inline during synthesis through
 * {@link #buildParam}: {@code @PrintFormat} on String format args,
 * {@code @Nullable} on {@code Object... args} varargs, and
 * {@code @Nullable} / {@code @NotNull} on primary setter params driven by the
 * field's companion annotations (including {@code @BuildFlag(nonNull)}). Only
 * no-attribute annotations can ride on a {@code LightModifierList}; attribute-
 * bearing annotations ({@code @XContract}, {@code @Contract}) are delivered at
 * query time by
 * {@link ClassBuilderInferredAnnotationProvider}.
 */
final class GeneratedMemberFactory {

    private static final String PRINT_FORMAT_FQN = "org.intellij.lang.annotations.PrintFormat";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";
    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";

    private GeneratedMemberFactory() {
    }

    static List<PsiMethod> bootstrapMethods(PsiClass target, EditorBuilderConfig config) {
        Project project = target.getProject();
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(project);

        String builderFqn = builderTypeFqn(target, config);
        PsiClassType builderType = (PsiClassType) elements.createTypeFromText(builderFqn, target);
        PsiClassType targetType = elements.createType(target);

        List<PsiMethod> out = new ArrayList<>(3);
        if (config.generateBuilder() && !config.builderMethodName().isEmpty()) {
            out.add(buildStaticNoArg(psiManager, target, config.builderMethodName(), builderType, config.access()));
        }
        if (config.generateFrom() && !config.fromMethodName().isEmpty()) {
            out.add(buildStaticOneArg(psiManager, target, config.fromMethodName(), builderType, targetType, "instance", config.access()));
        }
        if (config.generateMutate() && !config.toBuilderMethodName().isEmpty()) {
            out.add(buildInstanceNoArg(psiManager, target, config.toBuilderMethodName(), builderType, config.access()));
        }
        return out;
    }

    private static String builderTypeFqn(PsiClass target, EditorBuilderConfig config) {
        String qualified = target.getQualifiedName();
        String base = qualified != null ? qualified : target.getName();
        return base + "." + config.builderName();
    }

    private static PsiMethod buildStaticNoArg(PsiManager manager, PsiClass target,
                                              String name, PsiType returnType, String access) {
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .addModifier(PsiModifier.STATIC)
            .setContainingClass(target);
        applyAccess(m, access);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    private static PsiMethod buildStaticOneArg(PsiManager manager, PsiClass target,
                                               String name, PsiType returnType,
                                               PsiType paramType, String paramName, String access) {
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .addParameter(buildParam(target, paramName, paramType, false, NOT_NULL_FQN))
            .addModifier(PsiModifier.STATIC)
            .setContainingClass(target);
        applyAccess(m, access);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    /**
     * Builds a {@link LightParameter} carrying the given annotation FQNs on its
     * modifier list. Platform's {@link LightModifierList#addAnnotation(String)}
     * throws {@link com.intellij.util.IncorrectOperationException}, so this
     * helper hands the parameter a custom modifier list ({@link AnnotatedLightModifierList})
     * that stores pre-built {@link PsiAnnotation}s from
     * {@link PsiElementFactory#createAnnotationFromText}. Only FQN-only
     * (no-attribute) annotations are passed through this path; attribute-
     * bearing annotations ({@code @XContract} / {@code @Contract}) are
     * delivered at query time by
     * {@link ClassBuilderInferredAnnotationProvider}.
     */
    private static LightParameter buildParam(PsiElement context, String name, PsiType type,
                                             boolean varargs, String... annotationFqns) {
        PsiManager manager = context.getManager();
        AnnotatedLightModifierList modifiers = new AnnotatedLightModifierList(manager);
        if (annotationFqns.length > 0) {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(context.getProject());
            for (String fqn : annotationFqns) {
                try {
                    modifiers.add(factory.createAnnotationFromText("@" + fqn, context));
                } catch (Exception ignored) {
                    // Unresolvable FQN in this project's classpath - skip silently
                    // rather than break synthesis.
                }
            }
        }
        return new LightParameter(name, type, context, JavaLanguage.INSTANCE, modifiers, varargs);
    }

    /**
     * {@link LightModifierList} subclass whose {@link #getAnnotations()} and
     * {@link #findAnnotation(String)} surface a caller-populated list. The
     * platform's default implementation returns an empty annotation array and
     * throws on {@code addAnnotation(String)}; this subclass lets us ride
     * pre-built annotations (from {@code createAnnotationFromText}) so
     * IntelliJ inspections that walk {@code getModifierList().getAnnotations()}
     * (printf, nullability, etc.) see them.
     */
    private static final class AnnotatedLightModifierList extends LightModifierList {

        private final java.util.List<PsiAnnotation> annotations = new ArrayList<>(2);

        AnnotatedLightModifierList(PsiManager manager) {
            super(manager);
        }

        void add(PsiAnnotation annotation) {
            annotations.add(annotation);
        }

        @Override
        public @NotNull PsiAnnotation[] getAnnotations() {
            return annotations.toArray(PsiAnnotation.EMPTY_ARRAY);
        }

        @Override
        public @Nullable PsiAnnotation findAnnotation(@NotNull String qualifiedName) {
            for (PsiAnnotation a : annotations) {
                if (qualifiedName.equals(a.getQualifiedName())) return a;
            }
            return null;
        }

        @Override
        public boolean hasAnnotation(@NotNull String qualifiedName) {
            return findAnnotation(qualifiedName) != null;
        }

    }

    private static PsiMethod buildInstanceNoArg(PsiManager manager, PsiClass target,
                                                String name, PsiType returnType, String access) {
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .setContainingClass(target);
        applyAccess(m, access);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(target);
        return m;
    }

    /**
     * Adds the PUBLIC/PROTECTED/PRIVATE modifier to a light-method builder when
     * the access level requires one; PACKAGE-private is represented by the
     * absence of any of those three modifiers.
     */
    private static void applyAccess(LightMethodBuilder m, String access) {
        if (!access.isEmpty()) m.addModifier(access);
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
        if (!config.access().isEmpty()) builder.getModifierList().addModifier(config.access());
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

        // build() - always public (access attribute governs the enclosing
        // builder class + bootstraps, not the terminal build method).
        LightMethodBuilder build = new LightMethodBuilder(psiManager, config.buildMethodName())
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
            .addParameter(buildParam(ctx.target, field.name, field.type, false, primaryNullability(field)));
    }

    private static PsiMethod arrayVarargs(SetterCtx ctx, PsiFieldShape field) {
        // fall back to plain setter if the component type is unknown
        PsiType component = field.arrayComponent != null ? field.arrayComponent : PsiType.NULL;
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(buildParam(ctx.target, field.name, component, true, primaryNullability(field)));
    }

    private static PsiMethod booleanZeroArg(SetterCtx ctx, PsiFieldShape field, String methodBase, boolean inverse) {
        return newSetter(ctx, "is" + capitalise(methodBase));
    }

    private static PsiMethod booleanTyped(SetterCtx ctx, PsiFieldShape field, String methodBase) {
        return newSetter(ctx, "is" + capitalise(methodBase))
            .addParameter(buildParam(ctx.target, methodBase, PsiType.BOOLEAN, false));
    }

    // ------------------------------------------------------------------
    // Optional shapes
    // ------------------------------------------------------------------

    /** {@code Builder withX(T x)} - nullable-raw overload for an Optional field. */
    private static PsiMethod optionalNullableRaw(SetterCtx ctx, PsiFieldShape field) {
        PsiType inner = field.optionalInner != null
            ? field.optionalInner
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        // Raw overload wraps via Optional.ofNullable - null is explicitly allowed
        // regardless of whether the field carries @BuildFlag(nonNull).
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(buildParam(ctx.target, field.name, inner, false, NULLABLE_FQN));
    }

    /** {@code Builder withX(Optional<T> x)} - wrapped overload for an Optional field. */
    private static PsiMethod optionalWrapped(SetterCtx ctx, PsiFieldShape field) {
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(buildParam(ctx.target, field.name, field.type, false, NOT_NULL_FQN));
    }

    // ------------------------------------------------------------------
    // @Formattable shapes
    // ------------------------------------------------------------------

    /** {@code Builder withName(@PrintFormat String format, Object... args)} - String @Formattable. */
    private static PsiMethod stringFormattable(SetterCtx ctx, PsiFieldShape field) {
        PsiType stringType = ctx.elements.createTypeFromText("java.lang.String", ctx.target);
        PsiType objectType = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        // Mirrors library FieldMutators.stringFormattable: @PrintFormat on the format
        // arg, nullable when the field or @Formattable.nullable is set, otherwise
        // @NotNull; varargs always @Nullable.
        boolean formatNullable = (field.formattableNullable || field.nullable) && !field.nonNullByBuildFlag;
        String formatNullability = formatNullable ? NULLABLE_FQN : NOT_NULL_FQN;
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(buildParam(ctx.target, field.name, stringType, false, PRINT_FORMAT_FQN, formatNullability))
            .addParameter(buildParam(ctx.target, "args", objectType, true, NULLABLE_FQN));
    }

    /** {@code Builder withDescription(@PrintFormat @Nullable String format, Object... args)} - Optional<String> @Formattable. */
    private static PsiMethod optionalFormattable(SetterCtx ctx, PsiFieldShape field) {
        PsiType stringType = ctx.elements.createTypeFromText("java.lang.String", ctx.target);
        PsiType objectType = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        // Mirrors library FieldMutators.optionalFormattable: format is always
        // @Nullable because the runtime routes through Strings.formatNullable.
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(buildParam(ctx.target, field.name, stringType, false, PRINT_FORMAT_FQN, NULLABLE_FQN))
            .addParameter(buildParam(ctx.target, "args", objectType, true, NULLABLE_FQN));
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
            .addParameter(buildParam(ctx.target, field.name, element, true, primaryNullability(field)));
    }

    /** {@code Builder withEntries(Iterable<T> entries)} - replace-collection iterable form. */
    private static PsiMethod singularCollectionIterableReplace(SetterCtx ctx, PsiFieldShape field) {
        String elementText = field.collectionElement != null
            ? field.collectionElement.getCanonicalText()
            : "java.lang.Object";
        PsiType iterableType = ctx.elements.createTypeFromText(
            "java.lang.Iterable<" + elementText + ">", ctx.target);
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(buildParam(ctx.target, field.name, iterableType, false, primaryNullability(field)));
    }

    /** {@code Builder addEntry(T entry)} - append one element to the existing collection. */
    private static PsiMethod singularCollectionAdd(SetterCtx ctx, PsiFieldShape field) {
        String prefix = ctx.config.methodPrefix().isEmpty() ? "add" : ctx.config.methodPrefix();
        String name = prefix + capitalise(field.singularName);
        PsiType element = field.collectionElement != null
            ? field.collectionElement
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        return newSetter(ctx, name)
            .addParameter(buildParam(ctx.target, field.singularName, element, false, primaryNullability(field)));
    }

    /** {@code Builder withEntries(Map<K, V> entries)} - replace with fresh LinkedHashMap. */
    private static PsiMethod singularMapReplace(SetterCtx ctx, PsiFieldShape field) {
        return newSetter(ctx, methodName(ctx, field.name, false))
            .addParameter(buildParam(ctx.target, field.name, field.type, false, primaryNullability(field)));
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
            .addParameter(buildParam(ctx.target, "key", keyType, false, primaryNullability(field)))
            .addParameter(buildParam(ctx.target, "value", valueType, false, primaryNullability(field)));
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

    /**
     * Returns the nullability FQN the primary setter parameter should carry for
     * a non-formattable field. Mirrors the library-side decision: {@code @NotNull}
     * when the field has {@code @BuildFlag(nonNull=true)}, {@code @Nullable} when
     * the field carries {@code @Nullable} directly, otherwise no annotation - the
     * default varargs-friendly shape. Returns an empty array when no annotation
     * applies, which {@link #buildParam} accepts as a no-op.
     */
    private static String[] primaryNullability(PsiFieldShape field) {
        if (field.nonNullByBuildFlag) return new String[] {NOT_NULL_FQN};
        if (field.nullable) return new String[] {NULLABLE_FQN};
        return new String[0];
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
     * annotation. Carries every attribute the synthesiser needs to match the
     * AST-mutation output, so the two pipelines stay aligned.
     *
     * <p>{@code access} holds the PSI modifier keyword ({@code "public"},
     * {@code "protected"}, {@code "private"}, or {@code ""} for package-
     * private) so call sites can pass it straight into
     * {@code LightMethodBuilder.addModifier} without a second translation.
     */
    record EditorBuilderConfig(String builderName, String builderMethodName,
                               String buildMethodName, String fromMethodName,
                               String toBuilderMethodName, String methodPrefix,
                               String access, boolean generateBuilder,
                               boolean generateFrom, boolean generateMutate) {
        static EditorBuilderConfig fromAnnotation(PsiAnnotation annotation) {
            String builderName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_BUILDER_NAME, ClassBuilderConstants.DEFAULT_BUILDER_NAME);
            String builderMethodName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_BUILDER_METHOD_NAME, ClassBuilderConstants.DEFAULT_BUILDER_METHOD);
            String buildMethodName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_BUILD_METHOD_NAME, ClassBuilderConstants.DEFAULT_BUILD_METHOD);
            String fromMethodName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_FROM_METHOD_NAME, ClassBuilderConstants.DEFAULT_FROM_METHOD);
            String toBuilderMethodName = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_TO_BUILDER_METHOD_NAME, ClassBuilderConstants.DEFAULT_TO_BUILDER_METHOD);
            String methodPrefix = ClassBuilderConstants.stringAttr(annotation,
                ClassBuilderConstants.ATTR_METHOD_PREFIX, ClassBuilderConstants.DEFAULT_METHOD_PREFIX);
            String access = ClassBuilderConstants.accessKeyword(annotation);
            boolean generateBuilder = ClassBuilderConstants.booleanAttr(annotation,
                ClassBuilderConstants.ATTR_GENERATE_BUILDER, true);
            boolean generateFrom = ClassBuilderConstants.booleanAttr(annotation,
                ClassBuilderConstants.ATTR_GENERATE_FROM, true);
            boolean generateMutate = ClassBuilderConstants.booleanAttr(annotation,
                ClassBuilderConstants.ATTR_GENERATE_MUTATE, true);
            return new EditorBuilderConfig(builderName, builderMethodName, buildMethodName,
                fromMethodName, toBuilderMethodName, methodPrefix, access,
                generateBuilder, generateFrom, generateMutate);
        }
    }

}
