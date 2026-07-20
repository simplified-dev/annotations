package dev.simplified.classbuilder.editor;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor-side synthesis for a {@code @ClassBuilder} target that declares type
 * parameters. The synthesised {@code Builder} is {@code static}, so it cannot
 * see the enclosing type's variables and has to re-declare them; the static
 * bootstraps cannot see them either and declare their own.
 *
 * <p>Assertions run through {@link #errorsIn} rather than inspecting the
 * synthesised PSI shape directly. A raw or wrongly-substituted builder still
 * produces plausible-looking {@link PsiMethod}s - what it actually breaks is
 * the editor, where a chained call reads back as {@code Object}. Highlighting
 * the consumer is the assertion that matches what a user sees.
 */
public class GenericTargetAugmentTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
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
                String factoryMethod() default "";
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE }
            """);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    /** Error-severity highlights in the configured file, as descriptions. */
    private List<String> errorsIn() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() != HighlightSeverity.ERROR) continue;
            out.add(info.getDescription());
        }
        return out;
    }

    private void assertNoErrors() {
        List<String> errors = errorsIn();
        assertTrue("expected no editor errors, got: " + errors, errors.isEmpty());
    }

    // ------------------------------------------------------------------
    // The synthesised Builder carries the target's parameters
    // ------------------------------------------------------------------

    public void testBuilderClass_declaresTargetTypeParameters() {
        myFixture.configureByText("Crate.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder public class Crate<V> { V item; }
            """);
        PsiClass target = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiClass builder = target.getInnerClasses()[0];

        assertEquals("Builder must re-declare the target's parameter",
            1, builder.getTypeParameters().length);
        assertEquals("V", builder.getTypeParameters()[0].getName());
    }

    public void testBuilderClass_copiesTypeParameterBounds() {
        myFixture.configureByText("Ranked.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder public class Ranked<V extends Comparable<V>> { V key; }
            """);
        PsiClass target = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiClass builder = target.getInnerClasses()[0];

        assertEquals(1, builder.getTypeParameters().length);
        assertEquals("bound must be carried onto the Builder's copy",
            1, builder.getTypeParameters()[0].getExtendsListTypes().length);
        assertEquals("Comparable",
            builder.getTypeParameters()[0].getExtendsListTypes()[0].getClassName());
    }

    public void testNonGenericTarget_builderHasNoTypeParameters() {
        myFixture.configureByText("Plain.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder public class Plain { String a; }
            """);
        PsiClass target = ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
        PsiClass builder = target.getInnerClasses()[0];

        assertEquals("a non-generic target must not gain type parameters",
            0, builder.getTypeParameters().length);
    }

    // ------------------------------------------------------------------
    // The editor resolves a generic chain end to end
    // ------------------------------------------------------------------

    /**
     * The whole point of the feature: consuming the chain without an
     * intermediate local. A raw builder makes {@code getItem()} read as
     * {@code Object} and this reports an incompatible-types error.
     */
    public void testGenericChain_resolvesThroughToTheTargetsParameter() {
        myFixture.configureByText("Crate.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Crate<V> {
                V item;
                public V getItem() { return item; }
                public static String use() {
                    return Crate.<String>builder().item("x").build().getItem();
                }
            }
            """);
        assertNoErrors();
    }

    public void testGenericChain_parameterisedFieldType() {
        myFixture.configureByText("Holder.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import java.util.List;
            @ClassBuilder
            public class Holder<V> {
                List<V> values;
                public List<V> getValues() { return values; }
                public static List<String> use() {
                    return Holder.<String>builder().values(java.util.List.of("a")).build().getValues();
                }
            }
            """);
        assertNoErrors();
    }

    public void testGenericChain_twoTypeParameters() {
        myFixture.configureByText("Pair.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Pair<A, B> {
                A left;
                B right;
                public A getLeft() { return left; }
                public B getRight() { return right; }
                public static String use() {
                    Pair<String, Integer> p = Pair.<String, Integer>builder()
                        .left("a").right(1).build();
                    String l = p.getLeft();
                    Integer r = p.getRight();
                    return l + r;
                }
            }
            """);
        assertNoErrors();
    }

    /** {@code from(T)} infers its parameter from the argument - no witness needed. */
    public void testGenericChain_fromInfersFromItsArgument() {
        myFixture.configureByText("Crate.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Crate<V> {
                V item;
                public V getItem() { return item; }
                public static String use(Crate<String> existing) {
                    return Crate.from(existing).build().getItem();
                }
            }
            """);
        assertNoErrors();
    }

    /** {@code mutate()} is an instance method, so the class's parameters are in scope. */
    public void testGenericChain_mutateKeepsTheInstancesParameter() {
        myFixture.configureByText("Crate.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Crate<V> {
                V item;
                public V getItem() { return item; }
                public static String use(Crate<String> existing) {
                    return existing.mutate().build().getItem();
                }
            }
            """);
        assertNoErrors();
    }

    public void testGenericTarget_boundedParameterChainResolves() {
        myFixture.configureByText("Ranked.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Ranked<V extends Comparable<V>> {
                V key;
                public V getKey() { return key; }
                public static String use() {
                    return Ranked.<String>builder().key("a").build().getKey();
                }
            }
            """);
        assertNoErrors();
    }

    /** A generic record's builder carries the components' parameters too. */
    public void testGenericRecord_chainResolves() {
        myFixture.configureByText("Duo.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public record Duo<A, B>(A left, B right) {
                public static String use() {
                    return Duo.<String, Integer>builder().left("a").right(1).build().left();
                }
            }
            """);
        assertNoErrors();
    }

    /** Cross-file, so the synth Builder is resolved through the element finder. */
    public void testGenericChain_crossFile() {
        myFixture.addFileToProject("a/Crate.java",
            """
            package a;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Crate<V> {
                V item;
                public V getItem() { return item; }
            }
            """);
        myFixture.configureByText("Use.java",
            """
            import a.Crate;
            public class Use {
                public static String go() {
                    return Crate.<String>builder().item("x").build().getItem();
                }
            }
            """);
        assertNoErrors();
    }

    // ------------------------------------------------------------------
    // Non-generic targets are unaffected
    // ------------------------------------------------------------------

    public void testNonGenericTarget_chainStillResolves() {
        myFixture.configureByText("Doc.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Doc {
                int rank;
                String title;
                public int getRank() { return rank; }
                public static Doc make() {
                    return Doc.builder().rank(7).title("t").build();
                }
                public static int read(Doc d) {
                    return Doc.from(d).build().getRank() + d.mutate().build().getRank();
                }
            }
            """);
        assertNoErrors();
    }

}
