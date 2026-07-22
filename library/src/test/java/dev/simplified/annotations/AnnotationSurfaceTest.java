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
    public void builderDefault_metadata() {
        // APT-time only, so CLASS retention is enough - nothing reads it at runtime.
        assertRetention(BuilderDefault.class, RetentionPolicy.CLASS);
        assertTargets(BuilderDefault.class, ElementType.FIELD);
    }

    @Test
    public void builderIgnore_metadata() {
        assertRetention(BuilderIgnore.class, RetentionPolicy.CLASS);
        assertTargets(BuilderIgnore.class, ElementType.FIELD);
    }

    @Test
    public void buildFlag_metadata() {
        // The one annotation of the four that must survive to runtime:
        // BuildFlagValidator reads it reflectively inside the generated build().
        assertRetention(BuildFlag.class, RetentionPolicy.RUNTIME);
        // METHOD is for interface targets, which declare no fields to carry a
        // constraint - the processor copies it onto the generated Impl field.
        assertTargets(BuildFlag.class, ElementType.FIELD, ElementType.METHOD);
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
    public void setterNames_metadata() {
        // Only ever an attribute value on @ClassBuilder, so an empty @Target is
        // what stops it being written anywhere else.
        assertRetention(SetterNames.class, RetentionPolicy.CLASS);
        assertTargets(SetterNames.class);
    }

    @Test
    public void builderNames_metadata() {
        assertRetention(BuilderNames.class, RetentionPolicy.CLASS);
        assertTargets(BuilderNames.class);
    }

    /**
     * The two suppression sentinels must agree, since a scheme resolves both
     * groups through the same emptiness test.
     */
    @Test
    public void namingSentinels_areShared() {
        assertEquals(SetterNames.INHERIT, BuilderNames.INHERIT);
        assertEquals(SetterNames.NONE, BuilderNames.NONE);
        assertEquals("INHERIT must be the empty string so an unwritten attribute inherits",
            "", SetterNames.INHERIT);
        assertFalse("NONE must not be a legal identifier, or it could collide with a real name",
            isJavaIdentifier(SetterNames.NONE));
    }

    /**
     * Every name of every style must expand to something usable. The per-field
     * setters need the placeholder - without it every field would generate the
     * same method - while the once-per-target names default to plain literals.
     * A typo in the style table would otherwise surface as a compile error in
     * consumer code.
     */
    @Test
    public void namingStyle_everyPatternIsWellFormed() {
        for (NamingStyle style : NamingStyle.values()) {
            assertPattern(style, "set", style.set(), true);
            assertPattern(style, "flag", style.flag(), true);
            assertPattern(style, "add", style.add(), true);
            assertPattern(style, "put", style.put(), true);
            assertPattern(style, "compute", style.compute(), true);
            assertPattern(style, "clear", style.clear(), true);
            assertPattern(style, "builderType", style.builderType(), false);
            assertPattern(style, "builderMethod", style.builderMethod(), false);
            assertPattern(style, "buildMethod", style.buildMethod(), false);
            assertPattern(style, "fromMethod", style.fromMethod(), false);
            assertPattern(style, "toBuilderMethod", style.toBuilderMethod(), false);
            assertNotEquals(style + " must be able to assign a field",
                SetterNames.NONE, style.set());
            // A style cannot ship a builder with no class to name or no way to
            // finish, the two names @BuilderNames also refuses to suppress.
            assertNotEquals(style + " must name its builder class",
                SetterNames.NONE, style.builderType());
            assertNotEquals(style + " must name its build method",
                SetterNames.NONE, style.buildMethod());
        }
    }

    private static void assertPattern(NamingStyle style, String role, String pattern, boolean placeholderRequired) {
        if (SetterNames.NONE.equals(pattern)) return;
        String subject = "sample";
        String expanded = placeholderRequired || pattern.contains("{}")
            ? pattern.replace("{}", pattern.startsWith("{}") ? subject : "Sample")
            : pattern;
        assertTrue(style + "." + role + " must expand to a Java identifier, got '" + expanded + "'",
            isJavaIdentifier(expanded));
    }

    private static boolean isJavaIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    @Test
    public void obtainVia_metadata() {
        // Consumed by the processor when emitting from(T) / mutate(), so CLASS
        // retention suffices.
        assertRetention(ObtainVia.class, RetentionPolicy.CLASS);
        assertTargets(ObtainVia.class, ElementType.FIELD);
    }

    // ------------------------------------------------------------------
    // Default values (pin down so accidental renames/changes break tests)
    // ------------------------------------------------------------------

    @Test
    public void classBuilder_defaults() throws Exception {
        // Every generated name now comes from the style, so it is the one
        // naming default worth pinning here; the per-name defaults are the
        // style table, asserted by namingStyle_everyPatternIsWellFormed.
        assertDefault(ClassBuilder.class, "style", NamingStyle.SIMPLIFIED);
        assertDefault(ClassBuilder.class, "access", AccessLevel.PUBLIC);
        assertDefault(ClassBuilder.class, "constructorAccess", AccessLevel.PACKAGE);
        // Retain-all is the default: a field written `String x = "v"` keeps "v"
        // as its builder default without any per-field annotation.
        assertDefault(ClassBuilder.class, "retainInit", true);
        assertDefault(ClassBuilder.class, "validate", true);
        assertDefault(ClassBuilder.class, "emitContracts", true);
        assertDefault(ClassBuilder.class, "generateImpl", true);
        assertDefault(ClassBuilder.class, "factoryMethod", "");
        assertArrayEquals(new String[0], (String[]) ClassBuilder.class.getMethod("exclude").getDefaultValue());
    }

    @Test
    public void builderDefault_defaults() {
        // Bare @BuilderDefault means "retain", so the opt-out has to be written
        // explicitly as @BuilderDefault(false).
        assertDefault(BuilderDefault.class, "value", true);
    }

    @Test
    public void builderIgnore_isAMarker() {
        assertEquals("@BuilderIgnore takes no attributes",
            0, BuilderIgnore.class.getDeclaredMethods().length);
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
        @ClassBuilder(builder = @BuilderNames(type = "CtorBuilder"))
        FixtureOnConstructor(String x) {}
    }

    static final class FixtureOnMethod {
        @ClassBuilder(builder = @BuilderNames(type = "MethodBuilder"))
        static FixtureOnMethod of(String x) { return new FixtureOnMethod(); }
    }

    static final class FixtureOnFields {
        @BuildFlag(nonNull = true, notEmpty = true, limit = 10, pattern = "[a-z]+", group = {"g"}) String a;
        @Collector(singular = true, clearable = true) List<String> bs;
        @Collector(singularMethodName = "entry", singular = true) Map<String, String> cs;
        @Negate("disabled") boolean enabled;
        @Formattable String text;
        @BuilderDefault(false) String notDefaulted = "x";
        @BuilderIgnore String ignored;
        @ObtainVia(method = "getCustomAccess") String custom;
        @ObtainVia(field = "other") String redirect;
        @ObtainVia(method = "stat", isStatic = true) String staticCall;
        @SuppressWarnings("unused") Optional<String> optionalString;
    }

    /**
     * The interface-target surface: an accessor is the only place a constraint
     * can be written when the type declares no fields. That this compiles is
     * the target-compatibility proof; the copy onto the generated Impl field is
     * covered by the processor's own tests.
     *
     * <p>Deliberately not {@code @ClassBuilder}-annotated. The processor does
     * not run over this source set, but were it ever wired up it would try to
     * emit a top-level {@code FixtureOnAccessorsImpl implements
     * FixtureOnAccessors} - which does not resolve for a nested interface.
     */
    interface FixtureOnAccessors {
        @BuildFlag(nonNull = true, notEmpty = true) String name();
        @BuildFlag(limit = 25) List<String> tags();
    }

    @Test
    public void buildFlag_visibleAtRuntime_onAccessor() throws Exception {
        BuildFlag flag = FixtureOnAccessors.class.getDeclaredMethod("name").getAnnotation(BuildFlag.class);
        assertNotNull("BuildFlag on an accessor should be readable", flag);
        assertTrue(flag.nonNull());
        assertTrue(flag.notEmpty());
    }

    @Test
    public void buildFlag_visibleAtRuntime_onField() throws Exception {
        Field a = FixtureOnFields.class.getDeclaredField("a");
        BuildFlag flag = a.getAnnotation(BuildFlag.class);
        assertNotNull("BuildFlag is RUNTIME-retained and should be readable", flag);
        assertTrue(flag.nonNull());
        assertTrue(flag.notEmpty());
        assertEquals(10, flag.limit());
        assertEquals("[a-z]+", flag.pattern());
        assertArrayEquals(new String[] {"g"}, flag.group());
    }

    /**
     * Guards the reason the four field annotations were split apart: only
     * {@code @BuildFlag} needs to reach runtime, and the other three must not be
     * dragged into consumer class files with it. Before the split they shared a
     * single RUNTIME-retained parent and all four were reflectively visible.
     */
    @Test
    public void classRetentionAnnotations_invisibleAtRuntime_asDesigned() {
        assertEquals(0, annotationsByName(FixtureOnClass.class.getAnnotations(), "ClassBuilder"));
        for (Field f : FixtureOnFields.class.getDeclaredFields()) {
            Annotation[] annos = f.getAnnotations();
            assertEquals("Collector should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "Collector"));
            assertEquals("Negate should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "Negate"));
            assertEquals("Formattable should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "Formattable"));
            assertEquals("BuilderDefault should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "BuilderDefault"));
            assertEquals("BuilderIgnore should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "BuilderIgnore"));
            assertEquals("ObtainVia should be invisible at runtime on " + f.getName(),
                0, annotationsByName(annos, "ObtainVia"));
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
