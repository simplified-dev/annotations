package dev.simplified.classbuilder.apt;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Two types wanting one simple name in a generated file, on both source
 * emitters. An import set keyed on the fully qualified name cannot see the
 * clash, so it imports both and javac rejects the file on a line the consumer
 * cannot edit - which is why the assertions here lead with compilation success
 * and only then pin the spelling.
 */
public class ImportCollisionTest {

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

    private static final JavaFileObject ACME_ARRAYS = JavaFileObjects.forSourceLines("acme.Arrays",
        "package acme;",
        "public class Arrays { }");

    private static final JavaFileObject ZOO_ARRAYS = JavaFileObjects.forSourceLines("zoo.Arrays",
        "package zoo;",
        "public class Arrays { }");

    private static final JavaFileObject ACME_STRING = JavaFileObjects.forSourceLines("acme.String",
        "package acme;",
        "public class String { }");

    /**
     * Targets {@code TYPE_USE} and nothing else, so javac renders it inside the
     * qualified return type and it is not also a declaration annotation on the
     * accessor.
     */
    private static final JavaFileObject MARKED = JavaFileObjects.forSourceLines("demo.Marked",
        "package demo;",
        "import java.lang.annotation.ElementType;",
        "import java.lang.annotation.Retention;",
        "import java.lang.annotation.RetentionPolicy;",
        "import java.lang.annotation.Target;",
        "@Target(ElementType.TYPE_USE)",
        "@Retention(RetentionPolicy.CLASS)",
        "public @interface Marked { }");

    // ------------------------------------------------------------------
    // Two ordinary types, one simple name
    // ------------------------------------------------------------------

