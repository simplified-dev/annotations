package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The declarations the editor's synthesis for one target reads, rendered as
 * written - the key under which the synthesised members are reused.
 *
 * <p>The members are kept across reads so the platform's idempotence check,
 * which reruns a cached producer and compares what it returns, is handed the
 * same instances; they are rebuilt whenever this text differs. It covers what
 * the entry points, the builder class and the all-args or copy constructor are
 * built from: the target's header - its annotations, modifiers, kind, type
 * parameters, supertypes and record header - every field the target declares
 * with its annotations, written type and initializer, every method and
 * constructor it declares with its annotations, type parameters, return type,
 * parameters and throws clause, the names of its nested classes, and the same
 * of a builder the target declares. An annotated constructor or factory, its
 * {@code @BuilderSeed} parameters and {@code exclude} are all among them.
 *
 * <p>Built from names and written text alone, never a resolved type, so an
 * augment provider can ask for it without re-entering itself. A supertype's own
 * annotation lives in another declaration and is not read here.
 */
final class SynthesisFingerprint {

    private SynthesisFingerprint() {
    }

    /**
     * Renders the declarations the synthesis for a site reads.
     *
     * @param site the annotated site
     * @param declared the builder the target declares, or {@code null}
     * @return the text the reuse is keyed on
     */
    static @NotNull String of(@NotNull BuilderSite site, @Nullable PsiClass declared) {
        StringBuilder out = new StringBuilder();
        declaration(out, site.owner());
        if (declared != null) {
            out.append("declared\n");
            declaration(out, declared);
        }
        return out.toString();
    }

    /** Appends a class's header, then its own fields, methods and nested class names. */
    private static void declaration(StringBuilder out, PsiClass type) {
        out.append(type.isInterface() ? "interface " : type.isRecord() ? "record " : type.isEnum() ? "enum " : "class ")
            .append(type.getQualifiedName()).append(' ').append(type.getName());
        text(out, type.getModifierList());
        text(out, type.getTypeParameterList());
        text(out, type.getRecordHeader());
        text(out, type.getExtendsList());
        text(out, type.getImplementsList());
        out.append('\n');
        if (!(type instanceof PsiExtensibleClass extensible)) return;
        for (PsiField field : extensible.getOwnFields()) {
            // The declaration's own text, comments aside: its annotations,
            // modifiers, written type, name with any brackets after it, and
            // initializer. Nothing asks for the field's type, which an augment
            // pass for a @Lazy field answers by resolving its annotation.
            out.append("field ");
            for (PsiElement child = field.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof PsiComment)) out.append(child.getText());
            }
            out.append('\n');
        }
        for (PsiMethod method : extensible.getOwnMethods()) {
            out.append(method.isConstructor() ? "constructor " : "method ").append(method.getName());
            text(out, method.getModifierList());
            text(out, method.getTypeParameterList());
            text(out, method.getReturnTypeElement());
            text(out, method.getParameterList());
            text(out, method.getThrowsList());
            out.append('\n');
        }
        for (PsiClass nested : extensible.getOwnInnerClasses())
            out.append("nested ").append(nested.getName()).append('\n');
    }

    /** Appends an element's text between separators, or a marker where there is none. */
    private static void text(StringBuilder out, @Nullable PsiElement element) {
        out.append(" | ").append(element == null ? "-" : element.getText());
    }

}
