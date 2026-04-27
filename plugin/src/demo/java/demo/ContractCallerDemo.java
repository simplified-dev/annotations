package demo;

/**
 * Shot 5 - caller-side @XContract inspection. Composition goals:
 * <ul>
 *   <li>Inspection tooltip ("Argument always violates contract") visible
 *       over the literal-argument call. Hover the caret over the reddened
 *       argument and screenshot when the tooltip materialises.</li>
 *   <li>Two distinct violation flavours visible without scrolling: the
 *       relational range violation and the null-on-non-null violation.</li>
 * </ul>
 *
 * <p>The bridge to JetBrains' built-in {@code @Contract} inference (via the
 * {@code XContractInferredAnnotationProvider}) means standard data-flow also
 * lights up these calls; both inspections firing at once is the point.
 */
public final class ContractCallerDemo {

    public static void main(String[] args) {
        // value < min: contract says fail.
        MathUtils.requireInRange(0, 10, 20);

        // min > max: contract says fail.
        MathUtils.requireInRange(5, 100, 1);

        // input == null: contract says fail.
        MathUtils.asString(null);

        // name is empty: contract says throws IllegalArgumentException.
        MathUtils.requireName("");
    }
}
