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
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.TypeAnnotationProvider;
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
 * plus optional {@code @Formattable} overload, {@code @Collector}
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

    static List<PsiMethod> bootstrapMethods(PsiClass target, EditorBuilderConfig config,
                                            PsiClass builderClass) {
        Project project = target.getProject();
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(project);

        // Use createType(builderClass) instead of createTypeFromText(FQN):
        // textual resolution requires the IDE's symbol-lookup chain to find
        // the inner Builder class via our augment provider every time, and
        // the access-check pass in cross-package highlighting can't always
        // re-resolve cleanly through that chain - it then flags the bootstrap
        // call with "Cannot access ...Builder". Threading the actual PsiClass
        // instance bypasses resolution entirely.
        PsiClassType builderType = elements.createType(builderClass);
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
            .addModifier(PsiModifier.STATIC)
            .setContainingClass(target);
        m.addParameter(buildParam(m, paramName, paramType, false, NOT_NULL_FQN));
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
     *
     * <p>{@code declarationScope} must be the {@link com.intellij.psi.PsiMethod}
     * the parameter belongs to (matches what
     * {@code LightMethodBuilder.addParameter(name, type)} does internally).
     * Passing the containing class instead causes IntelliJ 233+ to silently
     * filter the whole synthetic method out during PSI enumeration.
     */
    private static LightParameter buildParam(PsiMethod declarationScope, String name, PsiType type,
                                             boolean varargs, String... annotationFqns) {
        PsiManager manager = declarationScope.getManager();
        AnnotatedLightModifierList modifiers = new AnnotatedLightModifierList(manager, JavaLanguage.INSTANCE);
        List<PsiAnnotation> typeUseAnnotations = new ArrayList<>(annotationFqns.length);
        if (annotationFqns.length > 0) {
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(declarationScope.getProject());
            for (String fqn : annotationFqns) {
                try {
                    PsiAnnotation annotation = factory.createAnnotationFromText("@" + fqn, declarationScope);
                    // Nullability annotations (@NotNull / @Nullable) also ride on
                    // the type so JavaDocumentationProvider's brief hover shows
                    // them (it renders each param via generateType(..., annotated=true),
                    // which walks the TYPE's annotations, not the modifier list).
                    // Declaration-only annotations like @PrintFormat stay only on
                    // the modifier list - attaching them to the type too makes the
                    // hover show them twice.
                    if (NOT_NULL_FQN.equals(fqn) || NULLABLE_FQN.equals(fqn)) {
                        typeUseAnnotations.add(annotation);
                    }
                    modifiers.add(fqn, annotation);
                } catch (Exception ignored) {
                    // Unresolvable FQN in this project's classpath - skip silently
                    // rather than break synthesis.
                }
            }
        }

        // Varargs params need PsiEllipsisType wrapping the component type, not
        // the raw component itself. Platform's LightMethodBuilder.addParameter
        // (name, type, isVarArgs) does this wrap internally; our direct
        // LightParameter construction must do it by hand. Without the wrap the
        // parameter's getType() returns Object, so calls with more than one
        // vararg argument resolve to a different overload (or fail).
        PsiType shapedType = varargs && !(type instanceof PsiEllipsisType)
            ? new PsiEllipsisType(type)
            : type;

        PsiType effectiveType = typeUseAnnotations.isEmpty()
            ? shapedType
            : shapedType.annotate(TypeAnnotationProvider.Static.create(
                typeUseAnnotations.toArray(PsiAnnotation.EMPTY_ARRAY)));

        return new LightParameter(name, effectiveType, declarationScope, JavaLanguage.INSTANCE, modifiers, varargs);
    }

    /**
     * {@link LightModifierList} subclass whose {@link #getAnnotations()} and
     * {@link #findAnnotation(String)} surface a caller-populated list. The
     * platform's default implementation returns an empty annotation array and
     * throws on {@code addAnnotation(String)}; this subclass lets us ride
     * pre-built annotations (from {@code createAnnotationFromText}) so
     * IntelliJ inspections that walk {@code getModifierList().getAnnotations()}
     * (printf, nullability, etc.) see them.
     *
     * <p>Mirrors Lombok's {@code LombokLightModifierList}: keyed map keeps
     * lookups O(1), Language is propagated to the platform base so the
     * modifier list participates correctly in language-aware checks, and
     * {@link #addAnnotation(String)} actually creates and stores the
     * annotation rather than throwing {@code IncorrectOperationException}.
     */
    private static final class AnnotatedLightModifierList extends LightModifierList {

        private final java.util.Map<String, PsiAnnotation> annotations = new java.util.LinkedHashMap<>(2);

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
     * Synthesises the empty nested {@code Builder} shell so
     * {@code Target.Builder} references resolve before the first javac round.
     * Methods are produced lazily by {@link #synthesizeBuilderMethods} when
     * the augment provider is re-entered for the synth class.
     *
     * <p>This pattern mirrors Lombok's {@code LombokLightClassBuilder}: in
     * IntelliJ 2023.3+, the IDE consults
     * {@link com.intellij.psi.augment.PsiAugmentProvider#collectAugments}
     * for the inner class's members rather than reading the
     * {@code LightPsiClassBuilder.myMethods} field that {@code addMethod}
     * populates. Pre-attaching methods to that field leaves them invisible
     * to completion / structure-view / inferred annotations. Routing all
     * paths through the augment provider's re-entry is the only way to
     * keep things in sync.
     */
    static PsiClass synthesizeBuilderClass(PsiClass target, EditorBuilderConfig config) {
        GeneratedBuilderClass builder = new GeneratedBuilderClass(target, config.builderName());
        if (!config.access().isEmpty()) builder.getModifierList().addModifier(config.access());
        builder.getModifierList().addModifier(PsiModifier.STATIC);
        builder.setContainingClass(target);
        builder.setNavigationElement(target);
        GeneratedMemberMarker.mark(builder);
        return builder;
    }

    /**
     * Builds the setters and {@code build()} that live on the synth Builder
     * class. Called by
     * {@link ClassBuilderAugmentProvider#getAugments} when the IDE asks
     * "what are the augmented methods of {@code Target.Builder}?".
     */
    static List<PsiMethod> synthesizeBuilderMethods(PsiClass target, EditorBuilderConfig config,
                                                    PsiClass builder) {
        Project project = target.getProject();
        PsiManager psiManager = PsiManager.getInstance(project);
        PsiElementFactory elements = JavaPsiFacade.getElementFactory(project);

        // Use createType(builder) rather than createTypeFromText(FQN) so the
        // self-type resolves directly to our synth class instance, bypassing
        // the inner-class lookup chain that fails the access-check pass during
        // cross-package highlighting.
        PsiClassType selfType = elements.createType(builder);
        PsiClassType targetType = elements.createType(target);

        Set<String> excluded = excludedNames(target);
        List<PsiFieldShape> fields = target.isRecord()
            ? PsiFieldShapeExtractor.fromRecord(target, excluded)
            : PsiFieldShapeExtractor.fromClass(target, excluded);

        SetterCtx ctx = new SetterCtx(psiManager, elements, target, builder, selfType, config);
        List<PsiMethod> methods = new ArrayList<>();
        for (PsiFieldShape field : fields) {
            methods.addAll(settersFor(ctx, field));
        }

        // build() - always public (access attribute governs the enclosing
        // builder class + bootstraps, not the terminal build method).
        LightMethodBuilder build = new LightMethodBuilder(psiManager, config.buildMethodName())
            .setMethodReturnType(targetType)
            .addModifier(PsiModifier.PUBLIC)
            .setContainingClass(builder);
        GeneratedMemberMarker.mark(build);
        build.setNavigationElement(target);
        methods.add(build);

        return methods;
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
        } else if ((field.isListLike || field.isMap) && field.collector) {
            if (field.isMap) {
                out.add(singularMapReplace(ctx, field));
                if (field.singular) out.add(singularMapPut(ctx, field));
                if (field.compute) out.add(singularMapPutIfAbsent(ctx, field));
            } else {
                out.add(singularCollectionVarargsReplace(ctx, field));
                out.add(singularCollectionIterableReplace(ctx, field));
                if (field.singular) out.add(singularCollectionAdd(ctx, field));
            }
            if (field.clearable) out.add(singularClear(ctx, field));
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
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        m.addParameter(buildParam(m, field.name, field.type, false, primaryNullability(field)));
        return m;
    }

    private static PsiMethod arrayVarargs(SetterCtx ctx, PsiFieldShape field) {
        // fall back to plain setter if the component type is unknown
        PsiType component = field.arrayComponent != null ? field.arrayComponent : PsiType.NULL;
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        m.addParameter(buildParam(m, field.name, component, true, primaryNullability(field)));
        return m;
    }

    private static PsiMethod booleanZeroArg(SetterCtx ctx, PsiFieldShape field, String methodBase, boolean inverse) {
        return newSetter(ctx, field, "is" + capitalise(methodBase));
    }

    private static PsiMethod booleanTyped(SetterCtx ctx, PsiFieldShape field, String methodBase) {
        LightMethodBuilder m = newSetter(ctx, field, "is" + capitalise(methodBase));
        m.addParameter(buildParam(m, methodBase, PsiType.BOOLEAN, false));
        return m;
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
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        m.addParameter(buildParam(m, field.name, inner, false, NULLABLE_FQN));
        return m;
    }

    /** {@code Builder withX(Optional<T> x)} - wrapped overload for an Optional field. */
    private static PsiMethod optionalWrapped(SetterCtx ctx, PsiFieldShape field) {
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        m.addParameter(buildParam(m, field.name, field.type, false, NOT_NULL_FQN));
        return m;
    }

    // ------------------------------------------------------------------
    // @Formattable shapes
    // ------------------------------------------------------------------

    /** {@code Builder withName(@PrintFormat String format, Object... args)} - String @Formattable. */
    private static PsiMethod stringFormattable(SetterCtx ctx, PsiFieldShape field) {
        PsiType stringType = ctx.elements.createTypeFromText("java.lang.String", ctx.target);
        PsiType objectType = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        // @PrintFormat on the format arg always. Nullability mirrors the
        // field: @NotNull when @BuildFlag(nonNull) or field-level @NotNull,
        // @Nullable when field-level @Nullable, otherwise neither (neutral
        // default - don't impose a nullability the user didn't ask for).
        // Varargs always @Nullable.
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        String[] formatAnnotations = prepend(PRINT_FORMAT_FQN, primaryNullability(field));
        m.addParameter(buildParam(m, field.name, stringType, false, formatAnnotations));
        m.addParameter(buildParam(m, "args", objectType, true, NULLABLE_FQN));
        return m;
    }

    /** {@code Builder withDescription(@PrintFormat @Nullable String format, Object... args)} - Optional<String> @Formattable. */
    private static PsiMethod optionalFormattable(SetterCtx ctx, PsiFieldShape field) {
        PsiType stringType = ctx.elements.createTypeFromText("java.lang.String", ctx.target);
        PsiType objectType = ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        // Mirrors library FieldMutators.optionalFormattable: format is always
        // @Nullable because the runtime routes through Strings.formatNullable.
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        m.addParameter(buildParam(m, field.name, stringType, false, PRINT_FORMAT_FQN, NULLABLE_FQN));
        m.addParameter(buildParam(m, "args", objectType, true, NULLABLE_FQN));
        return m;
    }

    // ------------------------------------------------------------------
    // @Collector shapes
    // ------------------------------------------------------------------

    /** {@code Builder withEntries(T... entries)} - replace-collection varargs form. */
    private static PsiMethod singularCollectionVarargsReplace(SetterCtx ctx, PsiFieldShape field) {
        PsiType element = field.collectionElement != null
            ? field.collectionElement
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        m.addParameter(buildParam(m, field.name, element, true, primaryNullability(field)));
        return m;
    }

    /** {@code Builder withEntries(Iterable<T> entries)} - replace-collection iterable form. */
    private static PsiMethod singularCollectionIterableReplace(SetterCtx ctx, PsiFieldShape field) {
        String elementText = field.collectionElement != null
            ? field.collectionElement.getCanonicalText()
            : "java.lang.Object";
        PsiType iterableType = ctx.elements.createTypeFromText(
            "java.lang.Iterable<" + elementText + ">", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        m.addParameter(buildParam(m, field.name, iterableType, false, primaryNullability(field)));
        return m;
    }

    /** {@code Builder addEntry(T entry)} - append one element to the existing collection. */
    private static PsiMethod singularCollectionAdd(SetterCtx ctx, PsiFieldShape field) {
        String prefix = ctx.config.methodPrefix().isEmpty() ? "add" : ctx.config.methodPrefix();
        String name = prefix + capitalise(field.singularName);
        PsiType element = field.collectionElement != null
            ? field.collectionElement
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, name);
        m.addParameter(buildParam(m, field.singularName, element, false, primaryNullability(field)));
        return m;
    }

    /** {@code Builder withEntries(Map<K, V> entries)} - replace with fresh LinkedHashMap. */
    private static PsiMethod singularMapReplace(SetterCtx ctx, PsiFieldShape field) {
        LightMethodBuilder m = newSetter(ctx, field, methodName(ctx, field.name, false));
        m.addParameter(buildParam(m, field.name, field.type, false, primaryNullability(field)));
        return m;
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
        LightMethodBuilder m = newSetter(ctx, field, name);
        m.addParameter(buildParam(m, "key", keyType, false, primaryNullability(field)));
        m.addParameter(buildParam(m, "value", valueType, false, primaryNullability(field)));
        return m;
    }

    /**
     * {@code Builder putEntryIfAbsent(K key, Supplier<V> valueSupplier)} -
     * puts only when the map doesn't already contain the key, calling the
     * supplier lazily for the value. Gated on {@code @Collector(compute = true)}.
     */
    private static PsiMethod singularMapPutIfAbsent(SetterCtx ctx, PsiFieldShape field) {
        String name = "put" + capitalise(field.singularName) + "IfAbsent";
        PsiType keyType = field.mapKey != null
            ? field.mapKey
            : ctx.elements.createTypeFromText("java.lang.Object", ctx.target);
        String valueText = field.mapValue != null
            ? field.mapValue.getCanonicalText()
            : "java.lang.Object";
        PsiType supplierType = ctx.elements.createTypeFromText(
            "java.util.function.Supplier<" + valueText + ">", ctx.target);
        LightMethodBuilder m = newSetter(ctx, field, name);
        m.addParameter(buildParam(m, "key", keyType, false, primaryNullability(field)));
        m.addParameter(buildParam(m, "valueSupplier", supplierType, false, NOT_NULL_FQN));
        return m;
    }

    /** {@code Builder clearEntries()} - empties the underlying collection or map. */
    private static PsiMethod singularClear(SetterCtx ctx, PsiFieldShape field) {
        String name = "clear" + capitalise(field.name);
        return newSetter(ctx, field, name);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Shared half-built setter: public, returns the nested Builder self-type,
     * lives on the synthesised builder class, navigates to the backing field
     * so Ctrl-click jumps to the right place, and exposes the field's Javadoc
     * as the setter's Javadoc so Ctrl-Q / brief-hover show the field doc on
     * the setter call. Callers chain {@code addParameter} calls then hand
     * the builder back; {@link GeneratedSetterMethod} doubles as the
     * resulting {@link PsiMethod}.
     */
    private static GeneratedSetterMethod newSetter(SetterCtx ctx, PsiFieldShape field, String name) {
        GeneratedSetterMethod m = (GeneratedSetterMethod) new GeneratedSetterMethod(ctx.manager, name)
            .setMethodReturnType(ctx.selfType)
            .addModifier(PsiModifier.PUBLIC)
            .setContainingClass(ctx.builder);
        m.withDocSource(field != null ? field.docSource : null);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(field != null && field.docSource != null ? field.docSource : ctx.target);
        return m;
    }

    /**
     * Returns the nullability FQN the primary setter parameter should carry.
     * Precedence:
     * <ol>
     *   <li>{@code @BuildFlag(nonNull=true)} - runtime-enforced, overrides any
     *       source-level annotation.</li>
     *   <li>Field-level {@code @NotNull} (any recognised variant) - propagates
     *       unchanged to the setter parameter.</li>
     *   <li>Field-level {@code @Nullable} (any recognised variant) - same.</li>
     *   <li>No annotation - default varargs-friendly shape.</li>
     * </ol>
     * Returns an empty array when no annotation applies, which
     * {@link #buildParam} accepts as a no-op.
     */
    private static String[] primaryNullability(PsiFieldShape field) {
        if (field.nonNullByBuildFlag) return new String[] {NOT_NULL_FQN};
        if (field.notNull) return new String[] {NOT_NULL_FQN};
        if (field.nullable) return new String[] {NULLABLE_FQN};
        return new String[0];
    }

    /** Returns an array that prepends {@code head} to {@code tail}. */
    private static String[] prepend(String head, String[] tail) {
        String[] out = new String[tail.length + 1];
        out[0] = head;
        System.arraycopy(tail, 0, out, 1, tail.length);
        return out;
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
