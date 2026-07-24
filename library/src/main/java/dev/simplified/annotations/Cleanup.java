package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Local-variable annotation that closes the declared resource at the end of the
 * enclosing block.
 *
 * <p>The annotation processor splits the block at the annotated declaration and
 * wraps everything after it - to the end of that block, not the end of the
 * method - in a try-with-resources statement over the already-declared variable:
 *
 * <pre><code>
 * &#64;Cleanup InputStream in = open();
 * a();
 * return b(in);
 * </code></pre>
 * Compiled to:
 * <pre><code>
 * InputStream in = open();
 * try (in) {
 *     a();
 *     return b(in);
 * }
 * </code></pre>
 *
 * <p>The declaration itself stays outside the try, so an initializer that throws
 * never reaches the close call. An early {@code return}, {@code break} or
 * {@code continue} needs no special handling - the wrapped region ends at the
 * block boundary, so leaving it runs the close first.
 *
 * <h2>Exceptions from the close</h2>
 * Because the desugaring is try-with-resources rather than a
 * {@code try}/{@code finally} pair, a close failure never masks the exception
 * that was already in flight. The body's exception stays primary and the close
 * failure is recorded on it via {@link Throwable#addSuppressed}. That is a
 * deliberate difference from the equivalent {@code finally}-based construct,
 * which discards whichever exception came first.
 *
 * <p>{@link AutoCloseable#close()} may itself be declared to throw a checked
 * exception, and the generated statement is an ordinary one, so the enclosing
 * method must already catch or declare it.
 *
 * <h2>Restrictions</h2>
 * <ul>
 *   <li>The declared type must be {@link AutoCloseable}. There is no attribute
 *       for naming a different method - the language's own resource form is what
 *       the annotation expands to.</li>
 *   <li>The variable must be effectively final, which the existing-variable
 *       resource form requires. Reassigning it after the declaration is a
 *       compile error.</li>
 *   <li>The declaration must have an initializer and must be a statement of a
 *       block. A {@code for}-statement initializer has no block remainder to
 *       close at, and is reported as an error.</li>
 * </ul>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.LOCAL_VARIABLE)
public @interface Cleanup {
}
