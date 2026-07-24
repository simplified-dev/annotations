package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Pins the nullness on the constructor {@link ClassBuilderAugmentProvider}
 * contributes for a {@code @ClassBuilder} target.
 *
 * <p>The constructor is the member the editor's own nullability inspections run
 * against on every {@code new Target(...)}, so a PSI copy whose parameters carry
 * less - or more - than the class file javac writes turns this plugin into the
 * source of the disagreement it exists to prevent. Two of the checks here are
 * about carrying <b>less</b>: a retyped parameter and a constraint the
 * constructor does not enforce.
 */
public class GeneratedConstructorNullnessTest extends BasePlatformTestCase {

    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder {
                BuilderNames builder() default @BuilderNames;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                SetterNames setters() default @SetterNames;
                String[] exclude() default {};
                boolean emitContracts() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/SetterNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({})
            public @interface SetterNames {
                String INHERIT = "";
                String NONE = "-";
                String set() default INHERIT;
                String flag() default INHERIT;
                String add() default INHERIT;
                String put() default INHERIT;
                String compute() default INHERIT;
                String clear() default INHERIT;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({})
            public @interface BuilderNames {
                String INHERIT = "";
                String NONE = "-";
                String type() default INHERIT;
                String builder() default INHERIT;
                String build() default INHERIT;
                String from() default INHERIT;
                String toBuilder() default INHERIT;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuildFlag.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
            public @interface BuildFlag {
                boolean nonNull() default false;
            }
            """);
        myFixture.addFileToProject("org/jetbrains/annotations/NotNull.java",
            """
            package org.jetbrains.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE_USE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            public @interface NotNull { }
            """);
        myFixture.addFileToProject("org/jetbrains/annotations/Nullable.java",
            """
            package org.jetbrains.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE_USE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            public @interface Nullable { }
            """);
    }

    /** A field's nullness reaches the constructor parameter built from it. */
    public void testFieldNullnessReachesConstructorParameters() {
        PsiClass target = configureTarget("Card",
            """
            import dev.simplified.annotations.ClassBuilder;
            import org.jetbrains.annotations.NotNull;
            import org.jetbrains.annotations.Nullable;
            @ClassBuilder
            public class Card {
                @NotNull String id;
                @Nullable String note;
                int rank;
            }
            """);

        PsiParameter[] params = ctor(target, 3).getParameterList().getParameters();
        assertNotNull("a @NotNull field gives a @NotNull parameter",
            params[0].getModifierList().findAnnotation(NOT_NULL_FQN));
        assertNotNull("a @Nullable field gives a @Nullable parameter",
            params[1].getModifierList().findAnnotation(NULLABLE_FQN));
        assertNull("an unannotated field imposes no nullness",
            params[2].getModifierList().findAnnotation(NOT_NULL_FQN));
        assertNull("an unannotated field imposes no nullness",
            params[2].getModifierList().findAnnotation(NULLABLE_FQN));
    }

    /**
     * A {@code @Lazy} field's parameter is retyped to {@code Supplier<T>}, and
     * the field's nullness describes {@code T}. Null in that slot is the
     * builder's own sentinel for "never set", so a copied {@code @NotNull}
     * would assert exactly what the slot uses to say the opposite.
     */
    public void testLazyParameterIsRetypedAndTakesNoNullness() {
        PsiClass target = configureTarget("Deferred",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            import org.jetbrains.annotations.NotNull;
            @ClassBuilder
            public class Deferred {
                @Lazy @NotNull String name;
            }
            """);

        PsiParameter name = ctor(target, 1).getParameterList().getParameters()[0];
        assertTrue("a @Lazy parameter is a Supplier, got " + name.getType().getCanonicalText(),
            name.getType().getCanonicalText().startsWith("java.util.function.Supplier"));
        assertNull("the field's @NotNull describes T, not the Supplier holding it",
            name.getModifierList().findAnnotation(NOT_NULL_FQN));
    }

    /**
     * {@code @BuildFlag(nonNull)} is enforced by the validator {@code build()}
     * runs on the finished object, not by the constructor, so the constructor's
     * class-file parameter carries nothing for it. The builder setter is where
     * the constraint is worth stating early, and it still does - the two rules
     * are deliberately different, which is why this asserts both halves.
     */
    public void testBuildFlagNonNullStopsAtTheBuilderSetter() {
        PsiClass target = configureTarget("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.BuildFlag;
            @ClassBuilder
            public class Widget {
                @BuildFlag(nonNull = true) String name;
            }
            """);

        PsiParameter ctorParam = ctor(target, 1).getParameterList().getParameters()[0];
        assertNull("the constructor does not enforce @BuildFlag, so it must not claim it",
            ctorParam.getModifierList().findAnnotation(NOT_NULL_FQN));

        PsiClass builder = target.getInnerClasses()[0];
        PsiParameter setterParam = builder.findMethodsByName("name", false)[0]
            .getParameterList().getParameters()[0];
        assertNotNull("the builder setter still states it",
            setterParam.getModifierList().findAnnotation(NOT_NULL_FQN));
    }

    private PsiClass configureTarget(String className, String source) {
        PsiFile file = myFixture.configureByText(className + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    private static PsiMethod ctor(PsiClass target, int arity) {
        for (PsiMethod ctor : target.getConstructors()) {
            if (ctor.getParameterList().getParametersCount() == arity) return ctor;
        }
        throw new AssertionError("no " + arity + "-arg constructor on " + target.getName());
    }

}
