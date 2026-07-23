package dev.simplified.cleanup.inspect;

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;

/**
 * The annotation and resource stubs both {@code @Cleanup} fixtures compile
 * against. The fixture does not see the real library sources, so the annotation
 * has to be written into the test project exactly as it ships.
 */
final class CleanupTestSources {

    private CleanupTestSources() {}

    static void install(JavaCodeInsightTestFixture fixture) {
        fixture.addFileToProject("dev/simplified/annotations/Cleanup.java",
            """
            package dev.simplified.annotations;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.SOURCE) @Target(ElementType.LOCAL_VARIABLE)
            public @interface Cleanup { }
            """);
        fixture.addFileToProject("demo/Res.java",
            """
            package demo;
            public class Res implements AutoCloseable {
                public boolean open() { return false; }
                public void use() { }
                @Override public void close() { }
            }
            """);
    }

}
