package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.apt.ClassBuilderProcessor;
import org.junit.Test;

import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Covers how the class-level {@code @ClassBuilder(retainInit)} policy and the
 * field-level {@code @BuilderDefault} override combine. Field always wins;
 * absent a field annotation the class policy applies; the class policy defaults
 * to retaining.
 */
public class RetainInitPolicyTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac()
            .withProcessors(new ClassBuilderProcessor())
            .compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("classbuilder-retaininit-test");
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
        return new URLClassLoader(new URL[]{tmp.toUri().toURL()}, RetainInitPolicyTest.class.getClassLoader());
    }

    private static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> inner : outer.getDeclaredClasses()) {
            if (inner.getSimpleName().equals(simpleName)) return inner;
        }
        fail("expected nested class '" + simpleName + "' on " + outer);
        return null;
    }

    /** Builds with no setters called, so every value observed is a builder default. */
    private static Object buildUntouched(Class<?> target) throws Exception {
        Class<?> builder = nested(target, "Builder");
        Object b = builder.getEnclosingClass().getMethod("builder").invoke(null);
        return builder.getMethod("build").invoke(b);
    }

    private static Object get(Class<?> target, Object instance, String getter) throws Exception {
        Method m = target.getMethod(getter);
        return m.invoke(instance);
    }

    // ------------------------------------------------------------------
    // Class policy defaults to retaining
    // ------------------------------------------------------------------

    @Test
    public void classDefault_retainsInitializerWithoutAnyFieldAnnotation() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Defaults",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Defaults {
                String name = "anonymous";
                int count = 7;
                public String getName() { return name; }
                public int getCount() { return count; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Defaults", true, loadClasses(c));
        Object built = buildUntouched(target);
        assertEquals("anonymous", get(target, built, "getName"));
        assertEquals(7, get(target, built, "getCount"));
    }

    @Test
    public void builderDefaultFalse_optsASingleFieldOut() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Mixed",
            """
            package demo;
            import dev.simplified.annotations.BuilderDefault;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Mixed {
                String kept = "kept";
                @BuilderDefault(false)
                String dropped = "dropped";
                public String getKept() { return kept; }
                public String getDropped() { return dropped; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Mixed", true, loadClasses(c));
        Object built = buildUntouched(target);
        assertEquals("kept", get(target, built, "getKept"));
        assertNull("@BuilderDefault(false) must fall back to the JVM default",
            get(target, built, "getDropped"));
    }

    // ------------------------------------------------------------------
    // Class-level opt-out, and opting back in per field
    // ------------------------------------------------------------------

    @Test
    public void classRetainInitFalse_ignoresInitializers() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Sparse",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, retainInit = false)
            public class Sparse {
                String name = "ignored";
                public String getName() { return name; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Sparse", true, loadClasses(c));
        assertNull(get(target, buildUntouched(target), "getName"));
    }

    @Test
    public void builderDefault_optsBackInUnderClassLevelFalse() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.OptIn",
            """
            package demo;
            import dev.simplified.annotations.BuilderDefault;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, retainInit = false)
            public class OptIn {
                String ignored = "ignored";
                @BuilderDefault
                String kept = "kept";
                public String getIgnored() { return ignored; }
                public String getKept() { return kept; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.OptIn", true, loadClasses(c));
        Object built = buildUntouched(target);
        assertNull(get(target, built, "getIgnored"));
        assertEquals("bare @BuilderDefault must opt back in", "kept", get(target, built, "getKept"));
    }

    // ------------------------------------------------------------------
    // Fields with no initializer
    // ------------------------------------------------------------------

    /**
     * Retain-all must stay silent on fields that have nothing to retain -
     * otherwise flipping the class default on would error out every
     * uninitialised field in the codebase.
     */
    @Test
    public void inheritedPolicy_onFieldWithoutInitializer_isNotAnError() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Bare",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Bare {
                String noInit;
                int alsoNoInit;
                public String getNoInit() { return noInit; }
                public int getAlsoNoInit() { return alsoNoInit; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Bare", true, loadClasses(c));
        Object built = buildUntouched(target);
        assertNull(get(target, built, "getNoInit"));
        assertEquals(0, get(target, built, "getAlsoNoInit"));
    }

    /**
     * The counterpart to the case above: asking for retention by name on a field
     * with nothing to retain is a user mistake and must not pass silently.
     */
    @Test
    public void explicitBuilderDefault_withoutInitializer_warns() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Pointless",
            """
            package demo;
            import dev.simplified.annotations.BuilderDefault;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Pointless {
                @BuilderDefault
                String noInit;
                public String getNoInit() { return noInit; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();
        assertThat(c).hadWarningContaining("@BuilderDefault has no effect on 'noInit'");
    }

    /** The inherited-policy case must stay quiet, or the warning would be unusable noise. */
    @Test
    public void inheritedPolicy_withoutInitializer_doesNotWarn() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Quiet",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Quiet {
                String noInit;
                public String getNoInit() { return noInit; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();
        // Not hadWarningCount(0): javac always emits a "source version RELEASE_17
        // less than -source 21" warning for this processor. Scope the assertion
        // to the diagnostic under test.
        for (var d : c.warnings()) {
            String message = d.getMessage(null);
            if (message != null && message.contains("@BuilderDefault")) {
                fail("inherited retainInit must not warn, but got: " + message);
            }
        }
    }

    // ------------------------------------------------------------------
    // Final fields whose retention is turned off
    // ------------------------------------------------------------------

    /**
     * Turning retention off must still lift a {@code final} field to a blank
     * final. The generated constructor assigns every field either way, so a
     * final field left holding its own initializer is doubly defined and javac
     * rejects it with "cannot assign a value to final variable".
     *
     * <p>The field then defaults to null, which is exactly what the non-final
     * case already did - opting out of retention means the declared value does
     * not reach the builder.
     */
    @Test
    public void builderDefaultFalse_onFinalField_stillCompiles() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.FinalOptOut",
            """
            package demo;
            import dev.simplified.annotations.BuilderDefault;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class FinalOptOut {
                @BuilderDefault(false) final String name = "declared";
                public String getName() { return name; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.FinalOptOut", true, loadClasses(c));
        assertNull("opting out drops the declared value, as for a non-final field",
            get(target, buildUntouched(target), "getName"));
    }

    /** Same lift, driven by the class-level policy rather than a field annotation. */
    @Test
    public void classRetainInitFalse_onFinalField_stillCompiles() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.FinalSparse",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, retainInit = false)
            public class FinalSparse {
                final String name = "declared";
                public String getName() { return name; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.FinalSparse", true, loadClasses(c));
        assertNull(get(target, buildUntouched(target), "getName"));
    }

    /**
     * An author constructor that assigns a {@code final} field nowhere keeps
     * the field's initializer, so a second one that does assign it writes a
     * final that already has a value - the error the editor shows on the same
     * line. The lift took the initializer off instead, and javac reported
     * {@code variable retries might not have been initialized} on the
     * constructor that leaves it, where the editor showed nothing.
     */
    @Test
    public void finalAnAuthorConstructorLeaves_isNotLifted() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Config",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Config {
                private final String name;
                private final int retries = 3;
                public Config(String name) { this.name = name; }
                public Config(String name, int retries) { this.name = name; this.retries = retries; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("cannot assign a value to final variable retries");
        assertThat(c).hadErrorCount(1);
    }

    /**
     * A {@code Target} with a {@code final int a = 128} and one constructor
     * whose body is {@code body}, written on the source's eighth line.
     */
    private static JavaFileObject finalAssignedBy(String body) {
        return JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Target {
                private final int a = 128;
                public int getA() { return a; }
                Target(int a) {
                    %s
                }
            }
            """.formatted(body).split("\n"));
    }

    /**
     * A constructor assigning a {@code final} field on one branch only does not
     * assign it on the other, so the field keeps its initializer and javac
     * refuses the branch's write as a second assignment - on the author's line,
     * where the editor reports it too. The lift took the initializer off, and
     * javac reported {@code variable a might not have been initialized} where
     * the editor showed nothing.
     */
    @Test
    public void finalAssignedOnABranchOnly_isNotLifted() {
        JavaFileObject src = finalAssignedBy("if (a > 0) this.a = a;");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("cannot assign a value to final variable a").inFile(src).onLine(8);
        assertThat(c).hadErrorCount(1);
    }

    /**
     * A loop's write may run any number of times, so it is no assignment the
     * lift counts either. The lift reported {@code variable a might be
     * assigned in loop} and {@code might not have been initialized}.
     */
    @Test
    public void finalAssignedInALoop_isNotLifted() {
        JavaFileObject src = finalAssignedBy("for (int i = 0; i < a; i++) this.a = i;");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("cannot assign a value to final variable a").inFile(src).onLine(8);
        assertThat(c).hadErrorCount(1);
    }

    /** A write inside a {@code try} may not complete, and is not counted. */
    @Test
    public void finalAssignedInATry_isNotLifted() {
        JavaFileObject src = finalAssignedBy(
            "try { this.a = Integer.parseInt(\"\" + a); } catch (RuntimeException e) { }");
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("cannot assign a value to final variable a").inFile(src).onLine(8);
        assertThat(c).hadErrorCount(1);
    }

    /**
     * Compiles a {@link #finalAssignedBy} target, builds it with {@code a} set
     * to {@code value}, and reads the field back.
     */
    private static Object builtWith(JavaFileObject src, int value) throws Exception {
        Compilation c = compile(src);
        assertThat(c).succeeded();
        Class<?> target = Class.forName("demo.Target", true, loadClasses(c));
        Class<?> builder = nested(target, "Builder");
        Object b = target.getMethod("builder").invoke(null);
        builder.getMethod("a", int.class).invoke(b, value);
        return get(target, builder.getMethod("build").invoke(b), "getA");
    }

    /**
     * An {@code if} and an {@code else} that both assign the field assign it,
     * so the field is lifted and the constructor compiles. Only a statement of
     * the body itself counted, so the initializer stayed and javac refused both
     * writes with {@code cannot assign a value to final variable a}.
     */
    @Test
    public void finalAssignedOnBothBranches_isLifted() throws Exception {
        JavaFileObject src = finalAssignedBy("if (a > 0) this.a = a;\n        else this.a = -a;");
        assertEquals(3, builtWith(src, -3));
    }

    /**
     * A {@code switch} with a {@code default} whose every arm assigns the field
     * and leaves the switch - arrow arms, or colon arms each ending in
     * {@code break} - assigns it too. Both forms were refused on every write.
     */
    @Test
    public void finalAssignedInEveryArmOfASwitchWithADefault_isLifted() throws Exception {
        JavaFileObject arrows = finalAssignedBy(
            "switch (a) {\n        case 1 -> this.a = 10;\n        default -> { this.a = a; }\n        }");
        assertEquals(10, builtWith(arrows, 1));
        assertEquals(4, builtWith(arrows, 4));
        JavaFileObject colons = finalAssignedBy(
            "switch (a) {\n        case 1: case 2: this.a = 10; break;\n        default: this.a = a; break;\n        }");
        assertEquals(10, builtWith(colons, 2));
        assertEquals(4, builtWith(colons, 4));
    }

    /** A block whose statements assign the field assigns it; the block's write was refused. */
    @Test
    public void finalAssignedInABlock_isLifted() throws Exception {
        assertEquals(6, builtWith(finalAssignedBy("{ int doubled = a * 2; this.a = doubled; }"), 3));
    }

    /**
     * A {@code switch} with no {@code default}, and one whose arm may break
     * ahead of its write, leave the field unassigned on a path through it: the
     * initializer stays and javac refuses each write.
     */
    @Test
    public void finalLeftUnassignedOnAPathThroughASwitch_isNotLifted() {
        JavaFileObject noDefault = finalAssignedBy(
            "switch (a) {\n        case 1 -> this.a = 1;\n        case 2 -> this.a = 2;\n        }");
        Compilation first = compile(noDefault);
        assertThat(first).hadErrorContaining("cannot assign a value to final variable a").inFile(noDefault).onLine(9);
        assertThat(first).hadErrorContaining("cannot assign a value to final variable a").inFile(noDefault).onLine(10);
        assertThat(first).hadErrorCount(2);

        JavaFileObject breaksFirst = finalAssignedBy(
            "switch (a) {\n        case 1: if (a > 5) break; this.a = 1; break;\n        default: this.a = 2; break;\n        }");
        Compilation third = compile(breaksFirst);
        assertThat(third).hadErrorContaining("cannot assign a value to final variable a").inFile(breaksFirst).onLine(9);
        assertThat(third).hadErrorContaining("cannot assign a value to final variable a").inFile(breaksFirst).onLine(10);
        assertThat(third).hadErrorCount(2);
    }

    /**
     * javac's lines for a lifted field written more than once in one
     * constructor, which the editor has to match: each write the constructor
     * reaches after the field may already be assigned is refused, and nothing
     * else. A loop's write is refused as a write in a loop, and javac reports
     * no second error on a write after a {@code for} loop. A branch that
     * returns leaves the rest of the body with the field unassigned.
     */
    @Test
    public void liftedFinal_writtenMoreThanOnceInAConstructor_isRejectedWhereItMayBeAssigned() {
        String already = "variable a might already have been assigned";
        assertOnlyError(finalAssignedBy("this.a = a;\n        this.a = 2;"), already, 9);
        assertOnlyError(finalAssignedBy("this.a = a;\n        if (a > 0) this.a = 2;"), already, 9);
        assertOnlyError(finalAssignedBy("if (a > 0) this.a = 2;\n        this.a = a;"), already, 9);
        assertOnlyError(finalAssignedBy("for (int i = 0; i < 2; i++) this.a = i;\n        this.a = a;"),
            "variable a might be assigned in loop", 8);
        assertOnlyError(finalAssignedBy("this.a = a;\n        for (int i = 0; i < 2; i++) this.a = i;"), already, 9);
        assertOnlyError(finalAssignedBy("if (a > 0) this.a = 1;\n        else this.a = 2;\n        this.a = 3;"),
            already, 10);
        assertThat(compile(finalAssignedBy("if (a > 0) { this.a = 1; return; }\n        this.a = a;"))).succeeded();
    }

    /** After {@code this(..)} every blank final is assigned, and a write is refused. */
    @Test
    public void liftedFinal_writtenAfterThis_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            public class Target {
                private final int a = 128;
                Target(int a) {
                    this.a = a;
                }
                Target() {
                    this(1);
                    this.a = 2;
                }
            }
            """.split("\n"));
        assertOnlyError(src, "variable a might already have been assigned", 11);
    }

    private static void assertOnlyError(JavaFileObject src, String message, int line) {
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining(message).inFile(src).onLine(line);
        assertThat(c).hadErrorCount(1);
    }

    /**
     * Under a {@code factoryMethod} with no author constructor nothing the
     * generator appends assigns the field, so its initializer stays and the
     * factory's {@code new Named()} reads it. The lift took it off, and javac
     * failed with {@code variable label not initialized in the default
     * constructor}.
     */
    @Test
    public void finalUnderAFactoryMethodWithNoConstructor_keepsItsInitializer() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Named",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, factoryMethod = "make")
            public class Named {
                private final String label = "declared";
                public String getLabel() { return label; }
                static Named make(String label) { return new Named(); }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();
        Class<?> target = Class.forName("demo.Named", true, loadClasses(c));
        assertEquals("declared", get(target, buildUntouched(target), "getLabel"));
    }

    /** An author constructor assigning the field answers for it under a factory, as before. */
    @Test
    public void finalUnderAFactoryMethodBesideAnAssigningConstructor_isLifted() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Named",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false, factoryMethod = "make")
            public class Named {
                private final String label = "declared";
                public String getLabel() { return label; }
                Named(String label) { this.label = label; }
                static Named make(String label) { return new Named(label + "!"); }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();
        Class<?> target = Class.forName("demo.Named", true, loadClasses(c));
        assertEquals("declared!", get(target, buildUntouched(target), "getLabel"));
    }

    /** A {@code lombok} annotation of the given simple name, compiled beside the target as a stub. */
    private static JavaFileObject lombokStub(String name) {
        return JavaFileObjects.forSourceLines("lombok." + name,
            "package lombok;",
            "public @interface " + name + " { }");
    }

    /**
     * Beside a constructor the author wrote, a Lombok constructor annotation
     * adds a constructor that assigns no {@code final} field carrying an
     * initializer, so the initializer stays and javac refuses the author's
     * write. The lift took it off wherever the processor ran ahead of Lombok,
     * and Lombok's constructor then failed with {@code variable a might not
     * have been initialized} on its annotation, where the editor showed nothing.
     */
    @Test
    public void finalBesideALombokConstructorAndAnAuthorOne_isNotLifted() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder(validate = false)
            @lombok.NoArgsConstructor
            public class Target {
                private final int a = 128;
                Target(int a) {
                    this.a = a;
                }
            }
            """.split("\n"));
        Compilation c = compile(src, lombokStub("NoArgsConstructor"));
        assertThat(c).hadErrorContaining("cannot assign a value to final variable a").inFile(src).onLine(8);
        assertThat(c).hadErrorCount(1);
    }

    /** {@code @Data} beside a written constructor implies no Lombok constructor, and the field is lifted. */
    @Test
    public void finalBesideLombokDataAndAnAuthorConstructor_isLifted() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            import lombok.Data;
            @ClassBuilder(validate = false)
            @Data
            public class Target {
                private final int a = 128;
                Target(int a) {
                    this.a = a;
                }
            }
            """.split("\n"));
        assertThat(compile(src, lombokStub("Data"))).succeeded();
    }

    /**
     * A lifted field is a blank final, which only a constructor of its own
     * class assigns: through {@code this}, by its bare name, or parenthesised,
     * each compiles.
     */
    @Test
    public void liftedFinal_writtenInConstructors_compiles() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                }
                public Target(long v) {
                    a = (int) v;
                }
                public Target(short v) {
                    (this.a) = v;
                }
            }
            """.split("\n"));
        assertThat(compile(src)).succeeded();
    }

    /** A method's write to a lifted field, bare or through {@code this}, is rejected on its line. */
    @Test
    public void liftedFinal_writtenInAMethod_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                }
                void reset(int v) {
                    a = v;
                }
                void again(int v) {
                    this.a = v;
                }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("cannot assign a value to final variable a").inFile(src).onLine(10);
        assertThat(c).hadErrorContaining("cannot assign a value to final variable a").inFile(src).onLine(13);
        assertThat(c).hadErrorCount(2);
    }

    /**
     * Every constructor assigns a lifted field, so an instance initializer's
     * write is a second assignment, reported on the constructor.
     */
    @Test
    public void liftedFinal_writtenInAnInstanceInitializer_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                {
                    a = 5;
                }
                public Target(int a) {
                    this.a = a;
                }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("variable a might already have been assigned").inFile(src).onLine(10);
        assertThat(c).hadErrorCount(1);
    }

    /** A compound assignment and an increment in a constructor read the field too, and are rejected. */
    @Test
    public void liftedFinal_compoundWriteInAConstructor_isRejected() {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                    this.a += 1;
                }
                public Target() {
                    this.a = 0;
                    this.a++;
                }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).hadErrorContaining("variable a might already have been assigned").inFile(src).onLine(8);
        assertThat(c).hadErrorContaining("variable a might already have been assigned").inFile(src).onLine(12);
        assertThat(c).hadErrorCount(2);
    }

    /**
     * Inside a constructor, a lambda's write, a local class's write and a write
     * to another instance's field are none of them the constructor assigning
     * its own field, and each is rejected on its line.
     */
    @Test
    public void liftedFinal_writtenFromAConstructorButNotToItsOwnField_isRejected() {
        JavaFileObject lambda = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                    Runnable r = () -> { this.a = 3; };
                }
            }
            """.split("\n"));
        Compilation fromLambda = compile(lambda);
        assertThat(fromLambda).hadErrorContaining("variable a might already have been assigned")
            .inFile(lambda).onLine(8);
        assertThat(fromLambda).hadErrorCount(1);

        JavaFileObject qualified = JavaFileObjects.forSourceLines("demo.Target",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            @ClassBuilder
            public class Target {
                private static Target last;
                private final int a = 128;
                public Target(int a) {
                    this.a = a;
                    class Local { void f() { Target.this.a = 1; } }
                    last.a = 4;
                }
            }
            """.split("\n"));
        Compilation throughQualifier = compile(qualified);
        assertThat(throughQualifier).hadErrorContaining("cannot assign a value to final variable a")
            .inFile(qualified).onLine(9);
        assertThat(throughQualifier).hadErrorContaining("cannot assign a value to final variable a")
            .inFile(qualified).onLine(10);
        assertThat(throughQualifier).hadErrorCount(2);
    }

    // ------------------------------------------------------------------
    // The constructor walk follows javac's definite-assignment rules
    // ------------------------------------------------------------------

    /**
     * Asserts the compilation failed with exactly one error on each of the
     * given lines of the source, and on no other.
     */
    private static void assertErrorLines(JavaFileObject src, int... lines) {
        Compilation c = compile(src);
        for (int line : lines) assertThat(c).hadErrorContaining("").inFile(src).onLine(line);
        assertThat(c).hadErrorCount(lines.length);
    }

    /**
     * A {@code return} reached while the field is unassigned leaves the
     * constructor with a blank final, so the field keeps its initializer and
     * javac refuses the write as a second assignment on the write's own line,
     * where the editor reports it too - at the top level, in a block and in a
     * {@code switch} arm alike. The lift counted the write after the
     * {@code return}, and javac reported {@code variable a might not have been
     * initialized} on the {@code return}, where the editor showed nothing.
     */
    @Test
    public void aReturnAheadOfTheOnlyWrite_keepsTheInitializer() {
        String refused = "cannot assign a value to final variable a";
        assertOnlyError(finalAssignedBy("if (a > 0) return;\n        this.a = a;"), refused, 9);
        assertOnlyError(finalAssignedBy("{ if (a > 0) return; this.a = a; }"), refused, 8);
        JavaFileObject inAnArm = finalAssignedBy(
            "switch (a) {\n        case 1: if (a > 5) return; this.a = 1; break;\n        default: this.a = 2; break;\n        }");
        Compilation c = compile(inAnArm);
        assertThat(c).hadErrorContaining(refused).inFile(inAnArm).onLine(9);
        assertThat(c).hadErrorContaining(refused).inFile(inAnArm).onLine(10);
        assertThat(c).hadErrorCount(2);
    }

    /** A {@code return} after the write leaves the field assigned, and the field is lifted as before. */
    @Test
    public void aReturnAfterTheWrite_isLifted() throws Exception {
        JavaFileObject src = finalAssignedBy("this.a = a;\n        if (a > 5) return;");
        assertEquals(3, builtWith(src, 3));
        assertEquals(7, builtWith(src, 7));
    }

    /**
     * A write in a statement that always runs it - a {@code synchronized} or
     * labelled block, a {@code try} whose {@code catch} rethrows or whose only
     * other clause is {@code finally}, a declaration's initializer, a
     * {@code do} loop over {@code false}, a {@code while (true)} that breaks
     * after it, an {@code if} whose {@code else} throws, or a {@code switch}
     * whose last colon arm falls out of it - definitely assigns the field, and
     * javac builds the lifted class. Each was read as a statement the rule
     * never counts, and javac refused the write with {@code cannot assign a
     * value to final variable a}.
     */
    @Test
    public void finalAssignedInAStatementThatAlwaysRunsTheWrite_isLifted() throws Exception {
        for (String body : new String[] {
            "synchronized (this) { this.a = a; }",
            "lbl: { this.a = a; }",
            "try { this.a = a; } finally { }",
            "try { this.a = a; } catch (RuntimeException e) { throw e; }",
            "int k = this.a = a;",
            "do { this.a = a; } while (false);",
            "while (true) { this.a = a; break; }",
            "if (a > 0) this.a = a;\n        else throw new IllegalArgumentException();",
            "switch (a) {\n        case 1: this.a = 3; break;\n        default: this.a = a;\n        }",
        })
            assertEquals(body, 3, builtWith(finalAssignedBy(body), 3));
    }

    /**
     * javac's own lines for a write inside a statement followed by a
     * top-level write, which the editor has to match: after a {@code do} or
     * enhanced {@code for} loop both writes are refused, and after a
     * {@code try}, a {@code synchronized} or labelled block or a declaration
     * only the top-level one is.
     */
    @Test
    public void aWriteInsideAStatementThenATopLevelWrite_isRejectedOnJavacsLines() {
        assertErrorLines(finalAssignedBy("do { this.a = 1; } while (a > 5);\n        this.a = a;"), 8, 9);
        assertErrorLines(finalAssignedBy("for (int i : new int[] {1}) this.a = i;\n        this.a = a;"), 8, 9);
        assertErrorLines(finalAssignedBy("try { this.a = 1; } catch (RuntimeException e) { }\n        this.a = a;"), 9);
        assertErrorLines(finalAssignedBy("try { this.a = 1; } finally { }\n        this.a = a;"), 9);
        assertErrorLines(finalAssignedBy("synchronized (this) { this.a = 1; }\n        this.a = a;"), 9);
        assertErrorLines(finalAssignedBy("lbl: { this.a = 1; }\n        this.a = a;"), 9);
        assertErrorLines(finalAssignedBy("int k = this.a = 1;\n        this.a = a;"), 9);
    }

    /**
     * A write the field's first on a path that then returns is accepted
     * wherever it sits, and so is the write after the {@code if}: javac builds
     * the lifted class.
     */
    @Test
    public void aFirstWriteInsideAStatementOnAPathThatReturns_isAccepted() throws Exception {
        JavaFileObject src = finalAssignedBy(
            "if (a > 0) { synchronized (this) { this.a = 1; } return; }\n        this.a = a;");
        assertEquals(1, builtWith(src, 5));
        assertEquals(-2, builtWith(src, -2));
    }

    /**
     * An args-annotation constructor beside the builder's assigns no field the
     * lift takes an initializer off, so each keeps the initializer's value
     * while {@code build()} sets the builder's. The lift stripped the
     * initializer for the builder's constructor and left the args
     * constructor's blank final unassigned: {@code variable label might not
     * have been initialized} on the annotation's line.
     */
    @Test
    public void finalBesideAnArgsAnnotationConstructor_keepsItsValueThere() throws Exception {
        JavaFileObject required = JavaFileObjects.forSourceLines("demo.Named",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.RequiredArgsConstructor;
            @ClassBuilder(validate = false)
            @RequiredArgsConstructor
            public class Named {
                private final String label = "declared";
                private final int count;
                public String getLabel() { return label; }
                public int getCount() { return count; }
            }
            """.split("\n"));
        Compilation first = compile(required);
        assertThat(first).succeeded();
        Class<?> named = Class.forName("demo.Named", true, loadClasses(first));
        var viaArgs = named.getDeclaredConstructor(int.class);
        viaArgs.setAccessible(true);
        Object argsBuilt = viaArgs.newInstance(4);
        assertEquals("declared", get(named, argsBuilt, "getLabel"));
        assertEquals(4, get(named, argsBuilt, "getCount"));
        Class<?> builder = nested(named, "Builder");
        Object b = named.getMethod("builder").invoke(null);
        builder.getMethod("label", String.class).invoke(b, "built");
        assertEquals("built", get(named, builder.getMethod("build").invoke(b), "getLabel"));

        JavaFileObject none = JavaFileObjects.forSourceLines("demo.Named",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            import dev.simplified.annotations.NoArgsConstructor;
            @ClassBuilder(validate = false)
            @NoArgsConstructor
            public class Named {
                private final String label = "declared";
                public String getLabel() { return label; }
            }
            """.split("\n"));
        Compilation second = compile(none);
        assertThat(second).succeeded();
        Class<?> bare = Class.forName("demo.Named", true, loadClasses(second));
        var noArgs = bare.getDeclaredConstructor();
        noArgs.setAccessible(true);
        assertEquals("declared", get(bare, noArgs.newInstance(), "getLabel"));
        Class<?> bareBuilder = nested(bare, "Builder");
        Object nb = bare.getMethod("builder").invoke(null);
        bareBuilder.getMethod("label", String.class).invoke(nb, "built");
        assertEquals("built", get(bare, bareBuilder.getMethod("build").invoke(nb), "getLabel"));
        assertEquals("declared", get(bare, buildUntouched(bare), "getLabel"));
    }

    // ------------------------------------------------------------------
    // Fresh-per-build semantics survive the policy change
    // ------------------------------------------------------------------

    @Test
    public void retainedInitializer_isEvaluatedFreshPerBuilder() throws Exception {
        JavaFileObject src = JavaFileObjects.forSourceLines("demo.Fresh",
            """
            package demo;
            import dev.simplified.annotations.ClassBuilder;
            import java.util.ArrayList;
            import java.util.List;
            @ClassBuilder(validate = false)
            public class Fresh {
                List<String> items = new ArrayList<>();
                public List<String> getItems() { return items; }
            }
            """.split("\n"));
        Compilation c = compile(src);
        assertThat(c).succeeded();

        Class<?> target = Class.forName("demo.Fresh", true, loadClasses(c));
        Object first = get(target, buildUntouched(target), "getItems");
        Object second = get(target, buildUntouched(target), "getItems");
        if (first == second) {
            fail("each build must get its own list instance, not a shared one");
        }
    }

}
