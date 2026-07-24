package dev.simplified.log.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import dev.simplified.log.apt.LogProcessor;
import org.junit.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end mutation tests for {@code @Log}. Compiles fixtures with the
 * processor, loads the result into a fresh classloader, and inspects the
 * synthesised field reflectively.
 *
 * <p>log4j2 is deliberately absent from this module's classpath - the annotation
 * emits a textual reference that resolves in the target's own compilation. The
 * two types the generated code names are therefore supplied here as stub
 * sources, compiled alongside every fixture. {@code LogManager} records the
 * argument that reached the factory so the class-literal and topic forms can be
 * told apart by the value rather than by the emitted text.
 */
public class LogProcessorTest {

    private static final String LOGGER_FQN = "org.apache.logging.log4j.Logger";

    private static final String LOG_MANAGER_FQN = "org.apache.logging.log4j.LogManager";

    private static final String GENERATED_DESCRIPTOR = "Ldev/simplified/annotations/Generated;";

    /** Minimal shape of the log4j2 logger the generated field is typed as. */
    private static final JavaFileObject LOGGER_STUB = JavaFileObjects.forSourceLines(LOGGER_FQN,
        "package org.apache.logging.log4j;",
        "public interface Logger {",
        "    void info(String message);",
        "    String getName();",
        "}");

    /** Minimal log4j2 factory, recording whatever the generated code hands it. */
    private static final JavaFileObject LOG_MANAGER_STUB = JavaFileObjects.forSourceLines(LOG_MANAGER_FQN,
        "package org.apache.logging.log4j;",
        "public final class LogManager {",
        "    public static Object lastArg;",
        "    public static Logger getLogger(Class<?> type) { lastArg = type; return null; }",
        "    public static Logger getLogger(String name) { lastArg = name; return null; }",
        "}");

    private static Compilation compile(JavaFileObject... sources) {
        JavaFileObject[] all = withStubs(sources);
        return Compiler.javac()
            .withProcessors(new LogProcessor())
            .compile(all);
    }

    private static JavaFileObject[] withStubs(JavaFileObject... sources) {
        JavaFileObject[] all = new JavaFileObject[sources.length + 2];
        all[0] = LOGGER_STUB;
        all[1] = LOG_MANAGER_STUB;
        System.arraycopy(sources, 0, all, 2, sources.length);
        return all;
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("log-mutate-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()},
            LogProcessorTest.class.getClassLoader());
    }

    // ------------------------------------------------------------------
    // The bare form, on both legal target kinds
    // ------------------------------------------------------------------

