package dev.simplified.args.editor;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import dev.simplified.args.apt.ArgsMode;
import dev.simplified.args.inspect.ArgsConstants;
import dev.simplified.classbuilder.editor.ClassBuilderAugmentProvider;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Works out which constructor annotation a {@code @ClassBuilder} target's own
 * constructor amounts to.
 *
 * <p>The annotation is never written into the author's source. Doing so would
 * be a document write on every reanalysis - it churns VCS diffs, fights undo,
 * and would have to be un-written the moment a {@code @BuilderIgnore} lands.
 * It is surfaced instead, as an inferred annotation and as gutter text.
 *
 * @param mode the annotation the constructor amounts to
 * @param accessKeyword the constructor's visibility, empty for package-private
 * @param fields the parameters, in order
 */
public record ArgsInference(ArgsMode mode, String accessKeyword, List<PsiField> fields) {

    /**
     * Infers the annotation for a target, or {@code null} when there is nothing
     * to infer.
     *
     * <p>Nothing is inferred when the author wrote one of the four - a written
     * annotation is not a guess - nor when the builder synthesises no
     * constructor at all, which is the case for a record, an abstract or
     * chained target, and one with a {@code factoryMethod}.
     *
     * @param target the class to inspect
     * @return the inference, or {@code null}
     */
    public static @Nullable ArgsInference of(@NotNull PsiClass target) {
        PsiAnnotation classBuilder = ClassBuilderAugmentProvider.classBuilderAnnotation(target);
        if (classBuilder == null) return null;
        if (!ArgsConstants.written(target).isEmpty()) return null;
        if (!ClassBuilderAugmentProvider.synthesisesConstructor(target)) return null;

        List<String> exclude = excluded(classBuilder);
        List<PsiField> builderFields = ArgsConstants.select(target, ArgsMode.BUILDER, exclude);
        if (builderFields.isEmpty()) return null;
        List<PsiField> allFields = ArgsConstants.select(target, ArgsMode.ALL, exclude);

        String access = ClassBuilderConstants.accessKeyword(
            classBuilder, ClassBuilderConstants.ATTR_CONSTRUCTOR_ACCESS, "");
        return new ArgsInference(mode(allFields, builderFields), access, builderFields);
    }

    /**
     * Set equality, not "nothing was excluded".
     *
     * <p>The two lists diverge in both directions. {@code transient},
     * {@code @BuilderIgnore} and {@code exclude} shorten the builder's, while a
     * {@code final} field <b>with</b> an initializer lengthens it, since
     * {@code retainInit} turns that initializer into a builder default and the
     * field is stripped to a blank final for the constructor to assign. So a
     * value class whose every field is initialized still infers
     * {@code @BuilderArgsConstructor} - the common case, not the exception -
     * and calling it {@code @AllArgsConstructor} would name a signature javac
     * never emits.
     */
    private static ArgsMode mode(List<PsiField> allFields, List<PsiField> builderFields) {
        if (allFields.size() != builderFields.size()) return ArgsMode.BUILDER;
        for (int i = 0; i < allFields.size(); i++) {
            String a = allFields.get(i).getName();
            if (!a.equals(builderFields.get(i).getName())) return ArgsMode.BUILDER;
        }
        return ArgsMode.ALL;
    }

    /**
     * The annotation as it would be written.
     *
     * @return the source spelling, with {@code access} only when it is not the
     *         annotation's own default
     */
    public @NotNull String annotationText() {
        String defaultAccess = mode == ArgsMode.BUILDER ? "" : "public";
        if (accessKeyword.equals(defaultAccess)) return mode.annotationName();
        return mode.annotationName() + "(access = AccessLevel."
            + (accessKeyword.isEmpty() ? "PACKAGE" : accessKeyword.toUpperCase(java.util.Locale.ROOT))
            + ")";
    }

    /**
     * The constructor the inference describes, rendered as a signature.
     *
     * <p>Shown beside the annotation name rather than instead of it, because
     * the name alone hides the thing most likely to surprise: adding one
     * {@code @BuilderIgnore} silently shortens the constructor, and every
     * hand-written {@code new Target(...)} in the package stops compiling.
     *
     * @param target the class the constructor belongs to
     * @return the rendered signature
     */
    public @NotNull String signature(@NotNull PsiClass target) {
        StringBuilder out = new StringBuilder(target.getName() == null ? "?" : target.getName());
        out.append('(');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) out.append(", ");
            PsiField field = fields.get(i);
            out.append(field.getType().getPresentableText()).append(' ').append(field.getName());
        }
        return out.append(')').toString();
    }

    private static List<String> excluded(PsiAnnotation classBuilder) {
        List<String> out = new java.util.ArrayList<>();
        var value = classBuilder.findAttributeValue("exclude");
        if (value instanceof com.intellij.psi.PsiArrayInitializerMemberValue array) {
            for (var entry : array.getInitializers()) {
                if (entry instanceof com.intellij.psi.PsiLiteralExpression literal
                    && literal.getValue() instanceof String s) out.add(s);
            }
        } else if (value instanceof com.intellij.psi.PsiLiteralExpression literal
            && literal.getValue() instanceof String s) {
            out.add(s);
        }
        return out;
    }

}
