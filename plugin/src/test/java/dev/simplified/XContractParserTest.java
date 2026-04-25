package dev.simplified;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import dev.simplified.contract.ContractAst;
import dev.simplified.contract.ContractParseException;
import dev.simplified.contract.ContractParser;

import java.util.List;

public class XContractParserTest extends BasePlatformTestCase {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static ContractAst.Clause single(String spec) throws ContractParseException {
        List<ContractAst.Clause> clauses = ContractParser.parse(spec);
        assertEquals("Expected exactly one clause in: " + spec, 1, clauses.size());
        return clauses.get(0);
    }

    /** Extracts the left-hand value from a CompExpr or the sole value from a ValExpr. */
    private static ContractAst.Value leftOf(ContractAst.Expr expr) {
        if (expr instanceof ContractAst.CompExpr c) return c.left();
        if (expr instanceof ContractAst.ValExpr  v) return v.value();
        throw new AssertionError("Unexpected expression type: " + expr.getClass().getSimpleName());
    }

    /** Asserts that parsing the given spec throws {@link ContractParseException} and returns it. */
    private static ContractParseException expectParseError(String spec) {
        try {
            ContractParser.parse(spec);
            throw new AssertionError("Expected ContractParseException for: " + spec);
        } catch (ContractParseException e) {
            return e;
        }
    }

    // ─── Return values ────────────────────────────────────────────────────────

    public void testReturnValues_keywords() throws Exception {
        assertInstanceOf(single("() -> true").returnVal(),  ContractAst.TrueRet.class);
        assertInstanceOf(single("() -> false").returnVal(), ContractAst.FalseRet.class);
        assertInstanceOf(single("() -> null").returnVal(),  ContractAst.NullRet.class);
        assertInstanceOf(single("() -> !null").returnVal(), ContractAst.NotNullRet.class);
        assertInstanceOf(single("() -> fail").returnVal(),  ContractAst.FailRet.class);
        assertInstanceOf(single("() -> this").returnVal(),  ContractAst.ThisRet.class);
        assertInstanceOf(single("() -> new").returnVal(),   ContractAst.NewRet.class);
    }

    public void testReturnValue_param() throws Exception {
        ContractAst.ReturnVal ret = single("() -> param2").returnVal();
        assertEquals(2, assertInstanceOf(ret, ContractAst.ParamRet.class).index());
    }

    public void testReturnValue_positiveInt() throws Exception {
        ContractAst.ReturnVal ret = single("() -> 1").returnVal();
        assertEquals(1, assertInstanceOf(ret, ContractAst.IntRet.class).value());
    }

    public void testReturnValue_negativeInt() throws Exception {
        ContractAst.ReturnVal ret = single("() -> -1").returnVal();
        assertEquals(-1, assertInstanceOf(ret, ContractAst.IntRet.class).value());
    }

    public void testReturnValue_zero() throws Exception {
        ContractAst.ReturnVal ret = single("() -> 0").returnVal();
        assertEquals(0, assertInstanceOf(ret, ContractAst.IntRet.class).value());
    }

    // ─── Empty condition ──────────────────────────────────────────────────────

    public void testEmptyCondition_isNull() throws Exception {
        assertNull(single("() -> fail").condition());
    }

    // ─── Comparison operators ─────────────────────────────────────────────────

    public void testComparisonOperators() throws Exception {
        String[] specs = {
            "(param1 <  0) -> fail",
            "(param1 >  0) -> fail",
            "(param1 <= 0) -> fail",
            "(param1 >= 0) -> fail",
            "(param1 == 0) -> fail",
            "(param1 != 0) -> fail"
        };
        ContractAst.CompOp[] ops = {
            ContractAst.CompOp.LT,  ContractAst.CompOp.GT,
            ContractAst.CompOp.LTE, ContractAst.CompOp.GTE,
            ContractAst.CompOp.EQ,  ContractAst.CompOp.NEQ
        };
        for (int i = 0; i < specs.length; i++) {
            ContractAst.Expr expr = single(specs[i]).condition();
            assertEquals(ops[i], assertInstanceOf(expr, ContractAst.CompExpr.class).op());
        }
    }

