package dev.simplified.classbuilder.apt;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The shared shape decision, exercised without javac and without PSI.
 *
 * <p>That it answers from names and flags alone is the property the whole
 * arrangement rests on: the processor fills the facts from a tree the round is
 * still building and the editor fills them from stubs it must not resolve, and
 * neither can hand the other a type. A case here that needed either model would
 * be a case the plugin could not run.
 */
public class DeclaredBuilderShapeTest {

    private static final List<String> SELF_NAMES = List.of("T", "B");

    private static DeclaredBuilderFacts facts(boolean nestedStatic, boolean nestedAbstract,
                                              List<String> parameterNames,
                                              List<String> parameterBounds,
                                              String writtenSuperType,
                                              DeclaredBuildMethod buildMethod) {
        return new DeclaredBuilderFacts(nestedStatic, nestedAbstract, parameterNames,
            parameterBounds, writtenSuperType, List.of(), buildMethod);
    }

    /** The shape a plain standalone target's author writes, and it is accepted. */
    private static DeclaredBuilderFacts usableStandalone() {
        return facts(true, false, List.of(), List.of(), null, null);
    }

    private static RoleExpectation standaloneExpectation() {
        return new RoleExpectation(List.of(), null, "Target");
    }

    // ------------------------------------------------------------------
    // expectedTypeParameters
    // ------------------------------------------------------------------

    @Test
    public void expectedTypeParameters_onARoot_appendsTheTrailingPair() {
        assertEquals(List.of("V", "T", "B"), DeclaredBuilderShape.expectedTypeParameters(
            ChainRole.ABSTRACT_ROOT, List.of("V"), SELF_NAMES));
    }

    @Test
    public void expectedTypeParameters_onAChainedAbstract_appendsThePairToo() {
        assertEquals(List.of("T", "B"), DeclaredBuilderShape.expectedTypeParameters(
            ChainRole.CHAINED_ABSTRACT, List.of(), SELF_NAMES));
    }

    /** A link binds the pair rather than declaring it, so it re-declares only its own. */
    @Test
    public void expectedTypeParameters_onALink_isTheTargetsOwn() {
        assertEquals(List.of("V"), DeclaredBuilderShape.expectedTypeParameters(
            ChainRole.CONCRETE_LINK, List.of("V"), SELF_NAMES));
    }

    @Test
    public void expectedTypeParameters_onAStandalone_isTheTargetsOwn() {
        assertEquals(List.of(), DeclaredBuilderShape.expectedTypeParameters(
            ChainRole.STANDALONE, List.of(), SELF_NAMES));
    }

    // ------------------------------------------------------------------
    // expectedSuperType and expectedBuildReturnType
    // ------------------------------------------------------------------

    @Test
    public void expectedSuperType_isTheAncestorsBuilderOnlyWhereThereIsAnAncestor() {
        assertEquals("Base.Builder",
            DeclaredBuilderShape.expectedSuperType(ChainRole.CONCRETE_LINK, "Base.Builder"));
        assertEquals("Base.Builder",
            DeclaredBuilderShape.expectedSuperType(ChainRole.CHAINED_ABSTRACT, "Base.Builder"));
        assertNull(DeclaredBuilderShape.expectedSuperType(ChainRole.ABSTRACT_ROOT, "Base.Builder"));
        assertNull(DeclaredBuilderShape.expectedSuperType(ChainRole.STANDALONE, null));
    }

    /** A self-typed role builds its first trailing parameter, not the target. */
    @Test
    public void expectedBuildReturnType_onASelfTypedRole_isTheBoundName() {
        assertEquals("T", DeclaredBuilderShape.expectedBuildReturnType(
            ChainRole.ABSTRACT_ROOT, "Target", "T"));
        assertEquals("Target", DeclaredBuilderShape.expectedBuildReturnType(
            ChainRole.CONCRETE_LINK, "Target", "T"));
        assertEquals("Target", DeclaredBuilderShape.expectedBuildReturnType(
            ChainRole.STANDALONE, "Target", "T"));
    }

    // ------------------------------------------------------------------
    // check
    // ------------------------------------------------------------------

    @Test
    public void check_onAUsableStandaloneShape_isNull() {
        assertNull(DeclaredBuilderShape.check(ChainRole.STANDALONE, usableStandalone(),
            standaloneExpectation()));
    }

    @Test
    public void check_onANonStaticBuilder_isNotStatic() {
        assertEquals(DeclaredBuilderRejection.NOT_STATIC,
            DeclaredBuilderShape.check(ChainRole.STANDALONE,
                facts(false, false, List.of(), List.of(), null, null), standaloneExpectation()));
    }

    /** The modifiers are asked first: an unusable class makes every later answer a consequence. */
    @Test
    public void check_asksTheModifiersBeforeTheParameterList() {
        assertEquals(DeclaredBuilderRejection.NOT_STATIC,
            DeclaredBuilderShape.check(ChainRole.STANDALONE,
                facts(false, false, List.of("WRONG"), Arrays.asList((String) null), null, null),
                standaloneExpectation()));
    }

