package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;

/**
 * Type parameters on a {@code @ClassBuilder} target. The nested {@code Builder}
 * is {@code static} and the interface path's builder is a separate top-level
 * class, so neither can see the target's type variables - both have to
 * re-declare them, and every static member that mentions one needs its own copy.
 *
 * <p>Each case compiles a consumer alongside the target rather than only
 * checking that the target itself compiles. A raw or wrongly-parameterised
 * builder still compiles on its own; it only fails where someone assigns the
 * result to a parameterised type, which is what these consumers do.
 */
public class GenericTargetTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-generic-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, GenericTargetTest.class.getClassLoader());
    }

    /** Invokes the compiled consumer's {@code go()} so the generics are exercised at runtime too. */
    private static Object runGo(Compilation c, String consumer) throws Exception {
        return Class.forName(consumer, true, loadClasses(c)).getMethod("go").invoke(null);
    }

    // ------------------------------------------------------------------
    // Plain classes and records
    // ------------------------------------------------------------------

    @Test
    public void genericClass_roundTripsThroughAllBootstraps() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Crate",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Crate<V> {",
                "    V item;",
                "    List<V> spares = new ArrayList<>();",
                "    public V getItem() { return item; }",
                "    public List<V> getSpares() { return spares; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseCrate",
                "package demo;",
                "public class UseCrate {",
                "    public static String go() {",
                "        Crate<String> a = Crate.<String>builder().item(\"x\").build();",
                "        Crate<String> b = Crate.from(a).item(\"y\").build();",
                "        Crate<String> d = b.mutate().item(\"z\").build();",
                "        String s = d.getItem();",
                "        return s + d.getSpares().size();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("z0", runGo(c, "demo.UseCrate"));
    }

    /**
     * The retained initializer moves into a {@code static $default$} provider,
     * which cannot see the class's type variables either and so declares its
     * own - the call site infers them back from the builder slot.
     */
    @Test
    public void genericClass_retainedInitializerOnAParameterisedField() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Seeded",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.ArrayList;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public class Seeded<V> {",
                "    List<V> values = new ArrayList<>();",
                "    public List<V> getValues() { return values; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSeeded",
                "package demo;",
                "public class UseSeeded {",
                "    public static Integer go() {",
                "        Seeded<String> s = Seeded.<String>builder().build();",
                "        return s.getValues().size();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals(0, runGo(c, "demo.UseSeeded"));
    }

    @Test
    public void genericClass_boundedTypeParameterKeepsItsBound() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Ranked",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Ranked<V extends Comparable<V>> {",
                "    V key;",
                "    public V getKey() { return key; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRanked",
                "package demo;",
                "public class UseRanked {",
                "    public static String go() {",
                "        Ranked<String> r = Ranked.<String>builder().key(\"a\").build();",
                "        return r.getKey();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a", runGo(c, "demo.UseRanked"));
    }

    @Test
    public void genericRecord_buildsThroughTheCanonicalConstructor() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Pair",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public record Pair<A, B>(A left, B right) {}"),
            JavaFileObjects.forSourceLines("demo.UsePair",
                "package demo;",
                "public class UsePair {",
                "    public static String go() {",
                "        Pair<String, Integer> p = Pair.<String, Integer>builder()",
                "            .left(\"a\").right(1).build();",
                "        String l = p.left();",
                "        Integer r = p.right();",
                "        return l + r;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a1", runGo(c, "demo.UsePair"));
    }

    /** Two parameters threaded through {@code @Collector} map and list shapes. */
    @Test
    public void genericClass_collectorShapesCarryBothParameters() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Reg",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import dev.simplified.annotations.Collector;",
                "import java.util.List;",
                "import java.util.Map;",
                "@ClassBuilder(validate = false)",
                "public class Reg<K, V> {",
                "    @Collector(singular = true) Map<K, V> entries;",
                "    @Collector(singular = true) List<V> extras;",
                "    public Map<K, V> getEntries() { return entries; }",
                "    public List<V> getExtras() { return extras; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseReg",
                "package demo;",
                "import java.util.List;",
                "import java.util.Map;",
                "public class UseReg {",
                "    public static String go() {",
                "        Reg<String, Integer> r = Reg.<String, Integer>builder()",
                "            .putEntry(\"k\", 1).addExtra(2).build();",
                "        Map<String, Integer> m = r.getEntries();",
                "        List<Integer> x = r.getExtras();",
                "        return m.get(\"k\") + \"/\" + x.get(0);",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("1/2", runGo(c, "demo.UseReg"));
    }

    /**
     * The type parameter has to survive every hop of the chain. A setter
     * returning the builder's bare name instead of its parameterised form
     * erases it to a raw type, and from there {@code build()} yields a raw
     * target whose getters return {@code Object} - which still assigns to a
     * parameterised local under an unchecked warning, so only consuming the
     * result directly exposes it.
     */
    @Test
    public void genericClass_typeParameterSurvivesTheWholeSetterChain() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Chain",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Chain<V> {",
                "    V item;",
                "    int n;",
                "    public V getItem() { return item; }",
                "    public int getN() { return n; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseChain",
                "package demo;",
                "public class UseChain {",
                "    public static String go() {",
                "        // No intermediate local: a raw builder makes getItem()",
                "        // return Object and this stops compiling.",
                "        String s = Chain.<String>builder().item(\"x\").n(1).build().getItem();",
                "        return s;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x", runGo(c, "demo.UseChain"));
    }

    /**
     * {@code builder()} is a generic static method with nothing in a chained
     * call to infer from, so the parameter settles on {@code Object} and only
     * the explicit witness gives a typed chain - matching Lombok's generic
     * {@code @Builder}. Assigning the bare form to a parameterised local still
     * compiles, under an unchecked warning; consuming it directly does not.
     */
    @Test
    public void genericClass_bareBuilderCallNeedsTheTypeWitness() {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Bare",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Bare<V> {",
                "    V item;",
                "    public V getItem() { return item; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseBare",
                "package demo;",
                "public class UseBare {",
                "    public static void go() {",
                "        String s = Bare.builder().item(\"x\").build().getItem();",
                "    }",
                "}"));
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("incompatible types");
    }

    // ------------------------------------------------------------------
    // SuperBuilder chains
    // ------------------------------------------------------------------

    /** Generic abstract root whose concrete link binds the parameter. */
    @Test
    public void superBuilder_genericRootWithBoundLink() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Box",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Box<V> {",
                "    V item;",
                "    public V getItem() { return item; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.SBox",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class SBox extends Box<String> {",
                "    int n;",
                "    public int getN() { return n; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSBox",
                "package demo;",
                "public class UseSBox {",
                "    public static String go() {",
                "        SBox s = SBox.builder().item(\"x\").n(1).build();",
                "        SBox t = SBox.from(s).n(2).build();",
                "        // The inherited setter binds V to String through the",
                "        // link, so getItem() is a String without a cast.",
                "        String item = t.mutate().n(3).build().getItem();",
                "        return item + t.mutate().n(3).build().getN();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x3", runGo(c, "demo.UseSBox"));
    }

    /** Generic abstract root whose concrete link forwards the parameter. */
    @Test
    public void superBuilder_genericRootWithGenericLink() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Base",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Base<V> {",
                "    V item;",
                "    public V getItem() { return item; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Impl",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Impl<V> extends Base<V> {",
                "    int n;",
                "    public int getN() { return n; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseImpl",
                "package demo;",
                "public class UseImpl {",
                "    public static String go() {",
                "        // Consumed without an intermediate local, so a raw or",
                "        // wrongly-bound chain fails rather than warning.",
                "        String item = Impl.<String>builder().item(\"x\").n(1).build().getItem();",
                "        return item + Impl.<String>builder().n(1).build().getN();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("x1", runGo(c, "demo.UseImpl"));
    }

    /** Generic root, generic abstract middle, bound leaf - the chained-abstract shape. */
    @Test
    public void superBuilder_genericThreeLevelChain() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.R",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class R<V> {",
                "    V a;",
                "    public V getA() { return a; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.M",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class M<V> extends R<V> {",
                "    V b;",
                "    public V getB() { return b; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.L",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class L extends M<String> {",
                "    int c;",
                "    public int getC() { return c; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseL",
                "package demo;",
                "public class UseL {",
                "    public static String go() {",
                "        L l = L.builder().a(\"A\").b(\"B\").c(3).build();",
                "        String a = l.getA();",
                "        String b = l.getB();",
                "        return a + b + l.getC();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("AB3", runGo(c, "demo.UseL"));
    }

    /**
     * A target declaring parameters named {@code T} and {@code B} collides with
     * the SuperBuilder self-type parameters, which are renamed out of the way.
     */
    @Test
    public void superBuilder_targetParametersNamedTAndB() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Holder",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class Holder<T, B> {",
                "    T value;",
                "    B other;",
                "    public T getValue() { return value; }",
                "    public B getOther() { return other; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.SHolder",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class SHolder extends Holder<String, Integer> {",
                "    int n;",
                "    public int getN() { return n; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseSHolder",
                "package demo;",
                "public class UseSHolder {",
                "    public static String go() {",
                "        SHolder h = SHolder.builder().value(\"v\").other(7).n(1).build();",
                "        String v = h.getValue();",
                "        Integer o = h.getOther();",
                "        return v + o + h.getN();",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("v71", runGo(c, "demo.UseSHolder"));
    }

    // ------------------------------------------------------------------
    // Interfaces
    // ------------------------------------------------------------------

    /** Generic interface - the sibling-emitted Impl and Builder both re-declare the parameter. */
    @Test
    public void genericInterface_siblingImplAndBuilderAreParameterised() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Repo",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "import java.util.List;",
                "@ClassBuilder(validate = false)",
                "public interface Repo<T> {",
                "    T head();",
                "    List<T> all();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseRepo",
                "package demo;",
                "import java.util.List;",
                "public class UseRepo {",
                "    public static String go() {",
                "        // Consumed without an intermediate local, so a raw or",
                "        // wrongly-bound chain fails rather than warning.",
                "        String head = new RepoBuilder<String>()",
                "            .head(\"h\").all(List.of(\"a\")).build().head();",
                "        Repo<String> r = new RepoBuilder<String>().head(\"h\").all(List.of(\"a\")).build();",
                "        String first = RepoBuilder.from(r).build().all().get(0);",
                "        return head + first;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("ha", runGo(c, "demo.UseRepo"));
    }

    @Test
    public void genericInterface_boundedParameterKeepsItsBound() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Keyed",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public interface Keyed<K extends Comparable<K>> {",
                "    K key();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseKeyed",
                "package demo;",
                "public class UseKeyed {",
                "    public static String go() {",
                "        Keyed<String> k = new KeyedBuilder<String>().key(\"a\").build();",
                "        String key = k.key();",
                "        return key;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("a", runGo(c, "demo.UseKeyed"));
    }

    // ------------------------------------------------------------------
    // Non-generic targets are unaffected
    // ------------------------------------------------------------------

    /**
     * Every emitter now asks the target for type parameters. A non-generic one
     * answers with nothing, and the output has to be byte-for-byte what it
     * always was - no stray {@code <>}, no raw types.
     */
    @Test
    public void nonGenericTargets_areUnchangedAcrossAllThreePaths() throws Exception {
        Compilation c = compile(
            JavaFileObjects.forSourceLines("demo.Plain",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class Plain {",
                "    String a = \"x\";",
                "    public String getA() { return a; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.P",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public abstract class P {",
                "    String t;",
                "    public String getT() { return t; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.K",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public class K extends P {",
                "    int n;",
                "    public int getN() { return n; }",
                "}"),
            JavaFileObjects.forSourceLines("demo.Simple",
                "package demo;",
                "import dev.simplified.annotations.ClassBuilder;",
                "@ClassBuilder(validate = false)",
                "public interface Simple {",
                "    String name();",
                "}"),
            JavaFileObjects.forSourceLines("demo.UseAll",
                "package demo;",
                "public class UseAll {",
                "    public static String go() {",
                "        String a = Plain.builder().a(\"y\").build().getA();",
                "        K k = K.builder().t(\"t\").n(1).build();",
                "        String t = K.from(k).build().getT() + k.mutate().build().getN();",
                "        String n = new SimpleBuilder().name(\"n\").build().name();",
                "        return a + t + n;",
                "    }",
                "}"));
        assertThat(c).succeeded();
        assertEquals("yt1n", runGo(c, "demo.UseAll"));
    }

}
