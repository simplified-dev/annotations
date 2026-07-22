package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
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
    // names / style - every generated method name comes from a role pattern
    // ------------------------------------------------------------------

    @Test
    public void names_setPatternAppliedToSetters() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Cfg",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.MethodNames;",
            "@ClassBuilder(names = @MethodNames(set = \"set{}\"), validate = false)",
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
    public void names_suffixPatternIsExpressible() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Suffixed",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.MethodNames;",
            "@ClassBuilder(names = @MethodNames(set = \"{}Value\"), validate = false)",
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

        assertTrue("builderName takes the {} pattern", builder.getSimpleName().equals("CardBuilder"));
        assertTrue("bare-name boolean setter", hasMethod(builder, "shiny", boolean.class));
        assertFalse("no zero-arg boolean form under LOMBOK", hasMethod(builder, "isShiny"));
        assertTrue("singular add takes the bare singular", hasMethod(builder, "tag", String.class));
        assertFalse("addX is the SIMPLIFIED name, not Lombok's", hasMethod(builder, "addTag", String.class));
        assertTrue("clear keeps its name across styles", hasMethod(builder, "clearTags"));
        assertTrue("toBuilderMethodName follows the style", hasMethod(card, "toBuilder"));
        assertFalse("mutate is the SIMPLIFIED name", hasMethod(card, "mutate"));
    }

    @Test
    public void namingStyle_explicitAttributeBeatsTheStyle() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Token",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.NamingStyle;",
            "@ClassBuilder(style = NamingStyle.LOMBOK, toBuilderMethodName = \"respawn\", validate = false)",
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
    public void names_suppressedFlagRoleDropsTheZeroArgSetter() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Switch",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.MethodNames;",
            "@ClassBuilder(names = @MethodNames(flag = MethodNames.NONE), validate = false)",
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
    public void names_malformedPatternIsRejectedAtTheAnnotation() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Broken",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.MethodNames;",
            "@ClassBuilder(names = @MethodNames(set = \"set\"), validate = false)",
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
    public void names_suppressedSetRoleIsRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoSetter",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.MethodNames;",
            "@ClassBuilder(names = @MethodNames(set = MethodNames.NONE), validate = false)",
            "public class NoSetter {",
            "    String name;",
            "    public NoSetter(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot suppress the 'set' naming role");
    }

    // ------------------------------------------------------------------
    // buildMethodName - build() should use the configured name
    // ------------------------------------------------------------------

    @Test
    public void buildMethodName_customNameReplacesBuild() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Part",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(buildMethodName = \"make\", validate = false)",
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
    // generateBuilder / generateFrom / generateMutate - opt-out gates
    // ------------------------------------------------------------------

    @Test
    public void generateBuilder_falseSkipsBuilderBootstrap() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoBuilder",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(generateBuilder = false, validate = false)",
            "public class NoBuilder {",
            "    int x;",
            "    public NoBuilder(int x) { this.x = x; }",
            "    public int getX() { return x; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> target = Class.forName("demo.NoBuilder", true, cl);

        assertFalse("generateBuilder=false must skip the static builder() factory",
            hasMethod(target, "builder"));
        // from and mutate should still be present
        assertTrue("from(T) remains when only builder is disabled",
            hasMethod(target, "from", target));
    }

    @Test
    public void generateFrom_falseSkipsFromButKeepsMutateInlined() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoFrom",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(generateFrom = false, validate = false)",
            "public class NoFrom {",
            "    int x;",
            "    public NoFrom(int x) { this.x = x; }",
            "    public int getX() { return x; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> target = Class.forName("demo.NoFrom", true, cl);

        assertFalse("generateFrom=false must skip the static from(T) factory",
            hasMethod(target, "from", target));
        assertTrue("builder() remains when only from is disabled",
            hasMethod(target, "builder"));
        // mutate() is decoupled from from(): it seeds inline off `this`, so it
        // survives generateFrom=false rather than dangling a from(this) call.
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
    public void generateMutate_falseSkipsMutateBootstrap() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoMutate",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(generateMutate = false, validate = false)",
            "public class NoMutate {",
            "    int x;",
            "    public NoMutate(int x) { this.x = x; }",
            "    public int getX() { return x; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> target = Class.forName("demo.NoMutate", true, cl);

        assertFalse("generateMutate=false must skip the instance mutate() method",
            hasMethod(target, "mutate"));
        assertTrue("builder() remains when only mutate is disabled",
            hasMethod(target, "builder"));
        assertTrue("from(T) remains when only mutate is disabled",
            hasMethod(target, "from", target));
    }
}
