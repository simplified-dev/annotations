package dev.simplified.enumlookup.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.enumlookup.apt.EnumLookupProcessor;
import org.junit.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * End-to-end mutation tests for {@code @EnumLookup}. Compiles fixtures with
 * the processor, loads the result into a fresh classloader, and exercises the
 * synthesised members reflectively.
 */
public class EnumLookupProcessorTest {

    private static final String XCONTRACT_DESCRIPTOR = "Ldev/simplified/annotations/XContract;";

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new EnumLookupProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("enumlookup-mutate-test");
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
            EnumLookupProcessorTest.class.getClassLoader());
    }

    // ------------------------------------------------------------------
    // Shape A: no @KeyField
    // ------------------------------------------------------------------

    @Test
    public void noKeyField_emitsPerEnumMembersOnly() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Color",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "@EnumLookup",
            "public enum Color { RED, GREEN, BLUE }");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> color = Class.forName("demo.Color", true, cl);

        // CACHED_VALUES field
        Field cv = color.getDeclaredField("CACHED_VALUES");
        cv.setAccessible(true);
        Object[] values = (Object[]) cv.get(null);
        assertEquals(3, values.length);

        // No CACHED_KEYS_* field
        for (Field f : color.getDeclaredFields()) {
            assertFalse("unexpected field " + f.getName(),
                f.getName().startsWith("CACHED_KEYS_"));
        }

        // size()
        Method size = color.getDeclaredMethod("size");
        assertEquals(3, size.invoke(null));

        // forEach(Consumer)
        Method forEachC = color.getDeclaredMethod("forEach", Consumer.class);
        java.util.List<Object> seen = new java.util.ArrayList<>();
        forEachC.invoke(null, (Consumer<Object>) seen::add);
        assertEquals(3, seen.size());

        // forEach(BiConsumer)
        Method forEachBC = color.getDeclaredMethod("forEach", BiConsumer.class);
        Map<Integer, Object> indexed = new HashMap<>();
        forEachBC.invoke(null, (BiConsumer<Integer, Object>) indexed::put);
        assertEquals(3, indexed.size());
        assertSame(values[0], indexed.get(0));
        assertSame(values[2], indexed.get(2));

        // stream / parallelStream
        @SuppressWarnings("unchecked")
        Stream<Object> s = (Stream<Object>) color.getDeclaredMethod("stream").invoke(null);
        assertEquals(3, s.count());
        @SuppressWarnings("unchecked")
        Stream<Object> ps = (Stream<Object>) color.getDeclaredMethod("parallelStream").invoke(null);
        assertEquals(3, ps.count());

        // ofName: case-insensitive
        Method ofName = color.getDeclaredMethod("ofName", String.class);
        assertSame(values[0], ofName.invoke(null, "RED"));
        assertSame(values[0], ofName.invoke(null, "red"));
        assertSame(values[0], ofName.invoke(null, "Red"));
        assertNull(ofName.invoke(null, "PURPLE"));

        // ofOrdinal: bound-checked
        Method ofOrdinal = color.getDeclaredMethod("ofOrdinal", int.class);
        assertSame(values[1], ofOrdinal.invoke(null, 1));
        assertNull(ofOrdinal.invoke(null, -1));
        assertNull(ofOrdinal.invoke(null, 99));

        // findByName / findByOrdinal
        Method findByName = color.getDeclaredMethod("findByName", String.class);
        Optional<?> hit = (Optional<?>) findByName.invoke(null, "blue");
        assertTrue(hit.isPresent());
        assertSame(values[2], hit.get());
        assertTrue(((Optional<?>) findByName.invoke(null, "nope")).isEmpty());

        Method findByOrdinal = color.getDeclaredMethod("findByOrdinal", int.class);
        assertTrue(((Optional<?>) findByOrdinal.invoke(null, 0)).isPresent());
        assertTrue(((Optional<?>) findByOrdinal.invoke(null, -5)).isEmpty());

        // No of<X> methods
        for (Method m : color.getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals("ofName") || n.equals("ofOrdinal")) continue;
            assertFalse("unexpected of-prefix method " + n, n.startsWith("of") && n.length() > 2);
        }
    }

    // ------------------------------------------------------------------
    // Shape B: primitive int @KeyField
    // ------------------------------------------------------------------

    @Test
    public void primitiveIntKey_emitsOfCodeAndFindByCode() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Status",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum Status {",
            "    OK(200), NOT_FOUND(404), ERROR(500);",
            "    @KeyField private final int code;",
            "    Status(int code) { this.code = code; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> status = Class.forName("demo.Status", true, cl);

        // CACHED_KEYS_code as int[]
        Field keys = status.getDeclaredField("CACHED_KEYS_code");
        keys.setAccessible(true);
        int[] kv = (int[]) keys.get(null);
        assertArrayEquals(new int[]{200, 404, 500}, kv);

        // ofCode(int)
        Method ofCode = status.getDeclaredMethod("ofCode", int.class);
        Object notFound = ofCode.invoke(null, 404);
        assertEquals("NOT_FOUND", ((Enum<?>) notFound).name());
        assertNull(ofCode.invoke(null, 999));

        // findByCode(int)
        Method findByCode = status.getDeclaredMethod("findByCode", int.class);
        Optional<?> hit = (Optional<?>) findByCode.invoke(null, 500);
        assertTrue(hit.isPresent());
        assertEquals("ERROR", ((Enum<?>) hit.get()).name());
    }

    // ------------------------------------------------------------------
    // Shape B variant: reference-typed @KeyField (String)
    // ------------------------------------------------------------------

    @Test
    public void stringKey_usesObjectsEqualsWithNullTolerance() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Slug",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum Slug {",
            "    A(\"alpha\"), B(\"beta\"), N(null);",
            "    @KeyField private final String tag;",
            "    Slug(String tag) { this.tag = tag; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> slug = Class.forName("demo.Slug", true, cl);

        Field keys = slug.getDeclaredField("CACHED_KEYS_tag");
        keys.setAccessible(true);
        String[] kv = (String[]) keys.get(null);
        assertArrayEquals(new String[]{"alpha", "beta", null}, kv);

        Method ofTag = slug.getDeclaredMethod("ofTag", String.class);
        assertEquals("A", ((Enum<?>) ofTag.invoke(null, "alpha")).name());
        assertEquals("N", ((Enum<?>) ofTag.invoke(null, (Object) null)).name());
        assertNull(ofTag.invoke(null, "missing"));
    }

    @Test
    public void keyFieldNamedNameCollidesWithPerEnumOfName_emitsNoteAndSkipsPerKey() {
        // Field 'name' would produce ofName(String) per key, colliding with
        // the per-enum ofName(String). The mutator must skip the per-key
        // emission and emit a NOTE pointing at methodName.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Conflict",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum Conflict {",
            "    A(\"alpha\");",
            "    @KeyField private final String name;",
            "    Conflict(String name) { this.name = name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("methodName");
    }

    @Test
    public void stringKey_renamedViaMethodName_avoidsCollision() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Slug2",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum Slug2 {",
            "    A(\"alpha\"), B(\"beta\");",
            "    @KeyField(methodName = \"Slug\") private final String slug;",
            "    Slug2(String slug) { this.slug = slug; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> slug2 = Class.forName("demo.Slug2", true, cl);

        Method ofSlug = slug2.getDeclaredMethod("ofSlug", String.class);
        assertEquals("A", ((Enum<?>) ofSlug.invoke(null, "alpha")).name());
        assertNull(ofSlug.invoke(null, "missing"));
        assertNull(ofSlug.invoke(null, (Object) null));

        Method findBySlug = slug2.getDeclaredMethod("findBySlug", String.class);
        Optional<?> hit = (Optional<?>) findBySlug.invoke(null, "beta");
        assertTrue(hit.isPresent());
    }

    // ------------------------------------------------------------------
    // Multiple @KeyField on one enum
    // ------------------------------------------------------------------

    @Test
    public void multipleKeyFields_perEnumMethodsEmittedOnce() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Multi",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum Multi {",
            "    OK(200, \"ok\"), BAD(400, \"bad\");",
            "    @KeyField private final int code;",
            "    @KeyField(methodName = \"Tag\") private final String slug;",
            "    Multi(int code, String slug) { this.code = code; this.slug = slug; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> multi = Class.forName("demo.Multi", true, cl);

        // Both per-key arrays
        assertEquals(int.class.arrayType(),
            multi.getDeclaredField("CACHED_KEYS_code").getType());
        assertEquals(String[].class, multi.getDeclaredField("CACHED_KEYS_slug").getType());

        // Both lookup pairs
        assertEquals("OK", ((Enum<?>) multi.getDeclaredMethod("ofCode", int.class).invoke(null, 200)).name());
        assertEquals("BAD", ((Enum<?>) multi.getDeclaredMethod("ofTag", String.class).invoke(null, "bad")).name());

        // Per-enum methods exist exactly once
        long sizeCount = 0, ofNameCount = 0;
        for (Method m : multi.getDeclaredMethods()) {
            if ("size".equals(m.getName()) && m.getParameterCount() == 0) sizeCount++;
            if ("ofName".equals(m.getName()) && m.getParameterCount() == 1) ofNameCount++;
        }
        assertEquals(1, sizeCount);
        assertEquals(1, ofNameCount);
    }

    // ------------------------------------------------------------------
    // Strict toggles are IDE-only - runtime never throws
    // ------------------------------------------------------------------

    @Test
    public void strictKeysTrueWithDuplicates_runtimeDoesNotThrow() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Dup",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum Dup {",
            "    A(1), B(1);",
            "    @KeyField(strictKeys = true) private final int code;",
            "    Dup(int code) { this.code = code; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> dup = Class.forName("demo.Dup", true, cl);
        // First match wins.
        Method ofCode = dup.getDeclaredMethod("ofCode", int.class);
        assertEquals("A", ((Enum<?>) ofCode.invoke(null, 1)).name());
    }

    @Test
    public void strictNullKeysTrueWithNull_runtimeDoesNotThrow() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NullSlug",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum NullSlug {",
            "    A(\"a\"), N(null);",
            "    @KeyField(strictNullKeys = true, methodName = \"Tag\") private final String slug;",
            "    NullSlug(String slug) { this.slug = slug; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> ns = Class.forName("demo.NullSlug", true, cl);
        // Class init succeeded; null is matchable.
        Method ofTag = ns.getDeclaredMethod("ofTag", String.class);
        assertEquals("N", ((Enum<?>) ofTag.invoke(null, (Object) null)).name());
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    @Test
    public void keyFieldOnStaticField_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.StaticKey",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum StaticKey {",
            "    ;",
            "    @KeyField private static final int code = 0;",
            "}");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("@KeyField is not allowed on static fields");
    }

    @Test
    public void enumLookupOnNonEnum_emitsWarningAndSkips() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NotEnum",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "@EnumLookup",
            "public class NotEnum {}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("@EnumLookup is only supported on enum types");
    }

    @Test
    public void existingStaticBlock_preserved() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Existing",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "@EnumLookup",
            "public enum Existing {",
            "    A, B;",
            "    public static int counter = 0;",
            "    static { counter = 42; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        ClassLoader cl = loadClasses(c);
        Class<?> e = Class.forName("demo.Existing", true, cl);
        // User's static block ran (counter set to 42)
        assertEquals(42, e.getDeclaredField("counter").getInt(null));
        // Our static block also ran (CACHED_VALUES populated)
        Field cv = e.getDeclaredField("CACHED_VALUES");
        cv.setAccessible(true);
        Object[] values = (Object[]) cv.get(null);
        assertEquals(2, values.length);
    }

    @Test
    public void userDeclaredMethod_winsAndEmitsNote() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Override",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "@EnumLookup",
            "public enum Override {",
            "    A, B;",
            "    public static int size() { return 99; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadNoteContaining("@EnumLookup skipped 'size/0'");
    }

    @Test
    public void keyFieldWithoutEnumLookup_silentlyIgnored() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bare",
            "package demo;",
            "import dev.simplified.annotations.KeyField;",
            "public enum Bare {",
            "    A(1);",
            "    @KeyField private final int code;",
            "    Bare(int code) { this.code = code; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        ClassLoader cl = loadClasses(c);
        Class<?> b = Class.forName("demo.Bare", true, cl);
        // No mutation happened.
        for (Field f : b.getDeclaredFields()) {
            assertFalse("unexpected synthesized field " + f.getName(),
                f.getName().equals("CACHED_VALUES") || f.getName().startsWith("CACHED_KEYS_"));
        }
    }

    // ------------------------------------------------------------------
    // @XContract emission on a representative subset of generated methods
    // ------------------------------------------------------------------

    @Test
    public void emitsXContractOnGeneratedMethods() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Contracted",
            "package demo;",
            "import dev.simplified.annotations.EnumLookup;",
            "import dev.simplified.annotations.KeyField;",
            "@EnumLookup",
            "public enum Contracted {",
            "    A(1);",
            "    @KeyField private final int code;",
            "    Contracted(int code) { this.code = code; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Map<String, MethodContracts> methods = readMethodContracts(c, "demo.Contracted");

        assertValueEquals(methods, "size", "()I", null, true, null);
        assertValueEquals(methods, "stream", "()Ljava/util/stream/Stream;", "-> !null", true, null);
        assertValueEquals(methods, "ofName", "(Ljava/lang/String;)Ldemo/Contracted;", "null -> null", true, null);
        assertValueEquals(methods, "ofCode", "(I)Ldemo/Contracted;", null, true, null);
        assertValueEquals(methods, "findByCode", "(I)Ljava/util/Optional;", "_ -> !null", true, null);
        assertValueEquals(methods, "forEach", "(Ljava/util/function/Consumer;)V", "null -> fail", null, null);
    }

    // ------------------------------------------------------------------
    // ASM-based contract reader (mirrors EmitContractsTest's helpers)
    // ------------------------------------------------------------------

    private static Map<String, MethodContracts> readMethodContracts(Compilation c, String className) throws Exception {
        byte[] bytes = findClassBytes(c, className);
        assertNotNull("expected class-file output for " + className, bytes);
        Map<String, MethodContracts> out = new java.util.LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodContracts slot = new MethodContracts(name + descriptor);
                out.put(slot.key, slot);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                        if (!XCONTRACT_DESCRIPTOR.equals(annDesc)) return null;
                        slot.contract = new ContractDescriptor();
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                switch (name) {
                                    case "value" -> slot.contract.value = (String) value;
                                    case "pure" -> slot.contract.pure = (Boolean) value;
                                    case "mutates" -> slot.contract.mutates = (String) value;
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return out;
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

    private static void assertValueEquals(Map<String, MethodContracts> methods,
                                          String name, String descriptor,
                                          String expectedValue, Boolean expectedPure, String expectedMutates) {
        MethodContracts m = methods.get(name + descriptor);
        assertNotNull("no method " + name + descriptor + " in " + methods.keySet(), m);
        assertNotNull("method " + name + descriptor + " carries no @XContract", m.contract);
        assertEquals("value on " + name, expectedValue, m.contract.value);
        assertEquals("pure on " + name, expectedPure, m.contract.pure);
        assertEquals("mutates on " + name, expectedMutates, m.contract.mutates);
    }

    private static final class MethodContracts {
        final String key;
        ContractDescriptor contract;

        MethodContracts(String key) { this.key = key; }
    }

    private static final class ContractDescriptor {
        String value;
        Boolean pure;
        String mutates;
    }
}
