package dev.simplified.enumlookup.apt;

import org.jetbrains.annotations.NotNull;

/**
 * Per-{@code @KeyField} IR captured by {@link EnumLookupProcessor} and consumed
 * by {@code EnumLookupMutator}. Plain data - no javac references - so the
 * processor and the mutator can live in different packages without coupling.
 *
 * @param fieldName the enum's instance field name, used to derive
 *        {@code CACHED_KEYS_<fieldName>}
 * @param methodSuffix the suffix used in the generated {@code of<Suffix>} /
 *        {@code findBy<Suffix>} method names; equals
 *        {@code @KeyField.methodName()} when set or the field name capitalised
 *        when not
 * @param declaredTypeDisplay the field's declared type as a display string
 *        ({@code "int"}, {@code "java.lang.String"}, ...) consumable by
 *        {@code JavacTypeFactory.parseType}
 * @param isPrimitive {@code true} when the field type is a primitive
 * @param ignoreCase whether the generated comparison folds case, which only a
 *        {@link String}-typed key can do
 * @param strictKeys IDE-only opt-in to the duplicate-keys inspection
 * @param strictNullKeys IDE-only opt-in to the null-keys inspection (no-op when
 *        {@link #isPrimitive} is {@code true})
 */
public record EnumKeySpec(
    @NotNull String fieldName,
    @NotNull String methodSuffix,
    @NotNull String declaredTypeDisplay,
    boolean isPrimitive,
    boolean ignoreCase,
    boolean strictKeys,
    boolean strictNullKeys
) {

    /** Whether the key is a {@link String}, the one type case folding applies to. */
    public boolean isString() {
        return "java.lang.String".equals(declaredTypeDisplay);
    }

}
