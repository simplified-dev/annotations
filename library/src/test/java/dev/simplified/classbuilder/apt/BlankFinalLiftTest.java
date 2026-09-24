package dev.simplified.classbuilder.apt;

import dev.simplified.classbuilder.apt.BlankFinalLift.Arm;
import dev.simplified.classbuilder.apt.BlankFinalLift.Assignment;
import dev.simplified.classbuilder.apt.BlankFinalLift.Block;
import dev.simplified.classbuilder.apt.BlankFinalLift.Branch;
import dev.simplified.classbuilder.apt.BlankFinalLift.Break;
import dev.simplified.classbuilder.apt.BlankFinalLift.Exit;
import dev.simplified.classbuilder.apt.BlankFinalLift.Other;
import dev.simplified.classbuilder.apt.BlankFinalLift.Statement;
import dev.simplified.classbuilder.apt.BlankFinalLift.Switch;
import dev.simplified.classbuilder.apt.BlankFinalLift.Write;
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
        return new BlankFinalLift.Writes(false, Set.of(names));
    }

    /** A write to {@code this.<name>}, its site the given label. */
    private static Write<String> write(String name, String site) {
        return new Write<>(name, true, site);
    }

    /** A statement {@code this.<name> = ..}, its site the given label. */
    private static Statement<String> assign(String name, String site) {
        return new Assignment<>(List.of(write(name, site)));
    }

    private static Statement<String> ifElse(Statement<String> then, Statement<String> otherwise) {
        return new Branch<>(then, otherwise);
    }

    private static Statement<String> block(List<Statement<String>> statements) {
        return new Block<>(statements);
    }

    private static Set<String> assigned(List<Statement<String>> body) {
        return BlankFinalLift.Writes.of(false, List.of(), body).assigned();
    }

    @Test
    public void aConstructorAssigningTheFieldNowhere_keepsTheInitializer() {
        assertFalse(BlankFinalLift.lifts("retries", List.of(writing("name")), true));
        assertFalse("one constructor that leaves it is enough",
            BlankFinalLift.lifts("retries", List.of(writing("name", "retries"), writing("name")), true));
        assertTrue(BlankFinalLift.lifts("retries", List.of(writing("name", "retries")), true));
    }

    @Test
    public void aDelegatingConstructor_hasNoSay() {
        BlankFinalLift.Writes delegating = new BlankFinalLift.Writes(true, Set.of());
        assertTrue(BlankFinalLift.lifts("retries", List.of(writing("retries"), delegating), true));
    }

    @Test
    public void aBareWrite_namesTheFieldOnlyWhereNothingShadowsIt() {
        List<Statement<String>> bareBody = List.of(new Assignment<>(List.of(new Write<>("retries", false, "w"))));
        assertEquals(Set.of(), BlankFinalLift.Writes.of(false, List.of("retries"), bareBody).assigned());
        assertEquals(Set.of("retries"), BlankFinalLift.Writes.of(false, List.of("r"), bareBody).assigned());
        assertEquals("this.retries names the field whatever shadows it", Set.of("retries"),
            BlankFinalLift.Writes.of(false, List.of("retries"), List.of(assign("retries", "w"))).assigned());
    }

    /**
     * With no author constructor, only a constructor the generator appends can
     * assign the field. Beside an author's own {@code build()} or under a
     * {@code factoryMethod} none is appended, and the field was lifted all the
     * same, leaving javac's no-argument default with a blank final it never
     * assigns: {@code variable label not initialized in the default constructor}.
     */
    @Test
    public void noAuthorConstructor_liftsOnlyWhereAGeneratedConstructorAssignsTheField() {
        assertTrue("the constructor build() reaches assigns it",
            BlankFinalLift.lifts("retries", List.of(), true));
        assertFalse("nothing generated assigns it", BlankFinalLift.lifts("retries", List.of(), false));
        assertTrue("an author constructor answers for itself whatever is generated",
            BlankFinalLift.lifts("retries", List.of(writing("retries")), false));
    }

    /**
     * An {@code if} and an {@code else} that both assign the field assign it,
     * and so does a block, an {@code else if} chain ending in an {@code else},
     * and a {@code switch} with a {@code default} whose every arm assigns it
     * and leaves the switch. Only a statement of the body itself counted, so
     * each kept the initializer and javac refused every write.
     */
    @Test
    public void aStructuralDefiniteAssignment_assignsTheField() {
        assertEquals(Set.of("a"), assigned(List.of(ifElse(assign("a", "t"), assign("a", "e")))));
        assertEquals(Set.of("a"), assigned(List.of(block(List.of(assign("a", "b"))))));
        assertEquals(Set.of("a"), assigned(List.of(
            ifElse(assign("a", "1"), ifElse(assign("a", "2"), block(List.of(new Exit<>(), assign("a", "3"))))))));
        assertEquals("arrow arms", Set.of("a"), assigned(List.of(new Switch<>(true, List.of(
            new Arm<>(true, List.of(assign("a", "1"))),
            new Arm<>(true, List.of(block(List.of(assign("a", "2"))))))))));
        assertEquals("colon arms each ending in break", Set.of("a"), assigned(List.of(new Switch<>(true, List.of(
            new Arm<>(false, List.of(assign("a", "1"), new Break<>())),
            new Arm<>(false, List.of(assign("a", "2"), new Break<>())))))));
    }

    /**
     * Every shape outside the structural rule keeps the initializer: an
     * {@code if} with no {@code else}, a {@code switch} with no
     * {@code default}, an arm falling out without a {@code break}, an arm that
     * may break before its write, one arm leaving the field, and any write the
     * rule never reads - a loop's, a {@code try}'s or a lambda's.
     */
    @Test
    public void aShapeOutsideTheRule_doesNotAssignTheField() {
        assertEquals(Set.of(), assigned(List.of(new Branch<>(assign("a", "t"), null))));
        assertEquals("no default", Set.of(), assigned(List.of(new Switch<>(false, List.of(
            new Arm<>(true, List.of(assign("a", "1"))),
            new Arm<>(true, List.of(assign("a", "2"))))))));
        assertEquals("the last colon arm falls out", Set.of(), assigned(List.of(new Switch<>(true, List.of(
            new Arm<>(false, List.of(assign("a", "1"), new Break<>())),
            new Arm<>(false, List.of(assign("a", "2"))))))));
        assertEquals("a break ahead of the write", Set.of(), assigned(List.of(new Switch<>(true, List.of(
            new Arm<>(false, List.of(new Branch<>(new Break<>(), null), assign("a", "1"), new Break<>())),
            new Arm<>(false, List.of(assign("a", "2"), new Break<>())))))));
        assertEquals("one arm leaves it", Set.of(), assigned(List.of(new Switch<>(true, List.of(
            new Arm<>(true, List.of(assign("a", "1"))),
            new Arm<>(true, List.of(new Exit<>())))))));
        assertEquals(Set.of(), assigned(List.of(new Other<>(List.of(write("a", "loop"))))));
    }

    /**
     * The writes javac accepts in a constructor of a lifted field are the ones
     * its statements reach while the field is still unassigned. Every plain
     * constructor write was cleared in the editor, so a second write javac
     * refuses with {@code variable a might already have been assigned} was
     * green.
     */
    @Test
    public void acceptedWrites_areTheOnesReachedWhileTheFieldIsUnassigned() {
        assertEquals("two top-level writes", Set.of("1"), accepted(false,
            assign("a", "1"), assign("a", "2")));
        assertEquals("a top-level write, then a branch's", Set.of("1"), accepted(false,
            assign("a", "1"), new Branch<>(assign("a", "2"), null)));
        assertEquals("a branch's write, then a top-level one", Set.of("1"), accepted(false,
            new Branch<>(assign("a", "1"), null), assign("a", "2")));
        assertEquals("both branches, then a top-level write", Set.of("1", "2"), accepted(false,
            ifElse(assign("a", "1"), assign("a", "2")), assign("a", "3")));
        assertEquals("a loop's write is never accepted and assigns nothing after it", Set.of("2"), accepted(false,
            new Other<>(List.of(write("a", "1"))), assign("a", "2")));
        assertEquals("a branch that returns leaves the rest unassigned", Set.of("1", "2"), accepted(false,
            new Branch<>(block(List.of(assign("a", "1"), new Exit<>())), null), assign("a", "2")));
        assertEquals("every arm, then a top-level write", Set.of("1", "2"), accepted(false,
            new Switch<>(true, List.of(
                new Arm<>(true, List.of(assign("a", "1"))),
                new Arm<>(true, List.of(assign("a", "2"))))),
            assign("a", "3")));
        assertEquals("an arm falling through into another's write", Set.of("1"), accepted(false,
            new Switch<>(true, List.of(
                new Arm<>(false, List.of(assign("a", "1"))),
                new Arm<>(false, List.of(assign("a", "2"), new Break<>()))))));
        assertEquals("after this(..) the field is assigned", Set.of(), accepted(true, assign("a", "1")));
    }

    @SafeVarargs
    private static Set<String> accepted(boolean delegates, Statement<String>... body) {
        return BlankFinalLift.acceptedWrites("a", delegates, List.of(), List.of(body));
    }

    /**
     * Beside a constructor the author wrote, each of Lombok's constructor
     * annotations adds a constructor that assigns no {@code final} field with
     * an initializer, which Lombok never assigns; {@code @Data} and
     * {@code @Value} imply none beside a written constructor. Without an author
     * constructor Lombok's constructors are left out.
     */
    @Test
    public void lombokConstructors_besideAWrittenConstructor_assignNothing() {
        assertEquals(List.of(new BlankFinalLift.Writes(false, Set.of())),
            BlankFinalLift.lombokConstructors(List.of("lombok.NoArgsConstructor"), true));
        assertEquals(2, BlankFinalLift.lombokConstructors(
            List.of("lombok.RequiredArgsConstructor", "lombok.AllArgsConstructor", "lombok.Getter"), true).size());
        assertEquals(List.of(), BlankFinalLift.lombokConstructors(List.of("lombok.Data", "lombok.Value"), true));
        assertEquals(List.of(), BlankFinalLift.lombokConstructors(List.of("lombok.NoArgsConstructor"), false));
        assertFalse(BlankFinalLift.lifts("a", List.of(writing("a"), new BlankFinalLift.Writes(false, Set.of())), true));
    }

}
