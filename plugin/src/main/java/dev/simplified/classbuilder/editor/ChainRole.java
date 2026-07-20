package dev.simplified.classbuilder.editor;

import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiModifier;
import dev.simplified.classbuilder.inspect.ClassBuilderConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where a {@code @ClassBuilder} target sits in a SuperBuilder chain, deciding
 * the shape of the Builder synthesised for it. Mirrors the branch in
 * {@code BuilderMutator.mutate}, which dispatches on the same two questions -
 * is the target abstract, and does its direct superclass carry
 * {@code @ClassBuilder} - so the editor's model of a chain matches what javac
 * will emit.
 */
enum ChainRole {

    /** Not in a chain: a plain nested {@code Builder} with {@code build()} returning the target. */
    STANDALONE,

    /**
     * Abstract, no annotated super. Root of a chain: the Builder is abstract and
     * self-typed {@code <T extends Target, B extends Builder<T, B>>}, with
     * abstract {@code self()} and {@code build()}.
     */
    ABSTRACT_ROOT,

    /**
     * Concrete, annotated super. The Builder binds the parent's self-types -
     * {@code extends Super.Builder<Target, Builder>} - and overrides
     * {@code self()} and {@code build()}.
     */
    CONCRETE_LINK,

    /**
     * Abstract, annotated super. Stays self-typed and forwards both parameters
     * up ({@code extends Super.Builder<T, B>}), leaving {@code self()} and
     * {@code build()} abstract.
     */
    CHAINED_ABSTRACT;

    /** Whether the Builder carries the self-typed {@code T} / {@code B} parameters. */
    boolean isSelfTyped() {
        return this == ABSTRACT_ROOT || this == CHAINED_ABSTRACT;
    }

    /** Whether the Builder extends a parent Builder. */
    boolean hasAnnotatedSuper() {
        return this == CONCRETE_LINK || this == CHAINED_ABSTRACT;
    }

    /**
     * Classifies a target.
     *
     * @param target the annotated type
     * @return its position in a SuperBuilder chain, never null
     */
    static @NotNull ChainRole of(@NotNull PsiClass target) {
        boolean isAbstract = target.hasModifierProperty(PsiModifier.ABSTRACT) && !target.isInterface();
        boolean annotatedSuper = annotatedSuperOf(target) != null;
        if (isAbstract) return annotatedSuper ? CHAINED_ABSTRACT : ABSTRACT_ROOT;
        return annotatedSuper ? CONCRETE_LINK : STANDALONE;
    }

    /**
     * The direct superclass when it also carries {@code @ClassBuilder}. Only the
     * immediate parent is consulted, matching Lombok's policy and the APT side's
     * {@code findAnnotatedDirectSuper} - an unannotated class in between breaks
     * the chain rather than being skipped over.
     *
     * @param target the annotated type
     * @return the annotated superclass, or {@code null}
     */
    static @Nullable PsiClass annotatedSuperOf(@NotNull PsiClass target) {
        if (target.isInterface() || target.isRecord() || target.isEnum()) return null;
        PsiClass superClass = target.getSuperClass();
        if (superClass == null) return null;
        if (CommonClassNames.JAVA_LANG_OBJECT.equals(superClass.getQualifiedName())) return null;
        return hasClassBuilder(superClass) ? superClass : null;
    }

    private static boolean hasClassBuilder(@NotNull PsiClass cls) {
        for (var annotation : cls.getAnnotations()) {
            if (ClassBuilderConstants.ANNOTATION_FQN.equals(annotation.getQualifiedName())) return true;
        }
        return false;
    }

}
