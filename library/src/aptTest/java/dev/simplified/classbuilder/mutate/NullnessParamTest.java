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
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
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

    /**
     * A type-use annotation must not change how the field is classified.
     *
     * <p>{@code FieldSpec} matched the container types against the rendered
     * type, which carries the annotation inline, so an annotated field matched
     * none of them. The visible half was that {@code Optional} lost its dual
     * setters; the damaging half was that the builder's constructor emitted no
     * container defaults at all, leaving an untouched list field null where the
     * unannotated form gives an empty one - so {@code build()} handed the
     * constructor a null nobody wrote.
     */
    @Test
    public void typeUseNullnessOnContainerFields_keepsTheBuilderDefaults() throws Exception {
        Compilation c = compile(boxesClass());
        assertThat(c).succeeded();

        Class<?> boxes = loadClasses(c).loadClass("demo.Boxes");
        Object builder = boxes.getMethod("builder").invoke(null);
        Object built = builder.getClass().getMethod("build").invoke(builder);

        assertEquals("an untouched @NotNull List must default to an empty list, not null",
            List.of(), boxes.getField("tags").get(built));
        assertEquals("an untouched @NotNull Map must default to an empty map, not null",
            Map.of(), boxes.getField("counts").get(built));
        assertEquals("an untouched @NotNull Optional must default to empty, not null",
            Optional.empty(), boxes.getField("note").get(built));
    }

    /**
     * The other half of the same classification: an {@code Optional} field
     * generates a raw setter beside the wrapped one, so that the wrapping lives
     * inside the builder rather than at every call site.
     */
    @Test
    public void typeUseNullnessOnAnOptionalField_stillGeneratesTheRawSetter() throws Exception {
        Compilation c = compile(boxesClass());
        assertThat(c).succeeded();

        Class<?> boxes = loadClasses(c).loadClass("demo.Boxes");
        Object builder = boxes.getMethod("builder").invoke(null);
        builder.getClass().getMethod("note", String.class).invoke(builder, "hi");
        Object built = builder.getClass().getMethod("build").invoke(builder);

        assertEquals("the raw setter must wrap rather than store",
            Optional.of("hi"), boxes.getField("note").get(built));
    }

    /**
     * The same defect one level down, on the type argument rather than on the
     * field's own type. {@code optionalInner} is a display string, so
     * {@code Optional<@NotNull String>} did not equal {@code "java.lang.String"}
     * and the {@code @Formattable} overload was dropped from exactly the fields
     * that carry a nullness annotation.
     */
    @Test
    public void typeUseNullnessOnAnOptionalTypeArgument_keepsTheFormattableOverload() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Fmt",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Formattable;",
            "import org.jetbrains.annotations.NotNull;",
            "import java.util.Optional;",
            "@ClassBuilder(validate = false)",
            "public class Fmt {",
            "    @Formattable public final Optional<@NotNull String> annotated;",
            "    @Formattable public final Optional<String> plain;",
            "    public Fmt(Optional<String> annotated, Optional<String> plain) {",
            "        this.annotated = annotated; this.plain = plain;",
            "    }",
            "}"));
        assertThat(c).succeeded();

        Class<?> fmt = loadClasses(c).loadClass("demo.Fmt");
        Object builder = fmt.getMethod("builder").invoke(null);
        // The annotated field must accept the printf shape its unannotated
        // sibling does. Reverted, this throws NoSuchMethodException.
        builder.getClass().getMethod("annotated", String.class, Object[].class)
            .invoke(builder, new Object[]{"%s-%d", new Object[]{"a", 1}});
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertEquals(Optional.of("a-1"), fmt.getField("annotated").get(built));
    }

    // ------------------------------------------------------------------
    // The all-args constructor build() calls
    // ------------------------------------------------------------------

    /**
     * A parameter typed as the field's own type carries the field's nullness.
     *
     * <p>These three are the whole reason the propagation exists: the
     * constructor is the one member a caller reaches without going through the
     * builder, and until now it was the only generated slot holding {@code T}
     * that said nothing about null.
     */
    @Test
    public void allArgsConstructorParams_carryTheFieldNullness() throws Exception {
        Compilation c = compile(ctorClass());
        assertThat(c).succeeded();

        List<Set<String>> params = readCtorParamAnnotations(c, "demo.Ctor");
        assertEquals("the fixture pins parameter positions, so a shape change must fail here",
            7, params.size());
        assertTrue("required is an ordinary String parameter and must carry @NotNull, saw " + params,
            params.get(0).contains(NOT_NULL));
        assertTrue("optional is an ordinary String parameter and must carry @Nullable, saw " + params,
            params.get(1).contains(NULLABLE));
        assertTrue("a static-default field's parameter is still plain T, saw " + params,
            params.get(2).contains(NOT_NULL));
    }

    /**
     * An instance default's parameter is {@code Supplier<T>}, and null in that
     * slot is how the constructor is told the builder was never filled. The
     * field's {@code @NotNull} describes {@code T}, so copying it here would
     * assert exactly what the slot uses to say the opposite.
     */
    @Test
    public void allArgsConstructorParams_supplierRetypedInstanceDefaultIsBare() throws Exception {
        Compilation c = compile(ctorClass());
        assertThat(c).succeeded();

        List<Set<String>> params = readCtorParamAnnotations(c, "demo.Ctor");
        assertEquals("derived arrives as Supplier<String> and must carry no nullness, saw " + params,
            Set.of(), params.get(3));
    }

    /**
     * The same exclusion, arrived at by a route no guard at the mint site can
     * see. {@code AllArgsConstructorFactory} declares a {@code @Lazy}
     * parameter as plain {@code T} and leaves the {@code Supplier<T>} retype to
     * {@code LazyFieldMutator}, which replaces the parameter's type in place
     * and does not touch its modifiers - so an annotation attached at mint time
     * survives onto a type it was never a claim about.
     */
    @Test
    public void allArgsConstructorParams_lazyFieldIsBare() throws Exception {
        Compilation c = compile(ctorClass());
        assertThat(c).succeeded();

        List<Set<String>> params = readCtorParamAnnotations(c, "demo.Ctor");
        assertEquals("heavy is retyped to Supplier<String> after this factory ran, saw " + params,
            Set.of(), params.get(4));
    }

    /**
     * A collected instance default contributes two parameters for one field -
     * the container plus the marker recording a wholesale replacement - and
     * neither takes the field's nullness. The container is typed as the
     * {@code java.util} interface off the matched supertype rather than as the
     * declared type, and the marker has no field behind it at all.
     *
     * <p>Asserted positionally on purpose: an implementation looping one
     * annotation per field rather than per parameter would land the field's
     * hint on the container and shift every later parameter, which is a defect
     * no single-index check can see.
     */
    @Test
    public void allArgsConstructorParams_collectedContainerAndItsMarkerAreBare() throws Exception {
        Compilation c = compile(ctorClass());
        assertThat(c).succeeded();

        List<Set<String>> params = readCtorParamAnnotations(c, "demo.Ctor");
        assertEquals("the whole parameter list, in order",
            List.of(Set.of(NOT_NULL), Set.of(NULLABLE), Set.of(NOT_NULL),
                Set.of(), Set.of(), Set.of(), Set.of()),
            params);
    }

    /**
     * The interface path needs no propagation of its own, and this pins why.
     * {@code InterfaceImplEmitter} renders the constructor parameter from the
     * same type string as the field it assigns, and JetBrains' two annotations
     * both target {@code TYPE_USE}, so the hint is already inside that string.
     * Writing a declaration-form prefix as well would emit the annotation
     * twice, and neither is repeatable.
     */
    @Test
    public void interfaceImplConstructorParams_carryTheAccessorNullness() throws Exception {
        Compilation c = compile(cardInterface());
        assertThat(c).succeeded();

        List<Set<String>> params = readCtorParamAnnotations(c, "demo.CardImpl");
        assertTrue("tags() is @NotNull, so the Impl constructor slot must be too, saw " + params,
            params.get(0).contains(NOT_NULL));
        assertTrue("subtitle() is @Nullable, so the Impl constructor slot must be too, saw " + params,
            params.get(1).contains(NULLABLE));
    }

    // ------------------------------------------------------------------
    // The accessor pass, which is the reference implementation
    // ------------------------------------------------------------------

    /**
     * {@code @Getter}/{@code @Setter} propagate through the shared
     * {@code NullnessAnnotations} rather than a private copy of the same
     * allowlist. Both members are typed from the field, so both take the hint.
     */
    @Test
    public void accessorPass_getterAndSetterKeepTheFieldNullness() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Accessed",
            "package demo;",
            "import dev.simplified.annotations.Getter;",
            "import dev.simplified.annotations.Setter;",
            "import org.jetbrains.annotations.NotNull;",
            "import org.jetbrains.annotations.Nullable;",
            "@Getter @Setter",
            "public class Accessed {",
            "    private @NotNull String required = \"r\";",
            "    private @Nullable String optional;",
            "}"));
        assertThat(c).succeeded();

        Map<String, Set<String>> methods = readMethodAnnotations(c, "demo.Accessed");
        assertTrue("getRequired() must carry @NotNull, saw " + methods,
            methods.get("getRequired()Ljava/lang/String;").contains(NOT_NULL));
        assertTrue("getOptional() must carry @Nullable, saw " + methods,
            methods.get("getOptional()Ljava/lang/String;").contains(NULLABLE));

        Map<String, Set<String>> params = readParamAnnotations(c, "demo.Accessed");
        assertTrue("setRequired(String) must carry @NotNull on its parameter, saw " + params,
            annotationsFor(params, "setRequired").contains(NOT_NULL));
        assertTrue("setOptional(String) must carry @Nullable on its parameter, saw " + params,
            annotationsFor(params, "setOptional").contains(NULLABLE));
    }

    /**
     * Every parameter shape the all-args constructor mints, in one target so
     * the positions are fixed: two ordinary fields, a static default (still
     * plain {@code T}), an instance default (a {@code Supplier}), a
     * {@code @Lazy} field (retyped to a {@code Supplier} by a later pass), and
     * a collected instance default (a container plus its marker).
     */
    private static JavaFileObject ctorClass() {
        return JavaFileObjects.forSourceLines("demo.Ctor",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import dev.simplified.annotations.Collector;",
            "import dev.simplified.annotations.Lazy;",
            "import org.jetbrains.annotations.NotNull;",
            "import org.jetbrains.annotations.Nullable;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "@ClassBuilder(validate = false)",
            "public class Ctor {",
            "    public @NotNull String required;",
            "    public @Nullable String optional;",
            "    public @NotNull String base = \"b\";",
            "    public @NotNull String derived = base + \"!\";",
            "    public @Lazy @NotNull String heavy;",
            "    @Collector public @NotNull List<String> tags = new ArrayList<>(List.of(base));",
            "}");
    }

    private static JavaFileObject boxesClass() {
        return JavaFileObjects.forSourceLines("demo.Boxes",
            "package demo;",
            "import dev.simplified.annotations.ClassBuilder;",
            "import org.jetbrains.annotations.NotNull;",
            "import java.util.List;",
            "import java.util.Map;",
            "import java.util.Optional;",
            "@ClassBuilder(validate = false)",
            "public class Boxes {",
            "    public final @NotNull List<String> tags;",
            "    public final @NotNull Map<String, Integer> counts;",
            "    public final @NotNull Optional<String> note;",
            "    public Boxes(List<String> tags, Map<String, Integer> counts, Optional<String> note) {",
            "        this.tags = tags; this.counts = counts; this.note = note;",
            "    }",
            "}");
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

    private static ClassLoader loadClasses(Compilation compilation) throws IOException {
        Path tmp = Files.createTempDirectory("nullness-param-test");
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
            NullnessParamTest.class.getClassLoader());
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

    /**
     * The annotation descriptors on each parameter of a class's constructor,
     * indexed by parameter position.
     *
     * <p>Positional rather than pooled, because the defect this guards against
     * is a hint landing one slot off: the collected pair contributes two
     * parameters for one field, so an emitter counting fields rather than
     * parameters produces a list that is individually plausible everywhere and
     * wrong throughout. Both annotation channels are merged into one set per
     * index, since javac routes a {@code TYPE_USE}-targeting annotation into
     * the declaration channel as well.
     *
     * @param c the finished compilation
     * @param className the binary name of the class to read
     * @return one set per constructor parameter, in declaration order
     */
    private static List<Set<String>> readCtorParamAnnotations(Compilation c, String className) throws IOException {
        byte[] bytes = findClassBytes(c, className);
        assertNotNull("expected class-file output for " + className, bytes);
        List<Set<String>> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("<init>")) return null;
                for (int i = 0; i < Type.getArgumentTypes(descriptor).length; i++) {
                    out.add(new LinkedHashSet<>());
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
                        out.get(parameter).add(desc);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                 String desc, boolean visible) {
                        // A non-null path means the annotation sits inside a
                        // type argument - List<@NotNull String> - which says
                        // something about the element rather than the slot.
                        if (typePath != null) return null;
                        TypeReference ref = new TypeReference(typeRef);
                        if (ref.getSort() != TypeReference.METHOD_FORMAL_PARAMETER) return null;
                        out.get(ref.getFormalParameterIndex()).add(desc);
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return out;
    }

    /**
     * Returns, per method ({@code name+descriptor}), the annotation descriptors
     * on the method itself - its declaration annotations plus anything
     * annotating its return type, which is where a getter's propagated nullness
     * lands.
     */
    private static Map<String, Set<String>> readMethodAnnotations(Compilation c, String className) throws IOException {
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
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        descriptors.add(desc);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                                 String desc, boolean visible) {
                        if (typePath != null) return null;
                        if (new TypeReference(typeRef).getSort() != TypeReference.METHOD_RETURN) return null;
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
