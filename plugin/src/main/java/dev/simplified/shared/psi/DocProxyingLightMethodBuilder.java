package dev.simplified.shared.psi;

import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypeParameterList;
import com.intellij.psi.impl.light.LightMethodBuilder;
import com.intellij.psi.impl.light.LightTypeParameterBuilder;
import com.intellij.psi.impl.light.LightTypeParameterListBuilder;
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
public class DocProxyingLightMethodBuilder extends GeneratedLightMethod {

    private @Nullable PsiDocCommentOwner docSource;
    private @Nullable LightTypeParameterListBuilder typeParameterList;

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

    /**
     * Declares type parameters on this method, copied from {@code sources} with
     * their bounds and re-owned by this method. Used for synthesised
     * {@code static} members of a generic type, which cannot see the enclosing
     * class's variables and so must introduce their own.
     *
     * <p>{@link LightMethodBuilder} exposes {@code getTypeParameterList()} but
     * no setter, so the list is held here and surfaced through the three
     * overrides below - the same shape Lombok's light method builder uses.
     *
     * <p>The copies are distinct {@link PsiTypeParameter}s from the sources, so
     * a return or parameter type meant to reference them must be built from
     * {@link #getTypeParameters()} rather than from the source owner's.
     *
     * @param sources the type parameters to copy
     * @return this builder
     */
    public DocProxyingLightMethodBuilder withTypeParameters(PsiTypeParameter @NotNull [] sources) {
        if (sources.length == 0) return this;
        LightTypeParameterListBuilder list = new LightTypeParameterListBuilder(getManager(), getLanguage());
        for (int i = 0; i < sources.length; i++) {
            LightTypeParameterBuilder copy = new LightTypeParameterBuilder(sources[i].getName(), this, i);
            for (PsiClassType bound : sources[i].getExtendsListTypes()) {
                copy.getExtendsList().addReference(bound);
            }
            list.addParameter(copy);
        }
        this.typeParameterList = list;
        return this;
    }

    @Override
    public @Nullable PsiTypeParameterList getTypeParameterList() {
        return typeParameterList != null ? typeParameterList : super.getTypeParameterList();
    }

    @Override
    public PsiTypeParameter @NotNull [] getTypeParameters() {
        return typeParameterList != null
            ? typeParameterList.getTypeParameters()
            : super.getTypeParameters();
    }

    @Override
    public boolean hasTypeParameters() {
        return typeParameterList != null && typeParameterList.getTypeParameters().length > 0;
    }

    @Override
    public @Nullable PsiDocComment getDocComment() {
        return docSource != null ? docSource.getDocComment() : null;
    }
}
