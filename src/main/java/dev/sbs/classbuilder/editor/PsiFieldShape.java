package dev.sbs.classbuilder.editor;

import com.intellij.psi.PsiType;

/**
 * PSI-side analogue of {@link dev.sbs.classbuilder.apt.FieldSpec}: the
 * minimal shape vocabulary the augment provider needs to synthesise
 * setters. FieldSpec itself is tied to {@code javax.lang.model} (APT-only);
 * this mirror is derived from PSI so editor-time synthesis has no APT
 * dependency.
 *
 * <p>Shape coverage matches the mutation-side subset - plain, boolean pair,
 * Optional dual, array varargs.
 */
public final class PsiFieldShape {

    public final String name;
    public final PsiType type;
    public final boolean isBoolean;
    public final boolean isArray;
    public final PsiType arrayComponent;
    public final boolean isOptional;
    public final PsiType optionalInner;

    private PsiFieldShape(String name, PsiType type, boolean isBoolean, boolean isArray,
                          PsiType arrayComponent, boolean isOptional, PsiType optionalInner) {
        this.name = name;
        this.type = type;
        this.isBoolean = isBoolean;
        this.isArray = isArray;
        this.arrayComponent = arrayComponent;
        this.isOptional = isOptional;
        this.optionalInner = optionalInner;
    }

    static PsiFieldShape of(String name, PsiType type) {
        boolean isBool = PsiType.BOOLEAN.equals(type);
        boolean isArr = type instanceof com.intellij.psi.PsiArrayType;
        PsiType arrComp = isArr ? ((com.intellij.psi.PsiArrayType) type).getComponentType() : null;
        boolean isOpt = isOptionalType(type);
        PsiType optInner = isOpt ? optionalInner(type) : null;
        return new PsiFieldShape(name, type, isBool, isArr, arrComp, isOpt, optInner);
    }

    private static boolean isOptionalType(PsiType type) {
        if (!(type instanceof com.intellij.psi.PsiClassType classType)) return false;
        return "java.util.Optional".equals(classType.resolve() == null ? null : classType.resolve().getQualifiedName());
    }

    private static PsiType optionalInner(PsiType type) {
        if (!(type instanceof com.intellij.psi.PsiClassType classType)) return null;
        PsiType[] params = classType.getParameters();
        return params.length == 0 ? null : params[0];
    }

}
