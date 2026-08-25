package dev.simplified.classbuilder.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The one remaining sibling-file path after the Phase 6 cutover:
 * interface targets still generate a {@code <Name>Impl.java} alongside a
 * {@code <Name>Builder.java} because there is no in-source mutation surface
 * on an interface body to inject into.
 */
public class InterfaceTargetTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static String generatedSource(Compilation compilation, String fqn) {
        Optional<JavaFileObject> out = compilation.generatedSourceFile(fqn);
        if (out.isEmpty()) {
            throw new AssertionError("expected generated source '" + fqn + "' - generated files: "
                + compilation.generatedFiles());
        }
        try {
            return out.get().getCharContent(false).toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void interfaceTarget_emitsImplAndBuilder() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Shape {",
            "    int sides();",
            "    String name();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.ShapeImpl");
        assertTrue(impl, impl.contains("final class ShapeImpl implements Shape"));
        assertTrue(impl, impl.contains("private final int sides;"));
        assertTrue(impl, impl.contains("private final String name;"));

        String builder = generatedSource(c, "demo.ShapeBuilder");
        assertTrue(builder, builder.contains("public class ShapeBuilder"));
        assertTrue(builder, builder.contains("sides"));
        assertTrue(builder, builder.contains("name"));
        assertTrue(builder, builder.contains("return new ShapeImpl(sides, name);"));
    }

    // ------------------------------------------------------------------
    // @BuildFlag on an accessor - copied onto the Impl field the validator reads
    // ------------------------------------------------------------------

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("interface-target");
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = f.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            String rel = anchor >= 0 ? uri.substring(anchor + "CLASS_OUTPUT/".length()) : f.getName();
            Path out = tmp.resolve(rel);
            Files.createDirectories(out.getParent());
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                in.transferTo(b);
                Files.write(out, b.toByteArray());
            }
        }
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, InterfaceTargetTest.class.getClassLoader());
    }

    /**
     * Invokes a generated builder chain and returns the
     * {@link IllegalStateException} it threw, failing when it did not.
     *
     * @param use the compiled caller class
     * @param method the zero-arg static method driving the builder
     * @return the rejection
     */
    private static IllegalStateException rejected(Class<?> use, String method) throws Exception {
        try {
            use.getMethod(method).invoke(null);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalStateException rejection) return rejection;
            throw new AssertionError("build() failed for something other than validation", e.getCause());
        }
        fail("expected build() to reject the instance");
        return null;
    }

    /**
     * The whole point of the feature: a constraint written on the accessor is
     * enforced by {@code build()}, which it can only be because the annotation
     * reaches the {@code Impl} field the validator reads.
     */
    @Test
    public void buildFlagOnAccessor_isEnforcedAtBuild() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Shape",
                "package demo;",
                "import dev.simplified.annotations.BuildFlag;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public interface Shape {",
                "    @BuildFlag(nonNull = true) String name();",
                "    int sides();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseShape",
                "package demo;",
                "public class UseShape {",
                "    public static Shape unset() { return Shape.builder().sides(3).build(); }",
                "    public static Shape set() { return Shape.builder().name(\"tri\").sides(3).build(); }",
                "}"));
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.ShapeImpl");
        assertTrue(impl, impl.contains("import dev.simplified.annotations.BuildFlag;"));
        assertTrue(impl, impl.contains("@BuildFlag(nonNull = true) private final String name;"));
        assertTrue("an unflagged accessor must stay bare\n" + impl,
            impl.contains("private final int sides;"));

        ClassLoader cl = loadClasses(c);
        Class<?> use = Class.forName("demo.UseShape", true, cl);
        IllegalStateException rejection = rejected(use, "unset");
        assertTrue("the message must name the accessor, got: " + rejection.getMessage(),
            rejection.getMessage().contains("'name'"));
        assertTrue("and the Impl it was enforced on, got: " + rejection.getMessage(),
            rejection.getMessage().contains("'ShapeImpl'"));

        // Through the interface, not the instance's own class - the generated
        // Impl is package-private, so its public members are not reflectively
        // reachable from here.
        Object built = use.getMethod("set").invoke(null);
        assertEquals("tri", Class.forName("demo.Shape", true, cl).getMethod("name").invoke(built));
    }

    /**
     * Only the attributes actually written are copied, so the generated field
     * reads as the accessor did rather than spelling out all five defaults.
     */
    @Test
    public void buildFlagOnAccessor_copiesOnlyWrittenAttributes() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Sized",
            "package demo;",
            "import dev.simplified.annotations.BuildFlag;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import java.util.List;",
            "@ClassBuilder",
            "public interface Sized {",
            "    @BuildFlag(nonNull = true, notEmpty = true, limit = 5, group = {\"a\", \"b\"}) List<String> tags();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.SizedImpl");
        assertTrue(impl, impl.contains(
            "@BuildFlag(nonNull = true, notEmpty = true, limit = 5, group = {\"a\", \"b\"}) "
                + "private final List<String> tags;"));
        assertFalse("an unwritten attribute must not be spelled out\n" + impl, impl.contains("pattern ="));
    }

    /**
     * A {@code pattern} is a regex and routinely carries backslashes, so the
     * copy has to re-escape rather than pass the decoded value through - the
     * generated source would otherwise fail to compile, or silently become a
     * different regex.
     */
    @Test
    public void buildFlagOnAccessor_reEscapesARegexPattern() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Account",
                "package demo;",
                "import dev.simplified.annotations.BuildFlag;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder",
                "public interface Account {",
                "    @BuildFlag(pattern = \"^[^@]+@[^@]+\\\\.[^@]+$\") String email();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseAccount",
                "package demo;",
                "public class UseAccount {",
                "    public static Account bad() { return Account.builder().email(\"nope\").build(); }",
                "    public static Account good() { return Account.builder().email(\"a@b.co\").build(); }",
                "}"));
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.AccountImpl");
        assertTrue("the backslash must survive as an escape\n" + impl,
            impl.contains("@BuildFlag(pattern = \"^[^@]+@[^@]+\\\\.[^@]+$\")"));

        ClassLoader cl = loadClasses(c);
        Class<?> use = Class.forName("demo.UseAccount", true, cl);
        assertTrue(rejected(use, "bad").getMessage().contains("does not match pattern"));
        Object ok = use.getMethod("good").invoke(null);
        assertEquals("a@b.co", Class.forName("demo.Account", true, cl).getMethod("email").invoke(ok));
    }

}
