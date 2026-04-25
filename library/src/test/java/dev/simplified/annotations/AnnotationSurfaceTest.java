package dev.simplified.annotations;

import org.junit.Test;

import java.lang.annotation.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Compile-time contract tests for the builder annotation surface. Does two jobs:
 * (1) proves every annotation compiles against its intended targets by literally
 *     annotating fixtures in this test, and (2) pins down attribute defaults so
 *     accidental changes surface in test runs.
 */
public class AnnotationSurfaceTest {

    // ------------------------------------------------------------------
    // Retention / Target metadata
    // ------------------------------------------------------------------

    /**
     * Testing 123
     */
    @Formattable
    private String test = "";

    /**
     * Boolean test
     */
    @Negate("notAbcd")
    private boolean abcd;

    private List<String> list;

    @Test
    public void classBuilder_metadata() {
        //builder().build();
        /*builder().withTest("");
        builder().isNotAbcd();
        builder();
        builder().withTest(null, "123", "abc").build();*/
        assertRetention(ClassBuilder.class, RetentionPolicy.CLASS);
        assertTargets(ClassBuilder.class, ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD);
    }

    @Test
    public void buildRule_metadata() {
        assertRetention(BuildRule.class, RetentionPolicy.RUNTIME);
        assertTargets(BuildRule.class, ElementType.FIELD);
    }

    @Test
    public void buildFlag_metadata() {
        // BuildFlag is nested-only (@Target({})) but stays RUNTIME-retained so
        // BuildFlagValidator can read it reflectively via BuildRule.flag().
        assertRetention(BuildFlag.class, RetentionPolicy.RUNTIME);
        assertTargets(BuildFlag.class /* no targets - nested-only */);
    }

    @Test
    public void collector_metadata() {
        assertRetention(Collector.class, RetentionPolicy.CLASS);
        assertTargets(Collector.class, ElementType.FIELD);
    }

    @Test
    public void negate_metadata() {
        assertRetention(Negate.class, RetentionPolicy.CLASS);
        assertTargets(Negate.class, ElementType.FIELD);
    }

    @Test
    public void formattable_metadata() {
        assertRetention(Formattable.class, RetentionPolicy.CLASS);
        assertTargets(Formattable.class, ElementType.FIELD);
    }

    @Test
    public void obtainVia_metadata() {
        // ObtainVia is nested-only (@Target({})). Retention stays CLASS but
        // effective retention when nested inside @BuildRule is RUNTIME.
        assertRetention(ObtainVia.class, RetentionPolicy.CLASS);
        assertTargets(ObtainVia.class /* no targets - nested-only */);
    }

    // ------------------------------------------------------------------
    // Default values (pin down so accidental renames/changes break tests)
    // ------------------------------------------------------------------

    @Test
    public void classBuilder_defaults() throws Exception {
        assertDefault(ClassBuilder.class, "builderName", "Builder");
        assertDefault(ClassBuilder.class, "builderMethodName", "builder");
        assertDefault(ClassBuilder.class, "buildMethodName", "build");
        assertDefault(ClassBuilder.class, "fromMethodName", "from");
        assertDefault(ClassBuilder.class, "toBuilderMethodName", "mutate");
        assertDefault(ClassBuilder.class, "methodPrefix", "");
        assertDefault(ClassBuilder.class, "access", AccessLevel.PUBLIC);
        assertDefault(ClassBuilder.class, "generateBuilder", true);
        assertDefault(ClassBuilder.class, "generateFrom", true);
        assertDefault(ClassBuilder.class, "generateMutate", true);
        assertDefault(ClassBuilder.class, "validate", true);
        assertDefault(ClassBuilder.class, "emitContracts", true);
        assertDefault(ClassBuilder.class, "generateImpl", true);
        assertDefault(ClassBuilder.class, "factoryMethod", "");
        assertArrayEquals(new String[0], (String[]) ClassBuilder.class.getMethod("exclude").getDefaultValue());
    }

    @Test
    public void buildRule_defaults() throws Exception {
        assertDefault(BuildRule.class, "retainInit", false);
        assertDefault(BuildRule.class, "ignore", false);
        BuildFlag flagDefault = (BuildFlag) BuildRule.class.getMethod("flag").getDefaultValue();
        assertNotNull(flagDefault);
        assertFalse(flagDefault.nonNull());
        assertFalse(flagDefault.notEmpty());
        assertEquals("", flagDefault.pattern());
        assertEquals(-1, flagDefault.limit());
        assertArrayEquals(new String[0], flagDefault.group());
        ObtainVia viaDefault = (ObtainVia) BuildRule.class.getMethod("obtainVia").getDefaultValue();
        assertNotNull(viaDefault);
        assertEquals("", viaDefault.method());
        assertEquals("", viaDefault.field());
        assertFalse(viaDefault.isStatic());
    }

    @Test
    public void buildFlag_defaults() throws Exception {
        assertDefault(BuildFlag.class, "nonNull", false);
        assertDefault(BuildFlag.class, "notEmpty", false);
        assertDefault(BuildFlag.class, "pattern", "");
        assertDefault(BuildFlag.class, "limit", -1);
        assertArrayEquals(new String[0], (String[]) BuildFlag.class.getMethod("group").getDefaultValue());
    }

    @Test
    public void collector_defaults() {
        assertDefault(Collector.class, "singularMethodName", "");
        assertDefault(Collector.class, "singular", false);
        assertDefault(Collector.class, "clearable", false);
        assertDefault(Collector.class, "compute", false);
    }

