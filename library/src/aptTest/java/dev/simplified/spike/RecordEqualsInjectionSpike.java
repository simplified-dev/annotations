package dev.simplified.spike;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import dev.simplified.shared.javac.JavacBridge;
import dev.simplified.shared.javac.JavacTypeFactory;
import dev.simplified.shared.javac.compat.JavacAccessFactory;
import org.junit.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Settles whether a processor can suppress a record's implicit
 * {@code equals}/{@code hashCode} by appending its own to the record's
 * {@code JCClassDecl}.
 *
 * <p>This is the one unproven step in generating equality for records, and it
 * has to be answered before the feature is designed around it. JLS 8.10.3
 * synthesises the implicit pair only when the record body does not declare
 * them, so the question is whether an appended declaration lands early enough
 * to count as declared - which depends on where an annotation-processing round
 * sits relative to record member synthesis, and that is not something to assert
 * from memory.
 *
 * <p>The probe injects <b>sentinel</b> bodies rather than real ones, because
 * the interesting outcome is not whether the file compiles but <b>which</b>
 * method survives. A record whose {@code hashCode} returns the sentinel proves
 * the injected member won; the compiler-generated one returns a value derived
 * from the components and never collides with it.
 *
 * <p>Three outcomes are distinguishable, and only the first is a pass:
 * <ul>
 *   <li>compiles and the sentinel is observed - injection suppresses the
 *       implicit pair,</li>
 *   <li>fails with a duplicate-method error - the injection lands after member
 *       entry, and the feature needs a different mechanism for records,</li>
 *   <li>compiles but the sentinel is absent - the injected member was dropped
 *       silently, which is the worst outcome and the reason this asserts on
 *       behaviour rather than on compilation.</li>
 * </ul>
 */
public class RecordEqualsInjectionSpike {

    private static final int SENTINEL = 4242;

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new RecordEqualsInjector()).compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("record-spike");
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
            RecordEqualsInjectionSpike.class.getClassLoader());
    }

    private static JavaFileObject arrayRecord() {
        return JavaFileObjects.forSourceLines("demo.Pixels",
            "package demo;",
            "public record Pixels(float[] values, int width) {",
            "}");
    }

    // ------------------------------------------------------------------
    // The question
    // ------------------------------------------------------------------

    @Test
    public void injectedPairSuppressesTheImplicitRecordPair() throws Exception {
        Compilation c = compile(arrayRecord());
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Pixels", true, loadClasses(c));
        Object instance = type.getDeclaredConstructors()[0]
            .newInstance(new float[]{1f, 2f}, 3);

        Method hashCode = type.getMethod("hashCode");
        assertEquals("the injected hashCode must be the one that survives - a compiler-generated "
            + "record hashCode is derived from the components and cannot return the sentinel",
            SENTINEL, hashCode.invoke(instance));
    }

    /**
     * The property the feature actually needs: two records holding equal array
     * contents in distinct arrays must be able to compare equal. The implicit
     * pair compares an array component by reference, so this is the defect
     * generating equality for records exists to fix.
     */
    @Test
    public void injectedEqualsGovernsComparison() throws Exception {
        Compilation c = compile(arrayRecord());
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Pixels", true, loadClasses(c));
        Object a = type.getDeclaredConstructors()[0].newInstance(new float[]{1f, 2f}, 3);
        Object b = type.getDeclaredConstructors()[0].newInstance(new float[]{1f, 2f}, 3);

        assertTrue("distinct arrays with equal contents must reach the injected equals, which the "
            + "implicit record equals would reject on reference identity", a.equals(b));
    }

    /**
     * A record that declares the pair itself is the control. Nothing is
     * injected, so the author's own members must stand - if this fails the
     * injector is overreaching rather than the mechanism working.
     */
    @Test
    public void anAuthorDeclaredPairIsLeftAlone() throws Exception {
        Compilation c = compile(JavaFileObjects.forSourceLines("demo.Owned",
            "package demo;",
            "public record Owned(int value) {",
            "    @Override public boolean equals(Object o) { return false; }",
            "    @Override public int hashCode() { return 7; }",
            "}"));
        assertThat(c).succeeded();

        Class<?> type = Class.forName("demo.Owned", true, loadClasses(c));
        Object instance = type.getDeclaredConstructors()[0].newInstance(1);
        assertEquals("the author's own hashCode must survive untouched",
            7, type.getMethod("hashCode").invoke(instance));
    }

    // ------------------------------------------------------------------
    // The probe
    // ------------------------------------------------------------------

    /**
     * Appends a sentinel {@code equals}/{@code hashCode} pair to every record
     * root element that does not already declare one.
     */
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    public static final class RecordEqualsInjector extends AbstractProcessor {

        static {
            JavacAccessFactory.forRuntime().open();
        }

        private final Set<JCClassDecl> done =
            Collections.newSetFromMap(new IdentityHashMap<>());
        private Optional<JavacBridge> bridge = Optional.empty();

        @Override
        public synchronized void init(ProcessingEnvironment processingEnv) {
            super.init(processingEnv);
            this.bridge = JavacBridge.of(processingEnv);
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (this.bridge.isEmpty()) return false;
            for (Element root : roundEnv.getRootElements()) {
                if (root.getKind() != ElementKind.RECORD) continue;
                JCClassDecl tree = this.bridge.get().treeOf((TypeElement) root);
                if (tree == null || !this.done.add(tree)) continue;
                if (declaresPair(tree)) continue;
                inject(tree);
            }
            return false;
        }

        /** Whether the record body already declares either member. */
        private static boolean declaresPair(JCClassDecl tree) {
            for (var def : tree.defs) {
                if (!(def instanceof JCMethodDecl m)) continue;
                String name = m.name.toString();
                if (name.equals("equals") || name.equals("hashCode")) return true;
            }
            return false;
        }

        private void inject(JCClassDecl tree) {
            JavacBridge b = this.bridge.get();
            TreeMaker make = b.treeMaker();
            Names names = b.names();
            JavacTypeFactory types = new JavacTypeFactory(make, names);
            make.at(tree.pos);

            JCVariableDecl param = make.VarDef(
                make.Modifiers(Flags.PARAMETER),
                names.fromString("o"),
                types.qualIdent("java.lang.Object"),
                null
            );
            JCMethodDecl equals = make.MethodDef(
                make.Modifiers(Flags.PUBLIC),
                names.fromString("equals"),
                make.TypeIdent(TypeTag.BOOLEAN),
                List.nil(),
                List.of(param),
                List.nil(),
                make.Block(0, List.of(make.Return(make.Literal(true)))),
                null
            );
            JCMethodDecl hashCode = make.MethodDef(
                make.Modifiers(Flags.PUBLIC),
                names.fromString("hashCode"),
                make.TypeIdent(TypeTag.INT),
                List.nil(),
                List.nil(),
                List.nil(),
                make.Block(0, List.of(make.Return(make.Literal(SENTINEL)))),
                null
            );

            b.compat().appendDef(tree, equals);
            b.compat().appendDef(tree, hashCode);
        }

    }

}
