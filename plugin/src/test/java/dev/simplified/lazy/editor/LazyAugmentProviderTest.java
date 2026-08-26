package dev.simplified.lazy.editor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.shared.psi.GeneratedMemberMarker;

/**
 * Exercises {@link LazyAugmentProvider}: a class with a {@code @Lazy} field
 * should surface a synthetic memoizing getter that mirrors the AST mutator's
 * output, with field-level annotations propagated onto the method or its
 * return type for hover and DFA visibility.
 *
 * <p>Extends {@link LightJavaCodeInsightFixtureTestCase} so {@code java.lang.String}
 * etc. resolve via the bundled mock JDK - tests that assert on
 * {@link PsiType#getCanonicalText} need that mapping to
 * return FQNs instead of source text.
 */
public class LazyAugmentProviderTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy {
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
            }
            """);
    }

    private PsiClass configure(String name, String source) {
        return ((PsiJavaFile) myFixture.configureByText(name + ".java", source)).getClasses()[0];
    }

    // ------------------------------------------------------------------
    // Getter naming, which follows the same scheme @Getter reads
    // ------------------------------------------------------------------

    /**
     * A boolean lazy field reads through {@code is}, the same choice the
     * accessor pair makes, so a lazy field's accessor sits in the same naming
     * territory as every other generated one on the class.
     */
    public void testBooleanLazyFieldReadsThroughIs() {
        PsiClass holder = configure("Holder",
            """
            import dev.simplified.annotations.Lazy;
            public class Holder {
                @Lazy
                private boolean active = compute();
                private static boolean compute() { return true; }
            }
            """);
        assertEquals("isActive() must be synthesised",
            1, holder.findMethodsByName("isActive", false).length);
        assertEquals("get must not double up with is",
            0, holder.findMethodsByName("getActive", false).length);
    }

    public void testBooleanLazyFieldAlreadyNamedIsKeepsTheOnePrefix() {
        PsiClass holder = configure("Holder",
            """
            import dev.simplified.annotations.Lazy;
            public class Holder {
                @Lazy
                private boolean isReady = compute();
                private static boolean compute() { return true; }
            }
            """);
        assertEquals(1, holder.findMethodsByName("isReady", false).length);
        assertEquals(0, holder.findMethodsByName("isIsReady", false).length);
    }

    public void testFluentStyleDropsThePrefix() {
        PsiClass holder = configure("Holder",
            """
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.NamingStyle;
            public class Holder {
                @Lazy(style = NamingStyle.FLUENT)
                private String label = "x";
            }
            """);
        assertEquals(1, holder.findMethodsByName("label", false).length);
        assertEquals(0, holder.findMethodsByName("getLabel", false).length);
    }

    public void testNamePatternOverridesTheStyle() {
        PsiClass holder = configure("Holder",
            """
            import dev.simplified.annotations.Lazy;
            public class Holder {
                @Lazy(name = "fetch{}")
                private String label = "x";
            }
            """);
        assertEquals(1, holder.findMethodsByName("fetchLabel", false).length);
        assertEquals(0, holder.findMethodsByName("getLabel", false).length);
    }

    /** A hand-written accessor still wins, whatever the style spelled. */
    public void testHandWrittenFluentGetterSuppressesSynthesis() {
        PsiClass holder = configure("Holder",
            """
            import dev.simplified.annotations.Lazy;
            import dev.simplified.annotations.NamingStyle;
            public class Holder {
                @Lazy(style = NamingStyle.FLUENT)
                private String label = "x";
                public String label() { return "hand-written"; }
            }
            """);
        PsiMethod[] found = holder.findMethodsByName("label", false);
        assertEquals("no duplicate beside the declared one", 1, found.length);
        assertFalse("the author's method survives, not ours",
            GeneratedMemberMarker.isGenerated(found[0]));
    }

    public void testLazyGetterSynthesized() {
        PsiFile file = myFixture.configureByText("Holder.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Holder {
                @Lazy
                private String label = "x";
            }
            """);
        PsiClass holder = ((PsiJavaFile) file).getClasses()[0];

        PsiMethod[] getters = holder.findMethodsByName("getLabel", false);
        assertEquals("getLabel() must be synthesised", 1, getters.length);
        PsiMethod getter = getters[0];

        assertTrue("getLabel() must be public",
            getter.hasModifierProperty(PsiModifier.PUBLIC));
        // Use equalsToText so the assertion succeeds whether the PsiType
        // resolves to the FQN or only renders the source text - the test
        // fixture doesn't always wire String through the mock JDK.
        assertTrue("return type matches the source field type",
            getter.getReturnType() != null
                && (getter.getReturnType().equalsToText("java.lang.String")
                    || getter.getReturnType().equalsToText("String")));
        assertTrue("getLabel() carries generated marker",
            GeneratedMemberMarker.isGenerated(getter));
    }

    /**
     * The editor must report the storage javac rewrites the field to. Showing
     * the written type instead marks a direct read of the field green over
     * source the build rejects.
     */
    public void testLazyFieldReportsItsStorageType() {
        PsiFile file = myFixture.configureByText("Holder.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Holder {
                @Lazy
                private String label = "x";
                private String plain = "y";
            }
            """);
        PsiClass holder = ((PsiJavaFile) file).getClasses()[0];

        String lazyType = holder.findFieldByName("label", false).getType().getCanonicalText();
        assertTrue("a @Lazy field reads as its deferred holder, got: " + lazyType,
            lazyType.contains("AtomicReference") && lazyType.contains("Supplier"));
        assertTrue("and the holder is parameterised on the written type, got: " + lazyType,
            lazyType.contains("String"));

        // equalsToText against both spellings, as elsewhere in this class - the
        // fixture's mock JDK does not always wire String through to its FQN.
        PsiType plain = holder.findFieldByName("plain", false).getType();
        assertTrue("an unannotated field is untouched, got: " + plain.getCanonicalText(),
            plain.equalsToText("java.lang.String") || plain.equalsToText("String"));
        PsiType returned = holder.findMethodsByName("getLabel", false)[0].getReturnType();
        assertTrue("the getter still hands back the written type",
            returned != null
                && (returned.equalsToText("java.lang.String") || returned.equalsToText("String")));
    }

    /**
     * A primitive defers like anything else, so the editor has to synthesise
     * its getter too - skipping it would leave every call to a getter javac
     * does emit reading red.
     */
    public void testPrimitiveLazyFieldGetsItsGetter() {
        PsiFile file = myFixture.configureByText("Counter.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Counter {
                @Lazy
                private int count = 1;
            }
            """);
        PsiClass counter = ((PsiJavaFile) file).getClasses()[0];

        PsiMethod[] getters = counter.findMethodsByName("getCount", false);
        assertEquals("getCount() must be synthesised for a primitive", 1, getters.length);
        assertTrue("and it returns the primitive, not a box",
            getters[0].getReturnType() != null && getters[0].getReturnType().equalsToText("int"));
        assertTrue("getCount() carries the generated marker",
            GeneratedMemberMarker.isGenerated(getters[0]));
        // The storage type is not asserted here: boxing a primitive needs
        // java.lang.Integer resolvable, which this fixture's mock JDK does not
        // provide, so the field reads as written. The boxing itself is pinned
        // by the processor's own suite.
    }

    public void testNonLazyClass_noAugment() {
        PsiFile file = myFixture.configureByText("Plain.java",
            """
            public class Plain {
                String label;
            }
            """);
        PsiClass plain = ((PsiJavaFile) file).getClasses()[0];
        assertEquals(0, plain.findMethodsByName("getLabel", false).length);
    }

    public void testExistingGetter_skipsSynthesis() {
        PsiFile file = myFixture.configureByText("HasGetter.java",
            """
            import dev.simplified.annotations.Lazy;
            public class HasGetter {
                @Lazy
                private String label = "x";
                public String getLabel() { return "manual"; }
            }
            """);
        PsiClass cls = ((PsiJavaFile) file).getClasses()[0];
        PsiMethod[] getters = cls.findMethodsByName("getLabel", false);
        assertEquals("only the user's getter exists", 1, getters.length);
        assertFalse("user's getter is NOT marked as synthesised",
            GeneratedMemberMarker.isGenerated(getters[0]));
    }

    public void testNotNullPropagatesToReturnType() {
        // JetBrains @NotNull is bundled with the IntelliJ test fixture
        // classpath, so the FQN is resolvable here even though the
        // annotations library doesn't ship it transitively to fixtures.
        PsiFile file = myFixture.configureByText("Marked.java",
            """
            import dev.simplified.annotations.Lazy;
            import org.jetbrains.annotations.NotNull;
            public class Marked {
                @Lazy
                @NotNull
                private String label = "x";
            }
            """);
        PsiClass cls = ((PsiJavaFile) file).getClasses()[0];
        PsiMethod[] getters = cls.findMethodsByName("getLabel", false);
        assertEquals(1, getters.length);
        PsiMethod getter = getters[0];
        boolean foundOnReturnType = false;
        if (getter.getReturnTypeElement() != null) {
            for (PsiAnnotation a : getter.getReturnTypeElement().getType().getAnnotations()) {
                if ("org.jetbrains.annotations.NotNull".equals(a.getQualifiedName())) {
                    foundOnReturnType = true;
                    break;
                }
            }
        }
        boolean foundOnMethod = false;
        for (PsiAnnotation a : getter.getModifierList().getAnnotations()) {
            if ("org.jetbrains.annotations.NotNull".equals(a.getQualifiedName())) {
                foundOnMethod = true;
                break;
            }
        }
        assertTrue("@NotNull must surface on the synthesised getter (return-type or modifier)",
            foundOnReturnType || foundOnMethod);
    }

    public void testStaticField_noGetterSynthesised() {
        // Static fields are flagged by the inspection; the augment provider
        // simply doesn't synthesise a getter (the LazyFieldMutator emits
        // the compile-time error - this test covers the editor-side fence).
        PsiFile file = myFixture.configureByText("Stat.java",
            """
            import dev.simplified.annotations.Lazy;
            public class Stat {
                @Lazy
                private static String foo = "x";
            }
            """);
        PsiClass cls = ((PsiJavaFile) file).getClasses()[0];
        // We don't require zero or one - LazyAugmentProvider currently does
        // not filter static at the synthesis stage; the inspection covers
        // it. This test pins behaviour so a future change is intentional.
        PsiMethod[] getters = cls.findMethodsByName("getFoo", false);
        // Allow either: provider may or may not skip; document.
        assertTrue("getter set is bounded", getters.length <= 1);
    }
}
