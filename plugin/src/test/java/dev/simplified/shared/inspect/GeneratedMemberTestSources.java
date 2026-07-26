package dev.simplified.shared.inspect;

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;

/**
 * Installs the annotation stubs the generated-member suppression tests resolve
 * against.
 *
 * <p>Stubs rather than the real {@code :library} annotations because the
 * decisions under test are made from written attributes, and a stub declaring
 * the same attributes exercises them without putting the library's classpath
 * into a light fixture.
 */
final class GeneratedMemberTestSources {

    private GeneratedMemberTestSources() {
    }

    /**
     * Adds every annotation the suppression path reads.
     *
     * @param fixture the fixture to add them to
     */
    static void install(JavaCodeInsightTestFixture fixture) {
        fixture.addFileToProject("org/jetbrains/annotations/NotNull.java",
            """
            package org.jetbrains.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE_USE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            public @interface NotNull { }
            """);
        fixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        fixture.addFileToProject("dev/simplified/annotations/ClassBuilder.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface ClassBuilder {
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                String factoryMethod() default "";
                String[] exclude() default {};
                boolean retainInit() default true;
                boolean validate() default true;
            }
            """);
        fixture.addFileToProject("dev/simplified/annotations/BuilderIgnore.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderIgnore { }
            """);
        fixture.addFileToProject("dev/simplified/annotations/AllArgsConstructor.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface AllArgsConstructor {
                AccessLevel access() default AccessLevel.PUBLIC;
                boolean emitGenerated() default true;
            }
            """);
        fixture.addFileToProject("dev/simplified/annotations/RequiredArgsConstructor.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE)
            public @interface RequiredArgsConstructor {
                AccessLevel access() default AccessLevel.PUBLIC;
                boolean emitGenerated() default true;
            }
            """);
        fixture.addFileToProject("dev/simplified/annotations/NoArgsConstructor.java",
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
        fixture.addFileToProject("dev/simplified/annotations/NamingStyle.java",
            """
            package dev.simplified.annotations;
            public enum NamingStyle { SIMPLIFIED, LOMBOK, FLUENT }
            """);
        fixture.addFileToProject("dev/simplified/annotations/Getter.java",
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
        fixture.addFileToProject("dev/simplified/annotations/Setter.java",
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

}
