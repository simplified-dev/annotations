package demo;

import dev.simplified.annotations.ClassBuilder;

/**
 * A concrete link of a chain whose own nested builder is declared, with the merge
 * opt-in written on.
 *
 * <p>The attribute is documented as ignored on a chain, and the processor does
 * ignore it - the chain branch returns ahead of the declared-builder check, so
 * not one member is appended to the class the author wrote. An editor that reads
 * the opt-in and the builder's name without asking about the chain role
 * contributes the setters, the self accessor and the build method regardless,
 * and every one of them fails the build.
 */
@ClassBuilder(mergeDeclaredBuilder = true)
public class Link extends Base {

    private String extra;

    public String getExtra() {
        return extra;
    }

    public static class Builder {

        public Builder apply(Runnable task) {
            task.run();
            return this;
        }

    }

}

@ClassBuilder
abstract class Base {

    private String label;

    public String getLabel() {
        return label;
    }

}
