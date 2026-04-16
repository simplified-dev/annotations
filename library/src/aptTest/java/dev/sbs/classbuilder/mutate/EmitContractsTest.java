package dev.sbs.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.sbs.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Round-trips for {@code @XContract} emission via the AST-mutation path.
 * Mirrors {@link dev.sbs.classbuilder.apt.BuilderEmitter#emitContract}.
 *
 * <p>{@code @XContract} has {@code @Retention(CLASS)}, so
 * {@link java.lang.reflect.Method#getDeclaredAnnotations()} can't observe it.
 * Parsing the class-file bytes via ASM reaches the
 * {@code RuntimeInvisibleAnnotations} attribute where class-retention
 * annotations live.
 */
public class EmitContractsTest {

    private static final String XCONTRACT_DESCRIPTOR = "Ldev/sbs/annotation/XContract;";

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    @Test
    public void emitContractsTrue_everyGeneratedMethodCarriesContract() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Pizza",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Pizza {",
            "    String name;",
            "    int slices;",
            "    boolean vegetarian;",
            "    public Pizza(String name, int slices, boolean vegetarian) {",
            "        this.name = name; this.slices = slices; this.vegetarian = vegetarian;",
            "    }",
            "    public String getName() { return name; }",
            "    public int getSlices() { return slices; }",
            "    public boolean isVegetarian() { return vegetarian; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Map<String, MethodContracts> pizza = readMethodContracts(c, "demo.Pizza");
        Map<String, MethodContracts> builder = readMethodContracts(c, "demo.Pizza$Builder");

        // Target-level bootstrap methods
        assertContract(pizza, "builder", "()Ldemo/Pizza$Builder;", "-> new", null, null);
        assertContract(pizza, "from", "(Ldemo/Pizza;)Ldemo/Pizza$Builder;", "_ -> new", true, null);
        assertContract(pizza, "mutate", "()Ldemo/Pizza$Builder;", "-> new", null, null);

        // Nested Builder: one setter per field + build()
        assertContract(builder, "name", "(Ljava/lang/String;)Ldemo/Pizza$Builder;",
            "_ -> this", null, "this");
        // boolean pair: isVegetarian() + isVegetarian(boolean)
        assertContract(builder, "isVegetarian", "()Ldemo/Pizza$Builder;",
            "-> this", null, "this");
        assertContract(builder, "isVegetarian", "(Z)Ldemo/Pizza$Builder;",
            "_ -> this", null, "this");
        assertContract(builder, "slices", "(I)Ldemo/Pizza$Builder;",
            "_ -> this", null, "this");
        assertContract(builder, "build", "()Ldemo/Pizza;", "-> new", null, null);
    }

    @Test
    public void emitContractsFalse_noAnnotationAttached() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.NoContracts",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false, emitContracts = false)",
            "public class NoContracts {",
            "    int x;",
            "    public NoContracts(int x) { this.x = x; }",
            "    public int getX() { return x; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Map<String, MethodContracts> target = readMethodContracts(c, "demo.NoContracts");
        Map<String, MethodContracts> builder = readMethodContracts(c, "demo.NoContracts$Builder");

        // No @XContract on the generated bootstraps or setters when disabled.
        for (MethodContracts m : target.values()) {
            assertNull("emitContracts=false must suppress @XContract on " + m.key,
                m.contract);
        }
        for (MethodContracts m : builder.values()) {
            assertNull("emitContracts=false must suppress @XContract on Builder." + m.key,
                m.contract);
        }
    }

    @Test
    public void collectorSetters_carryExpectedContracts() throws Exception {
        // Covers the add/put/clear family that has a mix of arities.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bag",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "import dev.sbs.annotation.Collector;",
            "import java.util.List;",
            "import java.util.Map;",
            "@ClassBuilder(validate = false)",
            "public class Bag {",
            "    @Collector(singular = true, clearable = true) List<String> tags;",
            "    @Collector(singularMethodName = \"entry\", singular = true, clearable = true) Map<String, Integer> entries;",
            "    public Bag(List<String> tags, Map<String, Integer> entries) {",
            "        this.tags = tags; this.entries = entries;",
            "    }",
            "    public List<String> getTags() { return tags; }",
            "    public Map<String, Integer> getEntries() { return entries; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Map<String, MethodContracts> builder = readMethodContracts(c, "demo.Bag$Builder");

        // addTag / putEntry (single-param add)
        assertValueOnMethod(builder, "addTag", "_ -> this");
        // putEntry (key, value) - two params
        assertValueOnMethod(builder, "putEntry", "_, _ -> this");
        // clearTags / clearEntries (no params)
        assertValueOnMethod(builder, "clearTags", "-> this");
        assertValueOnMethod(builder, "clearEntries", "-> this");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    /**
     * Returns every method on the named class keyed by {@code name+descriptor},
     * with the {@code @XContract} annotation (if any) parsed out of
     * {@code RuntimeInvisibleAnnotations}.
     */
    private static Map<String, MethodContracts> readMethodContracts(Compilation c, String className) throws IOException {
        byte[] bytes = findClassBytes(c, className);
        assertNotNull("expected class-file output for " + className, bytes);
        Map<String, MethodContracts> out = new LinkedHashMap<>();
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

    private static byte[] findClassBytes(Compilation c, String className) throws IOException {
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

    private static void assertContract(Map<String, MethodContracts> methods,
                                       String name, String descriptor,
                                       String expectedValue, Boolean expectedPure, String expectedMutates) {
        MethodContracts m = methods.get(name + descriptor);
        assertNotNull("no method " + name + descriptor + " in " + methods.keySet(), m);
        assertNotNull("method " + name + descriptor + " carries no @XContract", m.contract);
        assertEquals("value on " + name, expectedValue, m.contract.value);
        assertEquals("pure on " + name, expectedPure, m.contract.pure);
        assertEquals("mutates on " + name, expectedMutates, m.contract.mutates);
    }

    /**
     * Shallower check - asserts the method has an @XContract with a specific
     * value, without enforcing its descriptor. Useful when a method has
     * overloads and we only care that at least one carries the value.
     */
    private static void assertValueOnMethod(Map<String, MethodContracts> methods,
                                            String name, String expectedValue) {
        boolean found = false;
        for (MethodContracts m : methods.values()) {
            if (!m.key.startsWith(name + "(")) continue;
            if (m.contract == null) continue;
            if (expectedValue.equals(m.contract.value)) {
                found = true;
                break;
            }
        }
        if (!found) {
            fail("no @XContract(value=\"" + expectedValue + "\") on any " + name
                + " overload; saw " + methods.keySet());
        }
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
