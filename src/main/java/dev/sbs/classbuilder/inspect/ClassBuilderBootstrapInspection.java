package dev.sbs.classbuilder.inspect;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;

/**
 * Flags {@code @ClassBuilder}-annotated classes that are missing one or more
 * of the three bootstrap methods the annotation processor expects:
 * {@code builder()}, {@code from(T)}, and {@code mutate()} (names configurable
 * via the annotation attributes). Offers {@link AddBootstrapMethodsFix} to
 * insert the missing methods.
 */
public class ClassBuilderBootstrapInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass psiClass) {
                super.visitClass(psiClass);
                PsiAnnotation annotation = psiClass.getAnnotation(ClassBuilderConstants.ANNOTATION_FQN);
                if (annotation == null) return;
                if (psiClass.isInterface() || psiClass.isEnum() || psiClass.isAnnotationType()) return;

                String builderName = ClassBuilderConstants.stringAttr(annotation,
                    ClassBuilderConstants.ATTR_BUILDER_NAME, ClassBuilderConstants.DEFAULT_BUILDER_NAME);
                String builderMethod = ClassBuilderConstants.stringAttr(annotation,
                    ClassBuilderConstants.ATTR_BUILDER_METHOD_NAME, ClassBuilderConstants.DEFAULT_BUILDER_METHOD);
                String fromMethod = ClassBuilderConstants.stringAttr(annotation,
                    ClassBuilderConstants.ATTR_FROM_METHOD_NAME, ClassBuilderConstants.DEFAULT_FROM_METHOD);
                String toBuilderMethod = ClassBuilderConstants.stringAttr(annotation,
                    ClassBuilderConstants.ATTR_TO_BUILDER_METHOD_NAME, ClassBuilderConstants.DEFAULT_TO_BUILDER_METHOD);

                boolean generateBuilder = ClassBuilderConstants.booleanAttr(annotation, ClassBuilderConstants.ATTR_GENERATE_BUILDER, true);
                boolean generateFrom = ClassBuilderConstants.booleanAttr(annotation, ClassBuilderConstants.ATTR_GENERATE_FROM, true);
                boolean generateMutate = ClassBuilderConstants.booleanAttr(annotation, ClassBuilderConstants.ATTR_GENERATE_MUTATE, true);

                String className = psiClass.getName();
                if (className == null) return;

                boolean hasBuilder = !generateBuilder || findStaticNoArg(psiClass, builderMethod);
                boolean hasFrom = !generateFrom || fromMethod.isEmpty() || findStaticOneArg(psiClass, fromMethod, className);
                boolean hasMutate = !generateMutate || toBuilderMethod.isEmpty() || findInstanceNoArg(psiClass, toBuilderMethod);

                if (hasBuilder && hasFrom && hasMutate) return;

                holder.registerProblem(
                    annotation,
                    "@ClassBuilder bootstrap methods are not materialized - compilation will fail without them",
                    new AddBootstrapMethodsFix(
                        builderName, builderMethod, fromMethod, toBuilderMethod,
                        generateBuilder && !hasBuilder,
                        generateFrom && !hasFrom,
                        generateMutate && !hasMutate
                    )
                );
            }
        };
    }

    private static boolean findStaticNoArg(@NotNull PsiClass psiClass, @NotNull String name) {
        for (PsiMethod m : psiClass.findMethodsByName(name, false)) {
            if (!m.hasModifierProperty("static")) continue;
            if (m.getParameterList().getParametersCount() == 0) return true;
        }
        return false;
    }

    private static boolean findStaticOneArg(@NotNull PsiClass psiClass, @NotNull String name, @NotNull String typeSimpleName) {
        for (PsiMethod m : psiClass.findMethodsByName(name, false)) {
            if (!m.hasModifierProperty("static")) continue;
            if (m.getParameterList().getParametersCount() != 1) continue;
            PsiParameter p = m.getParameterList().getParameters()[0];
            PsiType pt = p.getType();
            if (pt.getPresentableText().equals(typeSimpleName)) return true;
        }
        return false;
    }

    private static boolean findInstanceNoArg(@NotNull PsiClass psiClass, @NotNull String name) {
        for (PsiMethod m : psiClass.findMethodsByName(name, false)) {
            if (m.hasModifierProperty("static")) continue;
            if (m.getParameterList().getParametersCount() == 0) return true;
        }
        return false;
    }

}
