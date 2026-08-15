package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import dev.simplified.classbuilder.validate.BuilderValidationException;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies the {@code @ClassBuilder} configuration attributes that drive
 * method naming, accessibility, and opt-out gates. Each attribute gets a
 * dedicated round-trip so a regression flags the exact attribute that broke.
 */
public class BuilderConfigAttributesTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-attrs-test");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = f.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            String rel = anchor >= 0 ? uri.substring(anchor + "CLASS_OUTPUT/".length()) : f.getName();
            Path out = tmp.resolve(rel);
            Files.createDirectories(out.getParent());
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                Files.write(out, baos.toByteArray());
            }
        }
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, BuilderConfigAttributesTest.class.getClassLoader());
    }

    /**
     * Whether any generated class file mentions {@code needle} in its constant
     * pool. Used to prove a reference is *absent* - which reflection cannot
     * show, a skipped call leaving nothing to look up.
     *
     * @param compilation the finished compilation
     * @param needle the constant-pool text to search for
     * @return whether any generated class file contains it
     */
    private static boolean anyClassFileMentions(Compilation compilation, String needle) throws Exception {
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                if (baos.toString(StandardCharsets.ISO_8859_1).contains(needle)) return true;
            }
        }
        return false;
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested '" + simpleName + "' on " + outer);
        return null;
    }

    private static boolean hasMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            cls.getDeclaredMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // style / setters / builder - every generated name comes from the scheme
    // ------------------------------------------------------------------

    @Test
    public void setterNames_setPatternAppliedToSetters() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Cfg",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.SetterNames;",
            "@ClassBuilder(setters = @SetterNames(set = \"set{}\"), validate = false)",
            "public class Cfg {",
            "    String name;",
            "    public Cfg(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> cfg = Class.forName("demo.Cfg", true, cl);
        Class<?> builder = nested(cfg, "Builder");

        assertTrue("setter must use the configured 'set' pattern",
            hasMethod(builder, "setName", String.class));
        assertFalse("bare-name setter must not leak through",
            hasMethod(builder, "name", String.class));
    }

    @Test
    public void setterNames_suffixPatternIsExpressible() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Suffixed",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.SetterNames;",
            "@ClassBuilder(setters = @SetterNames(set = \"{}Value\"), validate = false)",
            "public class Suffixed {",
            "    String name;",
            "    public Suffixed(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> builder = nested(Class.forName("demo.Suffixed", true, cl), "Builder");

        // A placeholder in the leading position leaves the subject uncapitalised,
        // which is what makes a suffix pattern read as a fluent setter.
        assertTrue(hasMethod(builder, "nameValue", String.class));
    }

    /**
     * The core of the boolean-naming fix: the typed setter is the ordinary
     * {@code set} role, so a boolean is named like every other field, while the
     * zero-arg convenience stays on the separate {@code flag} role.
     */
    @Test
    public void booleanSetter_typedUsesSetRoleAndZeroArgUsesFlagRole() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Sprite",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Sprite {",
            "    boolean animated;",
            "    public Sprite(boolean animated) { this.animated = animated; }",
            "    public boolean isAnimated() { return animated; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> builder = nested(Class.forName("demo.Sprite", true, cl), "Builder");

        assertTrue("typed boolean setter takes the bare field name",
            hasMethod(builder, "animated", boolean.class));
        assertTrue("zero-arg convenience keeps the is-prefixed flag name",
            hasMethod(builder, "isAnimated"));
        assertFalse("the is-prefixed typed overload is gone",
            hasMethod(builder, "isAnimated", boolean.class));
    }

    @Test
    public void namingStyle_lombokMatchesBuilderSurface() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Card",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import dev.simplified.annotations.NamingStyle;",
            "import java.util.List;",
            "@ClassBuilder(style = NamingStyle.LOMBOK, validate = false)",
            "public class Card {",
            "    boolean shiny;",
            "    @Collector(singular = true, clearable = true) List<String> tags;",
            "    public Card(boolean shiny, List<String> tags) { this.shiny = shiny; this.tags = tags; }",
            "    public boolean isShiny() { return shiny; }",
            "    public List<String> getTags() { return tags; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> card = Class.forName("demo.Card", true, cl);
        Class<?> builder = nested(card, "CardBuilder");

        assertEquals("builder type takes the style's pattern", "CardBuilder", builder.getSimpleName());
        assertTrue("bare-name boolean setter", hasMethod(builder, "shiny", boolean.class));
        assertFalse("no zero-arg boolean form under LOMBOK", hasMethod(builder, "isShiny"));
        assertTrue("singular add takes the bare singular", hasMethod(builder, "tag", String.class));
        assertFalse("addX is the SIMPLIFIED name, not Lombok's", hasMethod(builder, "addTag", String.class));
        assertTrue("clear keeps its name across styles", hasMethod(builder, "clearTags"));
        assertTrue("toBuilder name follows the style", hasMethod(card, "toBuilder"));
        assertFalse("mutate is the SIMPLIFIED name", hasMethod(card, "mutate"));
    }

    @Test
    public void namingStyle_explicitNameBeatsTheStyle() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Token",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.NamingStyle;",
            "import dev.simplified.annotations.BuilderNames;",
            "@ClassBuilder(style = NamingStyle.LOMBOK, builder = @BuilderNames(toBuilder = \"respawn\"), validate = false)",
            "public class Token {",
            "    String id;",
            "    public Token(String id) { this.id = id; }",
            "    public String getId() { return id; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> token = Class.forName("demo.Token", true, cl);

        assertTrue(hasMethod(token, "respawn"));
        assertFalse(hasMethod(token, "toBuilder"));
        // The rest of the style still applies.
        assertNotNull(nested(token, "TokenBuilder"));
    }

    @Test
    public void setterNames_noneDropsTheZeroArgSetter() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Switch",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.SetterNames;",
            "@ClassBuilder(setters = @SetterNames(flag = SetterNames.NONE), validate = false)",
            "public class Switch {",
            "    boolean on;",
            "    public Switch(boolean on) { this.on = on; }",
            "    public boolean isOn() { return on; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> builder = nested(Class.forName("demo.Switch", true, cl), "Builder");

        assertTrue(hasMethod(builder, "on", boolean.class));
        assertFalse(hasMethod(builder, "isOn"));
    }

    @Test
    public void setterNames_malformedPatternIsRejectedAtTheAnnotation() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Broken",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.SetterNames;",
            "@ClassBuilder(setters = @SetterNames(set = \"set\"), validate = false)",
            "public class Broken {",
            "    String name;",
            "    public Broken(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("placeholder");
    }

    @Test
    public void setterNames_suppressedSetRoleIsRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoSetter",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.SetterNames;",
            "@ClassBuilder(setters = @SetterNames(set = SetterNames.NONE), validate = false)",
            "public class NoSetter {",
            "    String name;",
            "    public NoSetter(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot suppress the 'set' role");
    }

    /**
     * The once-per-target names are the group the placeholder is optional for,
     * every default being a plain literal - so a name without one is correct
     * here where the same text would be rejected on a setter role.
     */
    @Test
    public void builderNames_plainLiteralIsAccepted() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.BuilderNames;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(builder = @BuilderNames(type = \"Assembler\", builder = \"assemble\"), validate = false)",
            "public class Widget {",
            "    String name;",
            "    public Widget(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> widget = Class.forName("demo.Widget", true, cl);

        assertNotNull(nested(widget, "Assembler"));
        assertTrue(hasMethod(widget, "assemble"));
        assertFalse(hasMethod(widget, "builder"));
    }

    @Test
    public void builderNames_suppressedTypeIsRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoType",
            "package demo;",
            "import dev.simplified.annotations.BuilderNames;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(builder = @BuilderNames(type = BuilderNames.NONE), validate = false)",
            "public class NoType {",
            "    String name;",
            "    public NoType(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot suppress 'type'");
    }

    @Test
    public void builderNames_suppressedBuildIsRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoBuild",
            "package demo;",
            "import dev.simplified.annotations.BuilderNames;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(builder = @BuilderNames(build = BuilderNames.NONE), validate = false)",
            "public class NoBuild {",
            "    String name;",
            "    public NoBuild(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot suppress 'build'");
    }

    // ------------------------------------------------------------------
    // BuilderNames.build - the terminal method should use the configured name
    // ------------------------------------------------------------------

    @Test
    public void builderNames_buildRenamesTheTerminalMethod() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Part",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.BuilderNames;",
            "@ClassBuilder(builder = @BuilderNames(build = \"make\"), validate = false)",
            "public class Part {",
            "    int id;",
            "    public Part(int id) { this.id = id; }",
            "    public int getId() { return id; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> part = Class.forName("demo.Part", true, cl);
        Class<?> builder = nested(part, "Builder");

        assertTrue("terminal method must be named 'make'", hasMethod(builder, "make"));
        assertFalse("default 'build' must not be generated when overridden",
            hasMethod(builder, "build"));
    }

    // ------------------------------------------------------------------
    // access - Builder class + bootstrap methods respect access level
    // ------------------------------------------------------------------

    @Test
    public void access_packagePrivateLimitsVisibility() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Box",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.AccessLevel;",
            "@ClassBuilder(access = AccessLevel.PACKAGE, validate = false)",
            "public class Box {",
            "    String tag;",
            "    public Box(String tag) { this.tag = tag; }",
            "    public String getTag() { return tag; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> box = Class.forName("demo.Box", true, cl);
        Class<?> builder = nested(box, "Builder");

        // PACKAGE access = no public/protected/private modifier bits set
        int builderMods = builder.getModifiers();
        assertFalse("Builder class must not be public under access=PACKAGE",
            Modifier.isPublic(builderMods));
        assertFalse("Builder class must not be protected under access=PACKAGE",
            Modifier.isProtected(builderMods));
        assertFalse("Builder class must not be private under access=PACKAGE",
            Modifier.isPrivate(builderMods));

        Method builderMethod = box.getDeclaredMethod("builder");
        int methodMods = builderMethod.getModifiers();
        assertFalse("builder() must not be public under access=PACKAGE",
            Modifier.isPublic(methodMods));
    }

    // ------------------------------------------------------------------
    // validate - the constraints the generated build() actually enforces
    // ------------------------------------------------------------------

    private static final String VALIDATOR = "dev/simplified/classbuilder/validate/BuildFlagValidator";

    /**
     * Builds and returns the {@link BuilderValidationException} the generated
     * {@code build()} threw, failing the test when it built successfully.
     *
     * @param builder the generated builder class
     * @param instance a builder instance ready to build
     * @return the rejection
     */
    private static BuilderValidationException buildRejected(Class<?> builder, Object instance) throws Exception {
        try {
            builder.getMethod("build").invoke(instance);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof BuilderValidationException rejection) return rejection;
            throw new AssertionError("build() failed for something other than validation", e.getCause());
        }
        fail("expected build() to reject the instance");
        return null;
    }

    /**
     * Nothing is enforced on a target no {@code @BuildFlag} reaches: the
     * validator caches an empty field list for its runtime class and returns.
     */
    @Test
    public void validate_flagFreeTargetEnforcesNothing() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Loose",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Loose {",
            "    String name;",
            "    public Loose(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> loose = Class.forName("demo.Loose", true, cl);
        Class<?> builder = nested(loose, "Builder");

        Object built = builder.getMethod("build").invoke(loose.getMethod("builder").invoke(null));
        assertNull("an unflagged field is left exactly as the builder set it",
            loose.getMethod("getName").invoke(built));
    }

    @Test
    public void validate_flaggedFieldRejectsTheBuild() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Strict",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Strict {",
            "    @BuildFlag(nonNull = true) String name;",
            "    public Strict(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> strict = Class.forName("demo.Strict", true, cl);
        Class<?> builder = nested(strict, "Builder");

        BuilderValidationException rejection =
            buildRejected(builder, strict.getMethod("builder").invoke(null));
        assertTrue("the message must name the offending field, got: " + rejection.getMessage(),
            rejection.getMessage().contains("'name'"));

        Object ok = builder.getMethod("build").invoke(
            builder.getMethod("name", String.class).invoke(strict.getMethod("builder").invoke(null), "n"));
        assertEquals("a satisfied constraint builds normally", "n", strict.getMethod("getName").invoke(ok));
    }

    /**
     * A numeric range is a constraint on its own, so a target whose only
     * {@code @BuildFlag} is a bound still gets the validator call in
     * {@code build()} - the case a range added without the emission rule
     * knowing about it would fail silently.
     */
    @Test
    public void validate_numericRangeRejectsTheBuild() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bounded",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Bounded {",
            "    @BuildFlag(min = 0, max = 100) int nearLossless;",
            "    public Bounded(int nearLossless) { this.nearLossless = nearLossless; }",
            "    public int getNearLossless() { return nearLossless; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> bounded = Class.forName("demo.Bounded", true, cl);
        Class<?> builder = nested(bounded, "Builder");

        Object over = builder.getMethod("nearLossless", int.class)
            .invoke(bounded.getMethod("builder").invoke(null), 101);
        BuilderValidationException rejection = buildRejected(builder, over);
        assertEquals("Field 'nearLossless' in 'Bounded' is 101, above the maximum of 100",
            rejection.getMessage());

        Object ok = builder.getMethod("build").invoke(
            builder.getMethod("nearLossless", int.class)
                .invoke(bounded.getMethod("builder").invoke(null), 100));
        assertEquals(100, bounded.getMethod("getNearLossless").invoke(ok));
    }

    /**
     * The validator walks the superclass chain, so a flag on an unannotated
     * parent is enforced on the child being built.
     */
    @Test
    public void validate_inheritedFlaggedFieldRejectsTheBuild() throws Exception {
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "public class Base {",
            "    @BuildFlag(nonNull = true) protected String id;",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Child",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Child extends Base {",
            "    String label;",
            "    public Child(String label) { this.label = label; }",
            "    public String getLabel() { return label; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> childClass = Class.forName("demo.Child", true, cl);
        Class<?> builder = nested(childClass, "Builder");

        BuilderValidationException rejection =
            buildRejected(builder, childClass.getMethod("builder").invoke(null));
        assertTrue("the inherited constraint must be the one that fired, got: " + rejection.getMessage(),
            rejection.getMessage().contains("'id'"));
    }

    /**
     * A {@code @BuilderIgnore}d field is invisible to the builder but not to
     * the validator, which reads every declared field off the built instance.
     */
    @Test
    public void validate_ignoredFlaggedFieldRejectsTheBuild() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Hidden",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "import dev.simplified.annotations.BuilderIgnore;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder",
            "public class Hidden {",
            "    String label;",
            "    @BuilderIgnore @BuildFlag(nonNull = true) String secret;",
            "    public Hidden(String label) { this.label = label; }",
            "    public String getLabel() { return label; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> hidden = Class.forName("demo.Hidden", true, cl);
        Class<?> builder = nested(hidden, "Builder");

        assertFalse("the ignored field must stay off the builder",
            hasMethod(builder, "secret", String.class));
        BuilderValidationException rejection =
            buildRejected(builder, hidden.getMethod("builder").invoke(null));
        assertTrue("a builder-invisible field is still validated, got: " + rejection.getMessage(),
            rejection.getMessage().contains("'secret'"));
    }

    /**
     * The payoff for deciding at runtime: a factory may hand back a subtype
     * carrying constraints of its own, which no annotation-processing-time
     * analysis of the declared type could have seen.
     */
    @Test
    public void validate_factorySubtypeFlagRejectsTheBuild() throws Exception {
        JavaFileObject target = JavaFileObjects.forSourceLines("demo.Made",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(factoryMethod = \"of\")",
            "public class Made {",
            "    String name;",
            "    protected Made(String name) { this.name = name; }",
            "    public static Made of(String name) { return new Special(name); }",
            "    public String getName() { return name; }",
            "}");
        JavaFileObject subtype = JavaFileObjects.forSourceLines("demo.Special",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "public class Special extends Made {",
            "    @BuildFlag(nonNull = true) String extra;",
            "    Special(String name) { super(name); }",
            "}");
        Compilation c = compile(target, subtype);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> made = Class.forName("demo.Made", true, cl);
        Class<?> builder = nested(made, "Builder");

        BuilderValidationException rejection =
            buildRejected(builder, made.getMethod("builder").invoke(null));
        assertTrue("the subtype's own constraint must fire, got: " + rejection.getMessage(),
            rejection.getMessage().contains("'extra'"));
        assertTrue("and it must be reported against the runtime class, got: " + rejection.getMessage(),
            rejection.getMessage().contains("'Special'"));
    }

    /**
     * The one case where nothing is emitted, so the claim is about the
     * generated bytecode rather than about behaviour - a call that is not
     * there leaves nothing to invoke.
     */
    @Test
    public void validate_falseSkipsEvenWithAFlaggedField() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.OptedOut",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class OptedOut {",
            "    @BuildFlag(nonNull = true) String name;",
            "    public OptedOut(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertFalse("an explicit opt-out still wins", anyClassFileMentions(c, VALIDATOR));

        ClassLoader cl = loadClasses(c);
        Class<?> optedOut = Class.forName("demo.OptedOut", true, cl);
        Class<?> builder = nested(optedOut, "Builder");
        Object built = builder.getMethod("build").invoke(optedOut.getMethod("builder").invoke(null));
        assertNull("the violated constraint goes unenforced", optedOut.getMethod("getName").invoke(built));
    }

    // ------------------------------------------------------------------
    // BuilderNames.NONE - the single opt-out for each entry point
    // ------------------------------------------------------------------

    @Test
    public void builderNames_noneSuppressesTheBuilderFactory() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoBuilder",
            "package demo;",
            "import dev.simplified.annotations.BuilderNames;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(builder = @BuilderNames(builder = BuilderNames.NONE), validate = false)",
            "public class NoBuilder {",
            "    int x;",
            "    public NoBuilder(int x) { this.x = x; }",
            "    public int getX() { return x; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> target = Class.forName("demo.NoBuilder", true, cl);

        assertFalse("builder = NONE must skip the static builder() factory",
            hasMethod(target, "builder"));
        // from and mutate should still be present
        assertTrue("from(T) remains when only builder is disabled",
            hasMethod(target, "from", target));
    }

    @Test
    public void builderNames_noneSuppressesFromButKeepsMutateInlined() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoFrom",
            "package demo;",
            "import dev.simplified.annotations.BuilderNames;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(builder = @BuilderNames(from = BuilderNames.NONE), validate = false)",
            "public class NoFrom {",
            "    int x;",
            "    public NoFrom(int x) { this.x = x; }",
            "    public int getX() { return x; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> target = Class.forName("demo.NoFrom", true, cl);

        assertFalse("from = NONE must skip the static from(T) factory",
            hasMethod(target, "from", target));
        assertTrue("builder() remains when only from is disabled",
            hasMethod(target, "builder"));
        // mutate() is decoupled from from(): it seeds inline off `this`, so it
        // survives a suppressed from rather than dangling a from(this) call.
        assertTrue("mutate() remains when only from is disabled",
            hasMethod(target, "mutate"));

        // Prove mutate() round-trips without any from(T) to delegate to.
        Class<?> builder = nested(target, "Builder");
        Object b = target.getMethod("builder").invoke(null);
        builder.getMethod("x", int.class).invoke(b, 7);
        Object first = builder.getMethod("build").invoke(b);

        Object b2 = target.getMethod("mutate").invoke(first);
        builder.getMethod("x", int.class).invoke(b2, 9);
        Object second = builder.getMethod("build").invoke(b2);
        assertEquals(7, target.getMethod("getX").invoke(first));
        assertEquals(9, target.getMethod("getX").invoke(second));
    }

    @Test
    public void builderNames_noneSuppressesMutate() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoMutate",
            "package demo;",
            "import dev.simplified.annotations.BuilderNames;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(builder = @BuilderNames(toBuilder = BuilderNames.NONE), validate = false)",
            "public class NoMutate {",
            "    int x;",
            "    public NoMutate(int x) { this.x = x; }",
            "    public int getX() { return x; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> target = Class.forName("demo.NoMutate", true, cl);

        assertFalse("toBuilder = NONE must skip the instance mutate() method",
            hasMethod(target, "mutate"));
        assertTrue("builder() remains when only mutate is disabled",
            hasMethod(target, "builder"));
        assertTrue("from(T) remains when only mutate is disabled",
            hasMethod(target, "from", target));
    }
}
