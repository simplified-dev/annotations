package dev.simplified.args.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.shared.psi.GeneratedMemberMarker;

import java.util.ArrayList;
import java.util.List;

/**
 * Exercises {@link ArgsAugmentProvider}: the constructors the annotations
 * synthesise must resolve in the editor before javac has ever run, with the
 * same parameters the processor selects.
 */
public class ArgsAugmentProviderTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
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
        myFixture.addFileToProject("dev/simplified/annotations/RequiredArgsConstructor.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface RequiredArgsConstructor {
                AccessLevel access() default AccessLevel.PUBLIC;
                boolean emitGenerated() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/NoArgsConstructor.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface NoArgsConstructor {
                AccessLevel access() default AccessLevel.PUBLIC;
                boolean force() default false;
                boolean emitGenerated() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
    }

    private PsiClass configure(String name, String source) {
        PsiFile file = myFixture.configureByText(name + ".java", source);
        return ((PsiJavaFile) file).getClasses()[0];
    }

    /** Parameter types of the constructor with the given arity. */
    private static List<String> paramTypes(PsiClass target, int arity) {
        for (PsiMethod ctor : target.getConstructors()) {
            if (ctor.getParameterList().getParametersCount() != arity) continue;
            List<String> out = new ArrayList<>();
            for (var p : ctor.getParameterList().getParameters()) {
                out.add(p.getType().getPresentableText());
            }
            return out;
        }
        fail("no " + arity + "-arg constructor on " + target.getName());
        return null;
    }

    public void testAllArgsConstructorResolves() {
        PsiClass point = configure("Point",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            @AllArgsConstructor
            public class Point {
                private final int x;
                private int y;
                private final String frozen = "no";
                private static int shared;
            }
            """);

        assertEquals(1, point.getConstructors().length);
        assertEquals(List.of("int", "int"), paramTypes(point, 2));
        assertTrue(point.getConstructors()[0].hasModifierProperty(PsiModifier.PUBLIC));
        assertTrue("carries the generated marker",
            GeneratedMemberMarker.isGenerated(point.getConstructors()[0]));
    }

    /** transient is a parameter here, unlike in the builder's field list. */
    public void testTransientFieldsAreParameters() {
        PsiClass model = configure("Model",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            @AllArgsConstructor
            public class Model {
                private String name;
                private transient String cache;
            }
            """);
        assertEquals(List.of("String", "String"), paramTypes(model, 2));
    }

    public void testRequiredArgsSelectsOnlyUnassignedFinals() {
        PsiClass stack = configure("PackStack",
            """
            import dev.simplified.annotations.RequiredArgsConstructor;
            import java.util.List;
            @RequiredArgsConstructor
            public class PackStack {
                private final String id;
                private final int depth;
                private final List<String> layers = List.of();
                private String mutable;
            }
            """);
        assertEquals(List.of("String", "int"), paramTypes(stack, 2));
    }

    /**
     * The reason this provider is mandatory rather than convenient: the point
     * of a private no-args constructor is to <b>remove</b> the public default
     * the platform would otherwise assume.
     */
    public void testNoArgsReplacesTheImplicitDefault() {
        PsiClass sealed = configure("Sealed",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.NoArgsConstructor;
            @NoArgsConstructor(access = AccessLevel.PRIVATE)
            public class Sealed {
                private String name;
            }
            """);

        assertEquals(1, sealed.getConstructors().length);
        PsiMethod only = sealed.getConstructors()[0];
        assertEquals(0, only.getParameterList().getParametersCount());
        assertTrue("must be private, or an outside new Sealed() wrongly resolves",
            only.hasModifierProperty(PsiModifier.PRIVATE));
    }

    public void testStackedAnnotationsBothResolve() {
        PsiClass profile = configure("Profile",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.NoArgsConstructor;
            @NoArgsConstructor @AllArgsConstructor
            public class Profile {
                private String id;
                private int rank;
            }
            """);
        assertEquals(2, profile.getConstructors().length);
        assertEquals(List.of("String", "int"), paramTypes(profile, 2));
        assertEquals(List.of(), paramTypes(profile, 0));
    }

    /** These annotations add rather than back off, so the author's survives. */
    public void testAddsBesideAnAuthorDeclaredConstructor() {
        PsiClass adds = configure("Adds",
            """
            import dev.simplified.annotations.NoArgsConstructor;
            @NoArgsConstructor
            public class Adds {
                private String a;
                public Adds(String a) { this.a = a; }
            }
            """);
        assertEquals(2, adds.getConstructors().length);
    }

    public void testEnumConstructorIsPrivateAndSkipsConstants() {
        PsiClass format = configure("Format",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.RequiredArgsConstructor;
            @RequiredArgsConstructor(access = AccessLevel.PUBLIC)
            public enum Format {
                PNG("png"), JPG("jpg");
                private final String extension;
            }
            """);

        assertEquals(1, format.getConstructors().length);
        PsiMethod ctor = format.getConstructors()[0];
        assertEquals("the constant list must not become parameters",
            List.of("String"), paramTypes(format, 1));
        assertTrue("an enum constructor is private whatever access says",
            ctor.hasModifierProperty(PsiModifier.PRIVATE));
    }

    /** @Lazy owns its field's storage, so no mode takes it as a parameter. */
    public void testLazyFieldIsNotAParameter() {
        PsiClass deferred = configure("Deferred",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import dev.simplified.annotations.Lazy;
            @AllArgsConstructor
            public class Deferred {
                @Lazy private String name;
                private int count;
            }
            """);
        assertEquals(List.of("int"), paramTypes(deferred, 1));
    }

    public void testRecordContributesNothing() {
        PsiClass rec = configure("Rec",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            @AllArgsConstructor
            public record Rec(int x) { }
            """);
        for (PsiMethod ctor : rec.getConstructors()) {
            assertFalse("nothing synthesised on a record",
                GeneratedMemberMarker.isGenerated(ctor));
        }
    }

    public void testAccessNoneContributesNothing() {
        PsiClass nope = configure("Nope",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.AllArgsConstructor;
            @AllArgsConstructor(access = AccessLevel.NONE)
            public class Nope {
                private int a;
            }
            """);
        for (PsiMethod ctor : nope.getConstructors()) {
            assertFalse(GeneratedMemberMarker.isGenerated(ctor));
        }
    }

}