    public void testComparison_paramVsParam() throws Exception {
        ContractAst.Expr expr = single("(param1 > param2) -> param1").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        assertEquals(1, ((ContractAst.ParamRef) comp.left()).index());
        assertEquals(2, ((ContractAst.ParamRef) comp.right()).index());
        assertEquals(ContractAst.CompOp.GT, comp.op());
    }

    public void testComparison_paramVsNull() throws Exception {
        ContractAst.Expr expr = single("(param1 == null) -> fail").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        assertInstanceOf(comp.right(), ContractAst.NullConst.class);
    }

    public void testComparison_intVsInt() throws Exception {
        ContractAst.Expr expr = single("(1 > 0) -> fail").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        assertEquals(1, ((ContractAst.IntConst) comp.left()).value());
        assertEquals(0, ((ContractAst.IntConst) comp.right()).value());
    }

    public void testComparison_negativeInt() throws Exception {
        ContractAst.Expr expr = single("(param1 >= -1) -> fail").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        assertEquals(-1, ((ContractAst.IntConst) comp.right()).value());
    }

    // ─── Negation ─────────────────────────────────────────────────────────────

    public void testNegation_ofParam() throws Exception {
        ContractAst.Expr expr = single("(!param1) -> fail").condition();
        ContractAst.NegExpr neg = assertInstanceOf(expr, ContractAst.NegExpr.class);
        ContractAst.ParamRef ref = assertInstanceOf(neg.value(), ContractAst.ParamRef.class);
        assertEquals(1, ref.index());
    }

    public void testNegation_ofNull() throws Exception {
        ContractAst.Expr expr = single("(!null) -> fail").condition();
        ContractAst.NegExpr neg = assertInstanceOf(expr, ContractAst.NegExpr.class);
        assertInstanceOf(neg.value(), ContractAst.NullConst.class);
    }

    // ─── AND expressions ──────────────────────────────────────────────────────

    public void testAnd_twoTerms_structure() throws Exception {
        ContractAst.Expr expr = single("(param1 > 0 && param2 > 0) -> true").condition();
        ContractAst.AndExpr and = assertInstanceOf(expr, ContractAst.AndExpr.class);
        assertInstanceOf(and.left(),  ContractAst.CompExpr.class);
        assertInstanceOf(and.right(), ContractAst.CompExpr.class);
    }

    public void testAnd_threeTerms_isLeftAssociative() throws Exception {
        // a && b && c should parse as AndExpr(AndExpr(a, b), c)
        ContractAst.Expr expr = single("(param1 > 0 && param2 > 0 && param3 != null) -> fail").condition();
        ContractAst.AndExpr outer = assertInstanceOf(expr, ContractAst.AndExpr.class);
        assertInstanceOf(outer.left(),  ContractAst.AndExpr.class);
        assertInstanceOf(outer.right(), ContractAst.CompExpr.class);
    }

    public void testAnd_mixedTermTypes() throws Exception {
        ContractAst.Expr expr = single("(param1.empty() && param2 != null) -> fail").condition();
        ContractAst.AndExpr and = assertInstanceOf(expr, ContractAst.AndExpr.class);
        assertInstanceOf(and.left(),  ContractAst.ValExpr.class);  // empty() is a bare boolean
        assertInstanceOf(and.right(), ContractAst.CompExpr.class);
    }

    // ─── OR expressions ───────────────────────────────────────────────────────

    public void testOr_twoTerms_structure() throws Exception {
        ContractAst.Expr expr = single("(param1 == null || param2 == null) -> fail").condition();
        ContractAst.OrExpr or = assertInstanceOf(expr, ContractAst.OrExpr.class);
        assertInstanceOf(or.left(),  ContractAst.CompExpr.class);
        assertInstanceOf(or.right(), ContractAst.CompExpr.class);
    }

    public void testOr_precedence_lowerThanAnd() throws Exception {
        // a || b && c should parse as OrExpr(a, AndExpr(b, c))
        ContractAst.Expr expr = single("(param1 != null || param2 != null && param3 > 0) -> fail").condition();
        ContractAst.OrExpr or = assertInstanceOf(expr, ContractAst.OrExpr.class);
        assertInstanceOf(or.left(),  ContractAst.CompExpr.class);
        assertInstanceOf(or.right(), ContractAst.AndExpr.class);
    }

