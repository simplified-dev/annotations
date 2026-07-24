package dev.simplified.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a member as synthesised by this library rather than written by the
 * author.
 *
 * <p>Coverage tools are the reason it exists. JaCoCo filters any member
 * carrying an annotation whose <b>simple name</b> is {@code Generated} and
 * whose retention outlives the compiler, so two properties here are load
 * bearing and neither is a style choice:
 * <ul>
 *   <li><b>The name must stay {@code Generated}.</b> Renaming it to something
 *       more descriptive turns the filtering off with no compile error and no
 *       failing test - generated members simply start counting as uncovered.</li>
 *   <li><b>Retention must stay {@link RetentionPolicy#CLASS}.</b>
 *       {@link RetentionPolicy#SOURCE} never reaches a class file, and a member
 *       injected by AST mutation has no source for a tool to read instead.
 *       {@link RetentionPolicy#RUNTIME} would work but lands the marker in
 *       {@code RuntimeVisibleAnnotations}, making it reflectively visible on
 *       every generated member for no gain.</li>
 * </ul>
 *
 * <p>It lands on synthesised members and synthesised types, never on the
 * annotated target itself - marking the target would discard coverage for the
 * author's own methods and turn a documentation marker into a silent coverage
 * hole. It carries no attributes: a timestamp would break reproducible builds,
 * and naming the generator duplicates what {@link ClassBuilder} on the
 * enclosing type already says.
 *
 * @see ClassBuilder#emitGenerated()
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD})
public @interface Generated {
}
