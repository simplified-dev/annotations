package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Type-level marker that drives synthesis of a log4j2 logger field on the
 * annotated class or enum.
 *
 * <p>The annotation processor injects, via javac AST mutation, exactly one
 * member:
 * <pre><code>
 * private static final org.apache.logging.log4j.Logger log =
 *     org.apache.logging.log4j.LogManager.getLogger(Foo.class);
 * </code></pre>
 *
 * <p>Writing {@link #topic()} replaces the class literal with that string, so
 * {@code @Log(topic = "audit")} yields {@code LogManager.getLogger("audit")}.
 * The field name comes from {@link #name()} and is {@code log} otherwise.
 *
 * <p>log4j2 is the one backend this annotation targets, and the library
 * declaring it does not depend on log4j2 at all - the generated reference is
 * textual, emitted as a fully-qualified name that javac resolves in the target's
 * own compilation. The annotated module must therefore carry log4j2 on its own
 * classpath.
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;Log
 * public class Uploader {
 *     public void upload(Path file) {
 *         log.info("uploading {}", file);
 *     }
 * }
 *
 * &#64;Log(topic = "audit", name = "AUDIT")
 * public class Ledger {
 *     void record(String entry) {
 *         AUDIT.info(entry);
 *     }
 * }
 * </code></pre>
 *
 * <h2>Restrictions</h2>
 * <ul>
 *   <li>Targets {@code class} and {@code enum} declarations only - an
 *       interface, record, or annotation type is a compiler error.</li>
 *   <li>A target already declaring a field under the resolved name keeps its
 *       own field and generates nothing, with a compiler warning. An inherited
 *       field of that name is not a collision, since the generated field is
 *       {@code private} and shadows rather than clashes.</li>
 *   <li>Requires log4j2 on the annotated module's own compile classpath. This
 *       library depends on nothing and names the logger type textually, so the
 *       dependency is the consumer's to add - {@code
 *       org.apache.logging.log4j:log4j-api} to compile, plus a binding such as
 *       {@code log4j-core} to log at runtime. A module without it is reported
 *       as a compiler error on the annotation rather than as an unresolved
 *       symbol inside the generated field.</li>
 *   <li>Requires javac for AST mutation. Builds running under ecj fail with a
 *       compiler error.</li>
 * </ul>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Log {

    /**
     * Logger topic passed to the factory in place of the target's class
     * literal. Empty keeps the class literal, which is what names the logger
     * after the annotated type.
     */
    String topic() default "";

    /**
     * Name of the generated field. Empty inherits the literal {@code log}; a
     * written pattern may carry the {@code "{}"} placeholder, which expands to the
     * target's simple name and is capitalised unless it opens the pattern, so
     * {@code "{}Log"} on {@code class Foo} yields {@code FooLog}. Suppression is
     * rejected - the field is this annotation's only output.
     */
    String name() default "";

    /**
     * Whether to emit {@link Generated} on the generated field so coverage tools
     * skip it.
     */
    boolean emitGenerated() default true;

}
