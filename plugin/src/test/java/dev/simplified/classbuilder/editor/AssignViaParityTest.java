package dev.simplified.classbuilder.editor;

import com.intellij.openapi.application.AccessToken;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The editor's half of {@code @AssignVia}, which is a signature-minting change
 * for one of its two shapes and none at all for the other.
 *
 * <p>A transform over the slot's own type moves no signature, so the
 * synthesised surface has to stay exactly as it was - an extra method there is
 * a duplicate in the editor over source javac compiles. A transform over any
 * other type adds one overload, so a missing method there is completion that
 * does not offer what the build emits. Both directions are pinned.
 */
public class AssignViaParityTest extends LightJavaCodeInsightFixtureTestCase {

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
        myFixture.addFileToProject("dev/simplified/annotations/AssignVia.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            @Repeatable(AssignVia.List.class)
            public @interface AssignVia {
                String method();
                @Retention(RetentionPolicy.CLASS)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                @interface List { AssignVia[] value(); }
            }
            """);
    }

    // ------------------------------------------------------------------
    // A transform over the slot's own type moves no signature
    // ------------------------------------------------------------------

    public void testDirectTransform_addsNoMethod() {
        List<String> signatures = builderSignaturesOf("WebPOptions",
            """
            import dev.simplified.annotations.AssignVia;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class WebPOptions {
                @AssignVia(method = "clampQuality") float quality;
                static float clampQuality(float quality) { return quality; }
            }
            """);
        assertTrue("the ordinary setter is still there: " + signatures,
            signatures.contains("quality(float)"));
        assertEquals("and it is the only one: " + signatures,
            1, countNamed(signatures, "quality"));
    }

    // ------------------------------------------------------------------
    // A transform over any other type adds exactly one overload
    // ------------------------------------------------------------------

    public void testCoercingTransform_addsOneOverload() {
        List<String> signatures = builderSignaturesOf("Rotation",
            """
            import dev.simplified.annotations.AssignVia;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Rotation {
                @AssignVia(method = "parsePrefix") CharSequence sourcePrefix;
                static CharSequence parsePrefix(String cidr) { return cidr; }
            }
            """);
        assertTrue("the slot's own setter: " + signatures,
            signatures.contains("sourcePrefix(CharSequence)"));
        assertTrue("and the transform's: " + signatures,
            signatures.contains("sourcePrefix(String)"));
    }

    public void testRepeatable_addsOneOverloadEach() {
        List<String> signatures = builderSignaturesOf("Reference",
            """
            import dev.simplified.annotations.AssignVia;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Reference {
                @AssignVia(method = "fromNumber")
                @AssignVia(method = "fromChar")
                String id;
                static String fromNumber(long id) { return ""; }
                static String fromChar(char id) { return ""; }
            }
            """);
        assertTrue("the slot's own setter: " + signatures, signatures.contains("id(String)"));
        assertTrue("the long transform: " + signatures, signatures.contains("id(long)"));
        assertTrue("the char transform: " + signatures, signatures.contains("id(char)"));
    }

    public void testCoercingTransform_onAConstructorParameter() {
        List<String> signatures = builderSignaturesOf("Level",
            """
            import dev.simplified.annotations.AssignVia;
            import dev.simplified.annotations.ClassBuilder;
            public final class Level {
                @ClassBuilder
                Level(@AssignVia(method = "parseDepth") int depth) { }
                static int parseDepth(String depth) { return 0; }
            }
            """);
        assertTrue("the slot's own setter: " + signatures, signatures.contains("depth(int)"));
        assertTrue("and the transform's: " + signatures, signatures.contains("depth(String)"));
    }

    // ------------------------------------------------------------------
    // Nothing the processor rejects may appear in completion
    // ------------------------------------------------------------------

    /**
     * The build rejects {@code @AssignVia} beside {@code @Collector}, so the
     * editor must not offer a setter for it either - a method in completion that
     * no build can produce is the same defect as a missing one, pointing the
     * other way.
     */
    public void testTransformBesideACollector_addsNoMethod() {
        List<String> signatures = builderSignaturesOf("Collected",
            """
            import dev.simplified.annotations.AssignVia;
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            @ClassBuilder
            public class Collected {
                @AssignVia(method = "parseTags") @Collector List<String> tags;
                static List<String> parseTags(String tags) { return List.of(); }
            }
            """);
        assertFalse("no setter takes the transform's type: " + signatures,
            signatures.contains("tags(String)"));
    }

    public void testTransformNamingNoSuchMethod_addsNoMethod() {
        List<String> signatures = builderSignaturesOf("Absent",
            """
            import dev.simplified.annotations.AssignVia;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Absent {
                @AssignVia(method = "nope") String id;
            }
            """);
        assertEquals("only the slot's own setter: " + signatures,
            1, countNamed(signatures, "id"));
    }

    public void testTransformNamingAnInstanceMethod_addsNoMethod() {
        List<String> signatures = builderSignaturesOf("Instanced",
            """
            import dev.simplified.annotations.AssignVia;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Instanced {
                @AssignVia(method = "parseId") String id;
                String parseId(long id) { return ""; }
            }
            """);
        assertEquals("only the slot's own setter: " + signatures,
            1, countNamed(signatures, "id"));
    }

    public void testAmbiguousTransform_addsNoMethod() {
        List<String> signatures = builderSignaturesOf("Ambiguous",
            """
            import dev.simplified.annotations.AssignVia;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Ambiguous {
                @AssignVia(method = "parseId") String id;
                static String parseId(long id) { return ""; }
                static String parseId(char id) { return ""; }
            }
            """);
        assertEquals("only the slot's own setter: " + signatures,
            1, countNamed(signatures, "id"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<String> builderSignaturesOf(String className, String source) {
        PsiFile file = myFixture.configureByText(className + ".java", source);
        PsiClass target = ((PsiJavaFile) file).getClasses()[0];
        PsiClass[] inner = target.getInnerClasses();
        assertEquals("expected exactly one synthesised Builder", 1, inner.length);
        List<String> out = new ArrayList<>();
        for (PsiMethod method : inner[0].getMethods()) {
            StringJoiner params = new StringJoiner(",", "(", ")");
            for (PsiParameter parameter : method.getParameterList().getParameters()) {
                params.add(simpleName(parameter.getType().getCanonicalText()));
            }
            out.add(method.getName() + params);
        }
        return out;
    }

    private static int countNamed(List<String> signatures, String name) {
        int count = 0;
        for (String signature : signatures) {
            if (signature.startsWith(name + "(")) count++;
        }
        return count;
    }

    /**
     * The trailing segment of a canonical type name. The light fixture answers
     * {@code getCanonicalText()} with the unqualified name in some shapes and the
     * qualified one in others, so neither spelling can be asserted on its own.
     */
    private static String simpleName(String canonical) {
        int generics = canonical.indexOf('<');
        String raw = generics < 0 ? canonical : canonical.substring(0, generics);
        int dot = raw.lastIndexOf('.');
        return dot < 0 ? raw : raw.substring(dot + 1);
    }

}
