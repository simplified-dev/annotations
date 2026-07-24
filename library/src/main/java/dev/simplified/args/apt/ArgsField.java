package dev.simplified.args.apt;

import javax.lang.model.element.VariableElement;

/**
 * One field as constructor synthesis sees it.
 *
 * <p>Deliberately not {@code FieldSpec}: that IR captures an initializer only
 * when the builder wants it as a default, so it cannot answer "is this
 * {@code final} field already assigned" in general. The answer here comes off
 * the declaration tree, which always can.
 *
 * @param name the field's simple name
 * @param typeDisplay the declared type as {@code javax.lang.model} renders it
 * @param isFinal whether the declaration carries {@code final}
 * @param hasInitializer whether the declaration carries an initializer
 * @param isLazy whether the field carries {@code @Lazy}
 * @param element the field element, for reading annotations off
 */
public record ArgsField(String name, String typeDisplay, boolean isFinal,
                        boolean hasInitializer, boolean isLazy, VariableElement element) {

    /**
     * Whether the constructor must assign this field for the type to compile.
     *
     * @return {@code true} for a {@code final} field with no initializer
     */
    public boolean isRequired() {
        return isFinal && !hasInitializer;
    }

}
