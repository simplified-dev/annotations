package dev.simplified.classbuilder.editor;

import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's half of a {@code @SetterNames} written on one slot, and of the
 * boolean-prefix rule the builder's setters take from the field's own name.
 *
 * <p>Both are name-minting changes, which is the class of change this pairing
 * exists to catch: a setter the IDE spells one way and the build spells another
 * is a red editor over source that compiles, and completion that offers a method
 * the build never emits.
 */
public class PerSlotSetterNamesParityTest extends LightJavaCodeInsightFixtureTestCase {

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
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/SetterNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface SetterNames {
                String INHERIT = "";
                String NONE = "-";
                String set() default INHERIT;
                String flag() default INHERIT;
                String add() default INHERIT;
                String put() default INHERIT;
                String compute() default INHERIT;
                String clear() default INHERIT;
                String remove() default INHERIT;
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
                boolean removable() default false;
                String key() default "";
            }
            """);
    }

    // ------------------------------------------------------------------
    // A pattern written on one slot
    // ------------------------------------------------------------------

    public void testSlotOverride_isHonouredForThatSlotOnly() {
        List<String> names = builderMethodsOf("WebPOptions",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            @ClassBuilder(setters = @SetterNames(set = "with{}"))
            public class WebPOptions {
                float quality;
                @SetterNames(set = "is{}") boolean lossless;
            }
            """);
        assertTrue("the target's pattern still governs quality: " + names,
            names.contains("withQuality"));
        assertTrue("the slot's own governs lossless: " + names, names.contains("isLossless"));
        assertFalse("and the target's must not reach it: " + names, names.contains("withLossless"));
    }

    /** An unwritten role on a slot inherits the target's pattern, not the style's. */
    public void testSlotOverride_unwrittenRolesInheritTheTargets() {
        List<String> names = builderMethodsOf("Registry",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import dev.simplified.annotations.SetterNames;
            import java.util.List;
            @ClassBuilder(setters = @SetterNames(set = "with{}", add = "append{}"))
            public class Registry {
                @Collector(singular = true) @SetterNames(set = "replace{}") List<String> tags;
            }
            """);
        assertTrue("the slot's own set role: " + names, names.contains("replaceTags"));
        assertTrue("the target's add role, inherited: " + names, names.contains("appendTag"));
    }

    /** A placeholder-free literal on a slot is simply that setter's name. */
    public void testSlotOverride_placeholderFreeLiteralIsTheSetterName() {
        List<String> names = builderMethodsOf("Seeded",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            @ClassBuilder
            public class Seeded {
                @SetterNames(set = "withLabel") String label;
                int size;
            }
            """);
        assertTrue("the literal names the setter outright: " + names, names.contains("withLabel"));
        assertTrue("its neighbour is untouched: " + names, names.contains("size"));
    }

    /** A constructor slot takes an override the same way a field does. */
    public void testSlotOverride_onAConstructorParameter() {
        List<String> names = builderMethodsOf("Ranged",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            public final class Ranged {
                @ClassBuilder(setters = @SetterNames(set = "with{}"))
                Ranged(int min, @SetterNames(set = "upTo{}") int max) { }
            }
            """);
        assertTrue("the target's pattern: " + names, names.contains("withMin"));
        assertTrue("the parameter's own: " + names, names.contains("upToMax"));
    }

    // ------------------------------------------------------------------
    // The plural inflection the collector's singular members are named from
    // ------------------------------------------------------------------

    /**
     * An {@code -es} ending is two letters of plural only after a sibilant, so
     * {@code frames} contributes {@code addFrame} while {@code boxes}
     * contributes {@code addBox}. Both halves read the same rule off
     * {@code NamePattern}, which is what stops the editor offering one name and
     * the build emitting another.
     */
    public void testCollectorSingular_keepsAnEThatBelongsToTheWord() {
        List<String> names = builderMethodsOf("Animation",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            @ClassBuilder
            public class Animation {
                @Collector(singular = true) List<String> frames;
                @Collector(singular = true) List<String> boxes;
                @Collector(singular = true) List<String> entries;
            }
            """);
        assertTrue("the e belongs to the word: " + names, names.contains("addFrame"));
        assertFalse("and must not be eaten: " + names, names.contains("addFram"));
        assertTrue("a sibilant stem gives up both: " + names, names.contains("addBox"));
        assertTrue("a consonant-y plural comes back: " + names, names.contains("addEntry"));
    }

    // ------------------------------------------------------------------
    // The boolean prefix the pattern is about to add
    // ------------------------------------------------------------------

    public void testBooleanNamedIsX_doesNotDoubleThePrefix() {
        List<String> names = builderMethodsOf("Forum",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Forum {
                boolean isPermaLink;
            }
            """);
        assertTrue("the zero-arg flag strips the field's own prefix: " + names,
            names.contains("isPermaLink"));
        assertFalse("and must not double it: " + names, names.contains("isIsPermaLink"));
    }

    public void testNegateStemNamedIsX_doesNotDoubleThePrefix() {
        List<String> names = builderMethodsOf("Toggle",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Negate;
            @ClassBuilder
            public class Toggle {
                @Negate("isDisabled") boolean enabled;
            }
            """);
        assertTrue("the negate stem strips too: " + names, names.contains("isDisabled"));
        assertFalse("and must not double: " + names, names.contains("isIsDisabled"));
    }

    public void testBeanStyleTypedSetter_doesNotDoubleThePrefix() {
        List<String> names = builderMethodsOf("Bean",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.NamingStyle;
            @ClassBuilder(style = NamingStyle.BEAN)
            public class Bean {
                boolean isDefault;
            }
            """);
        assertTrue("the value-taking setter strips as well: " + names, names.contains("setDefault"));
        assertFalse("and must not double: " + names, names.contains("setIsDefault"));
    }

    /**
     * The guards. A field whose name merely begins with the letters keeps them,
     * and a non-{@code boolean} field is not a subject for the rule at all.
     */
    public void testOnlyABooleanNamedIsCapitalIsStripped() {
        List<String> names = builderMethodsOf("Guards",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Guards {
                boolean island;
                String isPermaLink;
            }
            """);
        assertTrue("island keeps its letters: " + names, names.contains("isIsland"));
        assertTrue("a String slot is not a subject: " + names, names.contains("isPermaLink"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<String> builderMethodsOf(String className, String source) {
        PsiFile file = myFixture.configureByText(className + ".java", source);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        PsiClass[] inner = target.getInnerClasses();
        assertEquals("expected exactly one synthesised Builder", 1, inner.length);
        List<String> out = new ArrayList<>();
        for (PsiMethod method : inner[0].getMethods()) out.add(method.getName());
        return out;
    }

}
