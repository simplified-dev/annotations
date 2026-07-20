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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Covers how the class-level {@code @ClassBuilder(retainInit)} policy and the
 * field-level {@code @BuilderDefault} override combine. Field always wins;
 * absent a field annotation the class policy applies; the class policy defaults
 * to retaining.
 */
public class RetainInitPolicyTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-retaininit-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, RetainInitPolicyTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested class '" + simpleName + "' on " + outer);
        return null;
    }

    /** Builds with no setters called, so every value observed is a builder default. */
    private static Object buildUntouched(Class<?> target) throws Exception {
        Class<?> builder = nested(target, "Builder");
        Object b = builder.getDeclaredConstructor().newInstance();
        return builder.getMethod("build").invoke(b);
    }

    private static Object get(Class<?> target, Object instance, String getter) throws Exception {
        Method m = target.getMethod(getter);
        return m.invoke(instance);
    }

    // ------------------------------------------------------------------
    // Class policy defaults to retaining
    // ------------------------------------------------------------------

    @Test
    public void classDefault_retainsInitializerWithoutAnyFieldAnnotation() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Defaults",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Defaults {
                String name = "anonymous";
                int count = 7;
                public String getName() { return name; }
                public int getCount() { return count; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Defaults", true, loadClasses(c));
        Object built = buildUntouched(target);
        assertEquals("anonymous", get(target, built, "getName"));
        assertEquals(7, get(target, built, "getCount"));
    }

    @Test
    public void builderDefaultFalse_optsASingleFieldOut() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Mixed",
            """
            package demo;
            import dev.simplified.annotations.BuilderDefault;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Mixed {
                String kept = "kept";
                @BuilderDefault(false)
                String dropped = "dropped";
                public String getKept() { return kept; }
                public String getDropped() { return dropped; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Mixed", true, loadClasses(c));
        Object built = buildUntouched(target);
        assertEquals("kept", get(target, built, "getKept"));
        assertNull("@BuilderDefault(false) must fall back to the JVM default",
            get(target, built, "getDropped"));
    }

    // ------------------------------------------------------------------
    // Class-level opt-out, and opting back in per field
    // ------------------------------------------------------------------

    @Test
    public void classRetainInitFalse_ignoresInitializers() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Sparse",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, retainInit = false)
            public class Sparse {
                String name = "ignored";
                public String getName() { return name; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Sparse", true, loadClasses(c));
        assertNull(get(target, buildUntouched(target), "getName"));
    }

    @Test
    public void builderDefault_optsBackInUnderClassLevelFalse() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.OptIn",
            """
            package demo;
            import dev.simplified.annotations.BuilderDefault;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, retainInit = false)
            public class OptIn {
                String ignored = "ignored";
                @BuilderDefault
                String kept = "kept";
                public String getIgnored() { return ignored; }
                public String getKept() { return kept; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.OptIn", true, loadClasses(c));
        Object built = buildUntouched(target);
        assertNull(get(target, built, "getIgnored"));
        assertEquals("bare @BuilderDefault must opt back in", "kept", get(target, built, "getKept"));
    }

    // ------------------------------------------------------------------
    // Fields with no initializer
    // ------------------------------------------------------------------

    /**
     * Retain-all must stay silent on fields that have nothing to retain -
     * otherwise flipping the class default on would error out every
     * uninitialised field in the codebase.
     */
    @Test
    public void inheritedPolicy_onFieldWithoutInitializer_isNotAnError() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bare",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Bare {
                String noInit;
                int alsoNoInit;
                public String getNoInit() { return noInit; }
                public int getAlsoNoInit() { return alsoNoInit; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Bare", true, loadClasses(c));
        Object built = buildUntouched(target);
        assertNull(get(target, built, "getNoInit"));
        assertEquals(0, get(target, built, "getAlsoNoInit"));
    }

    /**
     * The counterpart to the case above: asking for retention by name on a field
     * with nothing to retain is a user mistake and must not pass silently.
     */
    @Test
    public void explicitBuilderDefault_withoutInitializer_warns() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Pointless",
            """
            package demo;
            import dev.simplified.annotations.BuilderDefault;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Pointless {
                @BuilderDefault
                String noInit;
                public String getNoInit() { return noInit; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("@BuilderDefault has no effect on 'noInit'");
    }

    /** The inherited-policy case must stay quiet, or the warning would be unusable noise. */
    @Test
    public void inheritedPolicy_withoutInitializer_doesNotWarn() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Quiet",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Quiet {
                String noInit;
                public String getNoInit() { return noInit; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();
        // Not hadWarningCount(0): javac always emits a "source version RELEASE_17
        // less than -source 21" warning for this processor. Scope the assertion
        // to the diagnostic under test.
        for (var d : c.warnings()) {
            String message = d.getMessage(null);
            if (message != null && message.contains("@BuilderDefault")) {
                fail("inherited retainInit must not warn, but got: " + message);
            }
        }
    }

    // ------------------------------------------------------------------
    // Final fields whose retention is turned off
    // ------------------------------------------------------------------

    /**
     * Turning retention off must still lift a {@code final} field to a blank
     * final. The generated constructor assigns every field either way, so a
     * final field left holding its own initializer is doubly defined and javac
     * rejects it with "cannot assign a value to final variable".
     *
     * <p>The field then defaults to null, which is exactly what the non-final
     * case already did - opting out of retention means the declared value does
     * not reach the builder.
     */
    @Test
    public void builderDefaultFalse_onFinalField_stillCompiles() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.FinalOptOut",
            """
            package demo;
            import dev.simplified.annotations.BuilderDefault;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class FinalOptOut {
                @BuilderDefault(false) final String name = "declared";
                public String getName() { return name; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.FinalOptOut", true, loadClasses(c));
        assertNull("opting out drops the declared value, as for a non-final field",
            get(target, buildUntouched(target), "getName"));
    }

    /** Same lift, driven by the class-level policy rather than a field annotation. */
    @Test
    public void classRetainInitFalse_onFinalField_stillCompiles() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.FinalSparse",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, retainInit = false)
            public class FinalSparse {
                final String name = "declared";
                public String getName() { return name; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.FinalSparse", true, loadClasses(c));
        assertNull(get(target, buildUntouched(target), "getName"));
    }

    // ------------------------------------------------------------------
    // Fresh-per-build semantics survive the policy change
    // ------------------------------------------------------------------

    @Test
    public void retainedInitializer_isEvaluatedFreshPerBuilder() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Fresh",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder(validate = false)
            public class Fresh {
                List<String> items = new ArrayList<>();
                public List<String> getItems() { return items; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Fresh", true, loadClasses(c));
        Object first = get(target, buildUntouched(target), "getItems");
        Object second = get(target, buildUntouched(target), "getItems");
        if (first == second) {
            fail("each build must get its own list instance, not a shared one");
        }
    }

}
