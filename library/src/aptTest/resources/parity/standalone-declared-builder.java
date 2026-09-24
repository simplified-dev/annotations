package demo;

import dev.simplified.annotations.ClassBuilder;

/**
 * A plain standalone target that declares a nested type of the builder's name -
 * the shape a hand-migration off a Lombok builder produces most naturally.
 *
 * <p>The processor merges into the declaration: the author's {@code apply} stays,
 * the generated setter and {@code build()} are appended beside it, and the three
 * entry points land on the target. Anything the editor withholds here is a red
 * call site over source that builds.
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
