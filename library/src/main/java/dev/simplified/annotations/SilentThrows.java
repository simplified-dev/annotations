package dev.simplified.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or constructor whose body may raise a checked exception the
 * declaration does not list.
 *
 * <p>The body is wrapped in {@code try { ... } catch (Throwable $t) { ... }} and
 * the caught throwable is rethrown through a generic helper whose type variable
 * is instantiated at {@link RuntimeException}. The cast erases to nothing, so
 * the original instance leaves the method unaltered - same object, same message,
 * same stack trace - while javac's checked-exception analysis sees only an
 * unchecked throw and asks for no {@code throws} clause.
 *
 * <p>The motivating shape is an implementation of an interface method that
 * declares nothing checked and so has nowhere to put an
 * {@link java.io.IOException}:
 *
 * <h2>Example</h2>
 * <pre><code>
 * &#64;Override
 * &#64;SilentThrows
 * public void write(Image image, OutputStream out) {
 *     ImageIO.write(image.toBuffered(), "png", out);
 * }
 * </code></pre>
 *
 * <h2>Callers can never catch the absorbed type</h2>
 *
 * <p>This is the cost of the feature and it is not recoverable at the call site.
 * The method declares no {@code throws}, so
 * {@code try { writer.write(img, out); } catch (IOException e) { ... }} does not
 * compile - javac rejects the clause as unreachable. A caller that has to react
 * is pushed to {@code catch (Exception e)}, which also swallows runtime bugs.
 * Use this only where the signature is genuinely fixed by something else; where
 * a {@code throws} clause is available, write it instead.
 *
 * <h2>Use the bare form</h2>
 *
 * <p>{@link #value()} narrows the emitted catch clause, and a narrowed type the
 * body cannot actually throw is a javac error about a catch clause for an
 * exception that is never thrown - reported against generated code rather than
 * against the annotation that asked for it. {@code Throwable} and
 * {@code Exception} clauses are never unreachable, so the bare form is always
 * safe and is the one to write.
 *
 * <h2>Restrictions</h2>
 * <ul>
 *   <li>Lambda bodies, method references and anonymous-class bodies are not
 *       covered. Each is its own exception-analysis context; only the annotated
 *       declaration's own body is wrapped, so a lambda nested inside it still has
 *       to handle or declare what it throws. A method reference is checked
 *       against the function type it is assigned to, which the wrap does not
 *       change either.</li>
 *   <li>Abstract, native and otherwise bodyless declarations have nothing to
 *       wrap and the annotation does nothing there.</li>
 *   <li>An initialiser block cannot carry an annotation at all. Move the code
 *       into an annotated method and call it.</li>
 * </ul>
 *
 * @see XContract
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface SilentThrows {

    /**
     * Throwable types the wrap absorbs, one emitted catch clause each.
     *
     * <p>Defaults to {@link Throwable}, which absorbs everything and is the
     * form to use. Narrowing is supported for completeness and turns a mistake
     * into a compile error pointing at code the author never wrote.
     */
    Class<? extends Throwable>[] value() default Throwable.class;

}
