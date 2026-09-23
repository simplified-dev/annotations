package demo;

import dev.simplified.annotations.ClassBuilder;

/**
 * An abstract annotated root that declares its own nested builder.
 *
 * <p>The declaration is a usable root shape - static, abstract, and self-typed
 * with a bounded trailing pair - so the merge appends the slot's field and its
 * self-typed setter, and the abstract self() and build(), beside the author's
 * verb. The verb reaches self(), which the author never wrote.
 */
@ClassBuilder
public abstract class Rooted {

    private String label;

    public String getLabel() {
        return label;
    }

    public abstract static class Builder<T extends Rooted, B extends Builder<T, B>> {

        public B apply(Runnable task) {
            task.run();
            return self();
        }

    }

}
