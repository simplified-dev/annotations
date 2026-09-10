package demo;

import dev.simplified.annotations.ClassBuilder;

/**
 * An abstract annotated root that declares its own nested builder, with the merge
 * opt-in off.
 *
 * <p>The chain path aborts on the declaration and notes why, so the root's
 * builder gains no self type, no setters and no build method. The abort is what
 * this case pins on the processor side; on the editor side the claim is that
 * nothing is contributed into the class the author wrote.
 */
@ClassBuilder
public abstract class Rooted {

    private String label;

    public String getLabel() {
        return label;
    }

    public static class Builder {

        public Builder apply(Runnable task) {
            task.run();
            return this;
        }

    }

}
