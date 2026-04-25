package dev.simplified.classbuilder.editor;

import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link LightMethodBuilder} subclass that exposes a backing field's
 * {@link PsiDocComment} via {@link #getDocComment()}. Used for synth setters
 * so that hovering / Ctrl-Q on a generated {@code withX(...)} shows the
 * Javadoc attached to field {@code x}.
 *
 * <p>Platform's default {@code LightMethodBuilder.getDocComment()} returns
 * {@code null} (it has no source tree to read from), so without this
 * subclass every synthesised setter is undocumented. Navigation already
 * jumps to the field via {@code setNavigationElement(field)}, but hover
 * info goes through {@link PsiDocCommentOwner#getDocComment()} directly -
 * navigation element isn't consulted.
 */
final class GeneratedSetterMethod extends LightMethodBuilder {

    private @Nullable PsiDocCommentOwner docSource;

    GeneratedSetterMethod(@NotNull PsiManager manager, @NotNull String name) {
        super(manager, name);
    }

    /**
     * Sets the element whose {@link PsiDocComment} this synth method should
     * expose. Typically the underlying field (or record component) that this
     * setter writes.
     */
    GeneratedSetterMethod withDocSource(@Nullable PsiDocCommentOwner source) {
        this.docSource = source;
        return this;
    }

    @Override
    public @Nullable PsiDocComment getDocComment() {
        return docSource != null ? docSource.getDocComment() : null;
    }

}