    @Test
    public void bareLogOnClass_emitsPrivateStaticFinalLoggerFromClassLiteral() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Uploader",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public class Uploader { }");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> uploader = Class.forName("demo.Uploader", true, cl);
        assertLoggerField(uploader, "log");

        // The bare form names the logger after the target, so the class literal
        // is what reaches the factory.
        assertSame(uploader, factoryArgument(cl));
    }

    @Test
    public void bareLogOnEnum_emitsPrivateStaticFinalLogger() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Phase",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public enum Phase { START, STOP }");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> phase = Class.forName("demo.Phase", true, cl);
        assertLoggerField(phase, "log");
        assertEquals(2, phase.getEnumConstants().length);
        assertSame(phase, factoryArgument(cl));
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    @Test
    public void writtenTopic_reachesTheFactoryInPlaceOfTheClassLiteral() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Ledger",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log(topic = \"audit\")",
            "public class Ledger { }");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> ledger = Class.forName("demo.Ledger", true, cl);
        assertLoggerField(ledger, "log");

        // Asserted on the value the factory actually received rather than on the
        // emitted text, which is why the stub records it.
        assertEquals("audit", factoryArgument(cl));
    }

    @Test
    public void writtenName_expandsThePlaceholderAndTakesALiteral() throws Exception {
        JavaFileObject placeholder = JavaFileObjects.forSourceLines("demo.Foo",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log(name = \"{}Log\")",
            "public class Foo { }");
        Compilation withPlaceholder = compile(placeholder);
        assertThat(withPlaceholder).succeeded();
        assertLoggerField(Class.forName("demo.Foo", true, loadClasses(withPlaceholder)), "FooLog");

        JavaFileObject literal = JavaFileObjects.forSourceLines("demo.Bar",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log(name = \"logger\")",
            "public class Bar { }");
        Compilation withLiteral = compile(literal);
        assertThat(withLiteral).succeeded();
        assertLoggerField(Class.forName("demo.Bar", true, loadClasses(withLiteral)), "logger");
    }

    @Test
    public void suppressedName_isAnError() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Silent",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log(name = \"-\")",
            "public class Silent { }");
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Log(name) cannot be suppressed with '-'");
    }

    // ------------------------------------------------------------------
    // The raw class literal
    // ------------------------------------------------------------------

    @Test
    public void genericTarget_compilesBecauseTheClassLiteralIsRaw() throws Exception {
        // A mutator that re-applied the target's type arguments would emit
        // Box<T>.class, which is not an expression - so the value here is that
        // the compilation succeeds at all.
        JavaFileObject one = JavaFileObjects.forSourceLines("demo.Box",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public class Box<T extends Number> { }");
        Compilation single = compile(one);
        assertThat(single).succeeded();

        ClassLoader singleLoader = loadClasses(single);
        Class<?> box = Class.forName("demo.Box", true, singleLoader);
        assertLoggerField(box, "log");
        assertSame(box, factoryArgument(singleLoader));

        JavaFileObject two = JavaFileObjects.forSourceLines("demo.Pair",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public class Pair<K, V extends Comparable<V>> { }");
        Compilation twin = compile(two);
        assertThat(twin).succeeded();
        assertLoggerField(Class.forName("demo.Pair", true, loadClasses(twin)), "log");
    }

    // ------------------------------------------------------------------
    // Collision and illegal targets
    // ------------------------------------------------------------------

    @Test
    public void declaredFieldOfTheSameName_survivesWithAWarning() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Taken",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public class Taken {",
            "    private static final String log = \"mine\";",
            "    String read() { return log; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("already declares a field named 'log'");

        Class<?> taken = Class.forName("demo.Taken", true, loadClasses(c));
        Field log = taken.getDeclaredField("log");
        assertEquals(String.class, log.getType());
        // Exactly one field of that name survived, and it is the author's.
        int named = 0;
        for (Field f : taken.getDeclaredFields()) {
            if ("log".equals(f.getName())) named++;
        }
        assertEquals(1, named);
    }

    @Test
    public void interfaceRecordAndAnnotationTargets_areErrors() {
        Compilation asInterface = compile(JavaFileObjects.forSourceLines("demo.Face",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public interface Face { }"));
        assertThat(asInterface).failed();
        assertThat(asInterface).hadErrorContaining("@Log is only supported on classes and enums");
        assertThat(asInterface).hadErrorContaining("is an interface");

        Compilation asRecord = compile(JavaFileObjects.forSourceLines("demo.Point",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public record Point(int x, int y) { }"));
        assertThat(asRecord).failed();
        assertThat(asRecord).hadErrorContaining("@Log is only supported on classes and enums");
        assertThat(asRecord).hadErrorContaining("is a record");

        Compilation asAnnotation = compile(JavaFileObjects.forSourceLines("demo.Marker",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public @interface Marker { }"));
        assertThat(asAnnotation).failed();
        assertThat(asAnnotation).hadErrorContaining("@Log is only supported on classes and enums");
        assertThat(asAnnotation).hadErrorContaining("is an annotation type");
    }

    // ------------------------------------------------------------------
    // Coverage marker
    // ------------------------------------------------------------------

    @Test
    public void generatedMarker_ridesTheFieldUnlessOptedOut() throws Exception {
        Compilation marked = compile(JavaFileObjects.forSourceLines("demo.Marked",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "public class Marked { }"));
        assertThat(marked).succeeded();
        assertTrue("the generated field should carry the coverage marker",
            fieldAnnotations(marked, "demo.Marked", "log").contains(GENERATED_DESCRIPTOR));

        Compilation bare = compile(JavaFileObjects.forSourceLines("demo.Unmarked",
            "package demo;",
            "import dev.simplified.annotations.Log;",
            "@Log(emitGenerated = false)",
            "public class Unmarked { }"));
        assertThat(bare).succeeded();
        assertFalse("emitGenerated = false should leave the field unmarked",
            fieldAnnotations(bare, "demo.Unmarked", "log").contains(GENERATED_DESCRIPTOR));
    }

    // ------------------------------------------------------------------
    // Classpath precondition
    // ------------------------------------------------------------------

    /**
     * Compiles without the stubs, which is the shape a consumer hits when
     * log4j2 is missing. The generated field carries no source position, so
     * letting javac fail on it would report "cannot find symbol" against a
     * declaration the author cannot open - the processor names the artifact
     * instead, and emits nothing.
     */
    @Test
    public void missingLog4j_namesTheDependencyRatherThanFailingInTheField() {
        Compilation c = Compiler.javac()
            .withProcessors(new LogProcessor())
            .compile(JavaFileObjects.forSourceLines("demo.Unresolved",
                "package demo;",
                "import dev.simplified.annotations.Log;",
                "@Log",
                "public class Unresolved { }"));

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("org.apache.logging.log4j:log4j-api");
        // The failure must be the guard's, not a symbol error from an emitted field.
        assertThat(c).hadErrorContaining("does not resolve");
    }

    // ------------------------------------------------------------------
    // Composition with @ClassBuilder
    // ------------------------------------------------------------------

    @Test
    public void loggerIsNotABuilderSlot() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Service",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Log;",
            "@Log",
            "@ClassBuilder",
            "public class Service {",
            "    private final String host;",
            "    private int retries;",
            "}");

        // Both processors, as a consumer's processor path carries them.
        Compilation c = Compiler.javac()
            .withProcessors(new LogProcessor(), new ClassBuilderProcessor())
            .compile(withStubs(src));
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> service = Class.forName("demo.Service", true, cl);
        assertLoggerField(service, "log");

        Class<?> builder = null;
        for (Class<?> nested : service.getDeclaredClasses()) {
            if ("Builder".equals(nested.getSimpleName())) builder = nested;
        }
        assertNotNull("expected a nested Builder on demo.Service", builder);

        // The field collector skips static fields, so the logger never becomes a
        // slot - this pins that property rather than exercising a guard.
        for (Method m : builder.getDeclaredMethods()) {
            assertFalse("builder should carry no 'log' setter", "log".equals(m.getName()));
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse("builder method " + m.getName() + " takes a Logger",
                    LOGGER_FQN.equals(p.getName()));
            }
        }
        for (Constructor<?> ctor : service.getDeclaredConstructors()) {
            for (Class<?> p : ctor.getParameterTypes()) {
                assertFalse("a generated constructor takes a Logger",
                    LOGGER_FQN.equals(p.getName()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Assertion plumbing
    // ------------------------------------------------------------------

    /**
     * Asserts the one member {@code @Log} promises: a field of the given name,
     * exactly {@code private static final}, typed as the log4j2 logger.
     *
     * @param type the loaded target
     * @param fieldName the resolved field name
     * @return the field, so a caller can read it
     * @throws Exception when the field is absent
     */
    private static Field assertLoggerField(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        assertEquals("modifiers on " + fieldName,
            Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL, field.getModifiers());
        assertEquals("type of " + fieldName, LOGGER_FQN, field.getType().getName());
        return field;
    }

    /** The argument the generated initializer passed to the log4j2 factory. */
    private static Object factoryArgument(ClassLoader cl) throws Exception {
        Class<?> manager = Class.forName(LOG_MANAGER_FQN, true, cl);
        Field lastArg = manager.getDeclaredField("lastArg");
        return lastArg.get(null);
    }

    /**
     * The annotation descriptors on one field, read from the class file because
     * {@code @Generated} is {@code CLASS}-retention and so invisible to
     * reflection.
     *
     * @param compilation the finished compilation
     * @param className the binary name of the declaring class
     * @param fieldName the field to read
     * @return every annotation descriptor on that field
     * @throws Exception when the class file cannot be read
     */
    private static Set<String> fieldAnnotations(Compilation compilation, String className,
                                                String fieldName) throws Exception {
        byte[] bytes = findClassBytes(compilation, className);
        assertNotNull("expected class-file output for " + className, bytes);
        Set<String> found = new LinkedHashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (!fieldName.equals(name)) return null;
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                        found.add(annDesc);
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return found;
    }

    private static byte[] findClassBytes(Compilation c, String className) throws Exception {
        String expected = "/CLASS_OUTPUT/" + className.replace('.', '/') + ".class";
        for (JavaFileObject f : c.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            if (!f.toUri().toString().contains(expected)) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                return baos.toByteArray();
            }
        }
        return null;
    }
}
