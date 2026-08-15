package dev.simplified.classbuilder.editor;

import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor half of {@code @ClassBuilder} on a constructor or static factory.
 *
 * <p>Every assertion here is a parity assertion against what the processor
 * emits for the same source, because that pairing is the whole point: a builder
 * the IDE offers and the build does not produce is a green editor over source
 * javac rejects, and a builder the build produces and the IDE does not know
 * about is twenty-one sites with no completion.
 */
public class ClassBuilderExecutableTargetTest extends LightJavaCodeInsightFixtureTestCase {

    private AccessToken jsvgSuppressor;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        addAnnotationSources();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (jsvgSuppressor != null) jsvgSuppressor.close();
        } finally {
            super.tearDown();
        }
    }

    /** Adds the annotation stubs the tests reference onto the fixture's source path. */
    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
            public @interface ClassBuilder {
                BuilderNames builder() default @BuilderNames;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                SetterNames setters() default @SetterNames;
                String factoryMethod() default "";
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                AccessLevel builderConstructorAccess() default AccessLevel.PACKAGE;
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN }
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
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderSeed.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.PARAMETER)
            public @interface BuilderSeed { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Negate.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface Negate { String value(); }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
                boolean append() default false;
            }
            """);
    }

    // ------------------------------------------------------------------
    // The two shapes
    // ------------------------------------------------------------------

    public void testConstructorTarget_entryPointAndBuilderAreSynthesised() {
        PsiClass target = targetFor("Range",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Range {
                private final int min;
                private final int max;
                @ClassBuilder
                Range(int min, int max) { this.min = min; this.max = max; }
            }
            """);

        PsiMethod[] builders = target.findMethodsByName("builder", false);
        assertEquals("builder() must be synthesised for a constructor target", 1, builders.length);
        assertTrue("builder() is static", builders[0].hasModifierProperty(PsiModifier.STATIC));

        PsiClass builder = onlyInnerClass(target);
        assertEquals("Builder", builder.getName());
        assertTrue("min setter", builder.findMethodsByName("min", false).length > 0);
        assertTrue("max setter", builder.findMethodsByName("max", false).length > 0);
        assertEquals("build()", 1, builder.findMethodsByName("build", false).length);
    }

    public void testStaticFactoryTarget_buildReturnsTheFactorysType() {
        PsiClass target = targetFor("Span",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Span {
                private Span(String label) { }
                @ClassBuilder
                public static Span of(String label) { return new Span(label); }
            }
            """);
        assertEquals(1, target.findMethodsByName("builder", false).length);

        PsiClass builder = onlyInnerClass(target);
        assertTrue("label setter", builder.findMethodsByName("label", false).length > 0);
        PsiMethod build = builder.findMethodsByName("build", false)[0];
        assertTrue("build() returns the factory's declared type, got "
                + build.getReturnType(),
            namesType(build.getReturnType().getCanonicalText(), "Span"));
    }

    /**
     * The processor emits neither on this path - a slot is a parameter, and no
     * mapping from one to an accessor exists for a copy factory to read
     * through. Offering them here would put two methods in completion that the
     * build does not produce.
     */
    public void testExecutableTarget_noCopyFactoryAndNoMutate() {
        PsiClass target = targetFor("Sole",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Sole {
                @ClassBuilder
                Sole(int n) { }
            }
            """);
        assertEquals("from(T) is not emitted for an executable target",
            0, target.findMethodsByName("from", false).length);
        assertEquals("mutate() is not emitted for an executable target",
            0, target.findMethodsByName("mutate", false).length);
    }

    /** The annotated member is what {@code build()} calls, so nothing is synthesised beside it. */
    public void testExecutableTarget_noAllArgsConstructorSynthesis() {
        PsiClass target = targetFor("Solo",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Solo {
                private final int n;
                @ClassBuilder
                Solo(int n) { this.n = n; }
            }
            """);
        assertEquals("only the author's constructor", 1, target.getConstructors().length);
    }

    public void testParameterCompanions_shapeTheSettersTheSameWayAFieldsWould() {
        PsiClass builder = builderFor("Wide",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import dev.simplified.annotations.Negate;
            import java.util.List;
            public final class Wide {
                @ClassBuilder
                Wide(@Negate("visible") boolean hidden,
                     @Collector(singular = true, clearable = true) List<String> tags) { }
            }
            """);
        List<String> names = methodNames(builder);
        assertTrue("boolean zero-arg pair: " + names, names.contains("isHidden"));
        assertTrue("negated zero-arg: " + names, names.contains("isVisible"));
        assertTrue("collector bulk: " + names, names.contains("tags"));
        assertTrue("collector add: " + names, names.contains("addTag"));
        assertTrue("collector clear: " + names, names.contains("clearTags"));
    }

    // ------------------------------------------------------------------
    // Seeded entry points
    // ------------------------------------------------------------------

    public void testSeededParameter_movesOntoBuilderAndDropsItsSetter() {
        PsiClass target = targetFor("Action",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Action {
                @ClassBuilder
                Action(@BuilderSeed String key, boolean enabled) { }
            }
            """);
        PsiMethod[] builders = target.findMethodsByName("builder", false);
        assertEquals(1, builders.length);
        assertEquals("the seed is a parameter of builder(...)",
            1, builders[0].getParameterList().getParametersCount());
        assertEquals("key", builders[0].getParameterList().getParameters()[0].getName());

        PsiClass builder = onlyInnerClass(target);
        List<String> names = methodNames(builder);
        assertFalse("a seeded slot has no setter: " + names, names.contains("key"));
        assertTrue("an unseeded one still does: " + names, names.contains("isEnabled"));
    }

    /** The builder's constructor is the only thing that can fill a final seeded slot. */
    public void testSeededParameter_builderConstructorTakesTheSeed() {
        PsiClass builder = builderFor("Seeded",
            """
            import dev.simplified.annotations.BuilderSeed;
            import dev.simplified.annotations.ClassBuilder;
            public final class Seeded {
                @ClassBuilder
                Seeded(@BuilderSeed String key, int n) { }
            }
            """);
        PsiMethod[] ctors = builder.getConstructors();
        assertEquals(1, ctors.length);
        assertEquals("the builder constructor carries the seed",
            1, ctors[0].getParameterList().getParametersCount());
        assertFalse("and stays package-private, so builder(...) is the way in",
            ctors[0].hasModifierProperty(PsiModifier.PUBLIC));
    }

    // ------------------------------------------------------------------
    // Generics
    // ------------------------------------------------------------------

    /** A constructor runs under the enclosing type's parameters. */
    public void testConstructorTarget_builderRedeclaresTheEnclosingTypesParameters() {
        PsiClass builder = builderFor("Crate",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Crate<V> {
                @ClassBuilder
                Crate(V item) { }
            }
            """);
        assertEquals("the Builder re-declares V", 1, builder.getTypeParameters().length);
        assertEquals("V", builder.getTypeParameters()[0].getName());
    }

    /**
     * A {@code static} factory cannot name the enclosing type's parameters at
     * all, so the builder carries the factory's own.
     */
    public void testStaticFactoryTarget_builderRedeclaresTheFactorysParameters() {
        PsiClass target = targetFor("Box",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Box<V> {
                private Box(V value) { }
                @ClassBuilder
                public static <T> Box<T> of(T value) { return new Box<>(value); }
            }
            """);
        PsiClass builder = onlyInnerClass(target);
        assertEquals(1, builder.getTypeParameters().length);
        assertEquals("the factory's T, not the class's V", "T", builder.getTypeParameters()[0].getName());

        PsiMethod entry = target.findMethodsByName("builder", false)[0];
        assertEquals("builder() declares its own copy", 1, entry.getTypeParameters().length);
    }

    // ------------------------------------------------------------------
    // Where nothing should be synthesised
    // ------------------------------------------------------------------

    public void testInstanceMethodTarget_isIgnored() {
        PsiClass target = targetFor("Inst",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Inst {
                @ClassBuilder
                public Inst make(int n) { return this; }
            }
            """);
        assertEquals("javac rejects it, so the editor offers nothing",
            0, target.findMethodsByName("builder", false).length);
        assertEquals(0, target.getInnerClasses().length);
    }

    public void testVoidMethodTarget_isIgnored() {
        PsiClass target = targetFor("Voided",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Voided {
                @ClassBuilder
                public static void go(int n) { }
            }
            """);
        assertEquals(0, target.findMethodsByName("builder", false).length);
        assertEquals(0, target.getInnerClasses().length);
    }

    /**
     * The processor rejects a type and a member both carrying the annotation, so
     * there is only ever one builder to model; the editor reads the type's,
     * which is the one the author is most likely to have meant.
     */
    public void testAnnotationOnBothTheTypeAndAMember_readsTheType() {
        PsiClass builder = builderFor("Both",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public final class Both {
                private final String field;
                @ClassBuilder
                Both(int parameter) { this.field = null; }
            }
            """);
        List<String> names = methodNames(builder);
        assertTrue("the type's field is the slot: " + names, names.contains("field"));
        assertFalse("not the member's parameter: " + names, names.contains("parameter"));
    }

    // ------------------------------------------------------------------
    // The user-facing payoff
    // ------------------------------------------------------------------

    /** A full chain off a constructor target must resolve before the first build. */
    public void testHighlighting_entryPointChainResolves() {
        myFixture.configureByText("Doc.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public class Doc {
                private final int rank;
                @ClassBuilder
                Doc(int rank) { this.rank = rank; }
                public static Doc make() {
                    return Doc.builder().rank(7).build();
                }
            }
            """);
        for (var info : myFixture.doHighlighting()) {
            String description = info.getDescription();
            if (description != null
                && (description.contains("Cannot access") || description.contains("Cannot resolve"))) {
                fail("the synthesised entry point must resolve, got: " + description);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiClass targetFor(String className, String source) {
        PsiFile file = myFixture.configureByText(className + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    private PsiClass builderFor(String className, String source) {
        return onlyInnerClass(targetFor(className, source));
    }

    private static PsiClass onlyInnerClass(PsiClass target) {
        PsiClass[] inner = target.getInnerClasses();
        assertEquals("expected exactly one synthesised Builder", 1, inner.length);
        return inner[0];
    }

    private static List<String> methodNames(PsiClass owner) {
        List<String> out = new ArrayList<>();
        for (PsiMethod method : owner.getMethods()) out.add(method.getName());
        return out;
    }

    /**
     * Whether a rendered type names the given class. The light fixture renders
     * some types unqualified, so both spellings have to be accepted.
     */
    private static boolean namesType(String canonicalText, String simpleName) {
        return canonicalText.equals(simpleName) || canonicalText.endsWith("." + simpleName);
    }

}
