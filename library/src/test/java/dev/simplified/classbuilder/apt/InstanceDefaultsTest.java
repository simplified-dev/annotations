package dev.simplified.classbuilder.apt;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shared rule deciding which slots take a default computed on the built
 * instance, and the form the builder holds each slot in, exercised without
 * javac and without PSI.
 *
 * <p>The editor classified no initialised slot, having no answer to whether its
 * initializer reads the instance, so it neither judged such a slot's declared
 * field nor contributed the slot into a merged builder. Both halves now ask this.
 */
public class InstanceDefaultsTest {

    private static final Set<String> MEMBERS = Set.of("name", "compute", "getClass");

    /** {@code this}, {@code super} and any instance member's name read the instance. */
    @Test
    public void readsInstanceState_onThisSuperOrAnInstanceMembersName() {
        assertTrue(InstanceDefaults.readsInstanceState(List.of("this"), MEMBERS));
        assertTrue(InstanceDefaults.readsInstanceState(List.of("super"), MEMBERS));
        assertTrue(InstanceDefaults.readsInstanceState(List.of("String", "name"), MEMBERS));
        assertTrue("an inherited method", InstanceDefaults.readsInstanceState(List.of("getClass"), MEMBERS));
    }

    /** A literal spells nothing, and a type or static name is no instance member. */
    @Test
    public void readsInstanceState_notOnALiteralOrAStaticName() {
        assertFalse(InstanceDefaults.readsInstanceState(List.of(), MEMBERS));
        assertFalse(InstanceDefaults.readsInstanceState(List.of("List", "of", "DEFAULT"), MEMBERS));
    }

    /** A written {@code @BuilderDefault} wins over the class-wide policy, either way. */
    @Test
    public void builderDefault_isTheWrittenValueElseTheClassPolicy() {
        assertTrue(InstanceDefaults.builderDefault(null, true));
        assertFalse(InstanceDefaults.builderDefault(null, false));
        assertTrue(InstanceDefaults.builderDefault(true, false));
        assertFalse(InstanceDefaults.builderDefault(false, true));
    }

    /** An initializer is captured when it is the default, or a custom collector's only factory. */
    @Test
    public void captures_aKeptDefaultOrACustomCollectorsInitializer() {
        assertTrue(InstanceDefaults.captures(true, false));
        assertTrue(InstanceDefaults.captures(false, true));
        assertFalse(InstanceDefaults.captures(false, false));
    }

    /**
     * The three storage forms, in the order the builder's field emitter decides
     * them: a collected instance default is a scratch container even when lazy,
     * a lazy slot is a supplier, an instance default is a supplier, and the rest
     * are held as declared.
     */
    @Test
    public void slotHolding_ofTheSlotsFlags() {
        assertEquals(SlotHolding.COLLECTED_SCRATCH, SlotHolding.of(true, true, true));
        assertEquals(SlotHolding.COLLECTED_SCRATCH, SlotHolding.of(false, true, true));
        assertEquals(SlotHolding.LAZY, SlotHolding.of(true, true, false));
        assertEquals(SlotHolding.LAZY, SlotHolding.of(true, false, true));
        assertEquals(SlotHolding.INSTANCE_DEFAULT, SlotHolding.of(false, false, true));
        assertEquals(SlotHolding.DECLARED, SlotHolding.of(false, true, false));
        assertEquals(SlotHolding.DECLARED, SlotHolding.of(false, false, false));
    }

}
