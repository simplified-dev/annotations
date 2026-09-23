package dev.simplified.classbuilder.apt;

import dev.simplified.annotations.AccessLevel;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shared rule for where {@code builderConstructorAccess} reaches and the two
 * sentences both halves report about it, exercised without javac and without
 * PSI.
 */
public class BuilderConstructorAccessTest {

    /** Only a builder whose entry points instantiate it takes the attribute. */
    @Test
    public void appliesTo_theStandaloneRoleAlone() {
        assertTrue(BuilderConstructorAccess.appliesTo(ChainRole.STANDALONE));
        assertFalse(BuilderConstructorAccess.appliesTo(ChainRole.ABSTRACT_ROOT));
        assertFalse(BuilderConstructorAccess.appliesTo(ChainRole.CONCRETE_LINK));
        assertFalse(BuilderConstructorAccess.appliesTo(ChainRole.CHAINED_ABSTRACT));
    }

    /** Every level but {@code NONE} names a modifier a constructor can carry. */
    @Test
    public void expressible_refusesNoneAlone() {
        for (AccessLevel access : AccessLevel.values())
            assertEquals(access.name(), access != AccessLevel.NONE, BuilderConstructorAccess.expressible(access));
    }

    /** The wording both suites assert on. */
    @Test
    public void sentences_keepTheWordingBothSuitesAssertOn() {
        assertEquals("@ClassBuilder(builderConstructorAccess = NONE) is not expressible - every "
                + "builder has a constructor, so choose PRIVATE, PACKAGE, PROTECTED or PUBLIC",
            BuilderConstructorAccess.notExpressible());
        assertEquals("@ClassBuilder(builderConstructorAccess) has no effect - the declared "
                + "'Builder' declares its own constructor, which keeps the access it is written "
                + "with. Write the access on that constructor, or drop the attribute",
            BuilderConstructorAccess.hasNoEffect("Builder"));
    }

}