    public void testOr_leftAssociative() throws Exception {
        // a || b || c should parse as OrExpr(OrExpr(a, b), c)
        ContractAst.Expr expr = single("(param1 > 0 || param2 > 0 || param3 > 0) -> fail").condition();
        ContractAst.OrExpr outer = assertInstanceOf(expr, ContractAst.OrExpr.class);
        assertInstanceOf(outer.left(),  ContractAst.OrExpr.class);
        assertInstanceOf(outer.right(), ContractAst.CompExpr.class);
    }

    // ─── Grouping parens ──────────────────────────────────────────────────────

    public void testGrouping_overridesPrecedence() throws Exception {
        // (a || b) && c should parse as AndExpr(OrExpr(a, b), c)
        ContractAst.Expr expr = single("((param1 > 0 || param2 > 0) && param3 != null) -> fail").condition();
        ContractAst.AndExpr and = assertInstanceOf(expr, ContractAst.AndExpr.class);
        assertInstanceOf(and.left(),  ContractAst.OrExpr.class);
        assertInstanceOf(and.right(), ContractAst.CompExpr.class);
    }

    public void testGrouping_redundantParens() throws Exception {
        ContractAst.Expr expr = single("((param1 == null)) -> fail").condition();
        assertInstanceOf(expr, ContractAst.CompExpr.class);
    }

    // ─── Boolean constants ────────────────────────────────────────────────────

    public void testBoolConst_trueInComparison() throws Exception {
        ContractAst.Expr expr = single("(param1 == true) -> fail").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        ContractAst.BoolConst b = assertInstanceOf(comp.right(), ContractAst.BoolConst.class);
        assertTrue(b.value());
    }

    public void testBoolConst_falseInComparison() throws Exception {
        ContractAst.Expr expr = single("(param1 == false) -> fail").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        ContractAst.BoolConst b = assertInstanceOf(comp.right(), ContractAst.BoolConst.class);
        assertFalse(b.value());
    }

    // ─── Parameter-name references ────────────────────────────────────────────

    public void testParamNameRef_basic() throws Exception {
        ContractAst.Expr expr = single("(index < 0) -> fail").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        ContractAst.ParamNameRef named = assertInstanceOf(comp.left(), ContractAst.ParamNameRef.class);
        assertEquals("index", named.name());
        assertTrue(named.fields().isEmpty());
    }

    public void testParamNameRef_withField() throws Exception {
        ContractAst.Expr expr = single("(name.empty()) -> fail").condition();
        ContractAst.ValExpr val = assertInstanceOf(expr, ContractAst.ValExpr.class);
        ContractAst.ParamNameRef named = assertInstanceOf(val.value(), ContractAst.ParamNameRef.class);
        assertEquals("name", named.name());
        assertEquals(List.of(ContractAst.SpecialField.EMPTY), named.fields());
    }

    public void testParamNameRef_paramNStillPrefersIndex() throws Exception {
        // 'param3' must still parse as ParamRef(3, []), not as ParamNameRef("param3")
        ContractAst.Expr expr = single("(param3 < 0) -> fail").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        ContractAst.ParamRef ref = assertInstanceOf(comp.left(), ContractAst.ParamRef.class);
        assertEquals(3, ref.index());
    }

    // ─── instanceof ───────────────────────────────────────────────────────────

    public void testInstanceOf_basic() throws Exception {
        ContractAst.Expr expr = single("(param1 instanceof Number) -> true").condition();
        ContractAst.InstanceOfExpr inst = assertInstanceOf(expr, ContractAst.InstanceOfExpr.class);
        assertEquals(1, ((ContractAst.ParamRef) inst.value()).index());
        assertEquals("Number", inst.typeName());
    }

    public void testInstanceOf_fullyQualified() throws Exception {
        ContractAst.Expr expr = single("(param1 instanceof java.lang.Number) -> true").condition();
        ContractAst.InstanceOfExpr inst = assertInstanceOf(expr, ContractAst.InstanceOfExpr.class);
        assertEquals("java.lang.Number", inst.typeName());
    }

