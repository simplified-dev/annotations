package dev.simplified.accessor.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.shared.psi.GeneratedMemberMarker;

/**
 * Exercises {@link AccessorAugmentProvider}: accessors must resolve in the
 * editor before javac has ever run, with the same names the processor mints.
 */
public class AccessorAugmentProviderTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, BEAN, FLUENT }
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
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Setter.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE, ElementType.FIELD})
            public @interface Setter {
                AccessLevel value() default AccessLevel.PUBLIC;
                NamingStyle style() default NamingStyle.SIMPLIFIED;
                String name() default "";
                String[] exclude() default {};
            }
            """);
    }

    private PsiClass configure(String name, String source) {
        PsiFile file = myFixture.configureByText(name + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    public void testBeanAccessorsAreSynthesised() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
                private boolean animated;
            }
            """);

        PsiMethod[] getLabel = widget.findMethodsByName("getLabel", false);
        assertEquals("getLabel() must be synthesised", 1, getLabel.length);
        assertTrue(getLabel[0].hasModifierProperty(PsiModifier.PUBLIC));
        assertEquals(0, getLabel[0].getParameterList().getParametersCount());
        assertTrue("carries the generated marker", GeneratedMemberMarker.isGenerated(getLabel[0]));

        assertEquals("boolean reads through the is pattern",
            1, widget.findMethodsByName("isAnimated", false).length);
    }

    public void testFluentStyleDropsThePrefix() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            import dev.simplified.annotations.NamingStyle;
            @Getter(style = NamingStyle.FLUENT)
            public class Widget {
                private String label;
            }
            """);
        assertEquals(1, widget.findMethodsByName("label", false).length);
        assertEquals(0, widget.findMethodsByName("getLabel", false).length);
    }

    public void testSetterTakesOneParameterAndReturnsVoid() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Setter;
            @Setter
            public class Widget {
                private String label;
            }
            """);
        PsiMethod[] setters = widget.findMethodsByName("setLabel", false);
        assertEquals(1, setters.length);
        assertEquals(1, setters[0].getParameterList().getParametersCount());
        assertTrue(setters[0].getReturnType() != null
            && setters[0].getReturnType().equalsToText("void"));
    }

    public void testFieldLevelNoneSubtracts() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
                @Getter(AccessLevel.NONE) private int cache;
            }
            """);
        assertEquals(1, widget.findMethodsByName("getLabel", false).length);
        assertEquals("NONE must suppress the accessor",
            0, widget.findMethodsByName("getCache", false).length);
    }

    public void testHandWrittenAccessorWins() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private String label;
                public String getLabel() { return "hand-written"; }
            }
            """);
        PsiMethod[] found = widget.findMethodsByName("getLabel", false);
        assertEquals("no duplicate beside the declared one", 1, found.length);
        assertFalse("the author's method survives, not ours",
            GeneratedMemberMarker.isGenerated(found[0]));
    }

    public void testFinalFieldGetsNoSetter() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Setter;
            @Setter
            public class Widget {
                private final String id = "x";
                private String label;
            }
            """);
        assertEquals(0, widget.findMethodsByName("setId", false).length);
        assertEquals(1, widget.findMethodsByName("setLabel", false).length);
    }

    public void testExcludeSkipsNamedFields() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter(exclude = "cache")
            public class Widget {
                private String label;
                private int cache;
            }
            """);
        assertEquals(1, widget.findMethodsByName("getLabel", false).length);
        assertEquals(0, widget.findMethodsByName("getCache", false).length);
    }

    public void testTypeLevelAnnotationPassesOverStaticFields() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Widget {
                private static final String CONSTANT = "c";
                private String label;
            }
            """);
        assertEquals(1, widget.findMethodsByName("getLabel", false).length);
        assertEquals("the processor generates nothing here, so neither may the editor",
            0, widget.findMethodsByName("getCONSTANT", false).length);
    }

    public void testStaticFieldNamedDirectlyKeepsItsAccessor() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter private static final String CONSTANT = "c";
            }
            """);
        PsiMethod[] found = widget.findMethodsByName("getCONSTANT", false);
        assertEquals(1, found.length);
        assertTrue(found[0].hasModifierProperty(PsiModifier.STATIC));
    }

    public void testUnannotatedClassGetsNothing() {
        PsiClass plain = configure("Plain",
            """
            public class Plain {
                private String label;
            }
            """);
        assertEquals(0, plain.findMethodsByName("getLabel", false).length);
    }

    public void testRecordGetsNothing() {
        PsiClass rec = configure("Point",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public record Point(int x) { }
            """);
        assertEquals("a record's components are accessors already",
            0, rec.findMethodsByName("getX", false).length);
    }

    public void testStaticFieldYieldsStaticAccessor() {
        PsiClass widget = configure("Widget",
            """
            import dev.simplified.annotations.Getter;
            public class Widget {
                @Getter private static String shared;
            }
            """);
        PsiMethod[] found = widget.findMethodsByName("getShared", false);
        assertEquals(1, found.length);
        assertTrue(found[0].hasModifierProperty(PsiModifier.STATIC));
    }

    public void testInheritedFieldsAreNotDuplicated() {
        myFixture.addFileToProject("Base.java",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Base {
                protected String owner;
            }
            """);
        PsiClass leaf = configure("Leaf",
            """
            import dev.simplified.annotations.Getter;
            @Getter
            public class Leaf extends Base {
                private int size;
            }
            """);
        assertEquals("the subclass declares its own field only",
            1, leaf.findMethodsByName("getSize", false).length);
        assertEquals("the parent's accessor must not be re-declared here",
            0, leaf.findMethodsByName("getOwner", false).length);
        assertEquals("but it is still reachable through the supertype",
            1, leaf.findMethodsByName("getOwner", true).length);
    }

}
