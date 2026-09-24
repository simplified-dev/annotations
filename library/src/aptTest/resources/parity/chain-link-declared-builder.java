package demo;

import dev.simplified.annotations.ClassBuilder;

/**
 * A concrete link of a chain whose own nested builder is declared.
 *
 * <p>The declaration extends the ancestor's builder with the self-typed pair
 * bound to the link and its builder, which is the shape a link's builder has to
 * take, so the merge appends the slot's field and setter and the concrete
 * self() and build() beside the author's verb, and the target gets all three
 * entry points. The ancestor's own setter is inherited through the extends
 * clause rather than merged.
 */
@ClassBuilder
public class Link extends Base {

    private String extra;

    public String getExtra() {
        return extra;
    }

    public static class Builder extends Base.Builder<Link, Builder> {

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
