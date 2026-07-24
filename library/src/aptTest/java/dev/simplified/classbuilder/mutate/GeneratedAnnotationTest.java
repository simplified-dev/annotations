package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * That generated members reach the class file carrying {@code @Generated}.
 *
 * <p>The marker is {@code @Retention(CLASS)}, so it is invisible to reflection
 * by design and these tests read the class file's constant pool instead: an
 * annotation on any member interns its type descriptor there, and nothing else
 * in these fixtures would. That proves emission and suppression, which is what
 * regresses silently. Which member each marker landed on is guaranteed
 * structurally instead - {@code AstMarkers.markGenerated} is the single place
 * that attaches one, and it is called only on nodes the pipeline built.
 */
public class GeneratedAnnotationTest {

    private static final String DESCRIPTOR = "Ldev/simplified/annotations/Generated;";

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    /** The compiled bytes of one generated class, decoded so the pool is searchable. */
    private static String classFileText(Compilation compilation, String binaryName) throws Exception {
        String want = binaryName.replace('.', '/') + ".class";
        for (JavaFileObject f : compilation.generatedFiles()) {
            if (f.getKind() != JavaFileObject.Kind.CLASS) continue;
            if (!f.toUri().toString().endsWith(want)) continue;
            try (InputStream in = f.openInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                in.transferTo(baos);
                // ISO-8859-1 is byte-preserving, so no pool entry can be mangled
                // into or out of a false match.
                return new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
            }
        }
        fail("no generated class file for '" + binaryName + "' - got " + compilation.generatedFiles());
        return null;
    }

    private static String generatedSource(Compilation compilation, String fqn) throws Exception {
        Optional<JavaFileObject> out = compilation.generatedSourceFile(fqn);
        if (out.isEmpty()) fail("expected generated source '" + fqn + "'");
        return out.get().getCharContent(false).toString();
    }

    private static JavaFileObject widget(String annotation) {
        return JavaFileObjects.forSourceLines("demo.Widget",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            annotation,
            "public class Widget {",
            "    private String label;",
            "    private int count;",
            "}");
    }

    // ------------------------------------------------------------------
    // AST-mutation path
    // ------------------------------------------------------------------

    @Test
    public void nestedBuilderCarriesTheMarker() throws Exception {
        Compilation c = compile(widget("@ClassBuilder(validate = false)"));
        assertThat(c).succeeded();
        assertTrue("nested Builder should carry @Generated",
            classFileText(c, "demo.Widget$Builder").contains(DESCRIPTOR));
    }

    @Test
    public void targetCarriesTheMarkerForItsInjectedMembers() throws Exception {
        // builder(), from(T), mutate() and the synthesised all-args constructor
        // are injected onto the target itself, which stays a measured class -
        // so the per-member marker is what excludes them, not a type-level one.
        Compilation c = compile(widget("@ClassBuilder(validate = false)"));
        assertThat(c).succeeded();
        assertTrue("target should carry @Generated on its injected members",
            classFileText(c, "demo.Widget").contains(DESCRIPTOR));
    }

    @Test
    public void emitGeneratedFalseSuppressesItEverywhere() throws Exception {
        Compilation c = compile(widget("@ClassBuilder(validate = false, emitGenerated = false)"));
        assertThat(c).succeeded();
        assertFalse("opted out, so the target must not carry it",
            classFileText(c, "demo.Widget").contains(DESCRIPTOR));
        assertFalse("opted out, so the nested Builder must not carry it",
            classFileText(c, "demo.Widget$Builder").contains(DESCRIPTOR));
    }

    @Test
    public void emitContractsFalseLeavesTheMarkerAlone() throws Exception {
        // The two gates answer different questions. Disliking @XContract in
        // decompiled output is no reason to lose coverage filtering.
        Compilation c = compile(widget("@ClassBuilder(validate = false, emitContracts = false)"));
        assertThat(c).succeeded();
        String target = classFileText(c, "demo.Widget");
        assertFalse("emitContracts = false should drop @XContract",
            target.contains("Ldev/simplified/annotations/XContract;"));
        assertTrue("emitContracts = false should not drop @Generated",
            target.contains(DESCRIPTOR));
    }

    @Test
    public void markerSurvivesOnARecordTarget() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Point",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public record Point(int x, int y) { }");
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertTrue(classFileText(c, "demo.Point$Builder").contains(DESCRIPTOR));
    }

    // ------------------------------------------------------------------
    // Sibling-emitter path (interface targets)
    // ------------------------------------------------------------------

    @Test
    public void siblingEmittersMarkTheGeneratedTypes() throws Exception {
        // Every member of these two files is generated, so one type-level
        // annotation removes the whole class from a coverage report. Written
        // fully qualified so it needs no import and cannot collide with
        // javax/jakarta @Generated.
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Shape {",
            "    int sides();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        assertTrue("generated builder should be marked",
            generatedSource(c, "demo.ShapeBuilder")
                .contains("@dev.simplified.annotations.Generated"));
        assertTrue("generated impl should be marked",
            generatedSource(c, "demo.ShapeImpl")
                .contains("@dev.simplified.annotations.Generated"));
    }

    @Test
    public void siblingEmittersHonourTheOptOut() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false, emitGenerated = false)",
            "public interface Shape {",
            "    int sides();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        assertFalse(generatedSource(c, "demo.ShapeBuilder")
            .contains("@dev.simplified.annotations.Generated"));
        assertFalse(generatedSource(c, "demo.ShapeImpl")
            .contains("@dev.simplified.annotations.Generated"));
    }

}
