package dev.simplified.classbuilder.mutate;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import dev.simplified.classbuilder.mutate.compat.JavacCompat;
import dev.simplified.classbuilder.mutate.compat.JavacCompatFactory;
import dev.simplified.classbuilder.mutate.compat.v17.JavacCompatV17;
import org.junit.Test;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Phase 1 smoke tests for the javac bridge and version-gated compat dispatch.
 * Verifies that {@link JavacBridge#of} succeeds when running under plain
 * javac (the compile-testing harness) and that {@link JavacCompatFactory}
 * returns the expected shim for a given feature version.
 */
public class JavacBridgeTest {

    @Test
    public void bridgeIsAvailableUnderJavac() {
        AtomicReference<Optional<JavacBridge>> captured = new AtomicReference<>(Optional.empty());
        Compilation c = Compiler.javac()
            .withProcessors(new BridgeCapturingProcessor(captured))
            .compile(JavaFileObjects.forSourceLines("demo.Marker",
                "package demo;",
                "@dev.simplified.classbuilder.mutate.JavacBridgeTest.Probe",
                "public class Marker { }"));
        assertThat(c).succeeded();

        Optional<JavacBridge> bridge = captured.get();
        assertTrue("bridge must be present when running on javac", bridge.isPresent());
        JavacBridge b = bridge.get();
        assertNotNull("TreeMaker must resolve", b.treeMaker());
        assertNotNull("Names must resolve", b.names());
        assertNotNull("Symtab must resolve", b.symtab());
        assertNotNull("Trees must resolve", b.trees());
        assertNotNull("compat shim must resolve", b.compat());
    }

    /**
     * Every currently supported JDK maps to the V17 baseline because nothing
     * in the pipeline has drifted. When a future divergence lands this test
     * will be joined by a second assertion on the new shim class.
     */
    @Test
    public void compatFactoryReturnsV17BaselineForAllSupportedVersions() {
        for (int feature : new int[]{17, 18, 20, 21, 22, 23, 24, 25, 99}) {
            assertEquals("feature " + feature + " must resolve to the V17 baseline",
                JavacCompatV17.class, JavacCompatFactory.forFeatureVersion(feature).getClass());
        }
    }

    @Test
    public void compatForRuntimeMatchesCurrentJdk() {
        int feature = Runtime.version().feature();
        JavacCompat runtime = JavacCompatFactory.forRuntime();
        JavacCompat explicit = JavacCompatFactory.forFeatureVersion(feature);
        assertSame("dispatch must agree between forRuntime and forFeatureVersion",
            runtime.getClass(), explicit.getClass());
    }

    /**
     * Minimal processor that captures the {@link JavacBridge} resolved during
     * {@code init()} so the test can assert on it. Keeps probe to trigger at
     * least one processing round.
     */
    @SupportedAnnotationTypes("dev.simplified.classbuilder.mutate.JavacBridgeTest.Probe")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    public static final class BridgeCapturingProcessor extends AbstractProcessor {

        private final AtomicReference<Optional<JavacBridge>> sink;

        public BridgeCapturingProcessor(AtomicReference<Optional<JavacBridge>> sink) {
            this.sink = sink;
        }

        @Override
        public synchronized void init(javax.annotation.processing.ProcessingEnvironment env) {
            super.init(env);
            sink.set(JavacBridge.of(env));
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return false;
        }

    }

    /** Trigger annotation for the capturing processor. */
    public @interface Probe { }

}
