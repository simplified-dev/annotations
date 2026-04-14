package dev.sbs.classbuilder.inspect;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.annotations.NotNull;

/**
 * Inserts the missing bootstrap methods onto a {@code @ClassBuilder}-annotated
 * class. Emits {@code @XContract} on each inserted method so IntelliJ data-flow
 * analysis understands their return shapes.
 */
final class AddBootstrapMethodsFix implements LocalQuickFix {

    private final @NotNull String builderName;
    private final @NotNull String builderMethodName;
    private final @NotNull String fromMethodName;
    private final @NotNull String toBuilderMethodName;
    private final boolean addBuilder;
    private final boolean addFrom;
    private final boolean addMutate;

    AddBootstrapMethodsFix(@NotNull String builderName, @NotNull String builderMethodName,
                           @NotNull String fromMethodName, @NotNull String toBuilderMethodName,
                           boolean addBuilder, boolean addFrom, boolean addMutate) {
        this.builderName = builderName;
        this.builderMethodName = builderMethodName;
        this.fromMethodName = fromMethodName;
        this.toBuilderMethodName = toBuilderMethodName;
        this.addBuilder = addBuilder;
        this.addFrom = addFrom;
        this.addMutate = addMutate;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Add missing @ClassBuilder bootstrap methods";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        PsiAnnotation annotation = element instanceof PsiAnnotation a ? a : null;
        if (annotation == null) return;
        PsiElement anchor = annotation.getParent();
        while (anchor != null && !(anchor instanceof PsiClass)) anchor = anchor.getParent();
        if (!(anchor instanceof PsiClass psiClass)) return;
        String targetName = psiClass.getName();
        if (targetName == null) return;

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        String generatedBuilderClass = targetName + "Builder";

        if (addBuilder) {
            PsiMethod m = factory.createMethodFromText(
                "@dev.sbs.annotation.XContract(value = \"-> new\", pure = true)\n"
                    + "public static " + builderName + " " + builderMethodName + "() {\n"
                    + "    return new " + generatedBuilderClass + "();\n"
                    + "}",
                psiClass
            );
            psiClass.add(m);
        }

        if (addFrom && !fromMethodName.isEmpty()) {
            PsiMethod m = factory.createMethodFromText(
                "@dev.sbs.annotation.XContract(value = \"_ -> new\", pure = true)\n"
                    + "public static " + builderName + " " + fromMethodName + "(" + targetName + " instance) {\n"
                    + "    return " + generatedBuilderClass + "." + fromMethodName + "(instance);\n"
                    + "}",
                psiClass
            );
            psiClass.add(m);
        }

        if (addMutate && !toBuilderMethodName.isEmpty()) {
            PsiMethod m = factory.createMethodFromText(
                "@dev.sbs.annotation.XContract(value = \"-> new\", pure = true)\n"
                    + "public " + builderName + " " + toBuilderMethodName + "() {\n"
                    + "    return " + (fromMethodName.isEmpty() ? generatedBuilderClass + ".from(this)" : fromMethodName + "(this)") + ";\n"
                    + "}",
                psiClass
            );
            psiClass.add(m);
        }

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiClass);
        CodeStyleManager.getInstance(project).reformat(psiClass);
    }

}
