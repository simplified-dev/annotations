package dev.simplified.args.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
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

    private static final String NOT_NULL_FQN = "org.jetbrains.annotations.NotNull";
    private static final String NULLABLE_FQN = "org.jetbrains.annotations.Nullable";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("org/jetbrains/annotations/NotNull.java",
            """
            package org.jetbrains.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE_USE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            public @interface NotNull { }
            """);
        myFixture.addFileToProject("org/jetbrains/annotations/Nullable.java",
            """
            package org.jetbrains.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE_USE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            public @interface Nullable { }
            """);
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

    /**
     * Parameter types of the constructor with the given arity.
     *
     * <p>{@code getPresentableText()} strips annotations, so nothing routed
     * through here can see a parameter's nullness - which is why
     * {@link #testFieldNullnessReachesConstructorParameters} reads the
     * annotations off the parameter itself.
     */
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

    /** The constructor with the given arity. */
    private static PsiMethod ctor(PsiClass target, int arity) {
        for (PsiMethod ctor : target.getConstructors()) {
            if (ctor.getParameterList().getParametersCount() == arity) return ctor;
        }
        throw new AssertionError("no " + arity + "-arg constructor on " + target.getName());
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

    /**
     * The class file javac produces carries the field's nullness on the
     * generated constructor parameter, so the PSI copy has to. Without it
     * IntelliJ's own nullability inspections contradict the compiled result -
     * a {@code null} passed to a {@code @NotNull} slot reads as fine in the
     * editor and is flagged by every analysis that runs on the artifact.
     *
     * <p>Read off the parameter rather than through {@code paramTypes}: that
     * helper's {@code getPresentableText()} drops annotations, which is exactly
     * why the existing suite could not see this.
     */
    public void testFieldNullnessReachesConstructorParameters() {
        PsiClass card = configure("Card",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import org.jetbrains.annotations.NotNull;
            import org.jetbrains.annotations.Nullable;
            @AllArgsConstructor
            public class Card {
                @NotNull private final String id;
                @Nullable private final String note;
                private final int rank;
            }
            """);

        PsiParameter[] params = ctor(card, 3).getParameterList().getParameters();
        assertNotNull("a @NotNull field gives a @NotNull parameter",
            params[0].getModifierList().findAnnotation(NOT_NULL_FQN));
        assertNotNull("a @Nullable field gives a @Nullable parameter",
            params[1].getModifierList().findAnnotation(NULLABLE_FQN));
        assertNull("an unannotated field imposes no nullness",
            params[2].getModifierList().findAnnotation(NOT_NULL_FQN));
        assertNull("an unannotated field imposes no nullness",
            params[2].getModifierList().findAnnotation(NULLABLE_FQN));
    }

    /**
     * The brief-hover tooltip renders each parameter through
     * {@code JavaDocInfoGenerator.generateType(..., annotated = true)}, which
     * walks the type's annotations rather than the modifier list. A parameter
     * annotated only on the modifier list analyses correctly and hovers blank.
     */
    public void testNullnessAlsoRidesTheParameterType() {
        PsiClass card = configure("Tagged",
            """
            import dev.simplified.annotations.AllArgsConstructor;
            import org.jetbrains.annotations.NotNull;
            @AllArgsConstructor
            public class Tagged {
                @NotNull private final String id;
            }
            """);

        PsiParameter id = ctor(card, 1).getParameterList().getParameters()[0];
        boolean onType = false;
        for (var a : id.getType().getAnnotations()) {
            if (NOT_NULL_FQN.equals(a.getQualifiedName())) onType = true;
        }
        assertTrue("@NotNull must ride the parameter's type as well", onType);
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
