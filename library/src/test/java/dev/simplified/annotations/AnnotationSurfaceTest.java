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

    // The three setter-shaping companions reach a PARAMETER as well as a FIELD,
    // because @ClassBuilder on a constructor or static factory derives its slots
    // from that member's parameters and they are the slots those shapes apply to.

    @Test
    public void collector_metadata() {
        assertRetention(Collector.class, RetentionPolicy.CLASS);
        assertTargets(Collector.class, ElementType.FIELD, ElementType.PARAMETER);
    }

    @Test
    public void negate_metadata() {
        assertRetention(Negate.class, RetentionPolicy.CLASS);
        assertTargets(Negate.class, ElementType.FIELD, ElementType.PARAMETER);
    }

    @Test
    public void formattable_metadata() {
        assertRetention(Formattable.class, RetentionPolicy.CLASS);
        assertTargets(Formattable.class, ElementType.FIELD, ElementType.PARAMETER);
    }

    @Test
    public void builderSeed_metadata() {
        // APT-time only, like the rest of the builder companions - the seed's
        // whole effect is on which members are generated.
        assertRetention(BuilderSeed.class, RetentionPolicy.CLASS);
        // A parameter is the only place a seed can be written: it names a value
        // supplied at the entry point, and only the executable path has one.
        assertTargets(BuilderSeed.class, ElementType.PARAMETER);
    }

    @Test
    public void setterNames_metadata() {
        assertRetention(SetterNames.class, RetentionPolicy.CLASS);
        // Written on a slot it overrides the target's patterns for that one
        // field, component or parameter. Being usable as @ClassBuilder's
        // attribute value costs nothing here - @Target restricts declaration
        // sites, and an annotation used as another's element value is not one.
        assertTargets(SetterNames.class, ElementType.FIELD, ElementType.PARAMETER);
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

    @Test
    public void assignVia_metadata() {
        // The write-direction twin of @ObtainVia, and consumed at the same
        // point - the processor emitting the setter - so CLASS retention too.
        assertRetention(AssignVia.class, RetentionPolicy.CLASS);
        // Reaches a PARAMETER as the three setter-shaping companions do,
        // shaping a constructor or factory slot exactly as it shapes a field.
        assertTargets(AssignVia.class, ElementType.FIELD, ElementType.PARAMETER);
    }

    @Test
    public void assignVia_isRepeatable() {
        Repeatable repeatable = AssignVia.class.getAnnotation(Repeatable.class);
        assertNotNull("@AssignVia has to repeat - one slot can take several coercions",
            repeatable);
        assertEquals(AssignVia.List.class, repeatable.value());
        assertRetention(AssignVia.List.class, RetentionPolicy.CLASS);
        assertTargets(AssignVia.List.class, ElementType.FIELD, ElementType.PARAMETER);
    }

    @Test
    public void assignVia_noDefault() throws Exception {
        // The method is the whole annotation, so leaving it out is a compile
        // error rather than an annotation that quietly does nothing.
        assertEquals(null, AssignVia.class.getMethod("method").getDefaultValue());
    }

    @Test
    public void equalsAndHashCode_metadata() {
        // Read while the processor mutates the target's AST, so nothing needs
        // it after the compile.
        assertRetention(EqualsAndHashCode.class, RetentionPolicy.CLASS);
        assertTargets(EqualsAndHashCode.class, ElementType.TYPE);
    }

    @Test
    public void toString_metadata() {
        assertRetention(ToString.class, RetentionPolicy.CLASS);
        assertTargets(ToString.class, ElementType.TYPE);
    }

    /**
     * All four selection markers carry one target set on purpose. METHOD is
     * what lets a derived value join the selection, and a marker legal on a
     * field but not on a method would let the two halves of one selection
     * disagree about which members a type is made of.
     */
    @Test
    public void selectionMarkers_metadata() {
        List<Class<? extends Annotation>> markers = List.of(
            EqualsExclude.class, EqualsInclude.class, ToStringExclude.class, ToStringInclude.class);
        for (Class<? extends Annotation> marker : markers) {
            assertRetention(marker, RetentionPolicy.CLASS);
            assertTargets(marker, ElementType.FIELD, ElementType.METHOD);
        }
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
        // Empty means "retain the initializer", which is what a field has and a
        // record component does not - naming a provider is the opt-in.
        assertDefault(BuilderDefault.class, "provider", "");
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
        // An infinity is the disabled state, so every finite value a numeric
        // field can hold is inside the range until one end is written.
        assertDefault(BuildFlag.class, "min", Double.NEGATIVE_INFINITY);
        assertDefault(BuildFlag.class, "max", Double.POSITIVE_INFINITY);
        assertArrayEquals(new String[0], (String[]) BuildFlag.class.getMethod("group").getDefaultValue());
    }

    @Test
    public void collector_defaults() {
        assertDefault(Collector.class, "singularMethodName", "");
        assertDefault(Collector.class, "singular", false);
        assertDefault(Collector.class, "clearable", false);
        assertDefault(Collector.class, "compute", false);
        // Replace is what a setter normally means, so accumulating is opt-in.
        assertDefault(Collector.class, "append", false);
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
    public void equalsAndHashCode_defaults() throws Exception {
        // EXACT_CLASS rather than Lombok's unconditional instanceof: it is the
        // only one of the three that is a valid equivalence relation without
        // cooperation from every subclass.
        assertDefault(EqualsAndHashCode.class, "identity", EqualsAndHashCode.Identity.EXACT_CLASS);
        // AUTO rather than a flat NO, which drops inherited state silently.
        assertDefault(EqualsAndHashCode.class, "callSuper", CallSuper.AUTO);
        // Memoizing is sound only while every compared member is immutable, so
        // it stays opt-in.
        assertDefault(EqualsAndHashCode.class, "cacheHashCode", false);
        // Inverts Lombok: a direct field read cannot be intercepted by an
        // overridden accessor, and it keeps the emitted pair independent of
        // whether an accessor generator ran first.
        assertDefault(EqualsAndHashCode.class, "useAccessors", false);
        assertDefault(EqualsAndHashCode.class, "emitContracts", true);
        assertDefault(EqualsAndHashCode.class, "emitGenerated", true);
        assertArrayEquals(new String[0], (String[]) EqualsAndHashCode.class.getMethod("of").getDefaultValue());
        assertArrayEquals(new String[0], (String[]) EqualsAndHashCode.class.getMethod("exclude").getDefaultValue());
    }

    @Test
    public void toString_defaults() throws Exception {
        assertDefault(ToString.class, "callSuper", CallSuper.AUTO);
        assertDefault(ToString.class, "includeFieldNames", true);
        // SIMPLIFIED is the shape a record already prints, so one library does
        // not ship two.
        assertDefault(ToString.class, "style", ToString.Style.SIMPLIFIED);
        assertDefault(ToString.class, "useAccessors", false);
        assertDefault(ToString.class, "emitContracts", true);
        assertDefault(ToString.class, "emitGenerated", true);
        assertArrayEquals(new String[0], (String[]) ToString.class.getMethod("of").getDefaultValue());
        assertArrayEquals(new String[0], (String[]) ToString.class.getMethod("exclude").getDefaultValue());
    }

    /**
     * The two annotations must default identically wherever they share an
     * attribute name. One component resolves both selections, so a type whose
     * compared state and printed state disagreed would do so for a reason
     * nothing in its source shows.
     */
    @Test
    public void equalityAndToString_shareTheirCommonDefaults() {
        for (String attr : List.of("callSuper", "useAccessors", "emitContracts", "emitGenerated")) {
            assertEquals("shared attribute " + attr,
                defaultOf(EqualsAndHashCode.class, attr), defaultOf(ToString.class, attr));
        }
    }

    @Test
    public void toStringInclude_defaults() {
        // An empty name keeps the member's own, so the annotation can be
        // written for rank alone and vice versa.
        assertDefault(ToStringInclude.class, "name", "");
        assertDefault(ToStringInclude.class, "rank", 0);
    }

    /**
     * Three of the four markers take no attributes, and {@code @EqualsInclude}
     * is the one worth stating: it deliberately carries no rank, since ordering
     * the compared terms would make an emitted hash depend on a rule invisible
     * in the source, where the same attribute on its printing counterpart only
     * reorders output.
     */
    @Test
    public void selectionMarkers_carryNoAttributes() {
        assertEquals("@EqualsExclude takes no attributes",
            0, EqualsExclude.class.getDeclaredMethods().length);
        assertEquals("@EqualsInclude takes no attributes",
            0, EqualsInclude.class.getDeclaredMethods().length);
        assertEquals("@ToStringExclude takes no attributes",
            0, ToStringExclude.class.getDeclaredMethods().length);
    }

    @Test
    public void accessLevel_keywords() {
        assertEquals("public", AccessLevel.PUBLIC.toKeyword());
        assertEquals("protected", AccessLevel.PROTECTED.toKeyword());
        assertEquals("", AccessLevel.PACKAGE.toKeyword());
        assertEquals("private", AccessLevel.PRIVATE.toKeyword());
    }

    /**
     * Pins the constant set and its order together. The set is published
     * surface - dropping or renaming a constant breaks every consumer naming it
     * - and holding the order as well means an insertion has to be a deliberate
     * edit rather than something that slides in between two existing constants.
     */
    @Test
    public void enumConstants_areStable() {
        assertConstants(EqualsAndHashCode.Identity.class,
            "EXACT_CLASS", "INSTANCE_OF", "INSTANCE_OF_CANEQUAL");
        assertConstants(ToString.Style.class, "SIMPLIFIED", "LOMBOK");
        assertConstants(CallSuper.class, "AUTO", "YES", "NO");
    }

    /**
     * {@link CallSuper} is top level rather than nested in either annotation.
     * Both face the identical question, and a type whose equality counted an
     * inherited field while its printed form hid one would read as a bug in
     * whichever member was looked at second.
     */
    @Test
    public void callSuper_isSharedByBothAnnotations() throws Exception {
        assertNull("CallSuper must stay top level so neither annotation owns it",
            CallSuper.class.getEnclosingClass());
        assertEquals(CallSuper.class, EqualsAndHashCode.class.getMethod("callSuper").getReturnType());
        assertEquals(CallSuper.class, ToString.class.getMethod("callSuper").getReturnType());
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

    /**
     * The equality and printing surface at every target it declares, with both
     * type-level annotations carrying a non-default value for each attribute
     * that has one. Compiling is the proof: none of it survives to runtime.
     */
    @EqualsAndHashCode(
        identity = EqualsAndHashCode.Identity.INSTANCE_OF_CANEQUAL,
        callSuper = CallSuper.NO,
        exclude = "loadedAt",
        cacheHashCode = true,
        useAccessors = true,
        emitContracts = false,
        emitGenerated = false)
    @ToString(
        callSuper = CallSuper.NO,
        includeFieldNames = false,
        style = ToString.Style.LOMBOK,
        of = {"name", "swatches"},
        useAccessors = true,
        emitContracts = false,
        emitGenerated = false)
    static class FixtureOnEqualityTarget {
        String name;
        byte[] swatches;
        @EqualsExclude @ToStringExclude long loadedAt;
        // The include markers override the transient skip, which is the one
        // place the two selections deliberately start from different sets.
        @EqualsInclude @ToStringInclude(name = "id", rank = 10) transient String key;

        @EqualsInclude @ToStringInclude String derived() { return name + key; }
    }

    /**
     * A record is a legal target for both, which is the shape that needs them
     * most - an implicit {@code equals} compares an array component by
     * reference.
     *
     * @param name a reference component, compared through {@code Objects.equals}
     * @param swatches an array component, the one an implicit {@code equals} gets wrong
     */
    @EqualsAndHashCode
    @ToString
    record FixtureOnEqualityRecord(String name, byte[] swatches) { }

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

    /**
     * The same guard for the equality and printing surface, which is
     * CLASS-retained end to end. Nothing reads any of the six after the
     * compile, so a RUNTIME retention would drag them into every consumer class
     * file that carried one for no reader at all.
     */
    @Test
    public void equalitySurface_invisibleAtRuntime_asDesigned() throws Exception {
        Annotation[] onType = FixtureOnEqualityTarget.class.getAnnotations();
        assertEquals("EqualsAndHashCode should be invisible at runtime",
            0, annotationsByName(onType, "EqualsAndHashCode"));
        assertEquals("ToString should be invisible at runtime",
            0, annotationsByName(onType, "ToString"));

        Annotation[] onExcluded = FixtureOnEqualityTarget.class.getDeclaredField("loadedAt").getAnnotations();
        assertEquals("EqualsExclude should be invisible at runtime",
            0, annotationsByName(onExcluded, "EqualsExclude"));
        assertEquals("ToStringExclude should be invisible at runtime",
            0, annotationsByName(onExcluded, "ToStringExclude"));

        Annotation[] onMethod = FixtureOnEqualityTarget.class.getDeclaredMethod("derived").getAnnotations();
        assertEquals("EqualsInclude should be invisible at runtime",
            0, annotationsByName(onMethod, "EqualsInclude"));
        assertEquals("ToStringInclude should be invisible at runtime",
            0, annotationsByName(onMethod, "ToStringInclude"));
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
        assertEquals(annotation.getSimpleName() + "#" + attr, expected, defaultOf(annotation, attr));
    }

    private static Object defaultOf(Class<? extends Annotation> annotation, String attr) {
        try {
            Method m = annotation.getMethod(attr);
            return m.getDefaultValue();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing attribute " + attr + " on " + annotation, e);
        }
    }

    private static void assertConstants(Class<? extends Enum<?>> type, String... expected) {
        List<String> actual = new ArrayList<>();
        for (Enum<?> constant : type.getEnumConstants()) actual.add(constant.name());
        assertEquals(type.getSimpleName() + " constants", Arrays.asList(expected), actual);
    }

}
