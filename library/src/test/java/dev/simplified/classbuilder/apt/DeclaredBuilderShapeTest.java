package dev.simplified.classbuilder.apt;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
     * A parameter typed by one of the declared builder's own type variables is
     * keyed by that variable's erasure - its first bound's, or {@code Object}
     * unbounded - which is the signature javac compares, so an author method
     * taking the erasure keys as the generated one does. The variable was keyed
     * by its name, and the two were kept beside each other as a name clash.
     */
    @Test
    public void methodKey_keysABuildersTypeVariableByItsErasure() {
        Map<String, String> erasures = DeclaredBuilderShape.typeVariableErasures(List.of("T", "N", "B", "U"),
            Arrays.asList(null, "java.lang.Number & Comparable<N>", "Builder<T, N, B>", "N"));
        assertEquals("value(Object)", DeclaredBuilderShape.methodKey("value", List.of("T"), erasures));
        assertEquals("the first bound, unqualified", "amount(Number)",
            DeclaredBuilderShape.methodKey("amount", List.of("N"), erasures));
        assertEquals("a bound applied to arguments", "self(Builder)",
            DeclaredBuilderShape.methodKey("self", List.of("B"), erasures));
        assertEquals("a bound naming another variable erases as that one does", "count(Number)",
            DeclaredBuilderShape.methodKey("count", List.of("U"), erasures));
        assertEquals("an array of a variable", "items(Object[])",
            DeclaredBuilderShape.methodKey("items", List.of("T..."), erasures));
        assertEquals("a variable as an argument erases with its type", "list(List)",
            DeclaredBuilderShape.methodKey("list", List.of("java.util.List<T>"), erasures));
        assertEquals("a name the builder does not declare stays as written", "value(V)",
            DeclaredBuilderShape.methodKey("value", List.of("V"), erasures));
        assertEquals("with no variables the key is the plain one", DeclaredBuilderShape.methodKey("value", List.of("T")),
            DeclaredBuilderShape.methodKey("value", List.of("T"), Map.of()));
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

    /**
     * The final-field and throwing-constructor sentences both halves print. The
     * throwing note said only that the constructor declares a throws clause, and
     * the final-field sentence was rendered whatever the author spelled.
     */
    @Test
    public void finalSlotAndThrowingConstructor_renderTheSharedSentences() {
        assertEquals("@ClassBuilder merged into 'Builder' finds 'items' declared final, and the generated "
                + "setter assigns it",
            DeclaredBuilderShape.finalSlot("Builder", "items", Map.of("items(List)", SetterShape.PLAIN), false,
                List.of()));
        List<List<String>> noArgument = List.of(List.of());
        assertFalse("a throwing constructor serves nothing",
            DeclaredBuilderShape.instantiable(noArgument, List.of(), List.of()));
        assertTrue("and says so", DeclaredBuilderShape.skippedForAThrowsClause(noArgument, List.of(), List.of()));
        assertFalse("not where no constructor takes what the entry points pass",
            DeclaredBuilderShape.skippedForAThrowsClause(List.of(List.of("String")), List.of(), List.of()));
        assertEquals("@ClassBuilder merged into 'Builder' but its constructor taking the seed 'builder' "
                + "passes declares a throws clause naming an exception not known to be unchecked, so "
                + "'builder' was not added - declare one throwing only unchecked exceptions or write it",
            DeclaredBuilderShape.throwingConstructor("Builder", List.of("builder"), List.of("origin")));
    }

    /**
     * A final field is refused only where a member the merge appends assigns
     * it - some generated setter of its slot the author does not spell under the
     * method key. A slot whose every setter the author writes has nothing
     * generated left to assign it, and a seed has no setter at all. The field
     * alone was asked, and a builder whose own setters never assign it was
     * refused though it compiles.
     */
    @Test
    public void finalSlot_isReportedOnlyWhereAGeneratedSetterIsLeftToAssignIt() {
        assertNull("every setter spelled", DeclaredBuilderShape.finalSlot("Builder", "port",
            Map.of("port(int)", SetterShape.PLAIN), false, List.of("port(int)", "build()")));
        assertNull("a seed has no setter", DeclaredBuilderShape.finalSlot("Builder", "origin",
            Map.of(), false, List.of()));
        assertNotNull("one of two left generated", DeclaredBuilderShape.finalSlot("Builder", "on",
            Map.of("on()", SetterShape.FLAG, "on(boolean)", SetterShape.BOOLEAN), false, List.of("on(boolean)")));
        assertNotNull("an overload beside it is no cover", DeclaredBuilderShape.finalSlot("Builder", "port",
            Map.of("port(int)", SetterShape.PLAIN), false, List.of("port(String)")));
    }

    /**
     * Only a generated setter that assigns the field makes a {@code final} one
     * unusable: a singular add, put, put-if-absent, remove or clear, and a bulk
     * setter of an appending slot, mutate the container the field holds, and an
     * {@code Optional} slot's inner-type setter hands the value on. Every
     * generated setter counted as assigning it, so a builder whose field only
     * those reach was refused though it compiles.
     */
    @Test
    public void finalSlot_countsOnlyTheGeneratedSettersThatAssignTheField() {
        assertNull("the container-mutating shapes left generated", DeclaredBuilderShape.finalSlot("Builder",
            "tags", Map.of("tags(String[])", SetterShape.BULK_VARARGS, "tags(Iterable)", SetterShape.BULK_ITERABLE,
                "addTag(String)", SetterShape.ADD, "clearTags()", SetterShape.CLEAR,
                "removeTag(String)", SetterShape.REMOVE), false,
            List.of("tags(String[])", "tags(Iterable)")));
        assertNull("a map's put and put-if-absent", DeclaredBuilderShape.finalSlot("Builder", "counts",
            Map.of("counts(Map)", SetterShape.BULK_MAP, "putCount(String,Integer)", SetterShape.PUT,
                "putCountIfAbsent(String,Supplier)", SetterShape.PUT_IF_ABSENT), false,
            List.of("counts(Map)")));
        assertNull("every bulk setter of an appending slot", DeclaredBuilderShape.finalSlot("Builder", "tags",
            Map.of("tags(String[])", SetterShape.BULK_VARARGS, "tags(Iterable)", SetterShape.BULK_ITERABLE),
            true, List.of()));
        assertNotNull("but a replacing one", DeclaredBuilderShape.finalSlot("Builder", "tags",
            Map.of("tags(String[])", SetterShape.BULK_VARARGS, "tags(Iterable)", SetterShape.BULK_ITERABLE),
            false, List.of("tags(Iterable)")));
        assertNull("an Optional slot's inner-type setter", DeclaredBuilderShape.finalSlot("Builder", "note",
            Map.of("note(String)", SetterShape.OPTIONAL_VALUE, "note(Optional)", SetterShape.OPTIONAL), false,
            List.of("note(Optional)")));
    }

    /**
     * A throws clause naming only the known unchecked types, by simple name or
     * by their {@code java.lang} or {@code java.util} qualified name, is one the
     * entry points can call through, and those names never reach the caller's
     * verdict. Any other name is answered by the verdict alone.
     */
    @Test
    public void throwsNothingChecked_readsTheKnownUncheckedNamesAndAsksTheVerdictOfTheRest() {
        Predicate<String> neverAsked = thrown -> {
            throw new AssertionError("a listed name was handed to the verdict: " + thrown);
        };
        assertTrue("no clause", DeclaredBuilderShape.throwsNothingChecked(List.of(), neverAsked));
        assertTrue("simple names", DeclaredBuilderShape.throwsNothingChecked(
            List.of("IllegalStateException", "Error", "NoSuchElementException"), neverAsked));
        assertTrue("qualified names", DeclaredBuilderShape.throwsNothingChecked(
            List.of("java.lang.RuntimeException", "java.util.ConcurrentModificationException",
                "java.lang . AssertionError"), neverAsked));

        Predicate<String> failureIsUnchecked = "Failure"::equals;
        assertTrue("an unlisted name the verdict reads as unchecked", DeclaredBuilderShape.throwsNothingChecked(
            List.of("IllegalStateException", "Failure"), failureIsUnchecked));
        assertFalse("an unlisted name the verdict reads as checked", DeclaredBuilderShape.throwsNothingChecked(
            List.of("IllegalStateException", "MyFailure"), failureIsUnchecked));
        assertFalse("a checked type", DeclaredBuilderShape.throwsNothingChecked(
            List.of("java.io.IOException"), failureIsUnchecked));
        assertFalse("a known name in the wrong package", DeclaredBuilderShape.throwsNothingChecked(
            List.of("java.util.IllegalStateException"), failureIsUnchecked));
        assertFalse("nor a checked exception in java.lang", DeclaredBuilderShape.throwsNothingChecked(
            List.of("Exception"), failureIsUnchecked));
        List<List<String>> noArgument = List.of(List.of());
        assertTrue("so the constructor serves the entry points",
            DeclaredBuilderShape.instantiable(noArgument, noArgument, List.of()));
    }

    /**
     * An author method covering a generated setter under the method key but
     * taking another parameterisation of the same generic type cannot take the
     * slot's own type, which {@code from(T)} and {@code mutate()} pass it. Only
     * where both carry type arguments, neither a wildcard nor one of the
     * builder's own type parameters, and a copy entry point is emitted - every
     * other shape compiles or is javac's to judge. It covered the setter with
     * nothing said, and javac failed on a generated line.
     */
    @Test
    public void setterWithOtherTypeArguments_isReportedOnlyWhereJavacRejectsTheCopy() {
        List<String> copies = List.of("from", "mutate");
        SetterShape plain = SetterShape.PLAIN;
        assertEquals("@ClassBuilder merged into 'Builder' finds items(List<Integer>) standing in for the "
                + "generated items(List<String>), and 'from' and 'mutate' pass it the slot's List<String>, "
                + "which its List<Integer> parameter cannot take",
            DeclaredBuilderShape.setterWithOtherTypeArguments("Builder", "items", plain, List.of("List<Integer>"),
                List.of("java.util.List<java.lang.String>"), List.of(), copies));
        assertEquals("one entry point named alone",
            "@ClassBuilder merged into 'Builder' finds items(List<Integer>) standing in for the generated "
                + "items(List<String>), and 'mutate' passes it the slot's List<String>, which its "
                + "List<Integer> parameter cannot take",
            DeclaredBuilderShape.setterWithOtherTypeArguments("Builder", "items", plain, List.of("List<Integer>"),
                List.of("List<String>"), List.of(), List.of("mutate")));
        assertNull("the same arguments in another spelling", DeclaredBuilderShape.setterWithOtherTypeArguments(
            "Builder", "items", plain, List.of("List<String>"), List.of("java.util.List<java.lang.String>"),
            List.of(), copies));
        assertNull("no copy entry point emitted", DeclaredBuilderShape.setterWithOtherTypeArguments(
            "Builder", "items", plain, List.of("List<Integer>"), List.of("List<String>"), List.of(), List.of()));
        assertNull("a raw side", DeclaredBuilderShape.setterWithOtherTypeArguments("Builder", "items", plain,
            List.of("List"), List.of("List<String>"), List.of(), copies));
        assertNull("a wildcard", DeclaredBuilderShape.setterWithOtherTypeArguments("Builder", "items", plain,
            List.of("List<? extends CharSequence>"), List.of("List<String>"), List.of(), copies));
        assertNull("a builder type parameter", DeclaredBuilderShape.setterWithOtherTypeArguments("Builder",
            "items", plain, List.of("List<T>"), List.of("List<String>"), List.of("T"), copies));
        assertNull("another erasure is no cover", DeclaredBuilderShape.setterWithOtherTypeArguments("Builder",
            "items", plain, List.of("Set<Integer>"), List.of("List<String>"), List.of(), copies));
        assertNotNull("a nested argument", DeclaredBuilderShape.setterWithOtherTypeArguments("Builder", "index",
            plain, List.of("Map<String, List<Integer>>"), List.of("Map<String, List<String>>"), List.of(), copies));
    }

    /**
     * {@code from(T)} and {@code mutate()} pass each slot to one setter - the
     * one taking the slot's own type - so an author method covering any other
     * shape is never handed the slot and compiles. Every covered setter was
     * judged, and a singular add over a slot of lists was refused.
     */
    @Test
    public void setterWithOtherTypeArguments_judgesOnlyTheSetterTheCopyCalls() {
        List<String> copies = List.of("from", "mutate");
        assertNull("a singular add", DeclaredBuilderShape.setterWithOtherTypeArguments("Builder", "addItem",
            SetterShape.ADD, List.of("List<Integer>"), List.of("List<String>"), List.of(), copies));
        assertNull("a varargs bulk form", DeclaredBuilderShape.setterWithOtherTypeArguments("Builder", "items",
            SetterShape.BULK_VARARGS, List.of("List<Integer>..."), List.of("List<String>..."), List.of(), copies));
        assertNull("an Optional slot's inner-type setter", DeclaredBuilderShape.setterWithOtherTypeArguments(
            "Builder", "note", SetterShape.OPTIONAL_VALUE, List.of("List<Integer>"), List.of("List<String>"),
            List.of(), copies));
        assertNotNull("the Iterable bulk form the copy calls", DeclaredBuilderShape.setterWithOtherTypeArguments(
            "Builder", "items", SetterShape.BULK_ITERABLE, List.of("Iterable<List<Integer>>"),
            List.of("Iterable<List<String>>"), List.of(), copies));
    }

    /**
     * A primitive field over a boxed slot takes every value the generated
     * members assign and reads back, so it is accepted; a boxed field over a
     * primitive slot is refused, because an unset one reaches the primitive
     * constructor parameter as {@code null}. Both directions were accepted.
     * Only the top level: a type argument is never primitive, and an array of
     * one is no array of the other.
     */
    @Test
    public void mistypedSlot_acceptsAPrimitiveOverItsBoxAndRefusesTheReverse() {
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "count", "int", "java.lang.Integer",
            SlotHolding.DECLARED));
        assertNull(DeclaredBuilderShape.mistypedSlot("Builder", "on", "boolean", "Boolean",
            SlotHolding.DECLARED));
        assertEquals("@ClassBuilder merged into 'Builder' finds 'count' declared as Integer, and the slot "
                + "it stands for is int - an unset Integer field reaches the primitive constructor parameter "
                + "as null",
            DeclaredBuilderShape.mistypedSlot("Builder", "count", "Integer", "int", SlotHolding.DECLARED));
        assertNotNull("qualified too", DeclaredBuilderShape.mistypedSlot("Builder", "on", "java.lang.Boolean",
            "boolean", SlotHolding.DECLARED));
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

    /**
     * javac reaches a constructor through more than its own parameter types:
     * a primitive seed boxed or widened, a boxed one unboxed, and any reference
     * seed passed as {@code Object}. Only equal types were counted, and every
     * one of these was skipped with the note though the call compiles.
     */
    @Test
    public void instantiable_takesTheSeedsThroughBoxingWideningOrObject() {
        assertTrue("a primitive into its box", instantiableOver(List.of("Integer"), "int"));
        assertTrue("a qualified box", instantiableOver(List.of("java.lang.Integer"), "int"));
        assertTrue("a box into its primitive", instantiableOver(List.of("int"), "java.lang.Integer"));
        assertTrue("a primitive widened", instantiableOver(List.of("long"), "int"));
        assertTrue("a narrow primitive widened to the widest", instantiableOver(List.of("double"), "byte"));
        assertTrue("char widened to int", instantiableOver(List.of("int"), "char"));
        assertTrue("a reference seed as Object", instantiableOver(List.of("Object"), "java.lang.String"));
        assertTrue("as java.lang.Object", instantiableOver(List.of("java.lang.Object"), "List<String>"));
        assertTrue("an array seed as Object", instantiableOver(List.of("Object"), "String[]"));
        assertTrue("every seed at once",
            instantiableOver(List.of("Integer", "long", "Object"), "int", "int", "String"));
    }

    /**
     * Any other conversion is one names cannot vouch for, so the constructor is
     * not counted and the entry point is skipped with the note: another
     * supertype, a primitive into {@code Object} or a wider box, a narrowing,
     * and an unboxing followed by a widening.
     */
    @Test
    public void instantiable_countsNoOtherConversion() {
        assertFalse("an unlisted supertype", instantiableOver(List.of("java.io.Serializable"), "String"));
        assertFalse("a primitive into Object", instantiableOver(List.of("Object"), "int"));
        assertFalse("into another box", instantiableOver(List.of("Long"), "int"));
        assertFalse("a narrowing", instantiableOver(List.of("int"), "long"));
        assertFalse("boolean is no number", instantiableOver(List.of("int"), "boolean"));
        assertFalse("an unboxing then a widening", instantiableOver(List.of("long"), "Integer"));
        assertFalse("an Object of another package", instantiableOver(List.of("org.acme.Object"), "String"));
        assertFalse("a primitive array widened", instantiableOver(List.of("long[]"), "int[]"));
    }

    /**
     * Where more than one constructor takes the seeds, the one counted is the
     * one javac selects: the earliest phase - no boxing before boxing - and in
     * it the most specific. Two that neither is more specific than, or a
     * constructor names cannot place that might be chosen first, leave no
     * single constructor, and a throws clause is read off the selected one.
     */
    @Test
    public void instantiable_readsTheConstructorJavacSelects() {
        List<List<String>> crossed = List.of(List.of("long", "int"), List.of("int", "long"));
        assertFalse("an ambiguous pair", DeclaredBuilderShape.instantiable(crossed, crossed, List.of("int", "int")));
        List<List<String>> nested = List.of(List.of("long", "int"), List.of("long", "long"));
        assertTrue("one more specific than the other",
            DeclaredBuilderShape.instantiable(nested, nested, List.of("int", "int")));
        List<List<String>> unplaced = List.of(List.of("Object"), List.of("java.io.Serializable"));
        assertFalse("a supertype javac may prefer",
            DeclaredBuilderShape.instantiable(unplaced, unplaced, List.of("String")));
        List<List<String>> exact = List.of(List.of("String"), List.of("CharSequence"));
        assertTrue("its own type beats any other",
            DeclaredBuilderShape.instantiable(exact, exact, List.of("String")));
        List<List<String>> laterPhase = List.of(List.of("Integer"), List.of("Number"));
        assertTrue("a supertype reached only by boxing competes with no widening",
            DeclaredBuilderShape.instantiable(List.of(List.of("long"), List.of("Number")),
                List.of(List.of("long"), List.of("Number")), List.of("int")));
        assertFalse("but with a boxing it may",
            DeclaredBuilderShape.instantiable(laterPhase, laterPhase, List.of("int")));
        List<List<String>> widenedOrBoxed = List.of(List.of("long"), List.of("Integer"));
        List<List<String>> boxedOnly = List.of(List.of("Integer"));
        assertFalse("the widening is selected, and it throws",
            DeclaredBuilderShape.instantiable(widenedOrBoxed, boxedOnly, List.of("int")));
        assertTrue("which the note is worded by",
            DeclaredBuilderShape.skippedForAThrowsClause(widenedOrBoxed, boxedOnly, List.of("int")));
        assertFalse("and not where nothing is selected",
            DeclaredBuilderShape.skippedForAThrowsClause(crossed, List.of(), List.of("int", "int")));
    }

    /**
     * Two distinct concrete parameterisations of one generic type are never
     * assignable, so a constructor taking one is never selected for a seed of
     * the other, and a constructor beside it that javac does call is. Every
     * other shape keeps the erasure's answer: the same arguments in another
     * spelling, a raw side, a wildcard and a type parameter of the builder's
     * own. The erasures were compared alone, and {@code Builder(List<Integer>)}
     * was taken for a {@code List<String>} seed's own type.
     */
    @Test
    public void instantiable_neverTakesAnotherParameterisationOfTheSeedsType() {
        assertFalse("another parameterisation", instantiableOver(List.of("List<Integer>"), "List<String>"));
        assertFalse("at depth", instantiableOver(List.of("java.util.Map<String, List<Integer>>"),
            "Map<String,List<String>>"));
        List<List<String>> beside = List.of(List.of("List<Integer>"), List.of("Object"));
        assertTrue("Object is what javac calls then",
            DeclaredBuilderShape.instantiable(beside, beside, List.of("List<String>")));
        assertTrue("the same arguments in another spelling",
            instantiableOver(List.of("java.util.List<java.lang.String>"), "List<String>"));
        assertTrue("a raw side", instantiableOver(List.of("List"), "List<String>"));
        assertTrue("a wildcard", instantiableOver(List.of("List<? extends CharSequence>"), "List<String>"));
        List<List<String>> generic = List.of(List.of("List<T>"));
        assertTrue("a type parameter of the builder's own",
            DeclaredBuilderShape.instantiable(generic, generic, List.of("List<String>"), List.of("T")));
        assertFalse("and nothing is selected to word a throws note by",
            DeclaredBuilderShape.skippedForAThrowsClause(List.of(List.of("List<Integer>")), List.of(),
                List.of("List<String>"), List.of()));
    }

    /**
     * A reference seed reaches a constructor taking one of the supertypes the
     * table lists for its type, spelled simply or in its own package, with the
     * seed's type arguments carried across; a concrete argument that differs
     * is refused as it is for one generic type. Only {@code Object} was
     * counted, and each of these was skipped with the note though javac calls
     * it.
     */
    @Test
    public void instantiable_takesTheSeedThroughAListedSupertype() {
        assertTrue("CharSequence over String", instantiableOver(List.of("CharSequence"), "String"));
        assertTrue("qualified", instantiableOver(List.of("java.lang.CharSequence"), "java.lang.StringBuilder"));
        assertTrue("Number over a box", instantiableOver(List.of("Number"), "Integer"));
        assertTrue("Number over BigDecimal", instantiableOver(List.of("java.lang.Number"), "java.math.BigDecimal"));
        assertTrue("Number over an atomic", instantiableOver(List.of("Number"),
            "java.util.concurrent.atomic.AtomicLong"));
        assertTrue("Comparable raw", instantiableOver(List.of("Comparable"), "String"));
        assertTrue("Comparable of the seed", instantiableOver(List.of("Comparable<Integer>"), "Integer"));
        assertTrue("Comparable of what a date compares to",
            instantiableOver(List.of("Comparable<java.time.chrono.ChronoLocalDate>"), "java.time.LocalDate"));
        assertTrue("List over ArrayList", instantiableOver(List.of("List<String>"), "ArrayList<String>"));
        assertTrue("Collection over List", instantiableOver(List.of("java.util.Collection<String>"),
            "List<String>"));
        assertTrue("Iterable over a set", instantiableOver(List.of("Iterable<String>"), "TreeSet<String>"));
        assertTrue("NavigableMap over TreeMap", instantiableOver(List.of("NavigableMap<String, Integer>"),
            "TreeMap<String,Integer>"));
        assertTrue("Map over ConcurrentHashMap", instantiableOver(List.of("Map<String, Integer>"),
            "java.util.concurrent.ConcurrentHashMap<String, Integer>"));
        assertTrue("Deque over ArrayDeque", instantiableOver(List.of("Deque<String>"), "ArrayDeque<String>"));
        assertTrue("a raw seed", instantiableOver(List.of("Collection<String>"), "ArrayList"));
        assertTrue("a raw parameter", instantiableOver(List.of("Collection"), "List<String>"));
        assertTrue("a wildcard", instantiableOver(List.of("Collection<? extends CharSequence>"), "List<String>"));

        assertFalse("another argument", instantiableOver(List.of("Collection<Integer>"), "List<String>"));
        assertFalse("another argument to Comparable", instantiableOver(List.of("Comparable<Long>"), "Integer"));
        assertFalse("a date is no Comparable of itself",
            instantiableOver(List.of("Comparable<LocalDate>"), "LocalDate"));
        assertFalse("another package", instantiableOver(List.of("org.acme.CharSequence"), "String"));
        assertFalse("the seed in another package", instantiableOver(List.of("CharSequence"), "org.acme.String"));
        assertFalse("a supertype not listed for it", instantiableOver(List.of("Number"), "String"));
        assertFalse("a list is no set", instantiableOver(List.of("Set<String>"), "ArrayList<String>"));
        assertFalse("a map is no collection", instantiableOver(List.of("Collection<String>"),
            "HashMap<String, String>"));
    }

    /**
     * A listed supertype is more specific than {@code Object} and than a
     * listed supertype of its own, as javac finds, and two listed supertypes
     * neither of which is the other's leave no single constructor.
     */
    @Test
    public void instantiable_readsTheMostSpecificListedSupertype() {
        List<List<String>> beside = List.of(List.of("Object"), List.of("CharSequence"));
        assertTrue("CharSequence over Object", DeclaredBuilderShape.instantiable(beside, beside, List.of("String")));
        List<List<String>> nested = List.of(List.of("Collection<String>"), List.of("List<String>"));
        assertTrue("List over Collection",
            DeclaredBuilderShape.instantiable(nested, nested, List.of("ArrayList<String>")));
        List<List<String>> crossed = List.of(List.of("CharSequence"), List.of("Comparable<String>"));
        assertFalse("neither is the other's", DeclaredBuilderShape.instantiable(crossed, crossed, List.of("String")));
    }

    /** Whether a builder whose one throw-free constructor takes {@code parameters} serves the seeds. */
    private static boolean instantiableOver(List<String> parameters, String... seedTypes) {
        List<List<String>> constructors = List.of(parameters);
        return DeclaredBuilderShape.instantiable(constructors, constructors, Arrays.asList(seedTypes));
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
        assertEquals("@ClassBuilder merged into 'Builder' but no single constructor it declares takes the 2 "
                + "seeds 'builder' passes as their own types, their boxes or primitives, wider primitives, "
                + "Object or listed JDK supertypes such as CharSequence, Number, Comparable or a java.util "
                + "collection interface, so 'builder' was not added - declare a constructor taking "
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
        assertEquals("@ClassBuilder merged into 'Builder' but no single constructor it declares takes the "
                + "seed 'builder' passes as its own type, its box or primitive, a wider primitive, Object or a "
                + "listed JDK supertype such as CharSequence, Number, Comparable or a java.util collection "
                + "interface, so 'builder' was not added - declare a constructor taking (origin) or write it",
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

    /**
     * The copy-constructor rule reads the builder through its target at any
     * depth of qualification and in either model's spelling, and refuses an
     * ancestor's builder of the same simple name.
     */
    @Test
    public void namesOwnBuilder_readsTheQualifierThroughTheTarget() {
        assertTrue(DeclaredBuilderShape.namesOwnBuilder("Builder", "Link", "Builder"));
        assertTrue(DeclaredBuilderShape.namesOwnBuilder("Builder<?, ?>", "Shape", "Builder"));
        assertTrue(DeclaredBuilderShape.namesOwnBuilder("Link.Builder", "Link", "Builder"));
        assertTrue(DeclaredBuilderShape.namesOwnBuilder("Shape.Builder<?,?>", "Shape", "Builder"));
        assertTrue(DeclaredBuilderShape.namesOwnBuilder("demo.Outer . Link.Builder", "Link", "Builder"));
        assertTrue(DeclaredBuilderShape.namesOwnBuilder("Link.Maker", "Link", "Maker"));
        assertFalse("an ancestor's builder",
            DeclaredBuilderShape.namesOwnBuilder("Base.Builder<?, ?>", "Link", "Builder"));
        assertFalse("a type ending in the target's name",
            DeclaredBuilderShape.namesOwnBuilder("MyLink.Builder", "Link", "Builder"));
        assertFalse("another name",
            DeclaredBuilderShape.namesOwnBuilder("Link.Builder", "Link", "Maker"));
        assertFalse("an array of the builder",
            DeclaredBuilderShape.namesOwnBuilder("Builder[]", "Link", "Builder"));
    }

    /**
     * A collected slot whose default reads the instance is held in a plain
     * {@code java.util} container of the matched supertype's arguments, in
     * the one spelling both halves print. The editor had no rendering of it, so
     * it neither contributed the slot nor judged a declared field against it.
     */
    @Test
    public void scratchContainerOf_isTheJavaUtilInterfaceOfTheMatchedArguments() {
        assertEquals("java.util.List<java.lang.String>",
            DeclaredBuilderShape.scratchContainerOf(false, false, "java.lang.String", null, null));
        assertEquals("java.util.Set<java.lang.String>",
            DeclaredBuilderShape.scratchContainerOf(false, true, "java.lang.String", null, null));
        assertEquals("either model's argument separator",
            "java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>",
            DeclaredBuilderShape.scratchContainerOf(true, false, null, "java.lang.String",
                "java.util.List<java.lang.Integer>"));
        assertEquals("a raw container's arguments are Object",
            "java.util.Map<java.lang.Object, java.lang.Object>",
            DeclaredBuilderShape.scratchContainerOf(true, false, null, null, null));
        assertEquals("java.util.Map", DeclaredBuilderShape.scratchContainerName(true, false));
        assertEquals("java.util.Set", DeclaredBuilderShape.scratchContainerName(false, true));
        assertEquals("java.util.List", DeclaredBuilderShape.scratchContainerName(false, false));
    }

    /**
     * A seed already assigned cannot be assigned again by a constructor, the
     * field being {@code final}; javac refuses the constructor, and the editor
     * reports it in this sentence, named by what assigned it first - an
     * instance initializer, the constructor delegated to, or the constructor
     * itself. Only the instance initializer was named.
     */
    @Test
    public void reassignedSeed_namesWhatAssignedTheSeedFirst() {
        assertEquals("@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, "
                + "and this constructor assigns it after an instance initializer already has",
            DeclaredBuilderShape.reassignedSeed("Builder", "origin",
                DeclaredBuilderShape.PriorAssignment.INSTANCE_INITIALIZER));
        assertEquals("@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, "
                + "and this constructor assigns it after the constructor it delegates to already has",
            DeclaredBuilderShape.reassignedSeed("Builder", "origin",
                DeclaredBuilderShape.PriorAssignment.DELEGATED_CONSTRUCTOR));
        assertEquals("@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, "
                + "and this constructor may assign it more than once",
            DeclaredBuilderShape.reassignedSeed("Builder", "origin",
                DeclaredBuilderShape.PriorAssignment.SAME_CONSTRUCTOR));
    }

    /**
     * A constructor a constructor annotation appends leaves an appended seed
     * unassigned, and the sentence names that constructor and the annotation,
     * its parameter types in either model's spelling. The editor had no
     * sentence for it and said the builder declares no constructor.
     */
    @Test
    public void unassignedByAppendedConstructor_namesTheConstructorAndItsAnnotation() {
        assertEquals("@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, "
                + "and the constructor Builder(String, List<Integer>) that @AllArgsConstructor appends leaves "
                + "it unassigned",
            DeclaredBuilderShape.unassignedByAppendedConstructor("Builder", "origin", "@AllArgsConstructor",
                List.of("java.lang.String", "java.util.List<java.lang.Integer>")));
        assertEquals("@ClassBuilder merged into 'Builder' appends the seed 'origin' as a final field, "
                + "and the constructor Builder() that @NoArgsConstructor appends leaves it unassigned",
            DeclaredBuilderShape.unassignedByAppendedConstructor("Builder", "origin", "@NoArgsConstructor",
                List.of()));
    }

    /**
     * An inherited method under a generated setter's name and erased parameter
     * types that is {@code final}, or returns a type the builder cannot stand
     * in for, is reported in one sentence naming the method and the supertype
     * declaring it. Neither half read past the declared builder's own members,
     * and javac refused the appended setter on the target's line.
     */
    @Test
    public void unoverridableInheritedMethod_namesTheMethodAndItsSupertype() {
        assertEquals("@ClassBuilder merged into 'Builder' finds tag(String) inherited from Fluent declared "
                + "final, so the generated setter of that signature cannot override it",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tag", List.of("String"), Map.of(),
                List.of(new InheritedMethod("tag", List.of("java.lang.String"), "Fluent", "B", true, true, false))));
        assertEquals("@ClassBuilder merged into 'Builder' finds tag(String) inherited from Fluent returning "
                + "void, which the generated setter returning Builder cannot override",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tag", List.of("java.lang.String"),
                Map.of(), List.of(new InheritedMethod("tag", List.of("java.lang.String"), "Fluent", "void", false,
                    false, false))));
        assertEquals("@ClassBuilder merged into 'Builder' finds count(int) inherited from Counter returning "
                + "Integer, which the generated setter returning Builder cannot override",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "count", List.of("int"), Map.of(),
                List.of(new InheritedMethod("count", List.of("int"), "Counter", "java.lang.Integer", false,
                    false, false))));
        assertEquals("the first inherited method that blocks the setter is named",
            "@ClassBuilder merged into 'Builder' finds wait(long) inherited from Object declared final, so "
                + "the generated setter of that signature cannot override it",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "wait", List.of("long"), Map.of(),
                List.of(new InheritedMethod("wait", List.of("long"), "Fluent", "B", false, true, false),
                    new InheritedMethod("wait", List.of("long"), "Object", "void", true, false, false))));
    }

    /**
     * A {@code static} inherited method under the setter's key is named as
     * static, whatever it returns - no instance method overrides one. Both
     * halves' readers skipped static methods, so javac's refusal on the
     * target's line was the only report.
     */
    @Test
    public void unoverridableInheritedMethod_namesAStaticMethodAsStatic() {
        assertEquals("@ClassBuilder merged into 'Builder' finds tag(String) inherited from Base declared "
                + "static, so the generated setter of that signature cannot override it",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tag", List.of("String"), Map.of(),
                List.of(new InheritedMethod("tag", List.of("java.lang.String"), "Base", "Base", false, true,
                    true))));
        assertEquals("static is named ahead of final",
            "@ClassBuilder merged into 'Builder' finds tag(String) inherited from Base declared static, so the "
                + "generated setter of that signature cannot override it",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tag", List.of("String"), Map.of(),
                List.of(new InheritedMethod("tag", List.of("java.lang.String"), "Base", "void", true, false,
                    true))));
        assertNull("a static method taking another type is an overload",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tag", List.of("String"), Map.of(),
                List.of(new InheritedMethod("tag", List.of("int"), "Base", "Base", false, true, true))));
    }

    /**
     * A setter parameter typed by one of the builder's type variables is keyed
     * by that variable's erasure, the form the inherited method's parameters
     * are in. The setter was keyed by the variable's name, so a generated
     * {@code value(T)} never met an inherited {@code value(Object)}.
     */
    @Test
    public void unoverridableInheritedMethod_keysATypeVariableByItsErasure() {
        List<InheritedMethod> finalObject = List.of(
            new InheritedMethod("value", List.of("java.lang.Object"), "Base", "Base", true, true, false));
        List<InheritedMethod> finalNumber = List.of(
            new InheritedMethod("value", List.of("java.lang.Number"), "Base", "Base", true, true, false));
        List<InheritedMethod> finalString = List.of(
            new InheritedMethod("value", List.of("java.lang.String"), "Base", "Base", true, true, false));
        Map<String, String> unbounded = DeclaredBuilderShape.typeVariableErasures(List.of("T"), Arrays.asList(
            (String) null));
        Map<String, String> bounded = DeclaredBuilderShape.typeVariableErasures(List.of("T"), List.of("Number"));
        assertEquals("@ClassBuilder merged into 'Builder' finds value(T) inherited from Base declared final, so "
                + "the generated setter of that signature cannot override it",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "value", List.of("T"), unbounded,
                finalObject));
        assertNotNull("a bounded variable erases to its bound",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "value", List.of("T"), bounded,
                finalNumber));
        assertNull("an unbounded variable does not erase to String",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "value", List.of("T"), unbounded,
                finalString));
    }

    /**
     * A setter of a builder the generator writes whole meets one of
     * {@code java.lang.Object}'s methods it cannot override - a final one, or
     * one whose return type the builder is not - and is named in the same
     * sentence. Nothing read {@code Object} for such a builder, so javac's
     * refusal on the target's line was the only report.
     */
    @Test
    public void unoverridableObjectMethod_namesAFinalOrMistypedObjectMethod() {
        assertEquals("@ClassBuilder generating 'Builder' finds wait(long) inherited from java.lang.Object "
                + "declared final, so the generated setter of that signature cannot override it",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "wait", List.of("long"), Map.of()));
        for (String zeroArgFinal : List.of("getClass", "notify", "notifyAll", "wait")) {
            assertNotNull(zeroArgFinal,
                DeclaredBuilderShape.unoverridableObjectMethod("Builder", zeroArgFinal, List.of(), Map.of()));
        }
        assertNotNull("wait(long, int)",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "wait", List.of("long", "int"), Map.of()));
        assertEquals("@ClassBuilder generating 'Builder' finds hashCode() inherited from java.lang.Object "
                + "returning int, which the generated setter returning Builder cannot override",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "hashCode", List.of(), Map.of()));
        assertEquals("@ClassBuilder generating 'Builder' finds toString() inherited from java.lang.Object "
                + "returning String, which the generated setter returning Builder cannot override",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "toString", List.of(), Map.of()));
        assertNotNull("equals(Object)",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "equals", List.of("Object"), Map.of()));
        assertNotNull("equals(T) erases to equals(Object)",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "equals", List.of("T"),
                Map.of("T", "Object")));
        assertNotNull("finalize()",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "finalize", List.of(), Map.of()));
    }

    /**
     * {@code clone()} returns {@code Object}, which a builder is, and an
     * overload of an {@code Object} method's name is no override at all.
     */
    @Test
    public void unoverridableObjectMethod_leavesCloneAndOverloads() {
        assertNull("clone()",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "clone", List.of(), Map.of()));
        assertNull("wait(int)",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "wait", List.of("int"), Map.of()));
        assertNull("notify(boolean)",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "notify", List.of("boolean"), Map.of()));
        assertNull("equals(String)",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "equals", List.of("String"), Map.of()));
        assertNull("a name Object does not declare",
            DeclaredBuilderShape.unoverridableObjectMethod("Builder", "name", List.of("String"), Map.of()));
    }

    /**
     * An inherited method the generated setter overrides legally, and one of
     * the setter's name taking other parameter types, are left to javac, which
     * accepts both.
     */
    @Test
    public void unoverridableInheritedMethod_leavesAnOverrideAndAnOverload() {
        assertNull("a non-final method returning a supertype of the builder is overridden",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tag", List.of("String"), Map.of(),
                List.of(new InheritedMethod("tag", List.of("java.lang.String"), "Fluent", "B", false, true,
                    false))));
        assertNull("another parameter type is an overload",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tag", List.of("String"), Map.of(),
                List.of(new InheritedMethod("tag", List.of("int"), "Fluent", "void", true, false, false))));
        assertNull("the erasure is compared, not the arguments",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tags", List.of("java.util.List<String>"),
                Map.of(), List.of(new InheritedMethod("tags", List.of("java.util.List"), "Fluent", "B", false,
                    true, false))));
        assertNotNull("under the same erasure it is the same key",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tags", List.of("java.util.List<String>"),
                Map.of(), List.of(new InheritedMethod("tags", List.of("java.util.List"), "Fluent", "B", true,
                    true, false))));
        assertNull("nothing inherited",
            DeclaredBuilderShape.unoverridableInheritedMethod("Builder", "tag", List.of("String"), Map.of(),
                List.of()));
    }

    /**
     * The all-args constructor is withheld exactly where the declared builder
     * spells a no-argument method of the build method's configured name, which
     * the merge keeps in place of the generated one - unless
     * {@code @BuilderArgsConstructor} is written, which asks for it by name.
     * It was emitted beside the author's {@code build()} regardless, and a
     * {@code build()} calling {@code new Target()} failed.
     */
    @Test
    public void withholdsAllArgsConstructor_whereTheAuthorsBuildSurvivesTheMerge() {
        assertTrue(DeclaredBuilderShape.withholdsAllArgsConstructor("build", List.of("x(int)", "build()"), false));
        assertTrue("the configured name, after a rename",
            DeclaredBuilderShape.withholdsAllArgsConstructor("make", List.of("make()"), false));
        assertFalse("a build() beside a renamed build method is only a method",
            DeclaredBuilderShape.withholdsAllArgsConstructor("make", List.of("build()"), false));
        assertFalse("a build method taking arguments covers nothing",
            DeclaredBuilderShape.withholdsAllArgsConstructor("build", List.of("build(int)"), false));
        assertFalse("no declared build method leaves the generated one",
            DeclaredBuilderShape.withholdsAllArgsConstructor("build", List.of(), false));
        assertFalse("a written @BuilderArgsConstructor keeps it",
            DeclaredBuilderShape.withholdsAllArgsConstructor("build", List.of("build()"), true));
    }

}
