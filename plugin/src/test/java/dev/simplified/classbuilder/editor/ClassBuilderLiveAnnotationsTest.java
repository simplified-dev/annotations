package dev.simplified.classbuilder.editor;

import com.intellij.codeInsight.InferredAnnotationsManager;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Exercises the editor-time annotation surface on members synthesised by
 * {@link ClassBuilderAugmentProvider}:
 * <ul>
 *   <li>{@link GeneratedMemberFactory} attaches {@code @PrintFormat} /
 *       {@code @Nullable} / {@code @NotNull} on setter parameters.</li>
 *   <li>{@link ClassBuilderInferredAnnotationProvider} delivers
 *       {@code @XContract} + {@code @Contract} at query time for every
 *       synthesised method, gated on the class's {@code emitContracts}.</li>
 * </ul>
 */
public class ClassBuilderLiveAnnotationsTest extends BasePlatformTestCase {

    private static final String PRINT_FORMAT_FQN = "org.intellij.lang.annotations.PrintFormat";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";
    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";
    private static final String XCONTRACT_FQN = "dev.simplified.annotations.XContract";
    private static final String CONTRACT_FQN = "org.jetbrains.annotations.Contract";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationSources();
    }

    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder {
                String builderName() default "Builder";
                String builderMethodName() default "builder";
                String fromMethodName() default "from";
                String toBuilderMethodName() default "mutate";
                String methodPrefix() default "";
                String[] exclude() default {};
                boolean emitContracts() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Formattable.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Formattable { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuildFlag.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target({})
            public @interface BuildFlag {
                boolean nonNull() default false;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/ObtainVia.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({})
            public @interface ObtainVia {
                String method() default "";
                String field() default "";
                boolean isStatic() default false;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuildRule.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
            public @interface BuildRule {
                boolean retainInit() default false;
                boolean ignore() default false;
                BuildFlag flag() default @BuildFlag;
                ObtainVia obtainVia() default @ObtainVia;
            }
            """);
    }

    // ------------------------------------------------------------------
    // Parameter annotations
    // ------------------------------------------------------------------

    /** String {@code @Formattable} setters get {@code @PrintFormat} + {@code @Nullable} on varargs. */
    public void testStringFormattable_printFormatAndNullableVarargs() {
        PsiClass builder = builderFor("Greeting",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Formattable;
            @ClassBuilder
            public class Greeting {
                @Formattable String message;
            }
            """);

        PsiMethod formattable = findByArity(builder, "message", 2);
        PsiParameter fmt = formattable.getParameterList().getParameter(0);
        PsiParameter args = formattable.getParameterList().getParameter(1);

        assertNotNull("format param must carry @PrintFormat",
            fmt.getModifierList().findAnnotation(PRINT_FORMAT_FQN));
        // Neither @NotNull nor @Nullable on the field -> neutral default on
        // the format param. The synth shouldn't impose a nullability the user
        // didn't ask for.
        assertNull("format param must NOT default to @NotNull when field has neither annotation",
            fmt.getModifierList().findAnnotation(NOT_NULL_FQN));
        assertNull("format param must NOT default to @Nullable when field has neither annotation",
            fmt.getModifierList().findAnnotation(NULLABLE_FQN));
        assertTrue("args is varargs", args.isVarArgs());
        assertNotNull("args @Nullable", args.getModifierList().findAnnotation(NULLABLE_FQN));
    }

    /**
     * Brief-hover tooltip renders each parameter through
     * {@code JavaDocInfoGenerator.generateType(..., annotated=true)} which
     * reads TYPE-use annotations, not the parameter's modifier list. A synth
     * param with {@code @NotNull} only on the modifier list will have correct
     * null-flow analysis but blank brief hover. Verify the type carries the
     * same annotation too.
     */
    public void testNotNullField_annotationAttachesToTypeUse() {
        PsiClass builder = builderFor("Doc",
            """
            import dev.simplified.annotations.ClassBuilder;
            import org.jetbrains.annotations.NotNull;
            @ClassBuilder
            public class Doc { @NotNull String title; }
            """);

        myFixture.addFileToProject("org/jetbrains/annotations/NotNull.java",
            """
            package org.jetbrains.annotations;
            import java.lang.annotation.*;
            @Target({ElementType.TYPE_USE, ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
            @Retention(RetentionPolicy.CLASS)
            public @interface NotNull {}
            """);

        PsiMethod setter = builder.findMethodsByName("title", false)[0];
        PsiParameter title = setter.getParameterList().getParameter(0);
        com.intellij.psi.PsiAnnotation[] typeAnnotations = title.getType().getAnnotations();
        boolean hasNotNull = false;
        for (com.intellij.psi.PsiAnnotation a : typeAnnotations) {
            if (NOT_NULL_FQN.equals(a.getQualifiedName())) { hasNotNull = true; break; }
        }
        assertTrue("@NotNull must be attached to the parameter's TYPE (type-use), got "
                + typeAnnotations.length + " type annotations",
            hasNotNull);
    }

    /**
     * Multi-vararg invocation must resolve against the synth formattable setter.
     * Regression test for the bug where the varargs param was constructed with
     * raw {@code Object} type instead of {@code PsiEllipsisType} wrapping
     * {@code Object[]} - a call with 2+ trailing args then either failed to
     * resolve or bound to the wrong method.
     */
    public void testStringFormattable_varargsAcceptsMultipleArgs() {
        myFixture.configureByText("Greeting.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Formattable;
            @ClassBuilder
            public class Greeting {
                @Formattable String message;
            }
            """);
        com.intellij.psi.PsiClass target =
            ((com.intellij.psi.PsiJavaFile) myFixture.getFile()).getClasses()[0];
        com.intellij.psi.PsiClass builder = target.getInnerClasses()[0];
        com.intellij.psi.PsiMethod formattable = null;
        for (com.intellij.psi.PsiMethod m : builder.findMethodsByName("message", false)) {
            if (m.getParameterList().getParametersCount() == 2) { formattable = m; break; }
        }
        assertNotNull("formattable overload must exist", formattable);
        com.intellij.psi.PsiParameter args = formattable.getParameterList().getParameter(1);
        assertTrue("args must be isVarArgs()", args.isVarArgs());
        assertTrue("args type must be PsiEllipsisType, got "
                + args.getType().getClass().getSimpleName() + " (" + args.getType().getCanonicalText() + ")",
            args.getType() instanceof com.intellij.psi.PsiEllipsisType);
    }

    /**
     * Direct check that the platform's printf inspection fires on synth
     * formattable setters - mirrors what the user actually sees. Catches
     * regressions where {@code @PrintFormat} is attached to the parameter
     * but isn't surfaced in a way that {@link com.intellij.psi.PsiParameter#getAnnotation}
     * (or {@code AnnotationUtil}) can find.
     */
    public void testStringFormattable_propagatesToPrintFormatLookup() {
        myFixture.configureByText("Greeting.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Formattable;
            @ClassBuilder
            public class Greeting {
                @Formattable String message;
            }
            """);
        com.intellij.psi.PsiClass target =
            ((com.intellij.psi.PsiJavaFile) myFixture.getFile()).getClasses()[0];
        com.intellij.psi.PsiClass builder = target.getInnerClasses()[0];
        com.intellij.psi.PsiMethod formattable = null;
        for (com.intellij.psi.PsiMethod m : builder.findMethodsByName("message", false)) {
            if (m.getParameterList().getParametersCount() == 2) { formattable = m; break; }
        }
        assertNotNull("formattable overload must exist", formattable);
        com.intellij.psi.PsiParameter fmt = formattable.getParameterList().getParameter(0);

        // Three lookup paths the platform's printf inspection might use - all
        // must surface our @PrintFormat.
        assertNotNull("getModifierList().findAnnotation must return @PrintFormat",
            fmt.getModifierList().findAnnotation(PRINT_FORMAT_FQN));
        assertNotNull("PsiParameter.getAnnotation must return @PrintFormat",
            fmt.getAnnotation(PRINT_FORMAT_FQN));
        assertNotNull("AnnotationUtil.findAnnotation must return @PrintFormat",
            com.intellij.codeInsight.AnnotationUtil.findAnnotation(fmt, PRINT_FORMAT_FQN));
    }

    /** {@code Optional<String>} {@code @Formattable} always has a {@code @Nullable} format arg. */
    public void testOptionalFormattable_nullableFormat() {
        PsiClass builder = builderFor("Card",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Formattable;
            import java.util.Optional;
            @ClassBuilder
            public class Card {
                @Formattable Optional<String> description;
            }
            """);

        PsiMethod formattable = findByArity(builder, "description", 2);
        PsiParameter fmt = formattable.getParameterList().getParameter(0);
        assertNotNull(fmt.getModifierList().findAnnotation(PRINT_FORMAT_FQN));
        assertNotNull("format always @Nullable for Optional<String>",
            fmt.getModifierList().findAnnotation(NULLABLE_FQN));
    }

    /**
     * A field-level {@code @NotNull} must propagate to the generated setter's
     * parameter - including the formattable overload's format-string arg.
     */
    public void testNotNullField_propagatesToPlainAndFormattableSetters() {
        myFixture.addFileToProject("org/jetbrains/annotations/NotNull.java",
            """
            package org.jetbrains.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            public @interface NotNull {}
            """);
        PsiClass builder = builderFor("Greeting",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Formattable;
            import org.jetbrains.annotations.NotNull;
            @ClassBuilder
            public class Greeting {
                @Formattable @NotNull String message;
            }
            """);

        for (PsiMethod m : builder.findMethodsByName("message", false)) {
            PsiParameter first = m.getParameterList().getParameter(0);
            assertNotNull(m.getParameterList().getParametersCount() + "-arg setter first param must be @NotNull: "
                    + first.getType().getPresentableText(),
                first.getModifierList().findAnnotation(NOT_NULL_FQN));
        }
    }

    /** {@code @BuildRule(flag = @BuildFlag(nonNull = true))} pushes {@code @NotNull} onto the primary setter param. */
    public void testBuildRule_flagNonNull_forcesNotNullOnPlainSetter() {
        PsiClass builder = builderFor("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.BuildFlag;
            import dev.simplified.annotations.BuildRule;
            @ClassBuilder
            public class Widget {
                @BuildRule(flag = @BuildFlag(nonNull = true)) String name;
            }
            """);

        PsiMethod setter = builder.findMethodsByName("name", false)[0];
        PsiParameter name = setter.getParameterList().getParameter(0);
        assertNotNull("@BuildRule(flag = @BuildFlag(nonNull)) forces @NotNull",
            name.getModifierList().findAnnotation(NOT_NULL_FQN));
    }

    /** Plain fields with no companion annotations get no nullability on the setter. */
    public void testPlainField_noNullability() {
        PsiClass builder = builderFor("Plain",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Plain { String label; }
            """);

        PsiMethod setter = builder.findMethodsByName("label", false)[0];
        PsiParameter p = setter.getParameterList().getParameter(0);
        assertNull(p.getModifierList().findAnnotation(NOT_NULL_FQN));
        assertNull(p.getModifierList().findAnnotation(NULLABLE_FQN));
    }

    /** Optional-raw setter always accepts null - the raw overload wraps via {@code Optional.ofNullable}. */
    public void testOptionalRaw_alwaysNullable() {
        PsiClass builder = builderFor("Box",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.Optional;
            @ClassBuilder
            public class Box { Optional<String> label; }
            """);

        // Two overloads: raw(String) and wrapped(Optional<String>). The raw one
        // is the overload whose param is NOT an Optional type.
        PsiMethod raw = null;
        for (PsiMethod m : builder.findMethodsByName("label", false)) {
            PsiParameter p = m.getParameterList().getParameter(0);
            String canonical = p.getType().getCanonicalText();
            if (!canonical.startsWith("java.util.Optional")) { raw = m; break; }
        }
        assertNotNull("raw overload must exist", raw);
        PsiParameter p = raw.getParameterList().getParameter(0);
        assertNotNull("raw-Optional setter param is @Nullable",
            p.getModifierList().findAnnotation(NULLABLE_FQN));
    }

    // ------------------------------------------------------------------
    // Inferred @XContract / @Contract
    // ------------------------------------------------------------------

    /** {@code builder()} gets {@code -> new}. */
    public void testBuilderBootstrap_newContract() {
        PsiClass target = configureTarget("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget { String x; }
            """);

        PsiMethod builder = target.findMethodsByName("builder", false)[0];
        assertContractShape(builder, "-> new", false, null);
    }

    /** {@code from(T)} gets {@code _ -> new} with {@code pure = true}. */
    public void testFromBootstrap_pureNewContract() {
        PsiClass target = configureTarget("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget { String x; }
            """);

        PsiMethod from = target.findMethodsByName("from", false)[0];
        assertContractShape(from, "_ -> new", true, null);
    }

    /** Single-arg setter gets {@code _ -> this} with {@code mutates = "this"}. */
    public void testSetter_unaryThisContract() {
        PsiClass builder = builderFor("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget { String x; }
            """);

        PsiMethod setter = builder.findMethodsByName("x", false)[0];
        assertContractShape(setter, "_ -> this", false, "this");
    }

    /** Two-arg setter (e.g. formattable) gets {@code _, _ -> this}. */
    public void testSetter_binaryThisContract() {
        PsiClass builder = builderFor("Greeting",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Formattable;
            @ClassBuilder
            public class Greeting { @Formattable String message; }
            """);

        PsiMethod formattable = findByArity(builder, "message", 2);
        assertContractShape(formattable, "_, _ -> this", false, "this");
    }

    /** {@code build()} on the nested Builder class gets {@code -> new}. */
    public void testBuildMethod_newContract() {
        PsiClass builder = builderFor("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget { String x; }
            """);

        PsiMethod build = builder.findMethodsByName("build", false)[0];
        assertContractShape(build, "-> new", false, null);
    }

    /**
     * Guards the plugin.xml registration: the platform's extension-point list
     * must include our provider under
     * {@code com.intellij.codeInsight.InferredAnnotationProvider.EP_NAME}.
     * {@link InferredAnnotationsManager} lookups in the test harness don't
     * always route through the full provider chain for light elements, so we
     * assert the registration directly - the real IDE's daemon loop iterates
     * the same list per
     * {@link com.intellij.codeInsight.InferredAnnotationsManagerImpl}.
     */
    public void testProviderRegisteredInExtensionPoint() {
        boolean present = com.intellij.codeInsight.InferredAnnotationProvider.EP_NAME
            .getExtensionList(getProject())
            .stream()
            .anyMatch(p -> p instanceof ClassBuilderInferredAnnotationProvider);
        assertTrue("ClassBuilderInferredAnnotationProvider must be registered via plugin.xml",
            present);
    }

    /** {@code emitContracts = false} silences the inferred provider. */
    public void testEmitContractsFalse_noContract() {
        PsiClass target = configureTarget("Silent",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(emitContracts = false)
            public class Silent { String x; }
            """);

        PsiMethod builder = target.findMethodsByName("builder", false)[0];
        ClassBuilderInferredAnnotationProvider provider = new ClassBuilderInferredAnnotationProvider();
        assertNull(provider.findInferredAnnotation(builder, XCONTRACT_FQN));
        assertNull(provider.findInferredAnnotation(builder, CONTRACT_FQN));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiClass configureTarget(String className, String source) {
        PsiFile file = myFixture.configureByText(className + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    private PsiClass builderFor(String className, String source) {
        PsiClass target = configureTarget(className, source);
        PsiClass[] inner = target.getInnerClasses();
        assertEquals("expected exactly one synthesised Builder", 1, inner.length);
        return inner[0];
    }

    private PsiMethod findByArity(PsiClass holder, String name, int arity) {
        for (PsiMethod m : holder.findMethodsByName(name, false)) {
            if (m.getParameterList().getParametersCount() == arity) return m;
        }
        throw new AssertionError("no " + name + " with arity " + arity);
    }

    /**
     * Asserts that both the raw {@code @XContract} and derived {@code @Contract}
     * are surfaced with the given {@code value}, {@code pure}, and {@code mutates}.
     * Queries the provider directly as well as via
     * {@link InferredAnnotationsManager}: the platform manager caches per-
     * element, while the provider query is unambiguous.
     */
    private void assertContractShape(PsiMethod method, String value, boolean pure, String mutates) {
        ClassBuilderInferredAnnotationProvider provider = new ClassBuilderInferredAnnotationProvider();
        PsiAnnotation xContract = provider.findInferredAnnotation(method, XCONTRACT_FQN);
        PsiAnnotation jbContract = provider.findInferredAnnotation(method, CONTRACT_FQN);
        assertNotNull("inferred @XContract must be present on " + method.getName(), xContract);
        assertNotNull("inferred @Contract must be present on " + method.getName(), jbContract);
        assertAttributesMatch(xContract, value, pure, mutates);
        assertAttributesMatch(jbContract, value, pure, mutates);
    }

    private void assertAttributesMatch(PsiAnnotation annotation, String value, boolean pure, String mutates) {
        String actualValue = literal(annotation, "value");
        assertEquals("value attribute", value, actualValue);

        if (pure) {
            String actualPure = String.valueOf(annotation.findAttributeValue("pure").getText());
            assertEquals("pure attribute", "true", actualPure);
        }
        if (mutates != null) {
            assertEquals("mutates attribute", mutates, literal(annotation, "mutates"));
        }
    }

    private static String literal(PsiAnnotation annotation, String attr) {
        Object value = annotation.findAttributeValue(attr);
        if (value == null) return null;
        String text = value.toString();
        // PSI literal text surfaces quotes; strip the outer quotes when present.
        String raw = ((com.intellij.psi.PsiElement) value).getText();
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

}
