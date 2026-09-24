package dev.simplified.classbuilder.apt;

import dev.simplified.classbuilder.apt.BlankFinalLift.Arm;
import dev.simplified.classbuilder.apt.BlankFinalLift.Assign;
import dev.simplified.classbuilder.apt.BlankFinalLift.Block;
import dev.simplified.classbuilder.apt.BlankFinalLift.Branch;
import dev.simplified.classbuilder.apt.BlankFinalLift.Constant;
import dev.simplified.classbuilder.apt.BlankFinalLift.Evaluate;
import dev.simplified.classbuilder.apt.BlankFinalLift.Expression;
import dev.simplified.classbuilder.apt.BlankFinalLift.Jump;
import dev.simplified.classbuilder.apt.BlankFinalLift.JumpKind;
import dev.simplified.classbuilder.apt.BlankFinalLift.Labelled;
import dev.simplified.classbuilder.apt.BlankFinalLift.Loop;
import dev.simplified.classbuilder.apt.BlankFinalLift.LoopKind;
import dev.simplified.classbuilder.apt.BlankFinalLift.Statement;
import dev.simplified.classbuilder.apt.BlankFinalLift.Switch;
import dev.simplified.classbuilder.apt.BlankFinalLift.Try;
import dev.simplified.classbuilder.apt.BlankFinalLift.Value;
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

    /** A condition that may go either way. */
    private static final Value<String> UNKNOWN = new Evaluate<>(List.of());

    private static BlankFinalLift.Writes writing(String... names) {
        return new BlankFinalLift.Writes(false, Set.of(names));
    }

    /** A write to {@code this.<name>}, its site the given label. */
    private static Write<String> write(String name, String site) {
        return new Write<>(name, true, site);
    }

    /** A statement {@code this.<name> = ..}, its site the given label. */
    private static Statement<String> assign(String name, String site) {
        return new Expression<>(new Assign<>(List.of(), write(name, site)));
    }

    private static Statement<String> ifElse(Statement<String> then, Statement<String> otherwise) {
        return new Branch<>(UNKNOWN, then, otherwise);
    }

    private static Statement<String> ifThen(Statement<String> then) {
        return new Branch<>(UNKNOWN, then, null);
    }

    @SafeVarargs
    private static Statement<String> block(Statement<String>... statements) {
        return new Block<>(List.of(statements));
    }

    private static Statement<String> jump(JumpKind kind) {
        return new Jump<>(kind, null, null);
    }

    private static Statement<String> loop(LoopKind kind, Value<String> condition, Statement<String> body) {
        return new Loop<>(kind, List.of(), condition, List.of(), body);
    }

    @SafeVarargs
    private static Set<String> assigned(Statement<String>... body) {
        return BlankFinalLift.Writes.of(false, List.of(), List.of(body)).assigned();
    }

    @SafeVarargs
    private static Set<String> accepted(boolean delegates, Statement<String>... body) {
        return BlankFinalLift.acceptedWrites("a", delegates, List.of(), List.of(body));
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
        List<Statement<String>> bareBody =
            List.of(new Expression<>(new Assign<>(List.of(), new Write<>("retries", false, "w"))));
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
        assertEquals(Set.of("a"), assigned(ifElse(assign("a", "t"), assign("a", "e"))));
        assertEquals(Set.of("a"), assigned(block(assign("a", "b"))));
        assertEquals(Set.of("a"), assigned(
            ifElse(assign("a", "1"), ifElse(assign("a", "2"), block(jump(JumpKind.THROW), assign("a", "3"))))));
        assertEquals("arrow arms", Set.of("a"), assigned(new Switch<>(UNKNOWN, true, List.of(
            new Arm<>(true, List.of(assign("a", "1"))),
            new Arm<>(true, List.of(block(assign("a", "2"))))))));
        assertEquals("colon arms each ending in break", Set.of("a"), assigned(new Switch<>(UNKNOWN, true, List.of(
            new Arm<>(false, List.of(assign("a", "1"), jump(JumpKind.BREAK))),
            new Arm<>(false, List.of(assign("a", "2"), jump(JumpKind.BREAK)))))));
    }

    /**
     * The shapes that leave the field unassigned on some way out keep the
     * initializer: an {@code if} with no {@code else}, a {@code switch} with no
     * {@code default}, an arm that may break before its write, an arm leaving
     * the field, and a loop whose condition may fail before its body runs.
     */
    @Test
    public void aShapeLeavingTheFieldOnSomePath_doesNotAssignTheField() {
        assertEquals(Set.of(), assigned(ifThen(assign("a", "t"))));
        assertEquals("no default", Set.of(), assigned(new Switch<>(UNKNOWN, false, List.of(
            new Arm<>(true, List.of(assign("a", "1"))),
            new Arm<>(true, List.of(assign("a", "2")))))));
        assertEquals("a break ahead of the write", Set.of(), assigned(new Switch<>(UNKNOWN, true, List.of(
            new Arm<>(false, List.of(ifThen(jump(JumpKind.BREAK)), assign("a", "1"), jump(JumpKind.BREAK))),
            new Arm<>(false, List.of(assign("a", "2"), jump(JumpKind.BREAK)))))));
        assertEquals("one arm leaves it", Set.of(), assigned(new Switch<>(UNKNOWN, true, List.of(
            new Arm<>(true, List.of(assign("a", "1"))),
            new Arm<>(true, List.of(block()))))));
        assertEquals("a while loop", Set.of(), assigned(loop(LoopKind.WHILE, UNKNOWN, assign("a", "loop"))));
    }

    /**
     * A {@code return} reached while the field is unassigned leaves the
     * constructor with a blank final, at the top level, in a block and in an
     * arm alike. The write after it was counted, and javac reported
     * {@code variable a might not have been initialized} on the {@code return}.
     */
    @Test
    public void aReturnAheadOfTheWrite_leavesTheFieldUnassigned() {
        assertEquals("at the top level", Set.of(), assigned(ifThen(jump(JumpKind.RETURN)), assign("a", "1")));
        assertEquals("in a block", Set.of(), assigned(block(ifThen(jump(JumpKind.RETURN)), assign("a", "1"))));
        assertEquals("in an arm", Set.of(), assigned(new Switch<>(UNKNOWN, true, List.of(
            new Arm<>(false, List.of(ifThen(jump(JumpKind.RETURN)), assign("a", "1"), jump(JumpKind.BREAK))),
            new Arm<>(false, List.of(assign("a", "2"), jump(JumpKind.BREAK)))))));
        assertEquals("a return after the write", Set.of("a"),
            assigned(assign("a", "1"), ifThen(jump(JumpKind.RETURN))));
        assertEquals("a branch assigning the field and returning", Set.of("a"),
            assigned(ifThen(block(assign("a", "1"), jump(JumpKind.RETURN))), assign("a", "2")));
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
            assign("a", "1"), ifThen(assign("a", "2"))));
        assertEquals("a branch's write, then a top-level one", Set.of("1"), accepted(false,
            ifThen(assign("a", "1")), assign("a", "2")));
        assertEquals("both branches, then a top-level write", Set.of("1", "2"), accepted(false,
            ifElse(assign("a", "1"), assign("a", "2")), assign("a", "3")));
        assertEquals("a for loop's write is refused and javac reads nothing after it", Set.of("2"), accepted(false,
            loop(LoopKind.FOR, UNKNOWN, assign("a", "1")), assign("a", "2")));
        assertEquals("a branch that returns leaves the rest unassigned", Set.of("1", "2"), accepted(false,
            ifThen(block(assign("a", "1"), jump(JumpKind.RETURN))), assign("a", "2")));
        assertEquals("every arm, then a top-level write", Set.of("1", "2"), accepted(false,
            new Switch<>(UNKNOWN, true, List.of(
                new Arm<>(true, List.of(assign("a", "1"))),
                new Arm<>(true, List.of(assign("a", "2"))))),
            assign("a", "3")));
        assertEquals("an arm falling through into another's write", Set.of("1"), accepted(false,
            new Switch<>(UNKNOWN, true, List.of(
                new Arm<>(false, List.of(assign("a", "1"))),
                new Arm<>(false, List.of(assign("a", "2"), jump(JumpKind.BREAK)))))));
        assertEquals("after this(..) every write is refused", Set.of(), accepted(true, assign("a", "1")));
    }

    /**
     * javac walks a loop body a second time where its first pass assigns the
     * field, and refuses there a write that may run again; after a {@code do}
     * or an enhanced {@code for} loop the field may be assigned, and after a
     * {@code while (true)} it is where each {@code break} leaves it. A loop's
     * write was never accepted and left the field unassigned after the loop.
     */
    @Test
    public void aLoop_followsJavacsTwoPasses() {
        assertEquals("do, then a top-level write", Set.of(), accepted(false,
            loop(LoopKind.DO, UNKNOWN, assign("a", "1")), assign("a", "2")));
        assertEquals("an enhanced for, then a top-level write", Set.of(), accepted(false,
            loop(LoopKind.FOREACH, null, assign("a", "1")), assign("a", "2")));
        assertEquals("a do over false runs its body once", Set.of("1"), accepted(false,
            loop(LoopKind.DO, new Constant<>(false), assign("a", "1"))));
        assertEquals(Set.of("a"), assigned(loop(LoopKind.DO, new Constant<>(false), assign("a", "1"))));
        Statement<String> breaking = loop(LoopKind.WHILE, new Constant<>(true),
            block(assign("a", "1"), jump(JumpKind.BREAK)));
        assertEquals("while (true) leaves only by its break", Set.of("a"), assigned(breaking));
        assertEquals(Set.of("1"), accepted(false, breaking, assign("a", "2")));
    }

    /**
     * A {@code try} block's write may not complete before a {@code catch}
     * block runs, a {@code finally} block runs on every way out, and a
     * labelled block completes where it breaks: javac's rules for each, which
     * a write inside any of them never reached.
     */
    @Test
    public void aTryOrALabelledStatement_followsJavacsRules() {
        List<Statement<String>> write = List.of(assign("a", "1"));
        assertEquals("try and finally", Set.of("a"), assigned(new Try<>(write, List.of(), List.of())));
        assertEquals("a catch that swallows", Set.of(), assigned(new Try<>(write, List.of(List.of()), null)));
        assertEquals("a catch that rethrows", Set.of("a"),
            assigned(new Try<>(write, List.of(List.of(jump(JumpKind.THROW))), null)));
        assertEquals("a finally that assigns", Set.of("a"),
            assigned(new Try<>(List.of(), List.of(List.of()), List.of(assign("a", "1")))));
        assertEquals("try, then a top-level write", Set.of("1"), accepted(false,
            new Try<>(write, List.of(List.of()), null), assign("a", "2")));
        assertEquals("a catch after a try block that may have assigned", Set.of("1"), accepted(false,
            new Try<>(write, List.of(List.of(assign("a", "2"))), null)));
        Statement<String> labelled = new Labelled<>("lbl", block(ifThen(new Jump<>(JumpKind.BREAK, "lbl", null)),
            assign("a", "1")));
        assertEquals("a labelled break ahead of the write", Set.of(), assigned(labelled));
        assertEquals(Set.of("1"), accepted(false, labelled));
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
