package dev.simplified.shared.psi;

import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.augment.PsiAugmentProvider;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.LoggedErrorRecorder;
import org.jetbrains.annotations.NotNull;

/**
 * Pins that gating a qualified-name test behind the written simple name still
 * answers by the qualified name.
 *
 * <p>The gate exists so that synthesis running inside a
 * {@link PsiAugmentProvider} does not resolve every annotation it walks past,
 * only the ones already spelled like something it cares about. That is only
 * worth having if it changes no answers, so the cases below are the two ways it
 * could: an annotation of the right simple name from the wrong package must
 * still be rejected, and one written out in full must still be accepted.
 *
 * <p>The highlighting cases at the end are a smoke check over the shapes that
 * walk a field's annotations during synthesis. They assert the pass logs
 * nothing, which is worth keeping but is <b>not</b> a detector for provider
 * re-entrancy: the platform records no recursion prevention for an augment
 * cycle, so a provider that re-enters itself raises nothing for the assertion
 * to catch. What they do pin is that these shapes synthesise and highlight
 * without incident.
 */
public class WrittenAnnotationsTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationSources();
    }

    // ------------------------------------------------------------------
    // The gate must not change which annotation counts
    // ------------------------------------------------------------------

    /** A type-level annotation of the right name from the wrong package. */
    public void testForeignClassBuilderIsNotHonoured() {
        PsiClass target = configure("Impostor.java",
            """
            import other.ClassBuilder;
            @ClassBuilder
            public class Impostor {
                String label;
            }
            """);
        assertEquals("a foreign @ClassBuilder must synthesise nothing",
            0, target.findMethodsByName("builder", false).length);
        assertEquals(0, target.getInnerClasses().length);
    }

    /** The same annotation written out in full rather than imported. */
    public void testFullyQualifiedClassBuilderIsHonoured() {
        PsiClass target = configure("Qualified.java",
            """
            @dev.simplified.annotations.ClassBuilder
            public class Qualified {
                String label;
            }
            """);
        assertEquals("a fully-qualified @ClassBuilder must still synthesise",
            1, target.findMethodsByName("builder", false).length);
        assertEquals(1, builderOf(target).findMethodsByName("label", false).length);
    }

    /** A field companion of the right name from the wrong package. */
    public void testForeignCollectorIsNotHonoured() {
        PsiClass target = configure("Basket.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import other.Collector;
            import java.util.List;
            @ClassBuilder
            public class Basket {
                @Collector(singular = true, clearable = true) List<String> tags;
            }
            """);
        PsiClass builder = builderOf(target);
        assertEquals("a foreign @Collector must not add the singular setter",
            0, builder.findMethodsByName("addTag", false).length);
        assertEquals("a foreign @Collector must not add the clear method",
            0, builder.findMethodsByName("clearTags", false).length);
        assertEquals("the field still gets its plain setter",
            1, builder.findMethodsByName("tags", false).length);
    }

    /** The real field companion, for contrast with the foreign one above. */
    public void testOwnCollectorIsHonoured() {
        PsiClass target = configure("Cart.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            @ClassBuilder
            public class Cart {
                @Collector(singular = true, clearable = true) List<String> tags;
            }
            """);
        PsiClass builder = builderOf(target);
        assertEquals(1, builder.findMethodsByName("addTag", false).length);
        assertEquals(1, builder.findMethodsByName("clearTags", false).length);
    }

    /** A foreign {@code @BuilderIgnore} must not drop the field from the builder. */
    public void testForeignBuilderIgnoreIsNotHonoured() {
        PsiClass target = configure("Keep.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import other.BuilderIgnore;
            @ClassBuilder
            public class Keep {
                @BuilderIgnore String label;
            }
            """);
        assertEquals("a foreign @BuilderIgnore must leave the field in the builder",
            1, builderOf(target).findMethodsByName("label", false).length);
    }

    /** The real one, which does drop it. */
    public void testOwnBuilderIgnoreIsHonoured() {
        PsiClass target = configure("Drop.java",
            """
            import dev.simplified.annotations.BuilderIgnore;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Drop {
                @BuilderIgnore String label;
                String kept;
            }
            """);
        PsiClass builder = builderOf(target);
        assertEquals(0, builder.findMethodsByName("label", false).length);
        assertEquals(1, builder.findMethodsByName("kept", false).length);
    }

    /** A foreign {@code @Lazy} must not mint a memoising getter. */
    public void testForeignLazyIsNotHonoured() {
        PsiClass target = configure("Eager.java",
            """
            import other.Lazy;
            public class Eager {
                @Lazy private String value = "x";
            }
            """);
        assertEquals("a foreign @Lazy must synthesise no getter",
            0, target.findMethodsByName("getValue", false).length);
    }

    /** A foreign {@code @EnumLookup} must not mint the lookup helpers. */
    public void testForeignEnumLookupIsNotHonoured() {
        PsiClass target = configure("Suit.java",
            """
            import other.EnumLookup;
            @EnumLookup
            public enum Suit {
                HEARTS
            }
            """);
        assertEquals("a foreign @EnumLookup must synthesise no name lookup",
            0, target.findMethodsByName("ofName", false).length);
        assertEquals("a foreign @EnumLookup must synthesise no ordinal lookup",
            0, target.findMethodsByName("ofOrdinal", false).length);
    }

    // ------------------------------------------------------------------
    // Smoke: the shapes that walk a field's annotations during synthesis
    // ------------------------------------------------------------------

    public void testAnnotatedFieldsHighlightCleanly() {
        assertHighlightsCleanly("Target.java",
            """
            import demo.Marker;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(exclude = "b")
            final class Target {
                private final @Marker String a, b;
                Target(String a, String b) { this.a = a; this.b = b; }
            }
            """);
    }

    public void testAnnotatedRecordComponentsHighlightCleanly() {
        assertHighlightsCleanly("Point.java",
            """
            import demo.Marker;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            record Point(@Marker int x, @Marker int y) { }
            """);
    }

    public void testStackedProvidersHighlightCleanly() {
        assertHighlightsCleanly("Stack.java",
            """
            import demo.Marker;
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Getter;
            import dev.simplified.annotations.Lazy;
            @ClassBuilder
            @Getter
            final class Stack {
                private final @Marker String a;
                @Lazy @Marker private String heavy = "x";
                Stack(String a) { this.a = a; }
            }
            """);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PsiClass configure(String fileName, String source) {
        myFixture.configureByText(fileName, source);
        return ((PsiJavaFile) myFixture.getFile()).getClasses()[0];
    }

    private PsiClass builderOf(PsiClass target) {
        PsiClass[] inner = target.getInnerClasses();
        assertEquals("Builder must be synthesised for " + target.getName(), 1, inner.length);
        return inner[0];
    }

    private void assertHighlightsCleanly(String fileName, String source) {
        PsiClass target = configure(fileName, source);
        LoggedErrorRecorder recorder = new LoggedErrorRecorder();
        try (AccessToken ignored = recorder.install()) {
            myFixture.doHighlighting();
        }
        assertEquals("highlighting " + fileName + " logged an error",
            "", String.join("\n\n", recorder.reports()));
        assertEquals("the shape under test must actually synthesise",
            1, target.findMethodsByName("builder", false).length);
    }

    /**
     * Adds the annotation stubs the sources above reference, including an
     * {@code other} package whose members share our simple names.
     */
    private void addAnnotationSources() {
        myFixture.addFileToProject("demo/Marker.java",
            """
            package demo;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
            public @interface Marker { }
            """);
        myFixture.addFileToProject("other/ClassBuilder.java",
            """
            package other;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder { String[] exclude() default {}; }
            """);
        myFixture.addFileToProject("other/Collector.java",
            """
            package other;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Collector {
                boolean singular() default false;
                boolean clearable() default false;
            }
            """);
        myFixture.addFileToProject("other/BuilderIgnore.java",
            """
            package other;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderIgnore { }
            """);
        myFixture.addFileToProject("other/Lazy.java",
            """
            package other;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
        myFixture.addFileToProject("other/EnumLookup.java",
            """
            package other;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface EnumLookup { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder {
                BuilderNames builder() default @BuilderNames;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                SetterNames setters() default @SetterNames;
                String factoryMethod() default "";
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                String[] exclude() default {};
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
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
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderIgnore.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderIgnore { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Getter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Getter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
                boolean emitContracts() default true;
                boolean emitGenerated() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy {
                AccessLevel access() default AccessLevel.PUBLIC;
            }
            """);
    }

}
