package dev.sbs.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.sbs.classbuilder.apt.ClassBuilderProcessor;
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
import java.util.List;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // ------------------------------------------------------------------
    // Advanced setter shapes on the abstract parent
    // ------------------------------------------------------------------

    /**
     * {@code @Collector} on an abstract parent: child inherits the full
     * varargs-replace + iterable-replace + add + clear family with the
     * concrete child Builder threaded through self-typed returns.
     */
    @Test
    public void collectorOnAbstractParent_inheritedThroughChild() throws Exception {
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Page",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Collector;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public abstract class Page {",
            "    @Collector(singular = true, clearable = true) List<String> tags;",
            "    public List<String> getTags() { return tags; }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.HomePage",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class HomePage extends Page {",
            "    int order;",
            "    public int getOrder() { return order; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> pageCls = Class.forName("demo.Page", true, cl);
        Class<?> homeCls = Class.forName("demo.HomePage", true, cl);
        Class<?> homeBuilder = nested(homeCls, "Builder");

        // varargs-replace, then withTag (singular add via inherited setter)
        Object b = homeCls.getMethod("builder").invoke(null);
        Object after = homeBuilder.getMethod("withTags", String[].class)
            .invoke(b, (Object) new String[]{"alpha", "beta"});
        assertEquals("self-typed returns must remain the concrete child Builder",
            homeBuilder, after.getClass());
        homeBuilder.getMethod("withTag", String.class).invoke(b, "gamma");
        Object built = homeBuilder.getMethod("build").invoke(b);
        assertEquals(List.of("alpha", "beta", "gamma"), pageCls.getMethod("getTags").invoke(built));

        // iterable-replace overload exists and works
        Object b2 = homeCls.getMethod("builder").invoke(null);
        homeBuilder.getMethod("withTags", Iterable.class).invoke(b2, List.of("x", "y"));
        assertEquals(List.of("x", "y"),
            pageCls.getMethod("getTags").invoke(homeBuilder.getMethod("build").invoke(b2)));

        // clearTags lives on the parent, callable via the child
        Object b3 = homeCls.getMethod("builder").invoke(null);
        homeBuilder.getMethod("withTags", String[].class).invoke(b3, (Object) new String[]{"to-clear"});
        homeBuilder.getMethod("clearTags").invoke(b3);
        assertTrue(((List<?>) pageCls.getMethod("getTags").invoke(homeBuilder.getMethod("build").invoke(b3))).isEmpty());
    }

    /** {@code @Collector} on a Map field placed on the abstract parent. */
    @Test
    public void collectorMapOnAbstractParent_putAndClear() throws Exception {
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Doc",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Collector;",
            "import java.util.Map;",
            "@ClassBuilder(validate = false)",
            "public abstract class Doc {",
            "    @Collector(singularMethodName = \"meta\", singular = true, clearable = true) Map<String, String> metas;",
            "    public Map<String, String> getMetas() { return metas; }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Article",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Article extends Doc {",
            "    String body;",
            "    public String getBody() { return body; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> docCls = Class.forName("demo.Doc", true, cl);
        Class<?> articleCls = Class.forName("demo.Article", true, cl);
        Class<?> articleBuilder = nested(articleCls, "Builder");

        Object b = articleCls.getMethod("builder").invoke(null);
        articleBuilder.getMethod("putMeta", String.class, String.class).invoke(b, "k1", "v1");
        articleBuilder.getMethod("putMeta", String.class, String.class).invoke(b, "k2", "v2");
        Object built = articleBuilder.getMethod("build").invoke(b);
        assertEquals(2, ((java.util.Map<?, ?>) docCls.getMethod("getMetas").invoke(built)).size());

        // clearMetas wipes
        Object b2 = articleCls.getMethod("builder").invoke(null);
        articleBuilder.getMethod("putMeta", String.class, String.class).invoke(b2, "k", "v");
        articleBuilder.getMethod("clearMetas").invoke(b2);
        assertTrue(((java.util.Map<?, ?>) docCls.getMethod("getMetas")
            .invoke(articleBuilder.getMethod("build").invoke(b2))).isEmpty());
    }

    /** {@code @Negate} on a boolean field placed on the abstract parent. */
    @Test
    public void negateOnAbstractParent_inversePairInherited() throws Exception {
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Switch",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Negate;",
            "@ClassBuilder(validate = false)",
            "public abstract class Switch {",
            "    @Negate(\"closed\") boolean open;",
            "    public boolean isOpen() { return open; }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Gate",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Gate extends Switch {",
            "    int height;",
            "    public int getHeight() { return height; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> switchCls = Class.forName("demo.Switch", true, cl);
        Class<?> gateCls = Class.forName("demo.Gate", true, cl);
        Class<?> gateBuilder = nested(gateCls, "Builder");

        // isOpen() sets open=true
        Object b = gateCls.getMethod("builder").invoke(null);
        gateBuilder.getMethod("isOpen").invoke(b);
        assertEquals(Boolean.TRUE,
            switchCls.getMethod("isOpen").invoke(gateBuilder.getMethod("build").invoke(b)));

        // isClosed() sets open=false (inverse)
        Object b2 = gateCls.getMethod("builder").invoke(null);
        gateBuilder.getMethod("isClosed").invoke(b2);
        assertEquals(Boolean.FALSE,
            switchCls.getMethod("isOpen").invoke(gateBuilder.getMethod("build").invoke(b2)));

        // Typed forms: isClosed(true) -> open=false; isClosed(false) -> open=true
        Object b3 = gateCls.getMethod("builder").invoke(null);
        gateBuilder.getMethod("isClosed", boolean.class).invoke(b3, true);
        assertEquals(Boolean.FALSE,
            switchCls.getMethod("isOpen").invoke(gateBuilder.getMethod("build").invoke(b3)));
        Object b4 = gateCls.getMethod("builder").invoke(null);
        gateBuilder.getMethod("isClosed", boolean.class).invoke(b4, false);
        assertEquals(Boolean.TRUE,
            switchCls.getMethod("isOpen").invoke(gateBuilder.getMethod("build").invoke(b4)));
    }

    /** {@code @Formattable} on a String field placed on the abstract parent. */
    @Test
    public void formattableOnAbstractParent_overloadInherited() throws Exception {
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Notice",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Formattable;",
            "@ClassBuilder(validate = false)",
            "public abstract class Notice {",
            "    @Formattable String message;",
            "    public String getMessage() { return message; }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Banner",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Banner extends Notice {",
            "    int priority;",
            "    public int getPriority() { return priority; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> noticeCls = Class.forName("demo.Notice", true, cl);
        Class<?> bannerCls = Class.forName("demo.Banner", true, cl);
        Class<?> bannerBuilder = nested(bannerCls, "Builder");

        // Plain inherited setter
        Object b = bannerCls.getMethod("builder").invoke(null);
        bannerBuilder.getMethod("withMessage", String.class).invoke(b, "hi");
        assertEquals("hi",
            noticeCls.getMethod("getMessage").invoke(bannerBuilder.getMethod("build").invoke(b)));

        // @PrintFormat overload inherited from parent
        Object b2 = bannerCls.getMethod("builder").invoke(null);
        Method formatted = bannerBuilder.getMethod("withMessage", String.class, Object[].class);
        formatted.invoke(b2, "code=%d", new Object[]{42});
        assertEquals("code=42",
            noticeCls.getMethod("getMessage").invoke(bannerBuilder.getMethod("build").invoke(b2)));
    }

    /** {@code @Formattable} on an Optional<String> field on the abstract parent. */
    @Test
    public void formattableOptionalOnAbstractParent_nullSafe() throws Exception {
        JavaFileObject parent = JavaFileObjects.forSourceLines("demo.Item",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Formattable;",
            "import java.util.Optional;",
            "@ClassBuilder(validate = false)",
            "public abstract class Item {",
            "    @Formattable Optional<String> caption;",
            "    public Optional<String> getCaption() { return caption; }",
            "}");
        JavaFileObject child = JavaFileObjects.forSourceLines("demo.Photo",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Photo extends Item {",
            "    String url;",
            "    public String getUrl() { return url; }",
            "}");
        Compilation c = compile(parent, child);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> itemCls = Class.forName("demo.Item", true, cl);
        Class<?> photoCls = Class.forName("demo.Photo", true, cl);
        Class<?> photoBuilder = nested(photoCls, "Builder");

        // Format-string overload inherited
        Object b = photoCls.getMethod("builder").invoke(null);
        Method formatted = photoBuilder.getMethod("withCaption", String.class, Object[].class);
        formatted.invoke(b, "tag=%s", new Object[]{"sunset"});
        Optional<?> got = (Optional<?>) itemCls.getMethod("getCaption")
            .invoke(photoBuilder.getMethod("build").invoke(b));
        assertTrue(got.isPresent());
        assertEquals("tag=sunset", got.get());

        // Null format yields Optional.empty
        Object b2 = photoCls.getMethod("builder").invoke(null);
        formatted.invoke(b2, null, new Object[0]);
        Optional<?> empty = (Optional<?>) itemCls.getMethod("getCaption")
            .invoke(photoBuilder.getMethod("build").invoke(b2));
        assertFalse(empty.isPresent());
    }

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
