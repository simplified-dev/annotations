package dev.simplified.classbuilder.apt;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /**
     * javac joins type arguments with a bare comma and PSI with a comma and a
     * space, so the same slot type printed by each half differed in the
     * mistyped-slot sentence until both rendered it through one spelling.
     */
    @Test
    public void typeText_rendersBothModelsSpellingsAlike() {
        String javac = DeclaredBuilderShape.typeText("java.util.Map<java.lang.String,java.lang.Integer>");
        String psi = DeclaredBuilderShape.typeText("java.util.Map<java.lang.String, java.lang.Integer>");
        assertEquals("java.util.Map<java.lang.String, java.lang.Integer>", javac);
        assertEquals(javac, psi);
        assertEquals("the author's spacing is not the author's type",
            "Map<String, List<Integer>>", DeclaredBuilderShape.typeText("Map< String ,List<Integer> >"));
        assertEquals("a type annotation is not part of the type either",
            "java.lang.String", DeclaredBuilderShape.typeText("@org.jetbrains.annotations.NotNull java.lang.String"));
    }

    /** {@code Supplier<int>} names no type; the generated storage boxes it. */
    @Test
    public void supplierOf_boxesAPrimitive() {
        assertEquals("java.util.function.Supplier<java.lang.Integer>", DeclaredBuilderShape.supplierOf("int"));
        assertEquals("java.util.function.Supplier<java.lang.String>",
            DeclaredBuilderShape.supplierOf("java.lang.String"));
    }

    /** The shared sentence, with the reason a lazy slot is not its declared type. */
    @Test
    public void mistypedSlot_rendersTheSharedSentence() {
        assertEquals("@ClassBuilder merged into 'Builder' finds 'note' declared as String, and the slot "
                + "it stands for is java.util.function.Supplier<java.lang.String> - the generated setter "
                + "has nothing to assign it to. A @Lazy field is held in the builder as a supplier of its "
                + "declared type",
            DeclaredBuilderShape.mistypedSlot("Builder", "note", "String",
                DeclaredBuilderShape.supplierOf("java.lang.String"), SlotHolding.LAZY));
    }

    /** Erasure decides, so a qualified or differently-spaced spelling of the storage passes. */
    @Test
    public void mistypedSlot_acceptsTheStorageTypeInAnySpelling() {
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "note", "Supplier<String>",
            "java.util.function.Supplier<java.lang.String>", SlotHolding.LAZY));
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "size", "int", "int",
            SlotHolding.DECLARED));
    }

    /**
     * The arity that serves is the seed count: zero on a type target, one per
     * seed on an executable one. A class declaring nothing keeps the implicit
     * default, which serves only an entry point passing nothing.
     */
    @Test
    public void instantiable_comparesTheDeclaredAritiesWithTheSeedCount() {
        assertTrue("the implicit default serves no seed", DeclaredBuilderShape.instantiable(List.of(), 0));
        assertFalse("and nothing else", DeclaredBuilderShape.instantiable(List.of(), 1));
        assertTrue("a seed-arity constructor serves a seeded entry point",
            DeclaredBuilderShape.instantiable(List.of(1), 1));
        assertFalse("a no-argument one does not", DeclaredBuilderShape.instantiable(List.of(0), 1));
        assertFalse("nor does a seeded one serve no seed", DeclaredBuilderShape.instantiable(List.of(1), 0));
        assertTrue("any one of them serving is enough", DeclaredBuilderShape.instantiable(List.of(2, 0), 0));
    }

    /** The skip note names only the entry points skipped, and the arity they needed. */
    @Test
    public void uninstantiable_wordsTheNoteByWhatTheEntryPointsPass() {
        assertEquals("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder', 'from' and 'mutate' were not added - declare a no-argument "
                + "constructor or write them",
            DeclaredBuilderShape.uninstantiable("Builder", List.of("builder", "from", "mutate"), List.of()));
        assertEquals("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder' was not added - declare a no-argument constructor or write it",
            DeclaredBuilderShape.uninstantiable("Builder", List.of("builder"), List.of()));
        assertEquals("@ClassBuilder merged into 'Builder' but none of its constructors takes the 2 seeds "
                + "'builder' passes, so 'builder' was not added - declare a constructor taking "
                + "(origin, kind) or write it",
            DeclaredBuilderShape.uninstantiable("Builder", List.of("builder"), List.of("origin", "kind")));
    }

    /** The unassigned-seed sentence names the constructor, or the absence of one. */
    @Test
    public void unassignedSeed_rendersTheSharedSentence() {
        assertEquals("@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, "
                + "and this constructor leaves it unassigned",
            DeclaredBuilderShape.unassignedSeed("Builder", "origin", true));
        assertEquals("@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, "
                + "and 'Builder' declares no constructor to assign it",
            DeclaredBuilderShape.unassignedSeed("Builder", "origin", false));
    }

}
