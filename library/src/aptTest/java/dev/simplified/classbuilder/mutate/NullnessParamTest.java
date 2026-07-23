package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression + feature coverage for nullness on generated setter parameters.
 *
 * <p>A field whose type carries a type-use {@code @NotNull}/{@code @Nullable}
 * (the near-universal shape in real codebases) once corrupted the generated
 * code: {@code TypeMirror.toString()} splices the annotation into the
 * qualified-name string and the type reconstruction turned {@code @org} into a
 * package segment. {@code parseType} now strips the type-use annotation from
 * the type, and {@link FieldMutators} re-emits it as a plain declaration
 * annotation on the setter parameter. This test proves both halves: the
 * fixture compiles, and the {@code @NotNull}/{@code @Nullable} hint survives on
 * the generated setter parameter.
 *
 * <p>{@code org.jetbrains.annotations.NotNull}/{@code Nullable} are
 * {@code @Retention(CLASS)}, so reflection can't observe them - the class-file
 * bytes are parsed with ASM, the same approach {@link EmitContractsTest} uses
 * for {@code @XContract}.
 */
public class NullnessParamTest {

    private static final String NOT_NULL = "Lorg/jetbrains/annotations/NotNull;";
    private static final String NULLABLE = "Lorg/jetbrains/annotations/Nullable;";

    @Test
    public void typeUseNullnessField_compilesAndSetterParamKeepsHint() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Boxed",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import org.jetbrains.annotations.NotNull;",
            "import org.jetbrains.annotations.Nullable;",
            "@ClassBuilder(validate = false)",
            "public class Boxed {",
            "    @NotNull String required;",
            "    @Nullable String optional;",
            "    public Boxed(@NotNull String required, @Nullable String optional) {",
            "        this.required = required; this.optional = optional;",
            "    }",
            "    public String getRequired() { return required; }",
            "    public String getOptional() { return optional; }",
            "}");
        Compilation c = compile(src);
        // Compilation success alone is the BUG-1 regression guard: a type-use
        // @NotNull on a field type used to blow up with "package
        // demo.@org.jetbrains.annotations does not exist".
        assertThat(c).succeeded();

        Map<String, Set<String>> params = readParamAnnotations(c, "demo.Boxed$Builder");

        assertTrue("required(String) setter must carry @NotNull on its parameter, saw " + params,
            annotationsFor(params, "required").contains(NOT_NULL));
        assertTrue("optional(String) setter must carry @Nullable on its parameter, saw " + params,
            annotationsFor(params, "optional").contains(NULLABLE));
    }

    /**
     * A field with no nullness annotation must still get a bare parameter -
     * the re-emission only restores what the field actually declared, it does
     * not invent a hint.
     */
    @Test
    public void plainField_setterParamHasNoNullnessHint() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Plain",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "@ClassBuilder(validate = false)",
            "public class Plain {",
            "    String name;",
            "    public Plain(String name) { this.name = name; }",
            "    public String getName() { return name; }",
            "}");
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Map<String, Set<String>> params = readParamAnnotations(c, "demo.Plain$Builder");
        Set<String> nameParam = annotationsFor(params, "name");
        assertTrue("plain field setter must not carry @NotNull, saw " + nameParam,
            !nameParam.contains(NOT_NULL));
        assertTrue("plain field setter must not carry @Nullable, saw " + nameParam,
            !nameParam.contains(NULLABLE));
    }

    /**
     * The interface target's twin of the first test, on the other emission
     * path. {@code BuilderEmitter} writes source text rather than mutating a
     * tree, and it wrote the nullability annotation twice: once as its own
     * declaration prefix and once inside the rendered type, since javac records
     * a {@code TYPE_USE}-targeting annotation in both places. Neither is
     * repeatable, so the generated file did not compile.
     */
    @Test
    public void typeUseNullnessAccessor_generatedBuilderCompilesAndKeepsTheHintOnce() throws Exception {
        Compilation c = compile(cardInterface());
        // The regression guard: this used to fail with "NotNull is not a
        // repeatable annotation interface" on a line the consumer cannot edit.
        assertThat(c).succeeded();

        Map<String, Set<String>> params = readParamAnnotations(c, "demo.CardBuilder");
        assertTrue("tags(List) setter must carry @NotNull on its parameter, saw " + params,
            annotationsFor(params, "tags").contains(NOT_NULL));
        assertTrue("subtitle(String) setter must carry @Nullable on its parameter, saw " + params,
            annotationsFor(params, "subtitle").contains(NULLABLE));
    }

    /**
     * Only the annotation the emitter is about to write itself is dropped, and
     * only where it sits. One nested in a type argument says something about
     * the element rather than about the parameter, so removing it would
     * silently weaken the generated signature.
     */
    @Test
    public void typeUseNullnessAccessor_keepsAnAnnotationNestedInATypeArgument() throws Exception {
        Compilation c = compile(cardInterface());
        assertThat(c).succeeded();
        assertThat(c).generatedSourceFile("demo.CardBuilder")
            .contentsAsUtf8String().contains("items(@NotNull List<@NotNull String> items)");
    }

    /**
     * The varargs component of an array setter sits directly under the
     * {@code @NotNull} that setter writes unconditionally, so the component's
     * own copy is the second one.
     */
    @Test
    public void typeUseNullnessAccessor_arraySetterComponentIsNotAnnotatedTwice() throws Exception {
        Compilation c = compile(cardInterface());
        assertThat(c).succeeded();
        assertThat(c).generatedSourceFile("demo.CardBuilder")
            .contentsAsUtf8String().contains("codes(@NotNull String... codes)");
    }

    private static JavaFileObject cardInterface() {
        return JavaFileObjects.forSourceLines("demo.Card",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import org.jetbrains.annotations.NotNull;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public interface Card {",
            "    @NotNull List<String> tags();",
            "    @Nullable String subtitle();",
            "    @NotNull List<@NotNull String> items();",
            "    @NotNull String @NotNull [] codes();",
            "}");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new ClassBuilderProcessor()).compile(sources);
    }

    private static Set<String> annotationsFor(Map<String, Set<String>> params, String setterName) {
        // The single-arg field setter shares its name with the field; match on
        // the method name prefix so we don't depend on the exact descriptor.
        for (Map.Entry<String, Set<String>> e : params.entrySet()) {
            if (e.getKey().startsWith(setterName + "(")) return e.getValue();
        }
        return Set.of();
    }

    /**
     * Returns, per method ({@code name+descriptor}), the set of annotation
     * descriptors attached to its parameters - collected from both the
     * declaration channel ({@code RuntimeInvisibleParameterAnnotations}) and
     * the type-use channel ({@code RuntimeInvisibleTypeAnnotations}) so the
     * assertion is robust to how javac routes a dual-target annotation.
     */
    private static Map<String, Set<String>> readParamAnnotations(Compilation c, String className) throws IOException {
        byte[] bytes = findClassBytes(c, className);
        assertNotNull("expected class-file output for " + className, bytes);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                Set<String> descriptors = new LinkedHashSet<>();
                out.put(name + descriptor, descriptors);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
                        descriptors.add(desc);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                 String desc, boolean visible) {
                        descriptors.add(desc);
                        return null;
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

}
