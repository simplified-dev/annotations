package dev.simplified.equality.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.shared.psi.GeneratedMemberMarker;

/**
 * Exercises {@link WholeObjectAugmentProvider}.
 *
 * <p>Three of the four contributed members are inherited from {@code Object}, so
 * nothing here fails loudly when the provider stops working - a missing
 * {@code equals} still resolves, and the only symptom is the IDE reporting a
 * type as not overriding it while the build says otherwise. That is exactly why
 * these assertions have to exist: this is the one provider in the plugin whose
 * regression is silent.
 */
public class WholeObjectAugmentProviderTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("dev/simplified/annotations/CallSuper.java",
            """
            package dev.simplified.annotations;
            public enum CallSuper { AUTO, YES, NO }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/EqualsAndHashCode.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface EqualsAndHashCode {
                Identity identity() default Identity.EXACT_CLASS;
                CallSuper callSuper() default CallSuper.AUTO;
                String[] of() default {};
                String[] exclude() default {};
                boolean cacheHashCode() default false;
                boolean useAccessors() default false;
                boolean emitContracts() default true;
                boolean emitGenerated() default true;
                enum Identity { EXACT_CLASS, INSTANCE_OF, INSTANCE_OF_CANEQUAL }
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/ToString.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ToString {
                CallSuper callSuper() default CallSuper.AUTO;
                boolean includeFieldNames() default true;
                Style style() default Style.SIMPLIFIED;
                String[] of() default {};
                String[] exclude() default {};
                boolean useAccessors() default false;
                boolean emitContracts() default true;
                boolean emitGenerated() default true;
                enum Style { SIMPLIFIED, LOMBOK }
            }
            """);
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private PsiClass configure(String name, String source) {
        PsiFile file = myFixture.configureByText(name + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    private static PsiMethod sole(PsiClass target, String name) {
        PsiMethod[] found = target.findMethodsByName(name, false);
        assertEquals("expected exactly one '" + name + "' on " + target.getName(), 1, found.length);
        return found[0];
    }

    private static void assertAbsent(PsiClass target, String name) {
        assertEquals("'" + name + "' must not be contributed on " + target.getName(),
            0, target.findMethodsByName(name, false).length);
    }

    // ------------------------------------------------------------------
    // The equality pair
    // ------------------------------------------------------------------

    public void testEqualityPairIsContributedWithTheRightShape() {
        PsiClass point = configure("Point",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public final class Point {
                private final int x;
                Point(int x) { this.x = x; }
            }
            """);

        PsiMethod equals = sole(point, "equals");
        assertTrue(equals.hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(1, equals.getParameterList().getParametersCount());
        assertEquals("java.lang.Object",
            equals.getParameterList().getParameters()[0].getType().getCanonicalText());
        assertEquals("boolean", equals.getReturnType().getCanonicalText());
        assertTrue("the member must be marked as plugin-generated",
            GeneratedMemberMarker.isGenerated(equals));

        PsiMethod hashCode = sole(point, "hashCode");
        assertTrue(hashCode.hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(0, hashCode.getParameterList().getParametersCount());
        assertEquals("int", hashCode.getReturnType().getCanonicalText());
    }

    /**
     * Ctrl-click on a contributed member has to land on the annotation that asked
     * for it. Navigating to the target instead would be no better than
     * {@code Object}'s own declaration, which is where the reader ends up when
     * the provider is not there at all.
     */
    public void testNavigationLandsOnTheAnnotation() {
        PsiClass point = configure("Point",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public final class Point { private final int x; Point(int x) { this.x = x; } }
            """);

        assertEquals("@EqualsAndHashCode",
            sole(point, "equals").getNavigationElement().getText());
    }

    public void testNothingIsContributedWithoutTheAnnotation() {
        PsiClass plain = configure("Plain",
            """
            public final class Plain { private final int x; Plain(int x) { this.x = x; } }
            """);
        assertAbsent(plain, "equals");
        assertAbsent(plain, "hashCode");
        assertAbsent(plain, "toString");
    }

    /**
     * The processor makes an already-declared member an error and then emits
     * neither half, so contributing the other half here would promise a member
     * javac never produces.
     */
    public void testNeitherHalfIsContributedWhenTheClassDeclaresOne() {
        PsiClass owned = configure("Owned",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public final class Owned {
                private final int x;
                Owned(int x) { this.x = x; }
                @Override public boolean equals(Object o) { return false; }
            }
            """);

        assertEquals("the author's own equals must be the only one", 1,
            owned.findMethodsByName("equals", false).length);
        assertFalse("the author's own member must not be marked generated",
            GeneratedMemberMarker.isGenerated(owned.findMethodsByName("equals", false)[0]));
        assertAbsent(owned, "hashCode");
    }

    /**
     * A typed convenience overload overrides nothing, so javac still generates
     * the pair beside it. Reading it as a written {@code equals} would withhold
     * both contributions from a class that ends up with both, leaving the
     * platform's "does not override equals()/hashCode()" report standing over a
     * build that succeeds.
     */
    public void testATypedEqualsOverloadStillLeavesThePairContributed() {
        PsiClass vec = configure("Vec",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public final class Vec {
                private final int x;
                Vec(int x) { this.x = x; }
                public boolean equals(Vec other) { return other != null && other.x == this.x; }
            }
            """);

        PsiMethod[] found = vec.findMethodsByName("equals", false);
        assertEquals("the author's overload beside the contributed equals(Object)", 2, found.length);

        PsiMethod contributed = null;
        for (PsiMethod method : found) {
            if (GeneratedMemberMarker.isGenerated(method)) contributed = method;
        }
        assertNotNull("a typed overload is not the override, so the pair is still generated",
            contributed);
        assertEquals("java.lang.Object",
            contributed.getParameterList().getParameters()[0].getType().getCanonicalText());
        assertTrue("the other half has to arrive with it",
            GeneratedMemberMarker.isGenerated(sole(vec, "hashCode")));
    }

    // ------------------------------------------------------------------
    // canEqual
    // ------------------------------------------------------------------

    public void testCanEqualIsContributedOnASubclassableTarget() {
        PsiClass shape = configure("Shape",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public class Shape {
                private final int sides;
                Shape(int sides) { this.sides = sides; }
            }
            """);

        PsiMethod canEqual = sole(shape, "canEqual");
        assertTrue("the hook is protected so a subclass can override it",
            canEqual.hasModifierProperty(PsiModifier.PROTECTED));
        assertEquals(1, canEqual.getParameterList().getParametersCount());
        assertEquals("boolean", canEqual.getReturnType().getCanonicalText());
    }

    /**
     * The processor suppresses the hook where no subclass can exist and says so
     * in a note. A contribution here would surface a member that never reaches
     * the class file.
     */
    public void testCanEqualIsAbsentOnAFinalRootTarget() {
        PsiClass sealedish = configure("Leaf",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public final class Leaf {
                private final int x;
                Leaf(int x) { this.x = x; }
            }
            """);
        assertAbsent(sealedish, "canEqual");
    }

    public void testCanEqualIsAbsentUnderTheOtherTwoRelations() {
        for (String identity : new String[]{"EXACT_CLASS", "INSTANCE_OF"}) {
            PsiClass target = configure("Rel" + identity,
                """
                import dev.simplified.annotations.EqualsAndHashCode;
                @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.%s)
                public class Rel%s { private final int x; Rel%s(int x) { this.x = x; } }
                """.formatted(identity, identity, identity));
            assertAbsent(target, "canEqual");
        }
    }

    /**
     * The hook is reused by the generated {@code equals} on its name and arity
     * alone - the emission calls {@code other.canEqual(this)} and never asks
     * what the parameter is spelled - so a hook of the wrong shape suppresses
     * the contribution exactly as a correct one does. Contributing beside it
     * would put two one-argument {@code canEqual} methods on a class that ends
     * up with one, and the extra would be the one autocompletion offers.
     */
    public void testAWrongShapedCanEqualStillSuppressesTheHook() {
        PsiClass hooked = configure("Hooked",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL)
            public class Hooked {
                private final int sides;
                Hooked(int sides) { this.sides = sides; }
                protected boolean canEqual(String other) { return false; }
            }
            """);

        PsiMethod[] found = hooked.findMethodsByName("canEqual", false);
        assertEquals("the author's own hook must be the only one", 1, found.length);
        assertFalse("nothing may be contributed beside it",
            GeneratedMemberMarker.isGenerated(found[0]));
    }

    // ------------------------------------------------------------------
    // The hash memo
    // ------------------------------------------------------------------

    public void testMemoFieldIsContributedUnderCacheHashCode() {
        PsiClass cached = configure("Cached",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode(cacheHashCode = true)
            public final class Cached {
                private final int x;
                Cached(int x) { this.x = x; }
            }
            """);

        PsiField memo = cached.findFieldByName("$hashCode", false);
        assertNotNull("the memo is a new name, so nothing resolves it without the contribution",
            memo);
        assertTrue(memo.hasModifierProperty(PsiModifier.PRIVATE));
        assertTrue("transient is what the processor emits - a memo that survives serialization "
            + "is a hash computed in another JVM", memo.hasModifierProperty(PsiModifier.TRANSIENT));
        assertEquals("int", memo.getType().getCanonicalText());
        assertTrue(GeneratedMemberMarker.isGenerated(memo));
    }

    public void testMemoFieldIsAbsentWithoutCacheHashCode() {
        PsiClass plain = configure("Uncached",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public final class Uncached { private final int x; Uncached(int x) { this.x = x; } }
            """);
        assertNull(plain.findFieldByName("$hashCode", false));
    }

    // ------------------------------------------------------------------
    // toString
    // ------------------------------------------------------------------

    public void testToStringIsContributed() {
        PsiClass bean = configure("Bean",
            """
            import dev.simplified.annotations.ToString;
            @ToString
            public class Bean { private int x; }
            """);

        PsiMethod toString = sole(bean, "toString");
        assertTrue(toString.hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(0, toString.getParameterList().getParametersCount());
        assertEquals("java.lang.String", toString.getReturnType().getCanonicalText());
        assertAbsent(bean, "equals");
    }

    public void testToStringIsNotContributedWhenTheClassDeclaresIt() {
        PsiClass bean = configure("Owned2",
            """
            import dev.simplified.annotations.ToString;
            @ToString
            public class Owned2 {
                private int x;
                @Override public String toString() { return "own"; }
            }
            """);
        assertEquals(1, bean.findMethodsByName("toString", false).length);
        assertFalse(GeneratedMemberMarker.isGenerated(bean.findMethodsByName("toString", false)[0]));
    }

    public void testBothAnnotationsOnOneTypeContributeAllThree() {
        PsiClass both = configure("Both",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.ToString;
            @EqualsAndHashCode @ToString
            public final class Both { private final int x; Both(int x) { this.x = x; } }
            """);
        sole(both, "equals");
        sole(both, "hashCode");
        sole(both, "toString");
    }

    // ------------------------------------------------------------------
    // Target kinds the provider stands back from
    // ------------------------------------------------------------------

    /**
     * A record's implicit members are already modelled by the platform, so a
     * second set reads as a duplicate declaration on members the author never
     * wrote. Nothing is lost: neither of the two names that are not inherited
     * can reach a record.
     */
    public void testRecordsAreSkipped() {
        PsiClass pixels = configure("Pixels",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.ToString;
            @EqualsAndHashCode @ToString
            public record Pixels(int width, int height) { }
            """);
        for (PsiMethod method : pixels.getMethods()) {
            assertFalse("no member on a record may be plugin-contributed, but " + method.getName()
                + " was", GeneratedMemberMarker.isGenerated(method));
        }
    }

    public void testEnumsAndInterfacesAreSkipped() {
        PsiClass colour = configure("Colour",
            """
            import dev.simplified.annotations.ToString;
            @ToString
            public enum Colour { RED, GREEN }
            """);
        for (PsiMethod method : colour.getMethods()) {
            assertFalse(GeneratedMemberMarker.isGenerated(method));
        }

        PsiClass shape = configure("Shaped",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            @EqualsAndHashCode
            public interface Shaped { int sides(); }
            """);
        for (PsiMethod method : shape.getMethods()) {
            assertFalse(GeneratedMemberMarker.isGenerated(method));
        }
    }

    // ------------------------------------------------------------------
    // Stability
    // ------------------------------------------------------------------

    /**
     * The synthesis reads the target's own members, and the platform re-runs a
     * cached-value producer to compare results. A read that went through the
     * augment-aware {@code getMethods()} instead of {@code getOwnMethods()} would
     * re-enter this provider and overflow the stack rather than fail an
     * assertion, so asking twice is the cheap way to pin it.
     */
    public void testRepeatedQueriesAgreeAndDoNotRecurse() {
        PsiClass point = configure("Stable",
            """
            import dev.simplified.annotations.EqualsAndHashCode;
            import dev.simplified.annotations.ToString;
            @EqualsAndHashCode(identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL,
                               cacheHashCode = true)
            public class Stable { private final int x; Stable(int x) { this.x = x; } }
            """);

        int first = point.getMethods().length;
        int second = point.getMethods().length;
        assertEquals("the contributed member set must be stable across queries", first, second);
        assertNotNull(point.findFieldByName("$hashCode", false));
        assertNotNull(point.findFieldByName("$hashCode", false));
        sole(point, "canEqual");
    }

}
