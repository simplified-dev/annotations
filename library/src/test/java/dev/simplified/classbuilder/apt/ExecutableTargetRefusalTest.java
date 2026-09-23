package dev.simplified.classbuilder.apt;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The shared rule refusing a constructor or static factory that cannot carry a
 * builder, and the five sentences both halves report, exercised without javac
 * and without PSI.
 */
public class ExecutableTargetRefusalTest {

    /** The wording the processor printed before the sentences were shared, byte for byte. */
    @Test
    public void sentences_keepTheProcessorsWording() {
        assertEquals("@ClassBuilder on an instance method has no receiver to call it on - builder() is "
                + "static, so the factory it builds through must be static too",
            ExecutableTargetRefusal.instanceMethod());
        assertEquals("@ClassBuilder on a void method has nothing for build() to return",
            ExecutableTargetRefusal.voidMethod());
        assertEquals("@ClassBuilder is on Job as well as on this member - one type carries one builder, "
                + "so keep whichever set of slots is wanted and drop the other annotation",
            ExecutableTargetRefusal.besideAnnotatedType("Job"));
        assertEquals("@ClassBuilder is already on another member of Job - one type carries one builder",
            ExecutableTargetRefusal.secondMember("Job"));
        assertEquals("@ClassBuilder on a member of Job, whose field 'cache' is @Lazy - that rewrites the "
                + "field's storage and every constructor parameter feeding it, so the slots this builder "
                + "passes would no longer match. Move @ClassBuilder onto the type",
            ExecutableTargetRefusal.lazyField("Job", "cache"));
    }

    /** Each refusal in the order the processor asks them, the first that applies winning. */
    @Test
    public void refusal_answersTheFirstThatAppliesInTheProcessorsOrder() {
        assertEquals(ExecutableTargetRefusal.instanceMethod(),
            ExecutableTargetRefusal.refusal(true, false, true, "Job", true, true, "cache"));
        assertEquals(ExecutableTargetRefusal.voidMethod(),
            ExecutableTargetRefusal.refusal(true, true, true, "Job", true, true, "cache"));
        assertEquals(ExecutableTargetRefusal.besideAnnotatedType("Job"),
            ExecutableTargetRefusal.refusal(true, true, false, "Job", true, true, "cache"));
        assertEquals(ExecutableTargetRefusal.secondMember("Job"),
            ExecutableTargetRefusal.refusal(false, false, false, "Job", false, true, "cache"));
        assertEquals(ExecutableTargetRefusal.lazyField("Job", "cache"),
            ExecutableTargetRefusal.refusal(false, false, false, "Job", false, false, "cache"));
    }

    /** A constructor asks neither the static nor the void question, and a usable member is refused nothing. */
    @Test
    public void refusal_answersNothingForAUsableMember() {
        assertNull(ExecutableTargetRefusal.refusal(false, false, false, "Job", false, false, null));
        assertNull(ExecutableTargetRefusal.refusal(true, true, false, "Job", false, false, null));
    }

}
