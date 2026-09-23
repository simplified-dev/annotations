package dev.simplified.classbuilder.apt;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shared blank-final lift rule, exercised without javac and without PSI.
 *
 * <p>The lift took every selected {@code final} field's initializer off it
 * whatever the author's constructors wrote, so a constructor that assigns the
 * field nowhere failed with {@code variable might not have been initialized}
 * while the editor, reading the initializer in source, showed nothing.
 */
public class BlankFinalLiftTest {

    private static BlankFinalLift.Writes writing(String... names) {
        return BlankFinalLift.Writes.of(false, List.of(), List.of(names), List.of());
    }

    @Test
    public void noAuthorConstructor_lifts() {
        assertTrue(BlankFinalLift.lifts("retries", List.of()));
    }

    @Test
    public void aConstructorAssigningTheFieldNowhere_keepsTheInitializer() {
        assertFalse(BlankFinalLift.lifts("retries", List.of(writing("name"))));
        assertFalse("one constructor that leaves it is enough",
            BlankFinalLift.lifts("retries", List.of(writing("name", "retries"), writing("name"))));
        assertTrue(BlankFinalLift.lifts("retries", List.of(writing("name", "retries"))));
    }

    @Test
    public void aDelegatingConstructor_hasNoSay() {
        BlankFinalLift.Writes delegating =
            BlankFinalLift.Writes.of(true, List.of(), List.of(), List.of());
        assertTrue(BlankFinalLift.lifts("retries", List.of(writing("retries"), delegating)));
    }

    @Test
    public void aBareWrite_namesTheFieldOnlyWhereNothingShadowsIt() {
        BlankFinalLift.Writes shadowed =
            BlankFinalLift.Writes.of(false, List.of("retries"), List.of(), List.of("retries"));
        assertEquals(Set.of(), shadowed.assigned());
        BlankFinalLift.Writes bare =
            BlankFinalLift.Writes.of(false, List.of("r"), List.of(), List.of("retries"));
        assertEquals(Set.of("retries"), bare.assigned());
        BlankFinalLift.Writes qualified =
            BlankFinalLift.Writes.of(false, List.of("retries"), List.of("retries"), List.of());
        assertEquals("this.retries names the field whatever shadows it",
            Set.of("retries"), qualified.assigned());
    }

    /**
     * Beside an author's own {@code build()} the all-args constructor is
     * withheld, and what is left - javac's no-argument default, or a
     * constructor an args annotation appends - assigns no initialized
     * {@code final}. The field was lifted all the same, and the default
     * constructor failed with {@code variable label not initialized in the
     * default constructor}.
     */
    @Test
    public void aWithheldAllArgsConstructor_keepsTheInitializer() {
        assertFalse(BlankFinalLift.lifts("retries", List.of(), true));
        assertTrue("the constructor build() reaches assigns it",
            BlankFinalLift.lifts("retries", List.of(), false));
        assertFalse("the author's constructors still answer for themselves",
            BlankFinalLift.lifts("retries", List.of(writing("name")), false));
    }

}
