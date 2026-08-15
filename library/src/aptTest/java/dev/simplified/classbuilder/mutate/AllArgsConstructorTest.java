package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the all-args constructor synthesised for plain classes that declare no
 * constructor of their own, including the visibility default, the opt-out paths,
 * and the interaction with {@code final} fields.
 */
public class AllArgsConstructorTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-allargs-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, AllArgsConstructorTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested class '" + simpleName + "' on " + outer);
        return null;
    }

    /** Drives builder.name(..).count(..).build() and returns the built instance. */
    private static Object buildSimple(Class<?> target, String name, int count) throws Exception {
        Class<?> builder = nested(target, "Builder");
        Object b = builder.getEnclosingClass().getMethod("builder").invoke(null);
        builder.getMethod("name", String.class).invoke(b, name);
        builder.getMethod("count", int.class).invoke(b, count);
        return builder.getMethod("build").invoke(b);
    }

    private static Constructor<?> allArgsCtor(Class<?> target) {
        for (Constructor<?> c : target.getDeclaredConstructors()) {
            if (c.getParameterCount() == 2) return c;
        }
        fail("expected a 2-arg constructor on " + target + "; found "
            + Arrays.toString(target.getDeclaredConstructors()));
        return null;
    }

    // ------------------------------------------------------------------
    // Core F2/C3 case: no hand-written constructor at all
    // ------------------------------------------------------------------

    @Test
    public void noDeclaredConstructor_synthesisedAndRoundTrips() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoCtor",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class NoCtor {",
            "    String name;",
            "    int count;",
            "    public String getName() { return name; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.NoCtor", true, loadClasses(c));
        Object built = buildSimple(target, "hello", 42);

        assertNotNull(built);
        assertEquals("hello", target.getMethod("getName").invoke(built));
        assertEquals(42, target.getMethod("getCount").invoke(built));
    }

    @Test
    public void synthesisedConstructor_isPackagePrivateByDefault() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.DefaultAccess",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class DefaultAccess {",
            "    String name;",
            "    int count;",
            "    public String getName() { return name; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.DefaultAccess", true, loadClasses(c));
        int mods = allArgsCtor(target).getModifiers();
        assertTrue("constructor must not be public",
            !Modifier.isPublic(mods) && !Modifier.isPrivate(mods) && !Modifier.isProtected(mods));
    }

    @Test
    public void constructorAccess_isHonoured() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.PrivateCtor",
            "package demo;",
            "import dev.simplified.annotations.AccessLevel;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false, constructorAccess = AccessLevel.PRIVATE)",
            "public class PrivateCtor {",
            "    String name;",
            "    int count;",
            "    public String getName() { return name; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.PrivateCtor", true, loadClasses(c));
        assertTrue("constructor must be private", Modifier.isPrivate(allArgsCtor(target).getModifiers()));
    }

    // ------------------------------------------------------------------
    // Opt-out paths
    // ------------------------------------------------------------------

    @Test
    public void explicitConstructor_isNotDuplicated() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.HandWritten",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class HandWritten {",
            "    String name;",
            "    int count;",
            "    public HandWritten(String name, int count) {",
            "        this.name = name.trim();",
            "        this.count = count;",
            "    }",
            "    public String getName() { return name; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.HandWritten", true, loadClasses(c));
        assertEquals("author's constructor must be the only one",
            1, target.getDeclaredConstructors().length);
        // The trim() proves the author's body survived rather than being replaced.
        assertEquals("hi", target.getMethod("getName").invoke(buildSimple(target, "  hi  ", 1)));
    }

    @Test
    public void factoryMethod_suppressesSynthesis() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.ViaFactory",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false, factoryMethod = \"of\")",
            "public class ViaFactory {",
            "    String name;",
            "    int count;",
            "    private ViaFactory() {}",
            "    public static ViaFactory of(String name, int count) {",
            "        ViaFactory v = new ViaFactory();",
            "        v.name = name;",
            "        v.count = count;",
            "        return v;",
            "    }",
            "    public String getName() { return name; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.ViaFactory", true, loadClasses(c));
        assertEquals("only the author's no-arg constructor should exist",
            1, target.getDeclaredConstructors().length);
        assertEquals("hello", target.getMethod("getName").invoke(buildSimple(target, "hello", 3)));
    }

    // ------------------------------------------------------------------
    // Immutability: blank finals are assignable from the synthesised ctor
    // ------------------------------------------------------------------

    @Test
    public void finalFields_areAssignedBySynthesisedConstructor() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Immutable",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Immutable {",
            "    final String name;",
            "    final int count;",
            "    public String getName() { return name; }",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Immutable", true, loadClasses(c));
        Object built = buildSimple(target, "frozen", 7);

        assertEquals("frozen", target.getMethod("getName").invoke(built));
        assertEquals(7, target.getMethod("getCount").invoke(built));
        for (java.lang.reflect.Field f : target.getDeclaredFields()) {
            if (f.isSynthetic()) continue;
            assertTrue("field '" + f.getName() + "' must stay final",
                Modifier.isFinal(f.getModifiers()));
        }
    }

    // ------------------------------------------------------------------
    // Records keep their canonical constructor
    // ------------------------------------------------------------------

    @Test
    public void record_isUnaffected() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Point",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public record Point(String name, int count) {}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Point", true, loadClasses(c));
        assertEquals("record must keep exactly its canonical constructor",
            1, target.getDeclaredConstructors().length);

        Object built = buildSimple(target, "pt", 5);
        assertEquals("pt", target.getMethod("name").invoke(built));
        assertEquals(5, target.getMethod("count").invoke(built));
    }

    // ------------------------------------------------------------------
    // Abstract targets take the SuperBuilder copy constructor instead
    // ------------------------------------------------------------------

    @Test
    public void abstractTarget_getsCopyConstructorNotAllArgs() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public abstract class Base {",
            "    String name;",
            "    int count;",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Base", true, loadClasses(c));
        for (Constructor<?> ctor : target.getDeclaredConstructors()) {
            assertEquals("abstract target must only take the single-arg copy constructor",
                1, ctor.getParameterCount());
        }
    }

    // ------------------------------------------------------------------
    // @Lazy fields still get the Supplier rewrite on the synthesised ctor
    // ------------------------------------------------------------------

    @Test
    public void lazyField_isRewrittenOnSynthesisedConstructor() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Deferred",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public class Deferred {",
            "    @Lazy String name;",
            "    int count;",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Deferred", true, loadClasses(c));
        Constructor<?> ctor = allArgsCtor(target);
        assertEquals("@Lazy parameter must be retyped to Supplier",
            "java.util.function.Supplier", ctor.getParameterTypes()[0].getName());

        Method getName = target.getMethod("getName");
        Class<?> builder = nested(target, "Builder");
        Object b = builder.getEnclosingClass().getMethod("builder").invoke(null);
        builder.getMethod("name", String.class).invoke(b, "deferred");
        assertEquals("deferred", getName.invoke(builder.getMethod("build").invoke(b)));
    }

}
