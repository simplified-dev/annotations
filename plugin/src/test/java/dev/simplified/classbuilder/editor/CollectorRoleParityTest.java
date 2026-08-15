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
 * The editor's half of the two {@code @Collector} roles the six-role scheme
 * lacked - the single-element remove, and the map put whose key comes off the
 * value.
 *
 * <p>The remove adds a method, and the derived key moves one: the put drops its
 * key parameter. Either way the editor has to agree with the processor about
 * which methods exist, or completion offers what no build emits.
 */
public class CollectorRoleParityTest extends LightJavaCodeInsightFixtureTestCase {

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


    public void testRemovable_addsOneSetterPerContainerKind() {
        List<String> signatures = builderSignaturesOf("Registry",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            import java.util.Map;
            @ClassBuilder
            public class Registry {
                @Collector(singular = true, removable = true) List<String> tags;
                @Collector(singular = true, removable = true) Map<String, Integer> counts;
            }
            """);
        assertTrue("a collection removes by element: " + signatures,
            signatures.contains("removeTag(String)"));
        assertTrue("a map removes by key: " + signatures,
            signatures.contains("removeCount(String)"));
    }

    public void testRemovable_isNotEmittedWithoutTheOptIn() {
        List<String> signatures = builderSignaturesOf("Plain",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.List;
            @ClassBuilder
            public class Plain {
                @Collector(singular = true) List<String> tags;
            }
            """);
        assertFalse("removable is opt-in: " + signatures, signatures.contains("removeTag(String)"));
    }

    /**
     * The one collector opt-in that moves a signature: the put drops its key
     * parameter, so an editor still offering the two-argument form would put a
     * method in completion that the build does not emit.
     */
    public void testDerivedKey_dropsThePutsKeyParameter() {
        List<String> signatures = builderSignaturesOf("Functions",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.Map;
            @ClassBuilder
            public class Functions {
                @Collector(singular = true, key = "name") Map<String, Named> functions;
            }
            class Named {
                String name() { return ""; }
            }
            """);
        assertTrue("the put takes the value alone: " + signatures,
            signatures.contains("putFunction(Named)"));
        assertFalse("and not a key beside it: " + signatures,
            signatures.contains("putFunction(String,Named)"));
    }

    public void testWithoutADerivedKey_thePutKeepsBothParameters() {
        List<String> signatures = builderSignaturesOf("Counts",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Collector;
            import java.util.Map;
            @ClassBuilder
            public class Counts {
                @Collector(singular = true) Map<String, Integer> counts;
            }
            """);
        assertTrue("the ordinary put: " + signatures,
            signatures.contains("putCount(String,Integer)"));
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
