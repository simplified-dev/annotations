package dev.simplified.classbuilder.inspect;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.AccessToken;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import dev.simplified.classbuilder.apt.ExecutableTargetRefusal;
import dev.simplified.testutil.JSvgErrorSuppressor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ClassBuilderFieldInspectionTest extends BasePlatformTestCase {

    /**
     * A real JDK rather than the empty default, which resolves no
     * {@code java.lang} type at all. Half of what this inspection asks is
     * whether a field's type inherits something - {@code Number},
     * {@code CharSequence}, {@code Collection} - and without an SDK every one of
     * those answers no, so a test could only ever confirm the warnings and never
     * their absence.
     */
    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return LightJavaCodeInsightFixtureTestCase.JAVA_17;
    }

    private AccessToken jsvgSuppressor;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        jsvgSuppressor = JSvgErrorSuppressor.install();
        myFixture.enableInspections((Class<? extends LocalInspectionTool>) ClassBuilderFieldInspection.class);
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
                SetterNames setters() default @SetterNames;
                AccessLevel access() default AccessLevel.PUBLIC;
                AccessLevel constructorAccess() default AccessLevel.PACKAGE;
                AccessLevel builderConstructorAccess() default AccessLevel.PACKAGE;
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
        myFixture.addFileToProject("dev/simplified/annotations/SetterNames.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target({ElementType.FIELD, ElementType.PARAMETER})
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
        myFixture.addFileToProject("dev/simplified/annotations/AccessLevel.java",
            """
            package dev.simplified.annotations;
            public enum AccessLevel { PUBLIC, PROTECTED, PACKAGE, PRIVATE, NONE }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Formattable.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Formattable { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Negate.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Negate { String value(); }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Collector.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Collector {
                String singularMethodName() default "";
                boolean singular() default false;
                boolean clearable() default false;
                boolean compute() default false;
                boolean removable() default false;
                String key() default "";
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuildFlag.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.FIELD, ElementType.METHOD})
            public @interface BuildFlag {
                boolean nonNull() default false;
                String pattern() default "";
                int limit() default -1;
                double min() default Double.NEGATIVE_INFINITY;
                double max() default Double.POSITIVE_INFINITY;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/ObtainVia.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface ObtainVia {
                String method() default "";
                String field() default "";
                boolean isStatic() default false;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderDefault.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderDefault {
                boolean value() default true;
            }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/BuilderIgnore.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface BuilderIgnore { }
            """);
        myFixture.addFileToProject("dev/simplified/annotations/Lazy.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS) @Target(ElementType.FIELD)
            public @interface Lazy { }
            """);
    }

    private boolean hasErrorContaining(String needle) {
        List<HighlightInfo> highlights = myFixture.doHighlighting();
        for (HighlightInfo h : highlights) {
            if (h.getSeverity() != HighlightSeverity.ERROR && h.getSeverity() != HighlightSeverity.WARNING) continue;
            String desc = h.getDescription();
            if (desc != null && desc.contains(needle)) return true;
        }
        return false;
    }

    public void testNegateOnNonBoolean_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Negate;
            public class Foo {
                @Negate("other") int count;
            }
            """);
        assertTrue(hasErrorContaining("@Negate requires a boolean field"));
    }

    public void testNegateOnBoolean_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Negate;
            public class Foo {
                @Negate("enabled") boolean disabled;
            }
            """);
        assertFalse(hasErrorContaining("@Negate"));
    }

    public void testCollectorOnNonCollection_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Collector;
            public class Foo {
                @Collector int count;
            }
            """);
        assertTrue(hasErrorContaining("@Collector requires"));
    }

    public void testBuildRuleFlagLimitOnIntField_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(limit = 10) int count;
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag(limit = ...) only applies to CharSequence, Collection, Map, array, or Optional<String>/Optional<Number> fields"));
    }

    public void testBuildRuleFlagLimit_notApplicableToAllTypes() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(limit = 10) boolean flag;
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag(limit = ...) only applies to CharSequence, Collection, Map, array, or Optional<String>/Optional<Number> fields"));
    }

    public void testBuildRuleFlagPattern_warnedOnIntField() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(pattern = "[a-z]+") int count;
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag(pattern = ...) only applies to CharSequence or Optional<String> fields"));
    }

    public void testBuildFlagRangeOnAStringField_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(min = 1) String name;
            }
            """);
        assertTrue(hasErrorContaining("@BuildFlag(min/max = ...) only applies to a numeric field"));
    }

    /** A {@code char} boxes to {@code Character}, which the validator cannot bound. */
    public void testBuildFlagRangeOnACharField_warned() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(max = 9) char digit;
            }
            """);
        assertTrue(hasErrorContaining("@BuildFlag(min/max = ...) only applies to a numeric field"));
    }

    public void testBuildFlagRangeOnAnIntField_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(min = 0, max = 100) int nearLossless;
            }
            """);
        assertFalse(hasErrorContaining("@BuildFlag(min/max = ...)"));
    }

    public void testBuildFlagRangeOnADoubleField_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(max = 1.0) double softCap;
            }
            """);
        assertFalse(hasErrorContaining("@BuildFlag(min/max = ...)"));
    }

    public void testBuildFlagRangeOnABoxedField_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(min = 1) Long size;
            }
            """);
        assertFalse(hasErrorContaining("@BuildFlag(min/max = ...)"));
    }

    public void testBuildFlagRangeOnAnOptionalNumber_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            import java.util.Optional;
            public class Foo {
                @BuildFlag(min = 1) Optional<Integer> retries;
            }
            """);
        assertFalse(hasErrorContaining("@BuildFlag(min/max = ...)"));
    }

    // ------------------------------------------------------------------
    // @Collector(key) - the one collector opt-in that moves a signature
    // ------------------------------------------------------------------

    public void testCollectorKeyOnANonMap_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Collector;
            import java.util.List;
            public class Foo {
                @Collector(singular = true, key = "toString") List<String> tags;
            }
            """);
        assertTrue(hasErrorContaining("this field is not a map"));
    }

    public void testCollectorKeyWithoutSingular_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Collector;
            import java.util.Map;
            public class Foo {
                @Collector(key = "toString") Map<String, String> entries;
            }
            """);
        assertTrue(hasErrorContaining("add singular = true"));
    }

    public void testCollectorKeyBesideCompute_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Collector;
            import java.util.Map;
            public class Foo {
                @Collector(singular = true, compute = true, key = "toString")
                Map<String, String> entries;
            }
            """);
        assertTrue(hasErrorContaining("cannot be combined with compute"));
    }

    public void testCollectorKeyNamingNoSuchMethod_flagged() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Collector;
            import java.util.Map;
            public class Foo {
                @Collector(singular = true, key = "nope") Map<String, String> entries;
            }
            """);
        assertTrue(hasErrorContaining("names no no-argument method on String"));
    }

    public void testCollectorKeyNamingARealMethod_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.Collector;
            import java.util.Map;
            public class Foo {
                @Collector(singular = true, key = "toString") Map<String, Integer> entries;
            }
            """);
        assertFalse(hasErrorContaining("@Collector(key"));
    }

    /** An unwritten range is the annotation's own infinite default, not a bound. */
    public void testBuildFlagWithNoRangeOnAStringField_clean() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(nonNull = true) String name;
            }
            """);
        assertFalse(hasErrorContaining("@BuildFlag(min/max = ...)"));
    }

    // ------------------------------------------------------------------
    // Accessors - an interface target declares its constraints there
    // ------------------------------------------------------------------

    public void testBuildFlagOnInterfaceAccessor_notFlagged() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public interface Shape {
                @BuildFlag(nonNull = true) String name();
            }
            """);
        assertFalse(hasErrorContaining("@BuildFlag"));
    }

    public void testBuildFlagLimitOnNonLimitableAccessor_warned() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public interface Shape {
                @BuildFlag(limit = 10) int sides();
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag(limit = ...) only applies to CharSequence, Collection, Map, array, "
                + "or Optional<String>/Optional<Number> fields"));
    }

    /** Widening the target to METHOD also widened the ways it can do nothing. */
    public void testBuildFlagOnConcreteMethod_warnedAsNoEffect() {
        myFixture.configureByText("Foo.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public class Foo {
                @BuildFlag(nonNull = true) String name() { return ""; }
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag is only read on an abstract zero-arg accessor of an interface target"));
    }

    public void testBuildFlagOnAccessorWithParameters_warnedAsNoEffect() {
        myFixture.configureByText("Shape.java",
            """
            import dev.simplified.annotations.BuildFlag;
            public interface Shape {
                @BuildFlag(nonNull = true) String name(int index);
            }
            """);
        assertTrue(hasErrorContaining(
            "@BuildFlag is only read on an abstract zero-arg accessor of an interface target"));
    }

    /**
     * {@code builderConstructorAccess = NONE} is an error on the written value,
     * in the processor's sentence. It used to be read as package-private here
     * while the processor failed the target with an internal message.
     */
    public void testBuilderConstructorAccessNone_isAnErrorOnTheAttribute() {
        myFixture.configureByText("Closed.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.NONE)
            public class Closed {
                String name;
            }
            """);
        HighlightInfo found = null;
        for (HighlightInfo h : myFixture.doHighlighting()) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getDescription() != null
                && h.getDescription().startsWith("@ClassBuilder(builderConstructorAccess")) {
                found = h;
            }
        }
        assertNotNull("an error on the attribute", found);
        assertEquals("@ClassBuilder(builderConstructorAccess = NONE) is not expressible - every "
                + "builder has a constructor, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC",
            found.getDescription());
        assertEquals("AccessLevel.NONE", myFixture.getEditor().getDocument().getText()
            .substring(found.getStartOffset(), found.getEndOffset()));
    }

    /** Every other level is legal, so nothing is said. */
    public void testBuilderConstructorAccessPrivate_isClean() {
        myFixture.configureByText("Closed.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builderConstructorAccess = AccessLevel.PRIVATE)
            public class Closed {
                String name;
            }
            """);
        assertFalse(hasErrorContaining("builderConstructorAccess"));
    }

    /**
     * {@code access = NONE} is an error on the written value, in the
     * processor's sentence. It used to pass here in silence while the processor
     * failed the target with an internal message.
     */
    public void testAccessNone_isAnErrorOnTheAttribute() {
        myFixture.configureByText("Closed.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(access = AccessLevel.NONE)
            public class Closed {
                String name;
            }
            """);
        HighlightInfo found = null;
        for (HighlightInfo h : myFixture.doHighlighting()) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getDescription() != null
                && h.getDescription().startsWith("@ClassBuilder(access")) {
                found = h;
            }
        }
        assertNotNull("an error on the attribute", found);
        assertEquals("@ClassBuilder(access = NONE) is not expressible - the builder class is always "
                + "generated, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC",
            found.getDescription());
        assertEquals("AccessLevel.NONE", myFixture.getEditor().getDocument().getText()
            .substring(found.getStartOffset(), found.getEndOffset()));
    }

    /**
     * {@code constructorAccess = NONE} is an error on the written value, in the
     * processor's sentence. The editor said nothing, while javac failed the
     * target with an internal message.
     */
    public void testConstructorAccessNone_isAnErrorOnTheAttribute() {
        myFixture.configureByText("Acc.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(constructorAccess = AccessLevel.NONE)
            public class Acc {
                String name;
            }
            """);
        HighlightInfo found = null;
        for (HighlightInfo h : myFixture.doHighlighting()) {
            if (h.getSeverity() == HighlightSeverity.ERROR && h.getDescription() != null
                && h.getDescription().startsWith("@ClassBuilder(constructorAccess")) {
                found = h;
            }
        }
        assertNotNull("an error on the attribute", found);
        assertEquals("@ClassBuilder(constructorAccess = NONE) is not expressible - it is the access of the "
                + "constructor build() calls, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC",
            found.getDescription());
        assertEquals("AccessLevel.NONE", myFixture.getEditor().getDocument().getText()
            .substring(found.getStartOffset(), found.getEndOffset()));
    }

    /** Every other level is legal, so nothing is said. */
    public void testAccessPackage_isClean() {
        myFixture.configureByText("Open.java",
            """
            import dev.simplified.annotations.AccessLevel;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(access = AccessLevel.PACKAGE)
            public class Open {
                String name;
            }
            """);
        assertFalse(hasErrorContaining("@ClassBuilder(access"));
    }

    /**
     * {@code type = BuilderNames.NONE} is refused as the literal is: the
     * constant is recognised by name. The check used to read only a literal,
     * so the constant passed in silence while javac refused it.
     */
    public void testTypeSuppressedByConstant_isAnError() {
        myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(type = BuilderNames.NONE))
            public class Named {
                String name;
            }
            """);
        assertTrue(hasErrorContaining("'type' cannot be suppressed"));
    }

    /**
     * A pattern written as a {@code String} constant is judged by the value it
     * holds, as javac judges it. The check used to read only a literal.
     */
    public void testPatternWrittenAsAConstant_isJudgedByItsValue() {
        myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(from = Named.COPY))
            public class Named {
                static final String COPY = "1copy";
                String name;
            }
            """);
        assertTrue(hasErrorContaining("Naming pattern for 'from' expands to an invalid Java identifier"));
    }

    /**
     * {@code BuilderNames.INHERIT} written on every attribute takes the style's
     * name, as javac takes it, so no attribute is reported. Each was an error
     * reading {@code Naming pattern for 'type' must not be empty}.
     */
    public void testBuilderNamesWrittenAsInherit_isClean() {
        myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(type = BuilderNames.INHERIT, builder = BuilderNames.INHERIT,
                build = BuilderNames.INHERIT, from = BuilderNames.INHERIT, toBuilder = BuilderNames.INHERIT))
            public class Named {
                String name;
            }
            """);
        assertEquals(List.of(), namingProblems());
    }

    /**
     * An empty literal is the value {@code INHERIT} holds and is read the same,
     * as javac reads it. It was an error reading
     * {@code Naming pattern for 'from' must not be empty}.
     */
    public void testBuilderNamesWrittenAsAnEmptyLiteral_isClean() {
        myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.BuilderNames;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(builder = @BuilderNames(from = ""))
            public class Named {
                String name;
            }
            """);
        assertEquals(List.of(), namingProblems());
    }

    /**
     * {@code SetterNames.INHERIT} written on every role, on the target and on a
     * field, takes the style's pattern, and javac builds it. Each role was an
     * error reading {@code Naming pattern for 'set' must not be empty}.
     */
    public void testSetterNamesWrittenAsInherit_isClean() {
        myFixture.configureByText("Named.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.SetterNames;
            @ClassBuilder(setters = @SetterNames(set = SetterNames.INHERIT, flag = SetterNames.INHERIT,
                add = SetterNames.INHERIT, put = SetterNames.INHERIT, compute = SetterNames.INHERIT,
                clear = SetterNames.INHERIT, remove = SetterNames.INHERIT))
            public class Named {
                String name;
                @SetterNames(set = SetterNames.INHERIT)
                boolean active;
            }
            """);
        assertEquals(List.of(), namingProblems());
    }

    /** The description of every highlight about a naming pattern. */
    private List<String> namingProblems() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo h : myFixture.doHighlighting()) {
            String description = h.getDescription();
            if (description != null && description.startsWith("Naming pattern")) out.add(description);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // A constructor or static factory the processor refuses
    //
    // Each is one javac error on the annotated member, in the sentence the
    // shared rule holds, and the processor generates nothing for it. The editor
    // reports the same sentence on the annotation.
    // ------------------------------------------------------------------

    /**
     * The ERROR highlights whose description starts with {@code @ClassBuilder},
     * each as {@code [highlighted text] description}.
     */
    private List<String> classBuilderErrors() {
        List<String> out = new ArrayList<>();
        for (HighlightInfo h : myFixture.doHighlighting()) {
            if (h.getSeverity() != HighlightSeverity.ERROR || h.getDescription() == null) continue;
            if (!h.getDescription().startsWith("@ClassBuilder")) continue;
            out.add("[" + myFixture.getEditor().getDocument().getText()
                .substring(h.getStartOffset(), h.getEndOffset()) + "] " + h.getDescription());
        }
        return out;
    }

    public void testAnInstanceMethodTarget_isTheProcessorsErrorOnTheAnnotation() {
        myFixture.configureByText("Job.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Job {
                private final String name;
                Job(String name) { this.name = name; }
                @ClassBuilder
                public Job copy(String name) { return new Job(name); }
            }
            """);
        assertEquals(List.of("[@ClassBuilder] " + ExecutableTargetRefusal.instanceMethod()),
            classBuilderErrors());
    }

    public void testAVoidMethodTarget_isTheProcessorsErrorOnTheAnnotation() {
        myFixture.configureByText("Voided.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Voided {
                @ClassBuilder
                public static void go(int n) { }
            }
            """);
        assertEquals(List.of("[@ClassBuilder] " + ExecutableTargetRefusal.voidMethod()),
            classBuilderErrors());
    }

    public void testAMemberOfAnAnnotatedType_isTheProcessorsErrorOnTheAnnotation() {
        myFixture.configureByText("Both.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public final class Both {
                private final int n;
                @ClassBuilder(access = dev.simplified.annotations.AccessLevel.PUBLIC)
                Both(int n) { this.n = n; }
            }
            """);
        assertEquals(List.of("[@ClassBuilder(access = dev.simplified.annotations.AccessLevel.PUBLIC)] "
                + ExecutableTargetRefusal.besideAnnotatedType("Both")),
            classBuilderErrors());
    }

    public void testASecondAnnotatedMember_isTheProcessorsErrorOnItsAnnotation() {
        myFixture.configureByText("Twice.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Twice {
                private final int n;
                @ClassBuilder
                Twice(int n) { this.n = n; }
                @ClassBuilder(access = dev.simplified.annotations.AccessLevel.PUBLIC)
                public static Twice of(int n) { return new Twice(n); }
            }
            """);
        assertEquals(List.of("[@ClassBuilder(access = dev.simplified.annotations.AccessLevel.PUBLIC)] "
                + ExecutableTargetRefusal.secondMember("Twice")),
            classBuilderErrors());
    }

    public void testAMemberOfATypeWithALazyField_isTheProcessorsErrorOnTheAnnotation() {
        myFixture.configureByText("Deferred.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.Lazy;
            public final class Deferred {
                @Lazy private final String value;
                @ClassBuilder
                Deferred(String value) { this.value = value; }
            }
            """);
        assertEquals(List.of("[@ClassBuilder] " + ExecutableTargetRefusal.lazyField("Deferred", "value")),
            classBuilderErrors());
    }

    /** A refused member ahead of a usable one takes nothing, so the usable one is not a second member. */
    public void testAUsableMemberAfterARefusedOne_isNotASecondMember() {
        myFixture.configureByText("After.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class After {
                private final int n;
                After(int n) { this.n = n; }
                @ClassBuilder
                public After copy(int n) { return new After(n); }
                @ClassBuilder(access = dev.simplified.annotations.AccessLevel.PUBLIC)
                public static After of(int n) { return new After(n); }
            }
            """);
        assertEquals(List.of("[@ClassBuilder] " + ExecutableTargetRefusal.instanceMethod()),
            classBuilderErrors());
    }

    public void testAStaticFactoryTarget_isClean() {
        myFixture.configureByText("Span.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Span {
                private Span(String label) { }
                @ClassBuilder
                public static Span of(String label) { return new Span(label); }
            }
            """);
        assertEquals(List.of(), classBuilderErrors());
    }

    public void testAConstructorTarget_isClean() {
        myFixture.configureByText("Range.java",
            """
            import dev.simplified.annotations.ClassBuilder;
            public final class Range {
                private final int min;
                @ClassBuilder
                Range(int min) { this.min = min; }
            }
            """);
        assertEquals(List.of(), classBuilderErrors());
    }
}
