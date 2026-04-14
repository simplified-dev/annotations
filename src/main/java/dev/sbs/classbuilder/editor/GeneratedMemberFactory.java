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
import com.intellij.psi.search.GlobalSearchScope;
import dev.sbs.classbuilder.inspect.ClassBuilderConstants;

import java.util.List;

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

        PsiMethod builderMethod = buildStaticNoArg(psiManager, target, config.builderMethodName, builderType);
        PsiMethod fromMethod = buildStaticOneArg(psiManager, target, config.fromMethodName, builderType, targetType, "instance");
        PsiMethod mutateMethod = buildInstanceNoArg(psiManager, target, config.toBuilderMethodName, builderType);

        return List.of(builderMethod, fromMethod, mutateMethod);
    }

    private static String builderTypeFqn(PsiClass target, EditorBuilderConfig config) {
        String qualified = target.getQualifiedName();
        String base = qualified != null ? qualified : target.getName();
        return base + "." + config.builderName;
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
