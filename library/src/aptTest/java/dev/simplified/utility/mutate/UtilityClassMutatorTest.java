package dev.simplified.utility.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.utility.apt.UtilityClassProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code @UtilityClass}: the class becomes final, the constructor javac already
 * generated is retrofitted to throw, and instance members are reported rather
 * than silently rewritten.
 */
public class UtilityClassMutatorTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new UtilityClassProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("utility-class-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, UtilityClassMutatorTest.class.getClassLoader());
    }

    private static JavaFileObject util(String annotation, String... members) {
        String[] lines = new String[members.length + 5];
        lines[0] = "package demo;";
        lines[1] = "import dev.simplified.annotations.UtilityClass;";
        lines[2] = annotation;
        lines[3] = "public class StringUtil {";
        System.arraycopy(members, 0, lines, 4, members.length);
        lines[lines.length - 1] = "}";
        return JavaFileObjects.forSourceLines("demo.StringUtil", lines);
    }

    // ------------------------------------------------------------------
    // The two effects everyone wants
    // ------------------------------------------------------------------

    @Test
    public void classBecomesFinalAndConstructorThrows() throws Exception {
        Compilation c = compile(util("@UtilityClass",
            "    public static String reverse(String v) { return new StringBuilder(v).reverse().toString(); }"));
        assertThat(c).succeeded();

        Class<?> util = Class.forName("demo.StringUtil", true, loadClasses(c));
        assertTrue("class should be final", Modifier.isFinal(util.getModifiers()));

        Constructor<?>[] ctors = util.getDeclaredConstructors();
        assertEquals("exactly one constructor - the generated one is retrofitted, not replaced",
            1, ctors.length);
        assertTrue("constructor should be private", Modifier.isPrivate(ctors[0].getModifiers()));

        ctors[0].setAccessible(true);
        try {
            ctors[0].newInstance();
            fail("expected the constructor to throw");
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof UnsupportedOperationException);
            assertEquals("StringUtil is a utility class and cannot be instantiated",
                e.getCause().getMessage());
        }

        // The static member is untouched and still callable.
        assertEquals("cba", util.getMethod("reverse", String.class).invoke(null, "abc"));
    }

    @Test
    public void messageOverrideIsCarriedIntoTheThrow() throws Exception {
        Compilation c = compile(util("@UtilityClass(message = \"nope\")",
            "    public static int one() { return 1; }"));
        assertThat(c).succeeded();

        Class<?> util = Class.forName("demo.StringUtil", true, loadClasses(c));
        Constructor<?> ctor = util.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        try {
            ctor.newInstance();
            fail("expected the constructor to throw");
        } catch (InvocationTargetException e) {
            assertEquals("nope", e.getCause().getMessage());
        }
    }

    @Test
    public void constructorAccessIsHonoured() throws Exception {
        Compilation c = compile(util("@UtilityClass(constructorAccess = dev.simplified.annotations.AccessLevel.PACKAGE)",
            "    public static int one() { return 1; }"));
        assertThat(c).succeeded();

        Class<?> util = Class.forName("demo.StringUtil", true, loadClasses(c));
        int mods = util.getDeclaredConstructors()[0].getModifiers();
        assertTrue("package-private carries no access modifier",
            !Modifier.isPrivate(mods) && !Modifier.isPublic(mods) && !Modifier.isProtected(mods));
    }

    @Test
    public void makeFinalFalseLeavesTheClassOpen() throws Exception {
        Compilation c = compile(util("@UtilityClass(makeFinal = false)",
            "    public static int one() { return 1; }"));
        assertThat(c).succeeded();

        Class<?> util = Class.forName("demo.StringUtil", true, loadClasses(c));
        assertTrue("class should not be final", !Modifier.isFinal(util.getModifiers()));
    }

    // ------------------------------------------------------------------
    // The half that is opt-in
    // ------------------------------------------------------------------

    @Test
    public void instanceMemberIsAnErrorByDefault() {
        Compilation c = compile(util("@UtilityClass",
            "    public int count;",
            "    public int count() { return count; }"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("field 'count' is not");
        assertThat(c).hadErrorContaining("method 'count' is not");
    }

    @Test
    public void makeStaticRewritesInsteadOfReporting() throws Exception {
        Compilation c = compile(util("@UtilityClass(members = dev.simplified.annotations.UtilityClass.Members.MAKE_STATIC)",
            "    public int count = 3;",
            "    public int count() { return count; }"));
        assertThat(c).succeeded();

        Class<?> util = Class.forName("demo.StringUtil", true, loadClasses(c));
        assertTrue("field should have been made static",
            Modifier.isStatic(util.getDeclaredField("count").getModifiers()));
        assertTrue("method should have been made static",
            Modifier.isStatic(util.getDeclaredMethod("count").getModifiers()));
        assertEquals(3, util.getDeclaredMethod("count").invoke(null));
    }

    @Test
    public void nestedTypesAreLeftAloneByDefault() throws Exception {
        // The setting that can change a nested type's meaning stays off, so an
        // inner class declared inside a utility class remains inner.
        Compilation c = compile(util("@UtilityClass",
            "    public static int one() { return 1; }",
            "    class Inner { }"));
        assertThat(c).succeeded();

        Class<?> inner = Class.forName("demo.StringUtil$Inner", true, loadClasses(c));
        assertTrue("nested type should still be inner", !Modifier.isStatic(inner.getModifiers()));
    }

    @Test
    public void nestedTypesTrueStaticisesThem() throws Exception {
        Compilation c = compile(util("@UtilityClass(nestedTypes = true)",
            "    public static int one() { return 1; }",
            "    static class Inner { }"));
        assertThat(c).succeeded();

        Class<?> inner = Class.forName("demo.StringUtil$Inner", true, loadClasses(c));
        assertTrue("nested type should be static", Modifier.isStatic(inner.getModifiers()));
    }

    // ------------------------------------------------------------------
    // Legality
    // ------------------------------------------------------------------

    @Test
    public void authorDeclaredConstructorWinsWithAWarning() throws Exception {
        Compilation c = compile(util("@UtilityClass",
            "    StringUtil() { }",
            "    public static int one() { return 1; }"));
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("declares its own");

        // The author's constructor is left exactly as written - it does not throw.
        Class<?> util = Class.forName("demo.StringUtil", true, loadClasses(c));
        Constructor<?> ctor = util.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }

    @Test
    public void recordsEnumsAndInterfacesAreRejected() {
        JavaFileObject rec = JavaFileObjects.forSourceLines("demo.R",
            "package demo;",
            "import dev.simplified.annotations.UtilityClass;",
            "@UtilityClass",
            "public record R(int x) { }");
        assertThat(compile(rec)).hadErrorContaining("only supported on classes");

        JavaFileObject en = JavaFileObjects.forSourceLines("demo.E",
            "package demo;",
            "import dev.simplified.annotations.UtilityClass;",
            "@UtilityClass",
            "public enum E { A }");
        assertThat(compile(en)).hadErrorContaining("only supported on classes");

        JavaFileObject itf = JavaFileObjects.forSourceLines("demo.I",
            "package demo;",
            "import dev.simplified.annotations.UtilityClass;",
            "@UtilityClass",
            "public interface I { }");
        assertThat(compile(itf)).hadErrorContaining("only supported on classes");
    }

    @Test
    public void innerClassTargetIsRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Outer",
            "package demo;",
            "import dev.simplified.annotations.UtilityClass;",
            "public class Outer {",
            "    @UtilityClass",
            "    public class Inner { }",
            "}");
        assertThat(compile(src)).hadErrorContaining("static all the way out");
    }

    @Test
    public void staticNestedTargetIsAccepted() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Outer",
            "package demo;",
            "import dev.simplified.annotations.UtilityClass;",
            "public class Outer {",
            "    @UtilityClass",
            "    public static class Inner {",
            "        public static int one() { return 1; }",
            "    }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> inner = Class.forName("demo.Outer$Inner", true, loadClasses(c));
        assertTrue(Modifier.isFinal(inner.getModifiers()));
    }

    @Test
    public void abstractClassIsRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.A",
            "package demo;",
            "import dev.simplified.annotations.UtilityClass;",
            "@UtilityClass",
            "public abstract class A { }");
        assertThat(compile(src)).hadErrorContaining("abstract");
    }

}