    public void testInstanceOf_inAndChain() throws Exception {
        ContractAst.Expr expr = single("(param1 instanceof Number && param2 != null) -> true").condition();
        ContractAst.AndExpr and = assertInstanceOf(expr, ContractAst.AndExpr.class);
        assertInstanceOf(and.left(), ContractAst.InstanceOfExpr.class);
    }

    // ─── Throws returns ───────────────────────────────────────────────────────

    public void testThrows_unqualified() throws Exception {
        ContractAst.ReturnVal ret = single("(param1 == null) -> throws IllegalArgumentException").returnVal();
        ContractAst.ThrowsRet t = assertInstanceOf(ret, ContractAst.ThrowsRet.class);
        assertEquals("IllegalArgumentException", t.typeName());
    }

    public void testThrows_qualified() throws Exception {
        ContractAst.ReturnVal ret = single("() -> throws java.lang.NullPointerException").returnVal();
        ContractAst.ThrowsRet t = assertInstanceOf(ret, ContractAst.ThrowsRet.class);
        assertEquals("java.lang.NullPointerException", t.typeName());
    }

    // ─── Named-parameter returns ──────────────────────────────────────────────

    public void testParamNameRet_returnsName() throws Exception {
        ContractAst.ReturnVal ret = single("() -> defaultValue").returnVal();
        ContractAst.ParamNameRet named = assertInstanceOf(ret, ContractAst.ParamNameRet.class);
        assertEquals("defaultValue", named.name());
    }

    public void testParamNameRet_notConfusedWithParamN() throws Exception {
        ContractAst.ReturnVal ret = single("() -> param1").returnVal();
        assertInstanceOf(ret, ContractAst.ParamRet.class);
    }

    // ─── Chained comparisons (range sugar) ────────────────────────────────────

    public void testRangeChain_basic() throws Exception {
        // 0 <= param1 < this.size()  =>  AndExpr(CompExpr(0, <=, param1), CompExpr(param1, <, this.size()))
        ContractAst.Expr expr = single("(0 <= param1 < this.size()) -> true").condition();
        ContractAst.AndExpr and = assertInstanceOf(expr, ContractAst.AndExpr.class);
        ContractAst.CompExpr left  = assertInstanceOf(and.left(),  ContractAst.CompExpr.class);
        ContractAst.CompExpr right = assertInstanceOf(and.right(), ContractAst.CompExpr.class);
        assertEquals(ContractAst.CompOp.LTE, left.op());
        assertEquals(ContractAst.CompOp.LT,  right.op());
        // middle operand is shared
        assertEquals(1, ((ContractAst.ParamRef) left.right()).index());
        assertEquals(1, ((ContractAst.ParamRef) right.left()).index());
    }

    public void testRangeChain_threeLevels() throws Exception {
        // a < b < c < d  =>  AndExpr(AndExpr(a<b, b<c), c<d)
        ContractAst.Expr expr = single("(0 < param1 < param2 < 100) -> true").condition();
        ContractAst.AndExpr outer = assertInstanceOf(expr, ContractAst.AndExpr.class);
        assertInstanceOf(outer.left(),  ContractAst.AndExpr.class);
        assertInstanceOf(outer.right(), ContractAst.CompExpr.class);
    }

    // ─── Single-pipe error ────────────────────────────────────────────────────

    public void testSinglePipe_throws() {
        ContractParseException ex = expectParseError("(param1 | param2) -> fail");
        assertTrue("Expected '||' hint, got: " + ex.getMessage(), ex.getMessage().contains("||"));
    }

    // ─── SpecialField access ──────────────────────────────────────────────────

    public void testSpecialFields_onParamRef() throws Exception {
        String[] specs = {
            "(param1.size()   == 0) -> true",
            "(param1.length() == 0) -> true",
            "(param1.length   == 0) -> true",
            "(param1.empty())       -> fail"
        };
        ContractAst.SpecialField[] fields = {
            ContractAst.SpecialField.SIZE,
            ContractAst.SpecialField.LENGTH_METHOD,
            ContractAst.SpecialField.LENGTH_FIELD,
            ContractAst.SpecialField.EMPTY
        };
        for (int i = 0; i < specs.length; i++) {
            ContractAst.Value left = leftOf(single(specs[i]).condition());
            ContractAst.ParamRef ref = assertInstanceOf(left, ContractAst.ParamRef.class);
            assertEquals(List.of(fields[i]), ref.fields());
        }
    }

