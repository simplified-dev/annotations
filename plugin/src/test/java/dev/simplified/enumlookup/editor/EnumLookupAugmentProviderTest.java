package dev.simplified.enumlookup.editor;
import dev.simplified.shared.psi.GeneratedMemberMarker;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Exercises {@link EnumLookupAugmentProvider}: a {@code @EnumLookup} enum
 * should surface per-enum static helpers plus per-{@code @KeyField} overloads
 * to the PSI layer.
 */
public class EnumLookupAugmentProviderTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addAnnotationSources();
    }

    private void addAnnotationSources() {
        myFixture.addFileToProject("dev/simplified/annotations/EnumLookup.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface EnumLookup { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/KeyField.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface KeyField {
                String methodName() default "";
                boolean strictKeys() default false;
                boolean strictNullKeys() default false;
            }
            """);
    }

    public void testPerEnumHelpersSynthesizedWithoutKeyField() {
        PsiFile file = myFixture.configureByText("Color.java",
            """
            import dev.simplified.annotations.EnumLookup;
            @EnumLookup
            public enum Color { RED, GREEN, BLUE }
            """);
        PsiClass color = ((PsiJavaFile) file).getClasses()[0];

        assertHasStaticMethod(color, "size", 0);
        assertHasStaticMethod(color, "forEach", 1);
        assertHasStaticMethod(color, "stream", 0);
        assertHasStaticMethod(color, "parallelStream", 0);
        assertHasStaticMethod(color, "ofName", 1);
        assertHasStaticMethod(color, "ofOrdinal", 1);
        assertHasStaticMethod(color, "findByName", 1);
        assertHasStaticMethod(color, "findByOrdinal", 1);
        assertNoStaticMethod(color, "ofCode");
        assertNoStaticMethod(color, "findByCode");
    }

    public void testPerKeyMethodsSynthesizedForPrimitiveField() {
        PsiFile file = myFixture.configureByText("Status.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Status {
                OK(200), ERROR(500);
                @KeyField private final int code;
                Status(int code) { this.code = code; }
            }
            """);
        PsiClass status = ((PsiJavaFile) file).getClasses()[0];

        PsiMethod[] ofCode = status.findMethodsByName("ofCode", false);
        assertEquals("ofCode synthesised", 1, ofCode.length);
        assertEquals("ofCode takes one parameter", 1, ofCode[0].getParameterList().getParametersCount());
        assertEquals("ofCode param is int", "int",
            ofCode[0].getParameterList().getParameters()[0].getType().getCanonicalText());
        assertTrue(GeneratedMemberMarker.isGenerated(ofCode[0]));

        PsiMethod[] findByCode = status.findMethodsByName("findByCode", false);
        assertEquals("findByCode synthesised", 1, findByCode.length);
        assertEquals("findByCode returns Optional<Status>",
            "java.util.Optional<Status>",
            findByCode[0].getReturnType().getCanonicalText());
    }

    public void testPerKeyMethodsRespectMethodNameOverride() {
        PsiFile file = myFixture.configureByText("Status.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Status {
                OK("ok"), ERROR("err");
                @KeyField(methodName = "Slug") private final String tag;
                Status(String tag) { this.tag = tag; }
            }
            """);
        PsiClass status = ((PsiJavaFile) file).getClasses()[0];

        assertHasStaticMethod(status, "ofSlug", 1);
        assertHasStaticMethod(status, "findBySlug", 1);
        assertNoStaticMethod(status, "ofTag");
    }

    public void testCachedFieldsSynthesized() {
        PsiFile file = myFixture.configureByText("Status.java",
            """
            import dev.simplified.annotations.EnumLookup;
            import dev.simplified.annotations.KeyField;
            @EnumLookup
            public enum Status {
                OK(200);
                @KeyField private final int code;
                Status(int code) { this.code = code; }
            }
            """);
        PsiClass status = ((PsiJavaFile) file).getClasses()[0];

        PsiField cachedValues = status.findFieldByName("CACHED_VALUES", false);
        assertNotNull("CACHED_VALUES synthesised", cachedValues);
        assertTrue(cachedValues.hasModifierProperty(PsiModifier.PRIVATE));
        assertTrue(cachedValues.hasModifierProperty(PsiModifier.STATIC));
        assertTrue(cachedValues.hasModifierProperty(PsiModifier.FINAL));
        assertEquals("Status[]", cachedValues.getType().getCanonicalText());

        PsiField cachedKeys = status.findFieldByName("CACHED_KEYS_code", false);
        assertNotNull("CACHED_KEYS_code synthesised", cachedKeys);
        assertEquals("int[]", cachedKeys.getType().getCanonicalText());
    }

    public void testNonEnumWithEnumLookup_noSynthesis() {
        PsiFile file = myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.EnumLookup;
            @EnumLookup
            public class Foo { }
            """);
        PsiClass foo = ((PsiJavaFile) file).getClasses()[0];
        assertNoStaticMethod(foo, "size");
    }

    public void testEnumWithoutEnumLookup_noSynthesis() {
        PsiFile file = myFixture.configureByText("Foo.java",
            """
            public enum Foo { A, B }
            """);
        PsiClass foo = ((PsiJavaFile) file).getClasses()[0];
        assertNoStaticMethod(foo, "size");
        assertNoStaticMethod(foo, "ofName");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void assertHasStaticMethod(PsiClass owner, String name, int arity) {
        for (PsiMethod m : owner.findMethodsByName(name, false)) {
            if (m.getParameterList().getParametersCount() == arity
                && m.hasModifierProperty(PsiModifier.STATIC)) {
                assertTrue("expected " + name + "/" + arity + " carries generated marker",
                    GeneratedMemberMarker.isGenerated(m));
                return;
            }
        }
        fail("missing synthesized static " + name + "/" + arity + " on " + owner.getName());
    }

    private static void assertNoStaticMethod(PsiClass owner, String name) {
        for (PsiMethod m : owner.findMethodsByName(name, false)) {
            if (m.hasModifierProperty(PsiModifier.STATIC)
                && GeneratedMemberMarker.isGenerated(m)) {
                fail("did not expect synthesized " + name + " on " + owner.getName());
            }
        }
    }
}
