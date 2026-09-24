package dev.simplified.classbuilder.apt;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * The two facts both halves ask of a generated setter's shape, which neither
 * can read off a body the editor's light setters do not have.
 */
public class SetterShapeTest {

    /**
     * {@code from(T)} and {@code mutate()} pass each slot to the setter of its
     * {@code set}-role name taking the slot's own type, and to no other shape.
     */
    @Test
    public void copied_isTheSetterTakingTheSlotsOwnType() {
        Set<SetterShape> copied = EnumSet.noneOf(SetterShape.class);
        for (SetterShape shape : SetterShape.values())
            if (shape.copied()) copied.add(shape);
        assertEquals(EnumSet.of(SetterShape.PLAIN, SetterShape.BOOLEAN, SetterShape.OPTIONAL, SetterShape.ARRAY,
            SetterShape.BULK_ITERABLE, SetterShape.BULK_MAP, SetterShape.LAZY_VALUE), copied);
    }

    /**
     * The singular container shapes and an {@code Optional} slot's inner-type
     * setter never assign the field, a bulk setter assigns it only where the
     * slot replaces rather than appends, and every other shape always does.
     */
    @Test
    public void assigns_leavesOutTheShapesThatOnlyMutateTheContainer() {
        Set<SetterShape> never = EnumSet.of(SetterShape.OPTIONAL_VALUE, SetterShape.ADD, SetterShape.PUT,
            SetterShape.PUT_IF_ABSENT, SetterShape.CLEAR, SetterShape.REMOVE);
        Set<SetterShape> unlessAppending = EnumSet.of(SetterShape.BULK_VARARGS, SetterShape.BULK_ITERABLE,
            SetterShape.BULK_MAP);
        for (SetterShape shape : SetterShape.values()) {
            boolean replacing = !never.contains(shape);
            boolean appending = !never.contains(shape) && !unlessAppending.contains(shape);
            assertEquals(shape + " on a replacing slot", replacing, shape.assigns(false));
            assertEquals(shape + " on an appending slot", appending, shape.assigns(true));
        }
    }

}