    @Test
    public void check_onASelfTypedRoleWithAConcreteBuilder_isNotAbstract() {
        assertEquals(DeclaredBuilderRejection.NOT_ABSTRACT,
            DeclaredBuilderShape.check(ChainRole.ABSTRACT_ROOT,
                facts(true, false, List.of("T", "B"), List.of("Target", "Builder<T, B>"), null, null),
                new RoleExpectation(List.of("T", "B"), null, "T")));
    }

    @Test
    public void check_onAConcreteRoleWithAnAbstractBuilder_isRejected() {
        assertEquals(DeclaredBuilderRejection.ABSTRACT_ON_CONCRETE_ROLE,
            DeclaredBuilderShape.check(ChainRole.STANDALONE,
                facts(true, true, List.of(), List.of(), null, null), standaloneExpectation()));
    }

    @Test
    public void check_onAMismatchedParameterList_isTypeParameters() {
        assertEquals(DeclaredBuilderRejection.TYPE_PARAMETERS,
            DeclaredBuilderShape.check(ChainRole.STANDALONE,
                facts(true, false, List.of("V"), Arrays.asList((String) null), null, null),
                standaloneExpectation()));
    }

    /** An unbounded trailing pair leaves a setter returning something with no members. */
    @Test
    public void check_onAnUnboundedTrailingPair_isSelfTypeBounds() {
        assertEquals(DeclaredBuilderRejection.SELF_TYPE_BOUNDS,
            DeclaredBuilderShape.check(ChainRole.ABSTRACT_ROOT,
                facts(true, true, List.of("T", "B"), Arrays.asList(null, null), null, null),
                new RoleExpectation(List.of("T", "B"), null, "T")));
    }

    @Test
    public void check_onALinkWithNoExtendsClause_isMissingSuperType() {
        assertEquals(DeclaredBuilderRejection.MISSING_SUPER_TYPE,
            DeclaredBuilderShape.check(ChainRole.CONCRETE_LINK,
                facts(true, false, List.of(), List.of(), null, null),
                new RoleExpectation(List.of(), "Base.Builder", "Target")));
    }

    @Test
    public void check_onALinkExtendingTheWrongBuilder_isWrongSuperType() {
        assertEquals(DeclaredBuilderRejection.WRONG_SUPER_TYPE,
            DeclaredBuilderShape.check(ChainRole.CONCRETE_LINK,
                facts(true, false, List.of(), List.of(), "Other.Builder", null),
                new RoleExpectation(List.of(), "Base.Builder", "Target")));
    }

    /**
     * On a chain the build method a link inherits has to be the one its role
     * declares, so a different return type cannot stand in for it.
     */
    @Test
    public void check_onAChainedMistypedBuildMethod_isBuildReturnType() {
        assertEquals(DeclaredBuilderRejection.BUILD_RETURN_TYPE,
            DeclaredBuilderShape.check(ChainRole.CONCRETE_LINK,
                facts(true, false, List.of(), List.of(), "Base.Builder",
                    new DeclaredBuildMethod("Object", false)),
                new RoleExpectation(List.of(), "Base.Builder", "Target")));
    }

    /**
     * Standing alone, nothing generated calls {@code build()} - the author's is
     * kept and reported as kept - so a different return type is theirs to write
     * and refusing it would reject source javac accepts.
     */
    @Test
    public void check_onAStandaloneMistypedBuildMethod_isAccepted() {
        assertNull(DeclaredBuilderShape.check(ChainRole.STANDALONE,
            facts(true, false, List.of(), List.of(), null,
                new DeclaredBuildMethod("Object", false)),
            standaloneExpectation()));
    }

    /** A build method returning what the role builds is the author's to keep. */
    @Test
    public void check_onACorrectlyTypedBuildMethod_isNull() {
        assertNull(DeclaredBuilderShape.check(ChainRole.STANDALONE,
            facts(true, false, List.of(), List.of(), null,
                new DeclaredBuildMethod("Target", false)),
            standaloneExpectation()));
    }

    // ------------------------------------------------------------------
    // The wording both halves render
    // ------------------------------------------------------------------

    /**
     * The two substrings the processor suite pins have to survive being rendered
     * through the shared template, or the apt negatives and the inspection cases
     * stop asserting the same sentence.
     */
    @Test
    public void message_keepsTheWordingBothSuitesAssertOn() {
        assertTrue(DeclaredBuilderRejection.NOT_STATIC.message("Builder", "builder")
            .contains("an inner class captures the enclosing instance"));
        assertTrue(DeclaredBuilderRejection.TYPE_PARAMETERS.message("Builder", "<V>", "none")
            .contains("re-declare the target's type parameters"));
    }

    @Test
    public void message_rendersTheOperandsInOrder() {
        assertEquals("@ClassBuilder cannot merge into 'Builder' - the builder of a chained target "
                + "has to extend Base.Builder, and this one extends Other.Builder",
            DeclaredBuilderRejection.WRONG_SUPER_TYPE.message("Builder", "Base.Builder",
                "Other.Builder"));
    }

}
