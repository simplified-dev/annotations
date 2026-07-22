package dev.simplified.args.apt;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.AllArgsConstructor;
import dev.simplified.annotations.BuilderArgsConstructor;
import dev.simplified.annotations.NoArgsConstructor;
import dev.simplified.annotations.RequiredArgsConstructor;

import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;

/**
 * One constructor annotation, resolved.
 *
 * <p>A type may carry several, and stacking is ordinary rather than exceptional
 * - a no-args form for a reflective framework beside a real one is the common
 * shape - so resolution yields a list rather than a single answer.
 *
 * @param mode which fields become parameters
 * @param access visibility of the generated constructor
 * @param force whether unassigned finals are filled with the JVM zero value
 * @param emitGenerated whether the constructor carries the coverage marker
 */
public record ArgsConfig(ArgsMode mode, AccessLevel access, boolean force, boolean emitGenerated) {

    /**
     * Reads whichever of the four annotations the target carries.
     *
     * @param target the annotated type
     * @return the resolved annotations, in a fixed order independent of how
     *         they were written
     */
    public static List<ArgsConfig> written(TypeElement target) {
        List<ArgsConfig> out = new ArrayList<>(2);
        AllArgsConstructor all = target.getAnnotation(AllArgsConstructor.class);
        if (all != null) {
            out.add(new ArgsConfig(ArgsMode.ALL, all.access(), false, all.emitGenerated()));
        }
        RequiredArgsConstructor required = target.getAnnotation(RequiredArgsConstructor.class);
        if (required != null) {
            out.add(new ArgsConfig(ArgsMode.REQUIRED, required.access(), false,
                required.emitGenerated()));
        }
        NoArgsConstructor none = target.getAnnotation(NoArgsConstructor.class);
        if (none != null) {
            out.add(new ArgsConfig(ArgsMode.NONE, none.access(), none.force(),
                none.emitGenerated()));
        }
        BuilderArgsConstructor builder = target.getAnnotation(BuilderArgsConstructor.class);
        if (builder != null) {
            out.add(new ArgsConfig(ArgsMode.BUILDER, builder.access(), false,
                builder.emitGenerated()));
        }
        return out;
    }

    /**
     * Whether the target carries any of the four.
     *
     * @param target the type to test
     * @return whether a constructor annotation is written on it
     */
    public static boolean anyWritten(TypeElement target) {
        return !written(target).isEmpty();
    }

    /**
     * The annotation a {@code @ClassBuilder} target's own constructor would be
     * spelled as, had the author written it.
     *
     * <p>Set equality, not "nothing was excluded". The two field lists diverge
     * in both directions - {@code transient}, {@code @BuilderIgnore} and
     * {@code exclude} shorten the builder's, while a {@code final} field with an
     * initializer lengthens it - so a type with nothing excluded still commonly
     * differs, and naming it {@code @AllArgsConstructor} would put a signature
     * in the gutter that javac never emits.
     *
     * @param allFields the {@link ArgsMode#ALL} selection
     * @param builderFields the {@link ArgsMode#BUILDER} selection
     * @return {@link ArgsMode#ALL} when the two coincide, {@link ArgsMode#BUILDER} otherwise
     */
    public static ArgsMode infer(List<ArgsField> allFields, List<ArgsField> builderFields) {
        if (allFields.size() != builderFields.size()) return ArgsMode.BUILDER;
        for (int i = 0; i < allFields.size(); i++) {
            if (!allFields.get(i).name().equals(builderFields.get(i).name())) return ArgsMode.BUILDER;
        }
        return ArgsMode.ALL;
    }

}
