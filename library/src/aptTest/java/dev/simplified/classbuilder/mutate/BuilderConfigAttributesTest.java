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
    // methodPrefix - setters should honour the configured prefix
    // ------------------------------------------------------------------

    @Test
    public void methodPrefix_customPrefixAppliedToSetters() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Cfg",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(methodPrefix = \"set\", validate = false)",
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

        assertTrue("setter must use configured prefix 'set'",
            hasMethod(builder, "setName", String.class));
        assertFalse("default 'with' prefix must not leak through",
            hasMethod(builder, "withName", String.class));
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
    public void generateFrom_falseSkipsFromBootstrap() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoFrom",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(generateFrom = false, generateMutate = false, validate = false)",
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
        // mutate must also be suppressed (we disabled it to avoid its from() dependency)
        assertFalse("generateMutate=false must skip mutate()",
            hasMethod(target, "mutate"));
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
