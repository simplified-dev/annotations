package demo;

import dev.simplified.annotations.ClassBuilder;

/**
 * A plain standalone target that declares a nested type of the builder's name,
 * with the merge opt-in off - the shape a hand-migration off a Lombok builder
 * produces most naturally.
 *
 * <p>The processor prints a note and emits nothing: no builder, and none of the
 * three entry points. Anything the editor offers here is completion for a member
 * the build answers {@code cannot find symbol} on.
 */
@ClassBuilder
public class Untouched {

    private String name;

    public String getName() {
        return name;
    }

    public static class Builder {

        public Builder apply(Runnable task) {
            task.run();
            return this;
        }

    }

}