    @Test
    public void negate_noDefault() throws Exception {
        assertEquals(null, Negate.class.getMethod("value").getDefaultValue());
    }

    @Test
    public void obtainVia_defaults() {
        assertDefault(ObtainVia.class, "method", "");
        assertDefault(ObtainVia.class, "field", "");
        assertDefault(ObtainVia.class, "isStatic", false);
    }

    @Test
    public void accessLevel_keywords() {
        assertEquals("public", AccessLevel.PUBLIC.toKeyword());
        assertEquals("protected", AccessLevel.PROTECTED.toKeyword());
        assertEquals("", AccessLevel.PACKAGE.toKeyword());
        assertEquals("private", AccessLevel.PRIVATE.toKeyword());
    }

    // ------------------------------------------------------------------
    // Fixtures proving each annotation applies at its declared target.
    //
    // @ClassBuilder and companions are CLASS-retained, so they are NOT
    // visible to runtime reflection (that is a deliberate design choice -
    // annotation processing sees them at compile time and they have no
    // runtime purpose). The fact that these fixtures COMPILE is the proof
    // of target compatibility. @BuildFlag is RUNTIME-retained because the
    // validator reads it; we assert its presence reflectively below.
    // ------------------------------------------------------------------

    @ClassBuilder
    static final class FixtureOnClass { }

    static final class FixtureOnConstructor {
        @ClassBuilder(builderName = "CtorBuilder")
        FixtureOnConstructor(String x) {}
    }

    static final class FixtureOnMethod {
        @ClassBuilder(builderName = "MethodBuilder")
        static FixtureOnMethod of(String x) { return new FixtureOnMethod(); }
    }

    static final class FixtureOnFields {
        @BuildRule(flag = @BuildFlag(nonNull = true, notEmpty = true, limit = 10, pattern = "[a-z]+", group = {"g"})) String a;
        @Collector(singular = true, clearable = true) List<String> bs;
        @Collector(singularMethodName = "entry", singular = true) Map<String, String> cs;
        @Negate("disabled") boolean enabled;
        @Formattable String text;
        @BuildRule(retainInit = true) String defaulted = "x";
        @BuildRule(ignore = true) String ignored;
        @BuildRule(obtainVia = @ObtainVia(method = "getCustomAccess")) String custom;
        @BuildRule(obtainVia = @ObtainVia(field = "other")) String redirect;
        @BuildRule(obtainVia = @ObtainVia(method = "stat", isStatic = true)) String staticCall;
        @SuppressWarnings("unused") Optional<String> optionalString;
    }

    @Test
    public void buildRule_flag_visibleAtRuntime_onField() throws Exception {
        Field a = FixtureOnFields.class.getDeclaredField("a");
        BuildRule rule = a.getAnnotation(BuildRule.class);
        assertNotNull("BuildRule is RUNTIME-retained and should be readable", rule);
        BuildFlag flag = rule.flag();
        assertNotNull("BuildFlag nested in BuildRule should be readable", flag);
        assertTrue(flag.nonNull());
        assertTrue(flag.notEmpty());
        assertEquals(10, flag.limit());
        assertEquals("[a-z]+", flag.pattern());
        assertArrayEquals(new String[] {"g"}, flag.group());
    }

    @Test
    public void buildRule_obtainVia_visibleAtRuntime_onField() throws Exception {
        Field custom = FixtureOnFields.class.getDeclaredField("custom");
        BuildRule rule = custom.getAnnotation(BuildRule.class);
        assertNotNull(rule);
        assertEquals("getCustomAccess", rule.obtainVia().method());
    }

    @Test
    public void classRetentionAnnotations_invisibleAtRuntime_asDesigned() {
        // Confirm the CLASS-retention contract: these should NOT be reflectively
        // visible. If they become visible, someone flipped a retention unintentionally.
        assertEquals(0, annotationsByName(FixtureOnClass.class.getAnnotations(), "ClassBuilder"));
        for (Field f : FixtureOnFields.class.getDeclaredFields()) {
            Annotation[] annos = f.getAnnotations();
            assertEquals("Collector should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "Collector"));
            assertEquals("Negate should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "Negate"));
            assertEquals("Formattable should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "Formattable"));
        }
    }

    private static long annotationsByName(Annotation[] annotations, String simpleName) {
        return Arrays.stream(annotations)
            .filter(a -> a.annotationType().getSimpleName().equals(simpleName))
            .count();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void assertRetention(Class<? extends Annotation> annotation, RetentionPolicy expected) {
        Retention r = annotation.getAnnotation(Retention.class);
        assertNotNull(annotation.getSimpleName() + " missing @Retention", r);
        assertEquals(annotation.getSimpleName(), expected, r.value());
    }

    private static void assertTargets(Class<? extends Annotation> annotation, ElementType... expected) {
        Target t = annotation.getAnnotation(Target.class);
        assertNotNull(annotation.getSimpleName() + " missing @Target", t);
        assertEquals(annotation.getSimpleName() + " target set",
            new HashSet<>(Arrays.asList(expected)),
            new HashSet<>(Arrays.asList(t.value())));
    }

    private static void assertDefault(Class<? extends Annotation> annotation, String attr, Object expected) {
        try {
            Method m = annotation.getMethod(attr);
            assertEquals(annotation.getSimpleName() + "#" + attr, expected, m.getDefaultValue());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing attribute " + attr + " on " + annotation, e);
        }
    }

}
