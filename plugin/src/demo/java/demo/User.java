package demo;

import dev.simplified.annotations.BuildFlag;
import dev.simplified.annotations.BuildRule;
import dev.simplified.annotations.ClassBuilder;

/**
 * Shot 1 - the headline @ClassBuilder example. Composition goals:
 * <ul>
 *   <li>Gutter icon visible on the @ClassBuilder annotation line</li>
 *   <li>Record components on a single screen, no scrolling</li>
 *   <li>@BuildFlag rules visible to advertise runtime validation</li>
 * </ul>
 */
@ClassBuilder
public record User(
        @BuildRule(flag = @BuildFlag(nonNull = true, notEmpty = true, limit = 64)) String name,
        @BuildRule(flag = @BuildFlag(nonNull = true, pattern = "^[^@]+@[^@]+\\.[^@]+$")) String email,
        int age,
        boolean verified
) {
}