    public void testSpecialField_onThisRef() throws Exception {
        ContractAst.Value left = leftOf(single("(this.length() == 0) -> true").condition());
        ContractAst.ThisRef ref = assertInstanceOf(left, ContractAst.ThisRef.class);
        assertEquals(List.of(ContractAst.SpecialField.LENGTH_METHOD), ref.fields());
    }

    public void testSpecialField_empty_isValExpr_notComparison() throws Exception {
        // empty() is boolean — used directly as a Term without a comparison operator
        ContractAst.Expr expr = single("(param1.empty()) -> fail").condition();
        assertInstanceOf(expr, ContractAst.ValExpr.class);
    }

    public void testSpecialField_empty_onThis() throws Exception {
        ContractAst.Value left = leftOf(single("(this.empty()) -> true").condition());
        ContractAst.ThisRef ref = assertInstanceOf(left, ContractAst.ThisRef.class);
        assertEquals(List.of(ContractAst.SpecialField.EMPTY), ref.fields());
    }

    // ─── Multi-clause ─────────────────────────────────────────────────────────

    public void testMultiClause_count() throws Exception {
        assertEquals(2, ContractParser.parse("(param1 > param2) -> param1; () -> param2").size());
    }

    public void testMultiClause_firstHasCondition_secondIsEmpty() throws Exception {
        List<ContractAst.Clause> clauses = ContractParser.parse("(param1 > param2) -> param1; () -> param2");
        assertNotNull(clauses.get(0).condition());
        assertNull(clauses.get(1).condition());
    }

    public void testMultiClause_three() throws Exception {
        List<ContractAst.Clause> clauses = ContractParser.parse(
            "(param1 < 0) -> fail; (param1 >= this.length()) -> fail; () -> !null"
        );
        assertEquals(3, clauses.size());
        assertInstanceOf(clauses.get(0).returnVal(), ContractAst.FailRet.class);
        assertInstanceOf(clauses.get(1).returnVal(), ContractAst.FailRet.class);
        assertInstanceOf(clauses.get(2).returnVal(), ContractAst.NotNullRet.class);
    }

    // ─── Real-world spec examples ─────────────────────────────────────────────

    public void testExample_stringIsEmpty() throws Exception {
        List<ContractAst.Clause> clauses = ContractParser.parse("(this.length() == 0) -> true; () -> false");
        assertEquals(2, clauses.size());
        assertInstanceOf(clauses.get(0).returnVal(), ContractAst.TrueRet.class);
        assertInstanceOf(clauses.get(1).returnVal(), ContractAst.FalseRet.class);
    }

    public void testExample_mathMax_returnsCorrectParam() throws Exception {
        List<ContractAst.Clause> clauses = ContractParser.parse("(param1 > param2) -> param1; () -> param2");
        assertEquals(1, ((ContractAst.ParamRet) clauses.get(0).returnVal()).index());
        assertEquals(2, ((ContractAst.ParamRet) clauses.get(1).returnVal()).index());
    }

    public void testExample_assertEquals() throws Exception {
        List<ContractAst.Clause> clauses = ContractParser.parse("(param1 != param2) -> fail");
        assertEquals(1, clauses.size());
        assertInstanceOf(clauses.get(0).returnVal(), ContractAst.FailRet.class);
    }

    public void testExample_charAt_twoGuards() throws Exception {
        List<ContractAst.Clause> clauses = ContractParser.parse(
            "(param1 < 0) -> fail; (param1 >= this.length()) -> fail"
        );
        assertEquals(2, clauses.size());
        for (ContractAst.Clause c : clauses)
            assertInstanceOf(c.returnVal(), ContractAst.FailRet.class);
    }

    public void testExample_optionalRequirePresent() throws Exception {
        List<ContractAst.Clause> clauses = ContractParser.parse("(param1.empty()) -> fail");
        assertEquals(1, clauses.size());
        assertInstanceOf(clauses.get(0).returnVal(), ContractAst.FailRet.class);
    }

    // ─── Whitespace tolerance ─────────────────────────────────────────────────

