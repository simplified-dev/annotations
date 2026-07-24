package dev.simplified.args.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.args.apt.ArgsMode;

/**
 * Exercises the rule that decides which constructor annotation a
 * {@code @ClassBuilder} target infers.
 *
 * <p>The rule is set equality between the builder's field list and the all-args
 * list, and the cases below are the ones where "nothing was excluded" gives the
 * wrong answer.
 */
public class ArgsInferenceTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder {
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                String factoryMethod() default "";
                String[] exclude() default {};
                boolean retainInit() default true;
                boolean validate() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderIgnore.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderIgnore { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AllArgsConstructor.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface AllArgsConstructor {
                AccessLevel access() default AccessLevel.PUBLIC;
                boolean emitGenerated() default true;
            }
            """);
    }

    private PsiClass configure(String name, String source) {
        PsiFile file = myFixture.configureByText(name + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    /** Plain mutable fields: the two lists coincide, so the all-args name is honest. */
    public void testCoincidingFieldListsInferAllArgs() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget {
                private String label;
                private int count;
            }
            """);
        ArgsInference inference = ArgsInference.of(widget);
        assertNotNull(inference);
        assertEquals(ArgsMode.ALL, inference.mode());
        assertEquals("Widget(String label, int count)", inference.signature(widget));
    }

    /**
     * The case "zero ignored fields" gets wrong, and the common one. Every
     * field is an initialised final, so all-args takes none of them while the
     * builder takes all of them - {@code retainInit} turns each initializer
     * into a builder default.
     */
    public void testInitialisedFinalsInferBuilderArgsWithNothingExcluded() {
        PsiClass options = configure("BlockOptions",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class BlockOptions {
                private final String blockId = "";
                private final int scale = 1;
            }
            """);
        ArgsInference inference = ArgsInference.of(options);
        assertNotNull(inference);
        assertEquals("an initialised final lengthens the builder's list, not shortens it",
            ArgsMode.BUILDER, inference.mode());
        assertEquals("BlockOptions(String blockId, int scale)", inference.signature(options));
    }

    /** The axis running the usual way: a field the builder never sees. */
    public void testBuilderIgnoreInfersBuilderArgs() {
        PsiClass target = configure("Partial",
            """
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Partial {
                private String kept;
                @BuilderIgnore private String dropped;
            }
            """);
        ArgsInference inference = ArgsInference.of(target);
        assertNotNull(inference);
        assertEquals(ArgsMode.BUILDER, inference.mode());
        assertEquals("Partial(String kept)", inference.signature(target));
    }

    public void testTransientInfersBuilderArgs() {
        PsiClass target = configure("Cached",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Cached {
                private String kept;
                private transient String cache;
            }
            """);
        assertEquals(ArgsMode.BUILDER, ArgsInference.of(target).mode());
    }

    public void testExcludeInfersBuilderArgs() {
        PsiClass target = configure("Excluded",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(exclude = "dropped")
            public class Excluded {
                private String kept;
                private String dropped;
            }
            """);
        assertEquals(ArgsMode.BUILDER, ArgsInference.of(target).mode());
    }

    /** A written annotation is not a guess, so nothing is inferred beside it. */
    public void testNothingIsInferredWhenTheAuthorWroteOne() {
        PsiClass target = configure("Written",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder @AllArgsConstructor
            public class Written {
                private String label;
            }
            """);
        assertNull(ArgsInference.of(target));
    }

    /** No builder constructor exists to describe when build() never calls new. */
    public void testFactoryMethodInfersNothing() {
        PsiClass target = configure("ViaFactory",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(factoryMethod = "of")
            public class ViaFactory {
                private String label;
            }
            """);
        assertNull(ArgsInference.of(target));
    }

    public void testAuthorConstructorInfersNothing() {
        PsiClass target = configure("HandWritten",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class HandWritten {
                private String label;
                public HandWritten(String label) { this.label = label; }
            }
            """);
        assertNull(ArgsInference.of(target));
    }

    public void testRecordInfersNothing() {
        PsiClass target = configure("Point",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public record Point(int x, int y) { }
            """);
        assertNull(ArgsInference.of(target));
    }

    public void testUnannotatedClassInfersNothing() {
        PsiClass target = configure("Plain",
            """
            public class Plain {
                private String label;
            }
            """);
        assertNull(ArgsInference.of(target));
    }

    /** Package-private is the builder default, so it is not spelled out. */
    public void testDefaultAccessIsNotRendered() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Widget {
                private final String label = "";
            }
            """);
        assertEquals("@BuilderArgsConstructor", ArgsInference.of(widget).annotationText());
    }

    public void testWrittenConstructorAccessIsRendered() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(constructorAccess = AccessLevel.PRIVATE)
            public class Widget {
                private final String label = "";
            }
            """);
        assertEquals("@BuilderArgsConstructor(access = AccessLevel.PRIVATE)",
            ArgsInference.of(widget).annotationText());
    }

}
