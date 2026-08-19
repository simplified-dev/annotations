package dev.simplified.expand.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the source expander: the members the mutation passes inject appear in
 * the written copy, carrying the documentation of whatever field they derive
 * from, and everything the author wrote survives byte for byte.
 *
 * <p>The javadoc tool runs no annotation processors, so a link to a generated
 * accessor only resolves against a real declaration. These pin the shape of that
 * declaration rather than the doclet's reaction to it.
 */
public class SourceExpansionTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private Path expandTo;

    private Compilation compile(JavaFileObject... sources) throws IOException {
        this.expandTo = this.folder.newFolder("expanded").toPath();
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor(), new SourceExpanderProcessor())
            .withOptions("-A" + SourceExpanderProcessor.EXPAND_TO + "=" + this.expandTo)
            .compile(sources);
    }

    private String expanded(String path) throws IOException {
        Path file = this.expandTo.resolve(path);
        assertTrue("expected an expanded '" + path + "' under " + this.expandTo
            + " - wrote: " + listing(), Files.exists(file));
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private String listing() throws IOException {
        try (var walk = Files.walk(this.expandTo)) {
            return walk.filter(Files::isRegularFile).map(Path::toString).toList().toString();
        }
    }

    // ------------------------------------------------------------------
    // Accessors, and the field documentation they carry
    // ------------------------------------------------------------------

    @Test
    public void getterIsWrittenWithTheFieldsDocumentation() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Person",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "/** A person. */",
            "@Getter",
            "public class Person {",
            "    /** The full legal name. */",
            "    private String name;",
            "}"));
        assertThat(c).succeeded();

        String src = expanded("demo/Person.java");
        assertTrue("the accessor must be declared with an empty body; saw:\n" + src,
            src.contains("public java.lang.String getName() { }"));
        assertTrue("the field's prose must carry onto the accessor; saw:\n" + src,
            src.contains("The full legal name."));
        assertTrue("a copied description without @return earns a doclint warning; saw:\n" + src,
            src.contains("@return the full legal name"));
    }

    @Test
    public void booleanGetterKeepsTheGeneratedName() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Flag",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "/** A flag. */",
            "@Getter",
            "public class Flag {",
            "    /** Whether it is ready. */",
            "    private boolean ready;",
            "}"));
        assertThat(c).succeeded();

        String src = expanded("demo/Flag.java");
        assertTrue("the boolean accessor keeps the name the pass minted; saw:\n" + src,
            src.contains("public boolean isReady() { }"));
    }

    // ------------------------------------------------------------------
    // The authored source survives
    // ------------------------------------------------------------------

    @Test
    public void authoredTextIsCarriedOverVerbatim() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Quirky",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "/** Holds a brace in a string. */",
            "@Getter",
            "public class Quirky {",
            "    /** The label, which mentions a } brace. */",
            "    private String label = \"a } brace\";",
            "    /** Reads through {@link #getLabel()}. */",
            "    public void use() { }",
            "}"));
        assertThat(c).succeeded();

        String src = expanded("demo/Quirky.java");
        assertTrue("the field initializer must survive untouched; saw:\n" + src,
            src.contains("private String label = \"a } brace\";"));
        assertTrue("the authored member must survive untouched; saw:\n" + src,
            src.contains("public void use() { }"));
        assertTrue("the authored link must survive untouched; saw:\n" + src,
            src.contains("Reads through {@link #getLabel()}."));
        assertTrue("a brace inside a string literal must not move the splice; saw:\n" + src,
            src.contains("public java.lang.String getLabel() { }"));
    }

    // ------------------------------------------------------------------
    // Nesting
    // ------------------------------------------------------------------

    @Test
    public void nestedClassMembersAreSplicedIntoTheNestedClass() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Outer",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "/** An outer type. */",
            "public class Outer {",
            "    /** An inner type. */",
            "    @Getter",
            "    public static class Inner {",
            "        /** The inner value. */",
            "        private int value;",
            "    }",
            "}"));
        assertThat(c).succeeded();

        String src = expanded("demo/Outer.java");
        int accessor = src.indexOf("public int getValue() { }");
        int innerClose = src.indexOf("}", src.indexOf("class Inner"));
        assertTrue("the accessor must be written; saw:\n" + src, accessor > 0);
        assertTrue("the accessor must land inside Inner, not beside it; saw:\n" + src,
            accessor < src.lastIndexOf('}'));
        assertTrue("Inner must still close after its accessor; saw:\n" + src, innerClose > 0);
    }

    @Test
    public void anEmptyBodyIsOpenedRatherThanRunInto() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Pixel",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "/** A pixel. */",
            "@EqualsAndHashCode",
            "public record Pixel(",
            "    /** The x coordinate. */",
            "    int x,",
            "    /** The y coordinate. */",
            "    int y",
            ") {}"));
        assertThat(c).succeeded();

        String src = expanded("demo/Pixel.java");
        assertTrue("members must be written into the opened body; saw:\n" + src,
            src.contains("hashCode() { }"));
        // A body written as "{}" carries no whitespace for the closing brace to
        // sit on, so without one the last member and the brace share a line.
        assertFalse("the closing brace must not run into the last member; saw:\n" + src,
            src.contains("}}"));
    }

    // ------------------------------------------------------------------
    // The builder, which is generated whole
    // ------------------------------------------------------------------

    @Test
    public void generatedBuilderIsRenderedAsAWholeNestedClass() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Box",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "/** A box. */",
            "@ClassBuilder(validate = false)",
            "public class Box {",
            "    /** The width in pixels. */",
            "    private int width;",
            "}"));
        assertThat(c).succeeded();

        String src = expanded("demo/Box.java");
        assertTrue("the nested builder must be declared; saw:\n" + src,
            src.contains("class Builder {"));
        assertTrue("the builder entry point must be declared; saw:\n" + src,
            src.contains("builder() { }"));
        assertTrue("build() must be declared inside the builder; saw:\n" + src,
            src.contains("build() { }"));
        assertTrue("the generated constructor must be declared, so the doclet stops "
            + "reporting an implicit one; saw:\n" + src, src.contains("Box(int width) { }"));
    }

    @Test
    public void objectMembersInheritTheirDocumentation() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Pair",
            "package demo;",
            "import dev.simplified.annotations.EqualsAndHashCode;",
            "import dev.simplified.annotations.ToString;",
            "/** A pair. */",
            "@EqualsAndHashCode",
            "@ToString",
            "public class Pair {",
            "    /** The left half. */",
            "    private String left;",
            "}"));
        assertThat(c).succeeded();

        String src = expanded("demo/Pair.java");
        assertTrue("equals must be declared; saw:\n" + src, src.contains("equals(java.lang.Object"));
        assertEquals("each of the three Object members inherits its contract rather than "
                + "carrying a composed sentence; saw:\n" + src,
            3, src.split("\\{@inheritDoc}", -1).length - 1);
    }

    // ------------------------------------------------------------------
    // Declarations the pipeline rewrote in place rather than introduced
    // ------------------------------------------------------------------

    @Test
    public void aRetypedFieldIsNotWrittenASecondTime() throws IOException {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Deferred",
            "package demo;",
            "import dev.simplified.annotations.Lazy;",
            "import java.util.List;",
            "/** A deferred holder. */",
            "public class Deferred {",
            "    /** The expensive value. */",
            "    @Lazy",
            "    private List<String> expensive = compute();",
            "    private static List<String> compute() { return List.of(\"a\"); }",
            "}"));
        assertThat(c).succeeded();

        String src = expanded("demo/Deferred.java");
        assertTrue("the author's declaration must survive verbatim; saw:\n" + src,
            src.contains("private List<String> expensive = compute();"));
        // @Lazy retypes that same declaration in place and marks it, so a filter
        // reading the mark alone writes the field a second time in its retyped
        // form and javadoc reports it as already defined.
        assertFalse("the retyped storage must not be written as a second declaration; saw:\n" + src,
            src.contains("dev.simplified.lazy.Lazy<"));
        assertTrue("the memoizing accessor must still be written; saw:\n" + src,
            src.contains("getExpensive() { }"));
        assertTrue("the accessor must carry the field's prose; saw:\n" + src,
            src.contains("@return the expensive value"));
    }

    // ------------------------------------------------------------------
    // Off by default
    // ------------------------------------------------------------------

    @Test
    public void nothingIsWrittenWithoutTheOption() throws IOException {
        Path unused = this.folder.newFolder("untouched").toPath();
        Compilation c = Compiler.javac()
            .withProcessors(new ClassBuilderProcessor(), new SourceExpanderProcessor())
            .compile(JavaFileObjects.forSourceLines("demo.Plain",
                "package demo;",
                "import dev.simplified.annotations.Getter;",
                "/** A plain type. */",
                "@Getter",
                "public class Plain {",
                "    /** The name. */",
                "    private String name;",
                "}"));
        assertThat(c).succeeded();

        try (var walk = Files.walk(unused)) {
            assertFalse("the expander must be inert until it is asked for",
                walk.anyMatch(Files::isRegularFile));
        }
    }

}
