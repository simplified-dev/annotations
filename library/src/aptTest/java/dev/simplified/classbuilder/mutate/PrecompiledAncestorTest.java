package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A chain whose parent is a class file rather than a source in the same round.
 *
 * <p>Every other suite here compiles its whole fixture in one compilation, so the
 * subclass reads its parent from a tree the round is still building. A consumer
 * almost never does: the parent arrives on the classpath, already compiled,
 * carrying only what a class file carries. The two views differ - a tree holds
 * the marks the pipeline set and a class file does not - and a pass that reads
 * one while the other is what ships is how a chain breaks on a line the author
 * never wrote.
 *
 * <p>Compiling in two stages is the only way to exercise that boundary, so it is
 * what these tests do.
 */
public class PrecompiledAncestorTest {

    /** Compiles in one round, the way the rest of the suite does. */
    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    /**
     * Compiles against an already-compiled ancestor.
     *
     * <p>{@code withClasspath} replaces the classpath rather than adding to it,
     * so the running test's own entries are passed through beside the directory
     * holding the ancestor - without them the annotations themselves would not
     * resolve.
     */
    private static Compilation compileAgainst(Path compiled, JavaFileObject... sources) {
        List<File> classpath = new ArrayList<>();
        classpath.add(compiled.toFile());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isBlank()) classpath.add(new File(entry));
        }
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .withClasspath(classpath)
            .compile(sources);
    }

    /** Writes a compilation's class files to a directory a later compile can read. */
    private static Path classesOf(Compilation compilation) throws Exception {
        Path out = Files.createTempDirectory("precompiled-ancestor");
        for (JavaFileObject file : compilation.generatedFiles()) {
            if (file.getKind() != JavaFileObject.Kind.CLASS) continue;
            String uri = file.toUri().toString();
            int anchor = uri.indexOf("CLASS_OUTPUT/");
            String relative =
                anchor >= 0 ? uri.substring(anchor + "CLASS_OUTPUT/".length()) : file.getName();
            Path destination = out.resolve(relative);
            Files.createDirectories(destination.getParent());
            try (InputStream in = file.openInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                Files.write(destination, bytes.toByteArray());
            }
        }
        return out;
    }

    /** A loader over the ancestor's classes plus the ones just compiled against them. */
    private static ClassLoader loadClasses(Path ancestor, Compilation compilation) throws Exception {
        Path child = classesOf(compilation);
        return new URLClassLoader(
            new URL[]{child.toUri().toURL(), ancestor.toUri().toURL()},
            PrecompiledAncestorTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested '" + simpleName + "' on " + outer);
        return null;
    }

    private static List<String> methodNames(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) out.add(method.getName());
        return out;
    }

    // ------------------------------------------------------------------
    // The chain still forms when the parent is a class file
    // ------------------------------------------------------------------

    /**
     * The shape every other chain test asserts, with the parent moved out of the
     * round. What it pins is that nothing in the chain needs the parent's tree.
     */
    @Test
    public void chainAcrossACompiledAncestor_inheritsItsSetters() throws Exception {
        Compilation parent = compile(JavaFileObjects.forSourceLines("demo.Page",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public abstract class Page {",
            "    String title;",
            "    public String getTitle() { return title; }",
            "}"));
        assertThat(parent).succeeded();
        Path compiled = classesOf(parent);

        Compilation child = compileAgainst(compiled,
            JavaFileObjects.forSourceLines("demo.TreePage",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class TreePage extends Page {",
                "    int depth;",
                "    public int getDepth() { return depth; }",
                "}"));
        assertThat(child).succeeded();

        ClassLoader loader = loadClasses(compiled, child);
        Class<?> treeCls = Class.forName("demo.TreePage", true, loader);
        Class<?> pageCls = Class.forName("demo.Page", true, loader);
        Class<?> childBuilder = nested(treeCls, "Builder");

        Object builder = treeCls.getMethod("builder").invoke(null);
        childBuilder.getMethod("title", String.class).invoke(builder, "Intro");
        childBuilder.getMethod("depth", int.class).invoke(builder, 3);
        Object built = childBuilder.getMethod("build").invoke(builder);

        assertEquals(treeCls, built.getClass());
        assertEquals("Intro", pageCls.getMethod("getTitle").invoke(built));
        assertEquals(3, treeCls.getMethod("getDepth").invoke(built));
    }

    // ------------------------------------------------------------------
    // A lazy ancestor field is one slot, not two
    // ------------------------------------------------------------------

    /**
     * A lazy field is given a sibling holding the value it memoizes. That sibling
     * is private, instance and non-transient, so none of the tests that exclude a
     * type's non-properties excludes it, and a subclass reading its parent's
     * fields off a class file sees it beside the field it belongs to.
     *
     * <p>Collecting it mints a second slot for one property, and the entry points
     * then read a value through an accessor that was never generated. The parent
     * has to be compiled for this to appear at all, which is why it is pinned
     * here rather than beside the other chain tests.
     */
    @Test
    public void aLazyAncestorField_doesNotBecomeASecondSlot() throws Exception {
        Compilation parent = compile(JavaFileObjects.forSourceLines("demo.Report",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Lazy;",
            "@ClassBuilder(validate = false)",
            "public abstract class Report {",
            "    @Lazy String summary = compute();",
            "    static String compute() { return \"computed\"; }",
            "}"));
        assertThat(parent).succeeded();
        Path compiled = classesOf(parent);

        Compilation child = compileAgainst(compiled,
            JavaFileObjects.forSourceLines("demo.YearReport",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class YearReport extends Report {",
                "    int year;",
                "    public int getYear() { return year; }",
                "}"));
        assertThat(child).succeeded();

        ClassLoader loader = loadClasses(compiled, child);
        Class<?> reportCls = Class.forName("demo.YearReport", true, loader);
        Class<?> childBuilder = nested(reportCls, "Builder");

        for (String name : methodNames(childBuilder)) {
            assertFalse("the value sibling of a lazy ancestor field became a builder slot: " + name,
                name.contains("$value$"));
        }
        for (java.lang.reflect.Field slot : childBuilder.getDeclaredFields()) {
            assertFalse("the value sibling of a lazy ancestor field became a builder field: "
                    + slot.getName(),
                slot.getName().contains("$value$"));
        }
        assertTrue("the child's own slot is still built",
            methodNames(childBuilder).contains("year"));
    }

    // ------------------------------------------------------------------
    // The runtime-free pin, across the class-file boundary
    // ------------------------------------------------------------------

    /**
     * The pin every other suite applies to a one-round compilation, applied to a
     * chain whose parent was compiled separately. A consumer scopes this artifact
     * compile-only, so a generated member naming a library type is a
     * {@code NoClassDefFoundError} their build cannot catch, and an inherited
     * builder is the shape no existing fixture covers.
     */
    @Test
    public void anInheritedBuilderNamesNoLibraryType() throws Exception {
        Compilation parent = compile(JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public abstract class Base {",
            "    String label;",
            "    @Collector(singular = true) List<String> tags = new ArrayList<>();",
            "}"));
        assertThat(parent).succeeded();
        Path compiled = classesOf(parent);

        Compilation child = compileAgainst(compiled,
            JavaFileObjects.forSourceLines("demo.Leaf",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Leaf extends Base {",
                "    int size;",
                "}"));
        assertThat(child).succeeded();

        for (JavaFileObject file : child.generatedFiles()) {
            if (file.getKind() != JavaFileObject.Kind.CLASS) continue;
            byte[] bytes;
            try (InputStream in = file.openInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                in.transferTo(out);
                bytes = out.toByteArray();
            }
            for (String named : constantPoolTypeNames(bytes)) {
                if (!named.startsWith("dev/simplified/")) continue;
                if (named.startsWith("dev/simplified/annotations/")) continue;
                fail(file.getName() + " names the library type " + named
                    + ", which a compile-only consumer does not have at run time");
            }
        }
    }

    /**
     * Every class name the constant pool mentions, read with the same reader the
     * one-round pin uses.
     */
    private static List<String> constantPoolTypeNames(byte[] bytes) {
        List<String> out = new ArrayList<>();
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) continue;
            int tag = bytes[offset - 1];
            if (tag != 7) continue; // CONSTANT_Class
            try {
                out.add(reader.readUTF8(offset, buffer));
            } catch (RuntimeException ignored) {
                // A constant this reader cannot render is one that names no type.
            }
        }
        return Arrays.asList(out.toArray(new String[0]));
    }

}
