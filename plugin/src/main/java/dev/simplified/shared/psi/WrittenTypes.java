package dev.simplified.shared.psi;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the type a field was written with, rather than the one an augment
 * provider infers for it.
 *
 * <p>{@code @Lazy} retypes its field's storage, so {@link PsiField#getType()}
 * answers with the holder. Most callers want the other thing: the getter it
 * synthesises returns the written type, and the builder shapes its setters
 * around the written type too.
 *
 * <p>The type is re-parsed from the declaration's own source text rather than
 * resolved through the type element. Resolving would hand back whatever
 * inference produced - the platform caches an inferred type on the element it
 * came from, so by the time a second caller asks, the written type is no longer
 * reachable that way. Text is the one form inference cannot shadow. It also
 * sidesteps the re-entrancy a provider reading the field it is being asked
 * about would otherwise cause, because the element parsed here belongs to no
 * field.
 */
public final class WrittenTypes {

    private WrittenTypes() {}

    /**
     * Returns the field's declared type, with no inference applied.
     *
     * @param field the field to read
     * @return the written type, or the resolved type when there is no declaration to read
     */
    public static @Nullable PsiType of(@NotNull PsiField field) {
        PsiTypeElement element = field.getTypeElement();
        // A field read out of a class file carries no declaration, and nothing
        // rewrote it either - what it resolves to is what was written.
        if (element == null) return field.getType();
        try {
            return JavaPsiFacade.getElementFactory(field.getProject())
                .createTypeFromText(element.getText(), field);
        } catch (IncorrectOperationException e) {
            return field.getType();
        }
    }

}
