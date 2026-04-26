package dev.simplified.annotations;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a method contract using an extended grammar that supersedes
 * {@link Contract}.
 *
 * <p>The {@code Simplified Annotations} plugin synthesises an equivalent
 * {@code @Contract} at analysis time via an
 * {@code InferredAnnotationProvider}, so IntelliJ's data-flow analysis
 * treats {@code @XContract}-annotated methods exactly like
 * {@code @Contract}-annotated ones. Callers do not need to declare both.
 *
 * <h2>Grammar</h2>
 * <pre>
 * ContractSpec := Clause (';' Clause)*
 * Clause       := '(' OrExpr? ')' '->' Return
 * OrExpr       := AndExpr ('||' AndExpr)*
 * AndExpr      := Term ('&amp;&amp;' Term)*
 * Term         := '(' OrExpr ')'                   (grouping)
 *               | '!' Value                        (boolean negation)
 *               | Value 'instanceof' TypeName      (type check)
 *               | Value CompOp (Value CompOp)* Value   (chained comparison)
 *               | Value                            (bare boolean)
 * CompOp       := '&lt;' | '&gt;' | '&lt;=' | '&gt;=' | '==' | '!='
 * Value        := Reference | Constant
 * Reference    := ('param' N | Name | 'this') ('.' SpecialField)*
 * SpecialField := 'size()' | 'length()' | 'length' | 'empty()'
 * Constant     := 'null' | 'true' | 'false' | integer
 * TypeName     := Ident ('.' Ident)*
 * Return       := 'true' | 'false' | 'null' | '!null' | 'fail'
 *               | 'this' | 'new' | 'param' N | Name | integer
 *               | 'throws' TypeName
 * </pre>
 *
 * <h2>Feature matrix (vs. {@code @Contract})</h2>
 * <ul>
 *   <li><b>Relational comparisons</b> - {@code <}, {@code >}, {@code <=},
 *       {@code >=}, {@code ==}, {@code !=} against parameters, constants,
 *       or field-access values.</li>
 *   <li><b>Boolean combinators</b> - {@code &&} and {@code ||} with
 *       grouping parentheses and standard precedence ({@code &&} binds
 *       tighter than {@code ||}).</li>
 *   <li><b>Field access</b> - {@code .size()}, {@code .length()},
 *       {@code .length}, {@code .empty()} on any reference.</li>
 *   <li><b>{@code this} references</b> - compare against receiver state.</li>
 *   <li><b>Named parameter references</b> - use a parameter's declared
 *       name (e.g. {@code index}) in addition to positional
 *       {@code paramN}.</li>
 *   <li><b>Integer / boolean constants</b> - full support for numeric
 *       (including negative) and {@code true}/{@code false} literals.</li>
 *   <li><b>Rich return values</b> - {@code paramN}, {@code this},
 *       {@code new}, and integer constants in addition to the standard
 *       {@code true}/{@code false}/{@code null}/{@code !null}/{@code fail}.</li>
 *   <li><b>{@code pure} / {@code mutates}</b> - same semantics as
 *       {@code @Contract} and forwarded directly to the synthesised
 *       annotation.</li>
 *   <li><b>{@code instanceof} conditions</b> - {@code param1 instanceof Number}.</li>
 *   <li><b>Named-parameter returns</b> - {@code () -> defaultValue} returns the
 *       argument passed for the named parameter.</li>
 *   <li><b>Typed failure returns</b> - {@code -> throws TypeName} documents which
 *       exception is thrown; collapses to {@code fail} in the synthesised
 *       {@code @Contract}.</li>
 *   <li><b>Chained comparisons</b> - {@code 0 &lt;= param1 &lt; this.size()} desugars
 *       to the equivalent {@code &amp;&amp;} chain.</li>
 *   <li><b>Inheritance checks</b> - overrides that weaken a super's {@code pure}
 *       declaration or add mutates targets are flagged.</li>
 *   <li><b>Caller-side violation warnings</b> - call sites whose literal
 *       arguments deterministically trigger a {@code fail}/{@code throws} clause
 *       are flagged by a companion inspection.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 * <pre><code>
 * // Multi-clause: guards with a fallback
 * &#64;XContract("(param1 &lt; 0) -> fail; (param1 &gt;= this.length()) -> fail; () -> !null")
 * char charAt(int index);
 *
 * // Logical OR
 * &#64;XContract("(param1 == null || param2 == null) -> fail")
 * void requireBoth(Object a, Object b);
 *
 * // Grouping + AND/OR mix
 * &#64;XContract("((param1 == null) &amp;&amp; (param2 &gt; 0)) -> fail")
 * void reject(Object a, int b);
 *
 * // Boolean constant and negation
 * &#64;XContract("(param1 == false) -> fail; (!param1) -> fail")
 * void requireTrue(boolean flag);
 *
 * // Named parameter reference (survives `paramN` reordering)
 * &#64;XContract("(index &lt; 0) -> fail")
 * void at(int index);
 *
 * // this/field access
 * &#64;XContract("(this.empty()) -> true; () -> false")
 * boolean isEmpty();
 *
 * // Integer return
 * &#64;XContract("(param1 &gt; param2) -> param1; () -> param2")
 * int max(int a, int b);
 *
 * // Pure and mutates
 * &#64;XContract(pure = true)
 * int size();
 *
 * &#64;XContract(value = "() -> this", mutates = "this")
 * StringBuilder append(String s);
 *
 * // Optional-style emptiness check
 * &#64;XContract("(param1.empty()) -> fail")
 * &lt;T&gt; T requirePresent(Optional&lt;T&gt; opt);
 *
 * // instanceof check
 * &#64;XContract("(param1 instanceof Number) -> true; () -> false")
 * boolean isNumeric(Object value);
 *
 * // Typed throws
 * &#64;XContract("(param1 == null) -> throws IllegalArgumentException")
 * void requireNonNull(Object value);
 *
 * // Chained comparison (range check)
 * &#64;XContract("(0 &lt;= param1 &lt; this.size()) -> !null")
 * Object at(int index);
 *
 * // Named-parameter return
 * &#64;XContract("(param1 == null) -> defaultValue; () -> param1")
 * &lt;T&gt; T orElse(T value, T defaultValue);
 * </code></pre>
 * @see <a href="https://youtrack.jetbrains.com/issue/IDEA-207114/Enhance-contract-conditions">Enhance contract conditions</a>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD, ElementType.CONSTRUCTOR })
public @interface XContract {

    /**
     * One or more semicolon-separated contract clauses. Grammar is documented
     * on the annotation itself.
     */
    @NotNull String value() default "";

    /**
     * When {@code true}, declares that this method has no observable side
     * effects. Equivalent to {@code @Contract(pure = true)} and forwarded
     * directly to the synthesised annotation.
     */
    boolean pure() default false;

    /**
     * Comma-separated list of values this method mutates. Tokens may be
     * {@code this}, {@code paramN}, or {@code io}. Equivalent to
     * {@code @Contract(mutates = "...")} and forwarded directly to the
     * synthesised annotation.
     *
     * <p>Example: {@code mutates = "this, param1"}.
     */
    @NotNull String mutates() default "";

}