    @Test
    public void twoTypesSharingASimpleName_bothGeneratedFilesCompile() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Board",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Board {",
            "    acme.Arrays first();",
            "    zoo.Arrays second();",
            "    int[] cells();",
            "}");
        assertThat(compile(ACME_ARRAYS, ZOO_ARRAYS, src)).succeeded();
    }

    @Test
    public void twoTypesSharingASimpleName_implImportsTheFirstAndSpellsOutTheSecond() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Board",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Board {",
            "    acme.Arrays first();",
            "    zoo.Arrays second();",
            "    int[] cells();",
            "}");
        Compilation c = compile(ACME_ARRAYS, ZOO_ARRAYS, src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.BoardImpl");
        assertTrue(impl, impl.contains("import acme.Arrays;"));
        assertFalse(impl, impl.contains("import zoo.Arrays;"));
        assertTrue(impl, impl.contains("private final Arrays first;"));
        assertTrue(impl, impl.contains("private final zoo.Arrays second;"));
        // The helper the primitive array reads through keeps its own qualified
        // spelling, which is what leaves the simple name free for the field.
        assertTrue(impl, impl.contains("java.util.Arrays.equals(this.cells, other.cells)"));
    }

    @Test
    public void twoTypesSharingASimpleName_builderImportsTheFirstAndSpellsOutTheSecond() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Board",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Board {",
            "    acme.Arrays first();",
            "    zoo.Arrays second();",
            "    int[] cells();",
            "}");
        Compilation c = compile(ACME_ARRAYS, ZOO_ARRAYS, src);
        assertThat(c).succeeded();

        String builder = generatedSource(c, "demo.BoardBuilder");
        assertTrue(builder, builder.contains("import acme.Arrays;"));
        assertFalse(builder, builder.contains("import zoo.Arrays;"));
        assertTrue(builder, builder.contains("private Arrays first;"));
        assertTrue(builder, builder.contains("private zoo.Arrays second;"));
    }

    // ------------------------------------------------------------------
    // The java.lang shadowing hazard
    // ------------------------------------------------------------------

    @Test
    public void aTargetTypeNamedString_doesNotShadowJavaLangForTheRestOfTheFile() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Label",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Label {",
            "    acme.String tag();",
            "    java.lang.String name();",
            "}");
        Compilation c = compile(ACME_STRING, src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.LabelImpl");
        assertTrue(impl, impl.contains("import acme.String;"));
        assertTrue(impl, impl.contains("private final String tag;"));
        assertTrue(impl, impl.contains("private final java.lang.String name;"));
        // The member that makes the shadow fatal rather than cosmetic: a
        // toString returning acme.String overrides nothing.
        assertTrue(impl, impl.contains("@Override public java.lang.String toString()"));

        String builder = generatedSource(c, "demo.LabelBuilder");
        assertTrue(builder, builder.contains("import acme.String;"));
        assertTrue(builder, builder.contains("private String tag;"));
        assertTrue(builder, builder.contains("private java.lang.String name;"));
    }

    // ------------------------------------------------------------------
    // The ordinary case, which must be untouched
    // ------------------------------------------------------------------

    @Test
    public void noCollision_keepsTheBareSimpleNameAndImportsNothingFromJavaLang() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Plain",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import java.time.Instant;",
            "@ClassBuilder(validate = false)",
            "public interface Plain {",
            "    String name();",
            "    Instant when();",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.PlainImpl");
        assertTrue(impl, impl.contains("import java.time.Instant;"));
        assertFalse(impl, impl.contains("import java.lang."));
        assertTrue(impl, impl.contains("private final String name;"));
        assertTrue(impl, impl.contains("private final Instant when;"));
        assertTrue(impl, impl.contains("@Override public String toString()"));

        String builder = generatedSource(c, "demo.PlainBuilder");
        assertTrue(builder, builder.contains("import java.time.Instant;"));
        assertFalse(builder, builder.contains("import java.lang."));
        assertTrue(builder, builder.contains("private String name;"));
        assertTrue(builder, builder.contains("private Instant when;"));
    }

    // ------------------------------------------------------------------
    // A type-use annotation splitting the qualified name
    // ------------------------------------------------------------------

    /**
     * javac renders a type-use annotation inside the qualified name -
     * {@code java.util.@Marked List<java.lang.String>} - so an identifier run
     * ends at the {@code '@'} holding the bare package qualifier
     * {@code "java.util."}. Registering that as a type produces
     * {@code import java.util.;} in both generated files and neither parses,
     * which is why the assertion leads with the whole compilation succeeding.
     */
    @Test
    public void aTypeUseAnnotatedAccessor_doesNotImportABarePackageQualifier() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Card",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public interface Card {",
            "    @Marked List<String> tags();",
            "    java.util.@Marked Map<String, String> meta();",
            "}");
        Compilation c = compile(MARKED, src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.CardImpl");
        assertFalse(impl, impl.contains("import java.util.;"));
        assertTrue(impl, impl.contains("import java.util.List;"));
        assertTrue(impl, impl.contains("import java.util.Map;"));
        // The annotation stays where javac put it, on the type it was written
        // on - the qualifier is rejoined to the type name, not dropped.
        assertTrue(impl, impl.contains("private final @Marked List<String> tags;"));

        String builder = generatedSource(c, "demo.CardBuilder");
        assertFalse(builder, builder.contains("import java.util.;"));
        assertTrue(builder, builder.contains("import java.util.List;"));
        assertTrue(builder, builder.contains("import java.util.Map;"));
    }

    /**
     * The registry's own guard against the same fragment, reached directly
     * because the tokenizers no longer produce one. It is the single funnel
     * every spelling passes through, so a malformed fragment must claim
     * nothing, import nothing, and leave the name it was not entitled to free.
     */
    @Test
    public void aBarePackageQualifier_claimsNothingAndImportsNothing() {
        ImportRegistry registry = new ImportRegistry("demo");
        assertEquals("java.util.", registry.use("java.util."));
        assertEquals("", registry.block());
        assertEquals("List", registry.use("java.util.List"));
        assertEquals("import java.util.List;\n\n", registry.block());
    }

    // ------------------------------------------------------------------
    // The names the generated files write bare
    // ------------------------------------------------------------------

    /**
     * The three names neither emitter ever spells through the registry - the
     * target, the impl and the builder - against field types that want the same
     * spellings. Every other case here collides two field types with each
     * other, which exercises the first-claimant rule; only this one exercises
     * the up-front claim that keeps a bare name meaning what the emitter wrote.
     */
    @Test
    public void fieldTypesNamedLikeTheGeneratedTypes_doNotTakeTheirSpellings() {
        JavaFileObject acmeTile = JavaFileObjects.forSourceLines("acme.Tile",
            "package acme;",
            "public class Tile { }");
        JavaFileObject acmeImpl = JavaFileObjects.forSourceLines("acme.TileImpl",
            "package acme;",
            "public class TileImpl { }");
        JavaFileObject acmeBuilder = JavaFileObjects.forSourceLines("acme.TileBuilder",
            "package acme;",
            "public class TileBuilder { }");
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Tile",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Tile {",
            "    acme.Tile origin();",
            "    acme.TileImpl shadow();",
            "    acme.TileBuilder maker();",
            "}");
        Compilation c = compile(acmeTile, acmeImpl, acmeBuilder, src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.TileImpl");
        assertTrue(impl, impl.contains("implements Tile {"));
        assertTrue(impl, impl.contains("private final acme.Tile origin;"));
        assertTrue(impl, impl.contains("private final acme.TileImpl shadow;"));

        String builder = generatedSource(c, "demo.TileBuilder");
        assertTrue(builder, builder.contains("private acme.Tile origin;"));
        assertTrue(builder, builder.contains("private acme.TileImpl shadow;"));
        assertTrue(builder, builder.contains("private acme.TileBuilder maker;"));
    }

    /**
     * The builder's impl name arrives after construction, so a type-parameter
     * bound resolved any earlier takes the spelling first and {@code build()}
     * then instantiates the bound's type instead of the generated impl.
     */
    @Test
    public void aTypeParameterBoundNamedLikeTheImpl_doesNotTakeItsSpelling() {
        JavaFileObject bound = JavaFileObjects.forSourceLines("acme.BoundImpl",
            "package acme;",
            "public class BoundImpl { }");
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bound",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Bound<T extends acme.BoundImpl> {",
            "    T held();",
            "}");
        Compilation c = compile(bound, src);
        assertThat(c).succeeded();

        String builder = generatedSource(c, "demo.BoundBuilder");
        assertFalse(builder, builder.contains("import acme.BoundImpl;"));
        assertTrue(builder, builder.contains("class BoundBuilder<T extends acme.BoundImpl>"));
        assertTrue(builder, builder.contains("new BoundImpl<>(held)"));
    }

    /**
     * {@code @Override} is the one java.lang name the impl writes as an
     * annotation rather than as a type reference, so an accessor returning a
     * type of that simple name is the one field type that can turn every
     * generated member's marker into a type error.
     */
    @Test
    public void anAccessorTypeNamedOverride_doesNotTakeTheAnnotationsSpelling() {
        JavaFileObject acmeOverride = JavaFileObjects.forSourceLines("acme.Override",
            "package acme;",
            "public class Override { }");
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Marker",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public interface Marker {",
            "    acme.Override flag();",
            "}");
        Compilation c = compile(acmeOverride, src);
        assertThat(c).succeeded();

        String impl = generatedSource(c, "demo.MarkerImpl");
        assertFalse(impl, impl.contains("import acme.Override;"));
        assertTrue(impl, impl.contains("private final acme.Override flag;"));
        assertTrue(impl, impl.contains("@Override public boolean equals("));
        assertTrue(impl, impl.contains("@Override public acme.Override flag()"));
    }

}
