package dev.sbs.classbuilder.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * The one remaining sibling-file path after the Phase 6 cutover:
 * interface targets still generate a {@code <Name>Impl.java} alongside a
 * {@code <Name>Builder.java} because there is no in-source mutation surface
 * on an interface body to inject into.
 */
public class InterfaceTargetTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static String generatedSource(Compilation compilation, String fqn) {
        Optional<JavaFileObject> out = compilation.generatedSourceFile(fqn);
        if (out.isEmpty()) {
            throw new AssertionError("expected generated source '" + fqn + "' - generated files: "
                + compilation.generatedFiles());
        }
        try {
            return out.get().getCharContent(false).toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void interfaceTarget_emitsImplAndBuilder() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Shape",
            "package demo;",
            "import dev.sbs.annotation.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Shape {",
            "    int sides();",
            "    String name();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.ShapeImpl");
        assertTrue(impl, impl.contains("final class ShapeImpl implements Shape"));
        assertTrue(impl, impl.contains("private final int sides;"));
        assertTrue(impl, impl.contains("private final String name;"));

        String builder = generatedSource(c, "demo.ShapeBuilder");
        assertTrue(builder, builder.contains("public class ShapeBuilder"));
        assertTrue(builder, builder.contains("withSides"));
        assertTrue(builder, builder.contains("withName"));
        assertTrue(builder, builder.contains("return new ShapeImpl(sides, name);"));
    }

}
