package dev.simplified;

import dev.simplified.xcontract.XContractTranslator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link XContractTranslator#translateValue(String, int)}.
 *
 * <p>The PSI-facing {@code toJetBrainsContract} wrapper is exercised at
 * runtime in the sandbox ({@code ./gradlew runIde}); this class covers the
 * grammar-level translation in isolation.
 */
public class XContractTranslatorTest {

    @Test
    public void paramNullFail_oneArg_translatesToPositional() {
        assertEquals("null -> fail",
            XContractTranslator.translateValue("(param1 == null) -> fail", 1));
    }

    @Test
    public void paramNotNullReturn_oneArg() {
        assertEquals("!null -> this",
            XContractTranslator.translateValue("(param1 != null) -> this", 1));
    }

    @Test
    public void nullFailWithElseNotNull_oneArg() {
        assertEquals("null -> fail; _ -> !null",
            XContractTranslator.translateValue("(param1 == null) -> fail; () -> !null", 1));
    }

    @Test
    public void twoArgs_secondParamConstraint() {
        assertEquals("_, null -> fail",
            XContractTranslator.translateValue("(param2 == null) -> fail", 2));
    }

    @Test
    public void twoArgs_bothConstraintsWithAnd() {
        assertEquals("null, null -> fail",
            XContractTranslator.translateValue("(param1 == null && param2 == null) -> fail", 2));
    }

    @Test
    public void boolConstantTrue_translatesToTrue() {
        assertEquals("true -> fail",
            XContractTranslator.translateValue("(param1 == true) -> fail", 1));
    }

    @Test
    public void boolConstantFalse_translatesToFalse() {
        assertEquals("false -> fail",
            XContractTranslator.translateValue("(param1 == false) -> fail", 1));
    }

    @Test
    public void relationalComparison_drops() {
        // (param1 > 0) has no @Contract equivalent - clause dropped.
        assertEquals("",
            XContractTranslator.translateValue("(param1 > 0) -> fail", 1));
    }

    @Test
    public void orExpression_drops() {
        assertEquals("",
            XContractTranslator.translateValue(
                "(param1 == null || param2 == null) -> fail", 2));
    }

    @Test
    public void fieldAccess_drops() {
        assertEquals("",
            XContractTranslator.translateValue("(param1.empty()) -> fail", 1));
    }

    @Test
    public void namedParamRef_drops() {
        assertEquals("",
            XContractTranslator.translateValue("(index < 0) -> fail", 1));
    }

    @Test
    public void integerReturn_drops() {
        // param1 == null -> 0: return has no @Contract equivalent.
        assertEquals("",
            XContractTranslator.translateValue("(param1 == null) -> 0", 1));
    }

    @Test
    public void mixedTranslatableAndNot_keepsTranslatable() {
        String out = XContractTranslator.translateValue(
            "(param1 == null) -> fail; (param1 > 0) -> true; () -> !null", 1);
        assertEquals("null -> fail; _ -> !null", out);
    }

    @Test
    public void emptySpec_returnsEmpty() {
        assertEquals("", XContractTranslator.translateValue("", 1));
    }

    @Test
    public void malformedSpec_returnsEmpty() {
        assertEquals("", XContractTranslator.translateValue("(param1 = null) -> fail", 1));
    }

    @Test
    public void paramRef_reversedComparison_normalised() {
        // 'null == param1' should still be recognised.
        assertEquals("null -> fail",
            XContractTranslator.translateValue("(null == param1) -> fail", 1));
    }

    @Test
    public void paramReturn_passesThrough() {
        assertEquals("_, _ -> param1",
            XContractTranslator.translateValue("() -> param1", 2));
    }

    @Test
    public void zeroArgMethod_unconditionalReturn() {
        assertEquals("-> !null",
            XContractTranslator.translateValue("() -> !null", 0));
    }

}
