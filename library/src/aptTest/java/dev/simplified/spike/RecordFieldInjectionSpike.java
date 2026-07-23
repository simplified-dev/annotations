package dev.simplified.spike;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;
import dev.simplified.shared.javac.JavacBridge;
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
import java.lang.reflect.Field;
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
 * Probes whether an instance field can be injected into a record's
 * {@code JCClassDecl}, which is the only mechanism that would let a record
 * memoize its own {@code hashCode}.
 *
 * <p>A record body may not declare an instance field - javac rejects it with
 * "field declaration must be static" - so the memo a class gets for free is
 * unavailable to the shape that most wants it: a record holding a large array
 * recomputes its whole content hash on every call. The restriction is syntactic
 * rather than semantic, since every record component is final and a memo could
 * never go stale.
 *
 * <p>The question is therefore whether the restriction is enforced before or
 * after an annotation-processing round, exactly as with the implicit
 * equals/hashCode pair. At the class-file level an extra field is legal: a
 * record is an ordinary class carrying a {@code Record} attribute that lists
 * its components, and a field absent from that attribute is not a component -
 * so reflection, deconstruction and serialization are all unaffected.
 *
 * <p><b>It passes, and the capability is deliberately not used.</b> Caching a
 * hash on a record is refused, and {@code @EqualsAndHashCode(cacheHashCode)}
 * reports an error on a record target - so this class exists to record that the
 * refusal is a choice rather than a limitation, and to stop the mechanism being
 * rediscovered and mistaken for an oversight.
 *
 * <p>Two reasons it is refused. Nothing measured needs it: the type that looked
 * like the motivating case holds a large pixel buffer but is only ever a map
 * <i>value</i>, so its {@code hashCode} is never called, and the workspace's
 * actual map keys are strings, boxed primitives, enums and string-backed
 * records whose hashes are already trivial or self-caching. And the mechanism
 * steps around a deliberate language rule rather than using a sanctioned seam,
 * which is a standing commitment to re-verify on every supported JDK.
 *
 * <p>Note the contrast with the sibling spike, whose mechanism <i>is</i> used:
 * JLS 8.10.3 synthesises the implicit pair only when the body does not declare
 * it, so appending a method operates inside the rule as written. The
 * instance-field restriction is a prohibition being stepped around.
 *
 * <p>The tests stay green so the finding stays true. If a hash expensive enough
 * to memoize ever appears on a record, this is the proof the mechanism works and
 * the shape to add is an opt-in attribute named for the mechanism, so that a
 * type depending on it says so in its own source.
 */
public class RecordFieldInjectionSpike {

    private static final String MEMO = "$hashCode";
    private static final int SENTINEL = 4242;

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new RecordMemoInjector()).compile(sources);
    }

    private static ClassLoader loadClasses(Compilation compilation) throws Exception {
        Path tmp = Files.createTempDirectory("record-field-spike");
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
            RecordFieldInjectionSpike.class.getClassLoader());
    }

    private static JavaFileObject arrayRecord() {
        return JavaFileObjects.forSourceLines("demo.Memoized",
            "package demo;",
            "public record Memoized(float[] values, int width) {",
            "}");
    }

    /**
     * The whole question. If this compiles, a record can hold a memo and the
     * caching restriction is syntactic only.
     */
    @Test
    public void anInstanceFieldCanBeInjectedIntoARecord() {
        Compilation c = compile(arrayRecord());
        assertThat(c).succeeded();
    }

    /**
     * The field must be a real instance field, absent from the record's
     * component list - otherwise it would leak into the canonical constructor,
     * {@code toString} and pattern deconstruction.
     */
    @Test
    public void theInjectedFieldIsNotARecordComponent() throws Exception {
        Compilation c = compile(arrayRecord());
        assertThat(c).succeeded();
        Class<?> type = Class.forName("demo.Memoized", true, loadClasses(c));

        assertTrue("the type must still be a record", type.isRecord());
        assertEquals("the memo must not become a component",
            2, type.getRecordComponents().length);

        Field memo = type.getDeclaredField(MEMO);
        assertTrue("the memo must be an instance field, not static",
            !java.lang.reflect.Modifier.isStatic(memo.getModifiers()));
        assertTrue("the memo must be transient so it is never serialized",
            java.lang.reflect.Modifier.isTransient(memo.getModifiers()));
    }

    /** The memo has to be writable from an injected method, or it buys nothing. */
    @Test
    public void theMemoIsReadableAndWritableAtRuntime() throws Exception {
        Compilation c = compile(arrayRecord());
        assertThat(c).succeeded();
        Class<?> type = Class.forName("demo.Memoized", true, loadClasses(c));
        Object instance = type.getDeclaredConstructors()[0]
            .newInstance(new float[]{1f, 2f}, 3);

        Field memo = type.getDeclaredField(MEMO);
        memo.setAccessible(true);
        assertEquals("starts at the not-computed sentinel", 0, memo.getInt(instance));

        assertEquals("the injected hashCode returns the sentinel",
            SENTINEL, type.getMethod("hashCode").invoke(instance));
        assertEquals("and stores it in the memo",
            SENTINEL, memo.getInt(instance));
    }

    /**
     * Injects {@code private transient int $hashCode} plus a {@code hashCode}
     * that memoizes a sentinel into it.
     */
    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    public static final class RecordMemoInjector extends AbstractProcessor {

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
                inject(tree);
            }
            return false;
        }

        private void inject(JCClassDecl tree) {
            JavacBridge b = this.bridge.get();
            TreeMaker make = b.treeMaker();
            Names names = b.names();
            make.at(tree.pos);

            JCVariableDecl memo = make.VarDef(
                make.Modifiers(Flags.PRIVATE | Flags.TRANSIENT),
                names.fromString(MEMO),
                make.TypeIdent(TypeTag.INT),
                null
            );

            // if ($hashCode != 0) return $hashCode;
            // $hashCode = SENTINEL;
            // return $hashCode;
            JCStatement early = make.If(
                make.Binary(com.sun.tools.javac.tree.JCTree.Tag.NE,
                    make.Ident(names.fromString(MEMO)),
                    make.Literal(0)),
                make.Return(make.Ident(names.fromString(MEMO))),
                null
            );
            JCStatement store = make.Exec(make.Assign(
                make.Ident(names.fromString(MEMO)), make.Literal(SENTINEL)));
            JCMethodDecl hashCode = make.MethodDef(
                make.Modifiers(Flags.PUBLIC),
                names.fromString("hashCode"),
                make.TypeIdent(TypeTag.INT),
                List.nil(),
                List.nil(),
                List.nil(),
                make.Block(0, List.of(early, store,
                    make.Return(make.Ident(names.fromString(MEMO))))),
                null
            );

            b.compat().appendDef(tree, memo);
            b.compat().appendDef(tree, hashCode);
        }

    }

}
