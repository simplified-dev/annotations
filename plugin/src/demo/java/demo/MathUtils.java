package demo;

import dev.simplified.annotations.XContract;

/**
 * Shot 4 - @XContract declaration with grammar features standard {@code @Contract}
 * cannot express. Composition goals:
 * <ul>
 *   <li>One method per extended-grammar feature, on consecutive lines</li>
 *   <li>No errors, no warnings - the declaration-side inspection is silent
 *       when the contract parses cleanly. The point is the rich syntax,
 *       not a problem report.</li>
 * </ul>
 */
public final class MathUtils {

    private MathUtils() {}

    /**
     * Demonstrates relational comparison + chained comparison.
     */
    @XContract(value = "(min > max) -> fail; (value < min || value > max) -> fail", pure = true)
    public static int requireInRange(int value, int min, int max) {
        if (min > max || value < min || value > max) {
            throw new IllegalArgumentException("value out of range");
        }
        return value;
    }

    /**
     * Demonstrates named-parameter references and {@code instanceof}.
     */
    @XContract(value = "(input == null) -> fail; (input instanceof String) -> !null", pure = true)
    public static String asString(Object input) {
        if (input == null) throw new NullPointerException("input");
        return input instanceof String s ? s : input.toString();
    }

    /**
     * Demonstrates throws-typed return + grouped boolean combinator.
     */
    @XContract("(name == null || name.empty()) -> throws java.lang.IllegalArgumentException")
    public static void requireName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }
    }
}