    public void testWhitespace_compact_parsesIdentically() throws Exception {
        List<ContractAst.Clause> spaced  = ContractParser.parse("( param1 > 0 ) -> fail");
        List<ContractAst.Clause> compact = ContractParser.parse("(param1>0)->fail");
        assertEquals(spaced.size(), compact.size());
        ContractAst.CompExpr spacedComp  = assertInstanceOf(spaced.get(0).condition(),  ContractAst.CompExpr.class);
        ContractAst.CompExpr compactComp = assertInstanceOf(compact.get(0).condition(), ContractAst.CompExpr.class);
        assertEquals(spacedComp.op(), compactComp.op());
    }

    // ─── Syntax error cases ───────────────────────────────────────────────────

    public void testInvalidSyntax() {
        Object[][] cases = {
            { "fail",                 "Expected '('"          },
            { "(param1 > 0) true",   "Expected '->'"         },
            { "(param1 > 0",         "Expected ')'"          },
            { "() ->",               "Expected a return value"},
            // Note: "() -> unknown" now parses as ParamNameRet - inspection validates name
            { "() -> true; ",        "Expected '('"          },
            { "() -> true extra",    "Unexpected token"      },
            { "(param1 & param2)",   "Use '&&'"              },
            { "(param1 = 0) -> fail","Use '=='"              }
        };
        for (Object[] c : cases) {
            String spec       = (String) c[0];
            String expectedMsg = (String) c[1];
            ContractParseException ex = expectParseError(spec);
            assertTrue("For spec '" + spec + "': expected message containing '" + expectedMsg
                + "' but was: " + ex.getMessage(), ex.getMessage().contains(expectedMsg));
        }
    }

    // ─── Semantic error cases (valid tokens, bad meaning) ─────────────────────

    public void testParamIndex_zero_inCondition_throws() {
        ContractParseException ex = expectParseError("(param0 > 0) -> fail");
        assertTrue(ex.getMessage().contains("must be >= 1"));
    }

    public void testParamIndex_zero_inReturn_throws() {
        ContractParseException ex = expectParseError("() -> param0");
        assertTrue(ex.getMessage().contains("must be >= 1"));
    }

    public void testBareParam_isTreatedAsName() throws Exception {
        // 'param' (no number) is valid now - it's a parameter reference by name.
        // Resolution against the actual method parameters happens in the inspection.
        ContractAst.Expr expr = single("(param > 0) -> fail").condition();
        ContractAst.CompExpr comp = assertInstanceOf(expr, ContractAst.CompExpr.class);
        ContractAst.ParamNameRef named = assertInstanceOf(comp.left(), ContractAst.ParamNameRef.class);
        assertEquals("param", named.name());
    }

    public void testSizeWithoutParens_throws() {
        ContractParseException ex = expectParseError("(this.size == 0) -> fail");
        assertTrue(ex.getMessage().contains("size()"));
    }

    public void testUnknownSpecialField_throws() {
        ContractParseException ex = expectParseError("(this.hashCode()) -> fail");
        assertTrue(ex.getMessage().contains("Unknown field"));
    }

    public void testNotNullReturn_withNonNullSuffix_throws() {
        ContractParseException ex = expectParseError("() -> !true");
        assertTrue(ex.getMessage().contains("!null"));
    }

    // ─── Error position accuracy ──────────────────────────────────────────────

    public void testErrorPosition_singleEquals() {
        // "(param1 = 0) -> fail"
        //  01234567 8
        //           ^ pos 8
        ContractParseException ex = expectParseError("(param1 = 0) -> fail");
        assertEquals(8, ex.getPosition());
    }

    public void testErrorPosition_unknownField() {
        // "(this.bad()) -> fail"
        //  012345 6
        //         ^ pos 6
        ContractParseException ex = expectParseError("(this.bad()) -> fail");
        assertEquals(6, ex.getPosition());
    }

    public void testErrorTokenLength_isReported() {
        // Token length lets the IDE highlight exactly the offending text.
        // "hashCode" starts at pos 6, has length 8.
        ContractParseException ex = expectParseError("(this.hashCode()) -> fail");
        assertEquals(6, ex.getPosition());
        assertEquals(8, ex.getTokenLength());
    }

}