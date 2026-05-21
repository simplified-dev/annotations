package dev.simplified.shared.psi;

import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link LightMethodBuilder} subclass that exposes a backing element's
 * {@link PsiDocComment} via {@link #getDocComment()}. Used for synthesised
 * methods (setters, accessors, etc.) so hovering / Ctrl-Q on the generated
 * method shows the Javadoc attached to the source field, record component,
 * or other element.
 *
 * <p>Platform's default {@code LightMethodBuilder.getDocComment()} returns
 * {@code null} (it has no source tree to read from), so without this
 * subclass every synthesised method is undocumented. Navigation already
 * jumps to the source element via {@code setNavigationElement(...)}, but
 * hover info goes through {@link PsiDocCommentOwner#getDocComment()}
 * directly - navigation element isn't consulted.
 */
public class DocProxyingLightMethodBuilder extends LightMethodBuilder {

    private @Nullable PsiDocCommentOwner docSource;

    public DocProxyingLightMethodBuilder(@NotNull PsiManager manager, @NotNull String name) {
        super(manager, name);
    }

    /**
     * Sets the element whose {@link PsiDocComment} this synthesised method
     * should expose. Typically the underlying field (or record component)
     * that the method writes/reads.
     */
    public DocProxyingLightMethodBuilder withDocSource(@Nullable PsiDocCommentOwner source) {
        this.docSource = source;
        return this;
    }

    @Override
    public @Nullable PsiDocComment getDocComment() {
        return docSource != null ? docSource.getDocComment() : null;
    }
}
