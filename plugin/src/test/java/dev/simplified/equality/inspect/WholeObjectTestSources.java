package dev.simplified.equality.inspect;

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;

import java.util.List;
import java.util.Map;

/**
 * The annotation stubs every whole-object fixture compiles against. The fixture
 * never sees the real library sources, so every annotation an inspection reads
 * has to be written into the test project carrying the attributes it reads - an
 * unresolved annotation is one the inspection silently declines to report on,
 * which reads as a passing negative test.
 *
 * <p>{@link #INSTALLED} and {@link #PERSISTENCE} are that hazard turned into an
 * assertion. Roughly a third of the whole-object tests assert only that nothing
 * is reported, and each of those passes identically against a project where the
 * annotation resolves to nothing at all - so a fixture that stops resolving is
 * indistinguishable from an inspection that stops running. Each entry names an
 * attribute an inspection actually reads, since dropping one from a stub is the
 * quieter half of the same failure.
 *
 * <p>Public rather than package-private, unlike the per-feature installers it
 * follows: the gutter marker's fixture builds the same project from the editor
 * package, and a second copy of these stubs is a second thing to keep in step.
 */
public final class WholeObjectTestSources {

    /**
     * What {@link #install} writes: each annotation against the attributes the
     * inspections and the gutter marker read off it.
     */
    public static final Map<String, List<String>> INSTALLED = Map.ofEntries(
        Map.entry("dev.simplified.annotations.CallSuper", List.of()),
        Map.entry("dev.simplified.annotations.EqualsAndHashCode",
            List.of("identity", "callSuper", "of", "exclude", "cacheHashCode", "useAccessors")),
        Map.entry("dev.simplified.annotations.EqualsAndHashCode.Identity", List.of()),
        Map.entry("dev.simplified.annotations.ToString",
            List.of("callSuper", "includeFieldNames", "style", "of", "exclude", "useAccessors")),
        Map.entry("dev.simplified.annotations.ToString.Style", List.of()),
        Map.entry("dev.simplified.annotations.EqualsExclude", List.of()),
        Map.entry("dev.simplified.annotations.EqualsInclude", List.of()),
        Map.entry("dev.simplified.annotations.ToStringExclude", List.of()),
        Map.entry("dev.simplified.annotations.ToStringInclude", List.of("name", "rank")),
        Map.entry("dev.simplified.annotations.ClassBuilder", List.of()),
        Map.entry("dev.simplified.annotations.BuilderIgnore", List.of()),
        Map.entry("dev.simplified.annotations.Lazy", List.of()));

    /** What {@link #installPersistence} writes, in the same shape. */
    public static final Map<String, List<String>> PERSISTENCE = Map.ofEntries(
        Map.entry("jakarta.persistence.FetchType", List.of()),
        Map.entry("jakarta.persistence.Entity", List.of("name")),
        Map.entry("jakarta.persistence.OneToMany", List.of("fetch")),
        Map.entry("jakarta.persistence.ManyToOne", List.of("fetch")));

    private WholeObjectTestSources() {}

    /**
     * Writes the annotation surface both features read into the test project.
     *
     * @param fixture the fixture to install into
     */
    public static void install(JavaCodeInsightTestFixture fixture) {
        fixture.addFileToProject("dev/simplified/annotations/CallSuper.java",
            """
            package dev.simplified.annotations;
            public enum CallSuper { AUTO, YES, NO }
            """);
        fixture.addFileToProject("dev/simplified/annotations/EqualsAndHashCode.java",
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
        fixture.addFileToProject("dev/simplified/annotations/ToString.java",
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
        fixture.addFileToProject("dev/simplified/annotations/EqualsExclude.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface EqualsExclude { }
            """);
        fixture.addFileToProject("dev/simplified/annotations/EqualsInclude.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface EqualsInclude { }
            """);
        fixture.addFileToProject("dev/simplified/annotations/ToStringExclude.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface ToStringExclude { }
            """);
        fixture.addFileToProject("dev/simplified/annotations/ToStringInclude.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface ToStringInclude {
                String name() default "";
                int rank() default 0;
            }
            """);
        fixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder { }
            """);
        fixture.addFileToProject("dev/simplified/annotations/BuilderIgnore.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderIgnore { }
            """);
        fixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
    }

    /**
     * Installs the persistence stubs the identity recommendation keys on, which
     * only the equality fixture needs - the recommendation is the one check
     * reading an annotation from outside this project.
     *
     * @param fixture the fixture to install into
     */
    public static void installPersistence(JavaCodeInsightTestFixture fixture) {
        fixture.addFileToProject("jakarta/persistence/FetchType.java",
            """
            package jakarta.persistence;
            public enum FetchType { LAZY, EAGER }
            """);
        fixture.addFileToProject("jakarta/persistence/Entity.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
            public @interface Entity { String name() default ""; }
            """);
        fixture.addFileToProject("jakarta/persistence/OneToMany.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
            public @interface OneToMany { FetchType fetch() default FetchType.LAZY; }
            """);
        fixture.addFileToProject("jakarta/persistence/ManyToOne.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
            public @interface ManyToOne { FetchType fetch() default FetchType.EAGER; }
            """);
    }

}
