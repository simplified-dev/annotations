package dev.simplified.classbuilder.apt;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    /**
     * A root's build method returning the root is overridden by every link's,
     * each link being a subtype of the root, so it stands in for the generated
     * one as the self type would. It was refused for naming the root.
     */
    @Test
    public void check_onARootsBuildMethodReturningTheRoot_isNull() {
        DeclaredBuilderFacts facts = facts(true, true, List.of("T", "B"),
            List.of("Shape", "Builder<T, B>"), null, new DeclaredBuildMethod("Shape", true));
        assertNull(DeclaredBuilderShape.check(ChainRole.ABSTRACT_ROOT, facts,
            DeclaredBuilderShape.expectation(ChainRole.ABSTRACT_ROOT, "Shape", "Builder", List.of(),
                facts.typeParameterNames(), null, List.of())));
    }

    /**
     * A link's build method has to return the link: the root's it overrides
     * returns the link's self type, which the root is not.
     */
    @Test
    public void check_onALinksBuildMethodReturningItsRoot_isBuildReturnType() {
        DeclaredBuilderFacts facts = new DeclaredBuilderFacts(true, false, List.of(), List.of(),
            "Shape.Builder", List.of("Circle", "Builder"), new DeclaredBuildMethod("Shape", false));
        assertEquals(DeclaredBuilderRejection.BUILD_RETURN_TYPE,
            DeclaredBuilderShape.check(ChainRole.CONCRETE_LINK, facts,
                DeclaredBuilderShape.expectation(ChainRole.CONCRETE_LINK, "Circle", "Builder",
                    List.of(), List.of(), "Shape", List.of())));
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
        assertEquals("@ClassBuilder cannot merge into 'Builder' - a static nested builder for a "
                + "generic target has to re-declare the target's type parameters <V>, and this one "
                + "declares none",
            DeclaredBuilderShape.describe(DeclaredBuilderRejection.TYPE_PARAMETERS,
                ChainRole.STANDALONE, "Builder", "Target", "builder", usableStandalone(),
                new RoleExpectation(List.of("V"), null, "Target")));
    }

    /**
     * On a root that is not generic the missing parameters are the self-typed
     * pair, not the target's - the sentence used to call the root generic and the
     * pair its type parameters.
     */
    @Test
    public void describe_typeParametersOnANonGenericRoot_namesTheSelfTypedPair() {
        assertEquals("@ClassBuilder cannot merge into 'Builder' - a static nested builder for an "
                + "abstract target in a builder chain has to declare the self-typed pair <T, B>, and "
                + "this one declares none",
            DeclaredBuilderShape.describe(DeclaredBuilderRejection.TYPE_PARAMETERS,
                ChainRole.ABSTRACT_ROOT, "Builder", "Shape", "builder",
                facts(true, true, List.of(), List.of(), null, null),
                new RoleExpectation(List.of("T", "B"), null, "T")));
        assertEquals("@ClassBuilder cannot merge into 'Builder' - a static nested builder for an "
                + "abstract target in a builder chain has to re-declare the target's type parameters "
                + "<V> followed by the self-typed pair <T, B>, and this one declares <V>",
            DeclaredBuilderShape.describe(DeclaredBuilderRejection.TYPE_PARAMETERS,
                ChainRole.ABSTRACT_ROOT, "Builder", "Shape", "builder",
                facts(true, true, List.of("V"), Arrays.asList((String) null), null, null),
                new RoleExpectation(List.of("V", "T", "B"), null, "T")));
        assertEquals("@ClassBuilder cannot merge into 'Builder' - a static nested builder for a "
                + "target with no type parameters has to declare none, and this one declares <T>",
            DeclaredBuilderShape.describe(DeclaredBuilderRejection.TYPE_PARAMETERS,
                ChainRole.STANDALONE, "Builder", "Plain", "builder",
                facts(true, false, List.of("T"), Arrays.asList((String) null), null, null),
                standaloneExpectation()));
    }

    /**
     * The bounds rejection only fires once the names matched, so rendering the
     * two name lists printed one list twice and never a bound. The sentence shows
     * the bounds the pair needs beside the pair as written.
     */
    @Test
    public void describe_selfTypeBounds_showsTheBoundsBesideThePairAsWritten() {
        assertEquals("@ClassBuilder cannot merge into 'Builder' - its trailing pair has to be bounded "
                + "as <T extends Box<V>, B extends Builder<V, T, B>> for the generated setters to "
                + "return the caller's own builder type, and this one declares <T extends Box<V>, B>",
            DeclaredBuilderShape.describe(DeclaredBuilderRejection.SELF_TYPE_BOUNDS,
                ChainRole.ABSTRACT_ROOT, "Builder", "Box", "builder",
                facts(true, true, List.of("V", "T", "B"), Arrays.asList(null, "Box<V>", null), null, null),
                new RoleExpectation(List.of("V", "T", "B"), null, "T")));
    }

    // ------------------------------------------------------------------
    // The extends clause of a linked role
    // ------------------------------------------------------------------

    /**
     * The clause is compared with its qualifier, so a fully qualified spelling
     * of the ancestor's builder is the ancestor's builder. Compared by erased
     * simple name, the expectation was bare and this spelling passed only by
     * accident of the erasure.
     */
    @Test
    public void check_onALinkNamingTheAncestorsBuilderFullyQualified_isAccepted() {
        assertNull(DeclaredBuilderShape.check(ChainRole.CONCRETE_LINK,
            facts(true, false, List.of(), List.of(), "demo.Base.Builder", null),
            new RoleExpectation(List.of(), "Base.Builder", "Link")));
    }

    /** The link's pair written the wrong way round is named, not left to a generated line. */
    @Test
    public void check_onALinkPassingItsPairReversed_isSuperTypeArguments() {
        DeclaredBuilderFacts reversed = new DeclaredBuilderFacts(true, false, List.of(), List.of(),
            "Base.Builder", List.of("Builder", "Link"), null);
        RoleExpectation expectation = DeclaredBuilderShape.expectation(ChainRole.CONCRETE_LINK,
            "Link", "Builder", List.of(), List.of(), "Base", List.of());
        assertEquals(DeclaredBuilderRejection.SUPER_TYPE_ARGUMENTS,
            DeclaredBuilderShape.check(ChainRole.CONCRETE_LINK, reversed, expectation));
        assertEquals("@ClassBuilder cannot merge into 'Builder' - the builder of a chained target has "
                + "to pass Base.Builder the arguments <Link, Builder>, and this one passes <Builder, Link>",
            DeclaredBuilderShape.describe(DeclaredBuilderRejection.SUPER_TYPE_ARGUMENTS,
                ChainRole.CONCRETE_LINK, "Builder", "Link", "builder", reversed, expectation));
    }

    /**
     * Arguments are compared by erased simple name, as each model can read
     * them: the processor renders the ancestor's argument qualified, the author
     * writes it however they import it, and a generic link applies its own
     * parameters to both of its names.
     */
    @Test
    public void check_onAGenericLinkPassingItsArgumentsInAnySpelling_isAccepted() {
        DeclaredBuilderFacts written = new DeclaredBuilderFacts(true, false, List.of("V"),
            Arrays.asList((String) null), "Box.Builder", List.of("String", "Impl<V>", "Impl.Builder<V>"),
            null);
        RoleExpectation expectation = DeclaredBuilderShape.expectation(ChainRole.CONCRETE_LINK,
            "Impl", "Builder", List.of("V"), List.of("V"), "Box", List.of("java.lang.String"));
        assertNull(DeclaredBuilderShape.check(ChainRole.CONCRETE_LINK, written, expectation));
    }

    /** A chained abstract forwards its own trailing pair, in the author's names. */
    @Test
    public void expectation_onAChainedAbstract_forwardsTheDeclaredPair() {
        RoleExpectation expectation = DeclaredBuilderShape.expectation(ChainRole.CHAINED_ABSTRACT,
            "Mid", "Builder", List.of(), List.of("R", "S"), "Base", List.of());
        assertEquals(List.of("R", "S"), expectation.typeParameterNames());
        assertEquals("Base.Builder", expectation.superType());
        assertEquals(List.of("R", "S"), expectation.superTypeArguments());
        assertEquals("R", expectation.buildReturnType());
    }

    /**
     * A declaration too short to carry the pair is measured against the pair
     * the generator would have written, whose names dodge the target's own.
     */
    @Test
    public void selfNames_withNoDeclaredPair_areTheGeneratorsDodgingTheTargets() {
        assertEquals(List.of("T$", "B"), DeclaredBuilderShape.selfNames(ChainRole.ABSTRACT_ROOT,
            List.of("T"), List.of("T")));
        assertEquals(List.of("R", "S"), DeclaredBuilderShape.selfNames(ChainRole.ABSTRACT_ROOT,
            List.of("T"), List.of("T", "R", "S")));
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

    /** Simple names decide, so a qualified or differently-spaced spelling of the storage passes. */
    @Test
    public void mistypedSlot_acceptsTheStorageTypeInAnySpelling() {
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "note", "Supplier<String>",
            "java.util.function.Supplier<java.lang.String>", SlotHolding.LAZY));
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "size", "int", "int",
            SlotHolding.DECLARED));
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "index", "Map<String, List<Integer>>",
            "java.util.Map<java.lang.String,java.util.List<java.lang.Integer>>", SlotHolding.DECLARED));
    }

    /**
     * A method is keyed by its name and each parameter's erasure by simple
     * name, so a same-arity method taking another type is another key, and one
     * spelling the same signature in another form is the same key.
     */
    @Test
    public void methodKey_readsTheErasedParameterTypes() {
        assertEquals("port(int)", DeclaredBuilderShape.methodKey("port", List.of("int")));
        assertFalse("another type is another key", DeclaredBuilderShape.methodKey("port", List.of("String"))
            .equals(DeclaredBuilderShape.methodKey("port", List.of("int"))));
        assertEquals("qualifier and arguments drop out",
            DeclaredBuilderShape.methodKey("items", List.of("List<Integer>")),
            DeclaredBuilderShape.methodKey("items", List.of("java.util.List<java.lang.String>")));
        assertEquals("varargs is the array it is",
            DeclaredBuilderShape.methodKey("tags", List.of("String[]")),
            DeclaredBuilderShape.methodKey("tags", List.of("java.lang.String...")));
        assertEquals("build()", DeclaredBuilderShape.methodKey("build", List.of()));
    }

    /**
     * A record, an enum and an interface cannot be a builder, and the kind is
     * asked ahead of the modifier the javac tree does not record on them.
     */
    @Test
    public void check_onANonClassKind_isNotAClass() {
        DeclaredBuilderFacts record = new DeclaredBuilderFacts(false, false, List.of(), List.of(), null,
            List.of(), null, DeclaredBuilderFacts.RECORD);
        assertEquals(DeclaredBuilderRejection.NOT_A_CLASS,
            DeclaredBuilderShape.check(ChainRole.STANDALONE, record, standaloneExpectation()));
        assertEquals("@ClassBuilder cannot merge into 'Builder' - it is declared as an enum, and only a "
                + "class can hold the builder's fields and the constructor builder() calls",
            DeclaredBuilderShape.describe(DeclaredBuilderRejection.NOT_A_CLASS, ChainRole.STANDALONE,
                "Builder", "Note", "builder",
                new DeclaredBuilderFacts(true, false, List.of(), List.of(), null, List.of(), null,
                    DeclaredBuilderFacts.ENUM), standaloneExpectation()));
    }

    /**
     * The target's own bounds are compared by simple name, in any order, with
     * a written {@code Object} bound standing for none.
     */
    @Test
    public void check_onTheTargetsBounds_isTypeParameterBoundsOnlyWhereTheyDiffer() {
        RoleExpectation expectation = DeclaredBuilderShape.expectation(ChainRole.STANDALONE, "Box",
            "Builder", List.of("T"), Arrays.asList("java.lang.Number & java.lang.Comparable<T>"),
            List.of("T"), null, List.of());
        assertNull(DeclaredBuilderShape.check(ChainRole.STANDALONE,
            facts(true, false, List.of("T"), List.of("Comparable<T> & Number"), null, null), expectation));
        DeclaredBuilderFacts looser = facts(true, false, List.of("T"), Arrays.asList((String) null), null, null);
        assertEquals(DeclaredBuilderRejection.TYPE_PARAMETER_BOUNDS,
            DeclaredBuilderShape.check(ChainRole.STANDALONE, looser, expectation));
        assertEquals("@ClassBuilder cannot merge into 'Builder' - a static nested builder has to bound "
                + "the target's type parameters as <T extends Number & Comparable<T>>, and this one "
                + "declares <T>",
            DeclaredBuilderShape.describe(DeclaredBuilderRejection.TYPE_PARAMETER_BOUNDS,
                ChainRole.STANDALONE, "Builder", "Box", "builder", looser, expectation));

        RoleExpectation unbounded = DeclaredBuilderShape.expectation(ChainRole.STANDALONE, "Box", "Builder",
            List.of("T"), Arrays.asList((String) null), List.of("T"), null, List.of());
        assertNull("Object is no bound", DeclaredBuilderShape.check(ChainRole.STANDALONE,
            facts(true, false, List.of("T"), List.of("Object"), null, null), unbounded));
    }

    /** The final-field and throwing-constructor sentences both halves print. */
    @Test
    public void finalSlotAndThrowingConstructor_renderTheSharedSentences() {
        assertEquals("@ClassBuilder merged into 'Builder' finds 'items' declared final, and the generated "
            + "setter assigns it", DeclaredBuilderShape.finalSlot("Builder", "items"));
        List<List<String>> noArgument = List.of(List.of());
        assertFalse("a throwing constructor serves nothing",
            DeclaredBuilderShape.instantiable(noArgument, List.of(), List.of()));
        assertTrue("and says so", DeclaredBuilderShape.skippedForAThrowsClause(noArgument, List.of(), List.of()));
        assertFalse("not where no constructor takes what the entry points pass",
            DeclaredBuilderShape.skippedForAThrowsClause(List.of(List.of("String")), List.of(), List.of()));
        assertEquals("@ClassBuilder merged into 'Builder' but its constructor taking the seed 'builder' "
                + "passes declares a throws clause, so 'builder' was not added - declare one that throws "
                + "nothing or write it",
            DeclaredBuilderShape.throwingConstructor("Builder", List.of("builder"), List.of("origin")));
    }

    /**
     * A primitive and its box take each other's values under boxing and
     * unboxing, so either spelled over the other is a field the generated
     * members can assign and read back. Both were refused as mistyped. Only the
     * top level: a type argument is never primitive, and an array of one is no
     * array of the other.
     */
    @Test
    public void mistypedSlot_acceptsABoxedOrPrimitiveTwin() {
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "count", "Integer", "int",
            SlotHolding.DECLARED));
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "count", "int", "java.lang.Integer",
            SlotHolding.DECLARED));
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "on", "java.lang.Boolean", "boolean",
            SlotHolding.DECLARED));
        assertNotNull("another box", DeclaredBuilderShape.mistypedSlot("Builder", "count", "Long", "int",
            SlotHolding.DECLARED));
        assertNotNull("an array of the box", DeclaredBuilderShape.mistypedSlot("Builder", "counts",
            "Integer[]", "int[]", SlotHolding.DECLARED));
    }

    /**
     * The field has to hold the storage type exactly - the setter assigns into
     * it and {@code build()} reads it back out - so a type argument that differs
     * at any depth is reported. The erasure alone was compared, and passed both.
     */
    @Test
    public void mistypedSlot_readsTheTypeArguments() {
        assertEquals("@ClassBuilder merged into 'Builder' finds 'tags' declared as List<Integer>, and "
                + "the slot it stands for is java.util.List<java.lang.String> - the generated setter has "
                + "nothing to assign it to",
            DeclaredBuilderShape.mistypedSlot("Builder", "tags", "List<Integer>",
                "java.util.List<java.lang.String>", SlotHolding.DECLARED));
        assertNotNull("a nested argument", DeclaredBuilderShape.mistypedSlot("Builder", "index",
            "Map<String, List<String>>", "java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>",
            SlotHolding.DECLARED));
        assertNotNull("a wildcard", DeclaredBuilderShape.mistypedSlot("Builder", "tags",
            "List<? extends CharSequence>", "java.util.List<java.lang.String>", SlotHolding.DECLARED));
    }

    /**
     * A raw spelling on either side assigns and reads back with an unchecked
     * warning rather than an error, so it is not reported.
     */
    @Test
    public void mistypedSlot_acceptsARawSpellingOfEitherSide() {
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "tags", "List",
            "java.util.List<java.lang.String>", SlotHolding.DECLARED));
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "tags", "List<String>",
            "java.util.List", SlotHolding.DECLARED));
    }

    /**
     * The constructor that serves takes the seeds' types in seed order: nothing
     * on a type target, one parameter per seed on an executable one. A class
     * declaring nothing keeps the implicit default, which serves only an entry
     * point passing nothing.
     */
    @Test
    public void instantiable_comparesTheDeclaredConstructorsWithTheSeedTypes() {
        List<List<String>> none = List.of();
        assertTrue("the implicit default serves no seed", DeclaredBuilderShape.instantiable(none, none, List.of()));
        assertFalse("and nothing else", DeclaredBuilderShape.instantiable(none, none, List.of("String")));
        List<List<String>> seeded = List.of(List.of("String"));
        assertTrue("a constructor taking the seed serves a seeded entry point",
            DeclaredBuilderShape.instantiable(seeded, seeded, List.of("java.lang.String")));
        List<List<String>> noArgument = List.of(List.of());
        assertFalse("a no-argument one does not",
            DeclaredBuilderShape.instantiable(noArgument, noArgument, List.of("String")));
        assertFalse("nor does a seeded one serve no seed",
            DeclaredBuilderShape.instantiable(seeded, seeded, List.of()));
        List<List<String>> both = List.of(List.of("int", "String"), List.of());
        assertTrue("any one of them serving is enough", DeclaredBuilderShape.instantiable(both, both, List.of()));
    }

    /**
     * A constructor of the seed count that takes other types, or the seeds'
     * types in another order, is not one {@code builder(..)} can pass them to.
     * The count alone was compared, and javac then failed on the class line.
     * The erasure by simple name decides, as the method key does, so a
     * qualified name, a type argument or a varargs spelling is the same type.
     */
    @Test
    public void instantiable_readsTheSeedsTypesInOrder() {
        List<List<String>> otherType = List.of(List.of("int"));
        assertFalse("another type at the seed's position",
            DeclaredBuilderShape.instantiable(otherType, otherType, List.of("java.lang.String")));
        List<List<String>> swapped = List.of(List.of("int", "String"));
        assertFalse("the seeds' types in another order",
            DeclaredBuilderShape.instantiable(swapped, swapped, List.of("java.lang.String", "int")));
        List<List<String>> spelled = List.of(List.of("java.lang.String", "List<V>", "String..."));
        assertTrue("any spelling of the same types",
            DeclaredBuilderShape.instantiable(spelled, spelled,
                List.of("String", "java.util.List<V>", "java.lang.String[]")));
        assertTrue("a throws clause is read off the matching constructor",
            DeclaredBuilderShape.skippedForAThrowsClause(List.of(List.of("int")), List.of(), List.of("int")));
        assertFalse("and not off one taking another type",
            DeclaredBuilderShape.skippedForAThrowsClause(List.of(List.of("int")), List.of(), List.of("String")));
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

    /**
     * The skip note both halves render names the entry points the path emits and
     * no others - all three on a type target, {@code builder(..)} alone on a
     * constructor or factory target, and never one the author named {@code NONE}.
     */
    @Test
    public void entryPointsSkipped_namesOnlyTheEntryPointsThePathEmits() {
        BuilderScheme all = new BuilderScheme("Builder", "builder", "build", "from", "mutate");
        assertEquals("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder', 'from' and 'mutate' were not added - declare a no-argument "
                + "constructor or write them",
            DeclaredBuilderShape.entryPointsSkipped("Builder", all, false, List.of()));
        BuilderScheme noFrom = new BuilderScheme("Builder", "builder", "build", "", "mutate");
        assertEquals("@ClassBuilder merged into 'Builder' but every constructor it declares takes "
                + "parameters, so 'builder' and 'mutate' were not added - declare a no-argument "
                + "constructor or write them",
            DeclaredBuilderShape.entryPointsSkipped("Builder", noFrom, false, List.of()));
        assertEquals("@ClassBuilder merged into 'Builder' but none of its constructors takes the seed "
                + "'builder' passes, so 'builder' was not added - declare a constructor taking "
                + "(origin) or write it",
            DeclaredBuilderShape.entryPointsSkipped("Builder", all, true, List.of("origin")));
    }

    /**
     * A path whose every entry point is named {@code NONE} skips nothing, so
     * there is no note to render - rather than one reporting an empty list as
     * not added.
     */
    @Test
    public void entryPointsSkipped_isNullWhereThePathEmitsNone() {
        assertNull("a type target with all three named NONE",
            DeclaredBuilderShape.entryPointsSkipped("Builder",
                new BuilderScheme("Builder", "", "build", "", ""), false, List.of()));
        assertNull("an executable target emits builder(..) alone",
            DeclaredBuilderShape.entryPointsSkipped("Builder",
                new BuilderScheme("Builder", "", "build", "from", "mutate"), true, List.of("origin")));
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
