package dev.sbs.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.sbs.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Exercises the SuperBuilder pipeline: abstract-root + concrete-subclass
 * chains, and the auto-generated copy constructor that glues them together.
 */
public class SuperBuilderMutatorTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-super-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, SuperBuilderMutatorTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested '" + simpleName + "' on " + outer);
        return null;
    }

    // ------------------------------------------------------------------
    // Abstract root + concrete subclass round-trip
    // ------------------------------------------------------------------

    @Test
    public void abstractParent_concreteChild_roundTrip() throws Exception {
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Page",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public abstract class Page {",
            "    String title;",
            "    public String getTitle() { return title; }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.TreePage",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class TreePage extends Page {",
            "    int depth;",
            "    public int getDepth() { return depth; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> pageCls = Class.forName("demo.Page", true, cl);
        Class<?> treeCls = Class.forName("demo.TreePage", true, cl);

        // Parent's Builder is abstract + generic
        Class<?> parentBuilder = nested(pageCls, "Builder");
        assertTrue("abstract root Builder must be abstract", Modifier.isAbstract(parentBuilder.getModifiers()));
        assertEquals("T + B type parameters", 2, parentBuilder.getTypeParameters().length);

        // Child's Builder extends parent's generic Builder
        Class<?> childBuilder = nested(treeCls, "Builder");
        assertTrue("concrete-link Builder must not be abstract", !Modifier.isAbstract(childBuilder.getModifiers()));
        Class<?> childBuilderSuper = childBuilder.getSuperclass();
        assertEquals("expected concrete Builder to extend parent's Builder",
            parentBuilder, childBuilderSuper);

        // Round-trip via child bootstrap
        Object b = treeCls.getMethod("builder").invoke(null);
        // withTitle is inherited from parent's Builder; on the concrete subclass
        // instance the return type is still child's Builder by self-typing.
        Object afterTitle = childBuilder.getMethod("withTitle", String.class).invoke(b, "Intro");

        // Inherited setter invoked on the concrete subclass sets the
        // parent-level private field; verify directly via reflection since
        // compile-time access would fail.
        java.lang.reflect.Field titleField = parentBuilder.getDeclaredField("title");
        titleField.setAccessible(true);
        assertEquals("Intro", titleField.get(b));
        assertEquals("self-typed return must remain the concrete builder",
            childBuilder, afterTitle.getClass());
        childBuilder.getMethod("withDepth", int.class).invoke(b, 3);
        Object built = childBuilder.getMethod("build").invoke(b);

        assertNotNull(built);
        assertEquals(treeCls, built.getClass());
        assertEquals("Intro", pageCls.getMethod("getTitle").invoke(built));
        assertEquals(3, treeCls.getMethod("getDepth").invoke(built));

        // from() round-trip exercises the copy constructor chain
        Object b2 = treeCls.getMethod("from", treeCls).invoke(null, built);
        Object rebuilt = childBuilder.getMethod("build").invoke(b2);
        assertEquals("Intro", pageCls.getMethod("getTitle").invoke(rebuilt));
        assertEquals(3, treeCls.getMethod("getDepth").invoke(rebuilt));
    }

    // ------------------------------------------------------------------
    // User-written copy constructor wins (opt-out behavior)
    // ------------------------------------------------------------------

    @Test
    public void userWrittenCopyCtor_respected() throws Exception {
        // When user hand-rolls a copy ctor, injection is skipped; their
        // version takes effect. build() calls new Target(this) which still
        // resolves because their version is compatible.
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Base",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public abstract class Base {",
            "    String name;",
            "    public String getName() { return name; }",
            "    protected Base(Builder<?, ?> b) {",
            "        this.name = b.name + \"!\";  // user-injected suffix",
            "    }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Leaf",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Leaf extends Base {",
            "    int count;",
            "    public int getCount() { return count; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> baseCls = Class.forName("demo.Base", true, cl);
        Class<?> leafCls = Class.forName("demo.Leaf", true, cl);
        Class<?> leafBuilder = nested(leafCls, "Builder");

        Object b = leafCls.getMethod("builder").invoke(null);
        leafBuilder.getMethod("withName", String.class).invoke(b, "hello");
        leafBuilder.getMethod("withCount", int.class).invoke(b, 2);
        Object built = leafBuilder.getMethod("build").invoke(b);
        assertEquals("hello!", baseCls.getMethod("getName").invoke(built));
        assertEquals(2, leafCls.getMethod("getCount").invoke(built));
    }

    // ------------------------------------------------------------------
    // Abstract target has no static builder() / from() bootstraps
    // ------------------------------------------------------------------

    @Test
    public void abstractTarget_doesNotEmitBootstraps() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public abstract class Shape {",
            "    String color;",
            "    public String getColor() { return color; }",
            "}");
        // Need a concrete subclass so the file compiles through further phases,
        // but the assertion is about Shape itself having no bootstraps.
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Red",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Red extends Shape {}");
        Compilation c = compile(src, child);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> shape = Class.forName("demo.Shape", true, cl);
        // No static builder() / from() on the abstract target.
        for (var m : shape.getDeclaredMethods()) {
            assertTrue("abstract target must not declare static 'builder()'; found "
                + m, !(m.getName().equals("builder") && Modifier.isStatic(m.getModifiers())));
            assertTrue("abstract target must not declare static 'from(T)'; found "
                + m, !(m.getName().equals("from") && Modifier.isStatic(m.getModifiers())));
        }
    }

}
