package dev.simplified.classbuilder.editor;

import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import dev.simplified.shared.psi.GeneratedMemberMarker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the one call shape an {@code Optional} field's dual setter cannot
 * serve, by rewriting a bare {@code null} argument to {@code Optional.empty()}.
 *
 * <p>An {@code Optional<T>} field generates {@code x(T)} alongside
 * {@code x(Optional<T>)} so a caller holding a maybe-null {@code T} need not
 * wrap it. The cost is that a literal {@code x(null)} is ambiguous - both
 * parameter types accept null and neither is more specific - which javac and
 * the IDE both report. Every other call shape resolves, including a null-valued
 * variable, so the gap is exactly this one expression.
 *
 * <p>{@code Optional.empty()} is the only replacement offered because it is the
 * only one worth writing. The raw overload wraps its argument with
 * {@code Optional.ofNullable}, so a disambiguating {@code x((T) null)} stores
 * precisely the same empty value while saying less about intent.
 *
 * <p>Deliberately keyed on the *shape* of the candidates rather than on
 * {@link GeneratedMemberMarker}: a builder read from a compiled dependency
 * carries no marker, and the fix is equally correct there - or on a
 * hand-written dual setter, which has the same ambiguity for the same reason.
 */
public final class OptionalSetterNullIntention extends BaseIntentionAction {

    private static final String OPTIONAL_FQN = "java.util.Optional";

    @Override
    public @NotNull String getFamilyName() {
        return "Replace ambiguous null with Optional.empty()";
    }

    @Override
    public @NotNull String getText() {
        return "Replace 'null' with 'Optional.empty()'";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return findNullArgument(elementAt(editor, file)) != null;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file)
            throws IncorrectOperationException {
        PsiExpression nullArgument = findNullArgument(elementAt(editor, file));
        if (nullArgument == null) return;
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiElement replaced = nullArgument.replace(
            factory.createExpressionFromText(OPTIONAL_FQN + ".empty()", nullArgument));
        // The call site may not import Optional yet - the field's own type is
        // often the only mention, and that lives on the target rather than here.
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(replaced);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor,
                                                         @NotNull PsiFile file) {
        invoke(project, editor, file);
        return IntentionPreviewInfo.DIFF;
    }

    private static @Nullable PsiElement elementAt(Editor editor, PsiFile file) {
        if (editor == null || file == null) return null;
        return file.findElementAt(editor.getCaretModel().getOffset());
    }

    /**
     * The {@code null} literal to rewrite, or {@code null} when the caret is not
     * inside a single-argument call whose candidates are an
     * {@code Optional}-and-raw setter pair.
     */
    private static @Nullable PsiExpression findNullArgument(@Nullable PsiElement element) {
        if (element == null) return null;
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
        if (call == null) return null;

        PsiExpressionList arguments = call.getArgumentList();
        PsiExpression[] argumentArray = arguments.getExpressions();
        if (argumentArray.length != 1) return null;
        PsiExpression argument = argumentArray[0];
        // The null *literal* is the ambiguous form; a null-valued variable
        // carries a static type and resolves without help.
        if (!PsiTypes.nullType().equals(argument.getType())) return null;

        return isOptionalDualSetter(call) ? argument : null;
    }

    /**
     * Whether the call resolves ambiguously to an {@code x(T)} /
     * {@code x(Optional<T>)} pair. Requiring the raw parameter to be the
     * {@code Optional}'s own type argument is what keeps this off an unrelated
     * ambiguity that merely happens to involve an {@code Optional}.
     */
    private static boolean isOptionalDualSetter(PsiMethodCallExpression call) {
        ResolveResult[] candidates = call.getMethodExpression().multiResolve(false);
        if (candidates.length < 2) return false;

        PsiType optionalArgument = null;
        boolean sawRaw = false;
        for (ResolveResult candidate : candidates) {
            if (!(candidate.getElement() instanceof PsiMethod method)) continue;
            PsiParameter[] parameters = method.getParameterList().getParameters();
            if (parameters.length != 1) continue;
            PsiType parameterType = parameters[0].getType();
            PsiType inner = optionalTypeArgument(parameterType);
            if (inner != null) {
                if (optionalArgument != null) return false;
                optionalArgument = inner;
            } else {
                sawRaw = true;
            }
        }
        if (optionalArgument == null || !sawRaw) return false;

        for (ResolveResult candidate : candidates) {
            if (!(candidate.getElement() instanceof PsiMethod method)) continue;
            PsiParameter[] parameters = method.getParameterList().getParameters();
            if (parameters.length != 1) continue;
            PsiType parameterType = parameters[0].getType();
            if (optionalTypeArgument(parameterType) == null
                    && optionalArgument.equals(parameterType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code T} of an {@code Optional<T>} parameter, or {@code null} when
     * the type is not a parameterised {@code Optional}.
     */
    private static @Nullable PsiType optionalTypeArgument(PsiType type) {
        if (!(type instanceof PsiClassType classType)) return null;
        if (!OPTIONAL_FQN.equals(classType.rawType().getCanonicalText())) return null;
        PsiType[] parameters = classType.getParameters();
        return parameters.length == 1 ? parameters[0] : null;
    }

}
