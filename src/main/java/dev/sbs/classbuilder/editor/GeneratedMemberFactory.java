package dev.sbs.classbuilder.editor;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightPsiClassBuilder;
import dev.sbs.classbuilder.inspect.ClassBuilderConstants;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Factory for the three bootstrap {@link PsiMethod}s that the augment
 * provider adds to a {@code @ClassBuilder}-annotated class:
 * {@code static Builder builder()}, {@code static Builder from(T)},
 * instance {@code Builder mutate()}.
 *
 * <p>The return type is {@code <TargetName>.Builder} - a textual reference
 * that resolves against the nested {@code Builder} class which the APT
 * pipeline injects at compile time. Before the first javac round the
 * reference may be unresolved; that is acceptable v1 behaviour and does
 * not block autocompletion of the bootstrap methods themselves.
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
     * The class carries one setter per discoverable target field plus a
     * {@code build()} method returning the target type.
     *
     * <p>Setter shape coverage is intentionally narrow at the editor layer:
     * one plain {@code withX(T x)} per field. This is enough for IDE-time
     * autocompletion and resolution; the rich shape matrix (boolean pair,
     * Optional dual, {@code @Singular} family, {@code @Formattable} overload)
     * lives on the APT side and lights up after the first build through the
     * compiled class file.
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

        // Per-field setters - shape coverage is intentionally narrow at the
        // editor layer (one plain setter per field). The rich shape matrix
        // lives on the APT side and lights up after the first build.
        Set<String> excluded = excludedNames(target);
        List<PsiFieldShape> fields = target.isRecord()
            ? PsiFieldShapeExtractor.fromRecord(target, excluded)
            : PsiFieldShapeExtractor.fromClass(target, excluded);
        for (PsiFieldShape field : fields) {
            builder.addMethod(plainSetter(psiManager, builder, selfType, field, config.methodPrefix()));
        }

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

    private static PsiMethod plainSetter(PsiManager manager, PsiClass containing,
                                         PsiType returnType, PsiFieldShape field,
                                         String methodPrefix) {
        String name = methodPrefix.isEmpty()
            ? field.name
            : methodPrefix + Character.toUpperCase(field.name.charAt(0)) + field.name.substring(1);
        LightMethodBuilder m = new LightMethodBuilder(manager, name)
            .setMethodReturnType(returnType)
            .addParameter(field.name, field.type)
            .addModifier(PsiModifier.PUBLIC)
            .setContainingClass(containing);
        GeneratedMemberMarker.mark(m);
        m.setNavigationElement(containing);
        return m;
    }

    private static Set<String> excludedNames(PsiClass target) {
        Set<String> out = new HashSet<>();
        com.intellij.psi.PsiAnnotation annotation = PsiFieldShapeExtractor.classBuilderAnnotation(target);
        if (annotation == null) return out;
        com.intellij.psi.PsiAnnotationMemberValue value = annotation.findAttributeValue("exclude");
        if (value instanceof com.intellij.psi.PsiArrayInitializerMemberValue arr) {
            for (com.intellij.psi.PsiAnnotationMemberValue elem : arr.getInitializers()) {
                if (elem instanceof com.intellij.psi.PsiLiteralExpression lit
                    && lit.getValue() instanceof String s) {
                    out.add(s);
                }
            }
        } else if (value instanceof com.intellij.psi.PsiLiteralExpression lit
            && lit.getValue() instanceof String s) {
            out.add(s);
        }
        return out;
    }

    /**
     * Resolved builder configuration from the editor's PSI view of the
     * annotation. Kept small - the augment provider only needs the names.
     */
    record EditorBuilderConfig(String builderName, String builderMethodName,
                               String fromMethodName, String toBuilderMethodName,
                               String methodPrefix) {
        static EditorBuilderConfig fromAnnotation(com.intellij.psi.PsiAnnotation annotation) {
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
