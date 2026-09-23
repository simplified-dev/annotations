package dev.simplified.classbuilder.apt;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The rule refusing a constructor or static factory that cannot carry a
 * builder, and the sentence for each refusal.
 *
 * <p>Both halves ask it, each reporting the answer on the annotated member: the
 * processor from the element model, the editor from the names and modifiers PSI
 * holds without resolving. A refused member generates nothing, so the editor
 * contributes no builder for it either. The questions are asked in one order and
 * the first that applies is the one reported:
 * <ul>
 *   <li><b>an instance method</b> - {@code builder()} is static, so the factory it builds through must be too</li>
 *   <li><b>a {@code void} method</b> - {@code build()} has nothing to return</li>
 *   <li><b>a member of an annotated type</b> - one type carries one builder, and the type's wins</li>
 *   <li><b>a second annotated member</b> - the first usable one already took the type</li>
 *   <li><b>a member of a type with a {@code @Lazy} field</b> - the field's storage and every constructor
 *       parameter feeding it are rewritten, so the slots would no longer match</li>
 * </ul>
 */
public final class ExecutableTargetRefusal {

    private ExecutableTargetRefusal() { }

    /**
     * Answers why an annotated constructor or static factory cannot carry a
     * builder, or {@code null} when it can.
     *
     * @param method whether the member is a method rather than a constructor
     * @param isStatic whether it is declared {@code static}
     * @param returnsVoid whether it is a method declared {@code void}
     * @param enclosingName the simple name of the type it is declared in
     * @param enclosingAnnotated whether that type carries {@code @ClassBuilder} itself
     * @param anotherMemberClaimed whether an earlier annotated member of that type already carries its builder
     * @param lazyField the name of the first {@code @Lazy} field that type declares, or {@code null} when it declares none
     * @return the sentence reported on the member, or {@code null} when it is usable
     */
    public static @Nullable String refusal(boolean method, boolean isStatic, boolean returnsVoid,
                                           @NotNull String enclosingName, boolean enclosingAnnotated,
                                           boolean anotherMemberClaimed, @Nullable String lazyField) {
        if (method && !isStatic) return instanceMethod();
        if (method && returnsVoid) return voidMethod();
        if (enclosingAnnotated) return besideAnnotatedType(enclosingName);
        if (anotherMemberClaimed) return secondMember(enclosingName);
        if (lazyField != null) return lazyField(enclosingName, lazyField);
        return null;
    }

    /**
     * The error for {@code @ClassBuilder} on an instance method.
     *
     * @return the sentence both halves report
     */
    public static @NotNull String instanceMethod() {
        return "@ClassBuilder on an instance method has no receiver to call it on - builder() is "
            + "static, so the factory it builds through must be static too";
    }

    /**
     * The error for {@code @ClassBuilder} on a {@code void} method.
     *
     * @return the sentence both halves report
     */
    public static @NotNull String voidMethod() {
        return "@ClassBuilder on a void method has nothing for build() to return";
    }

    /**
     * The error for {@code @ClassBuilder} on a member of a type that carries it
     * too.
     *
     * @param enclosingName the simple name of the annotated type
     * @return the sentence both halves report
     */
    public static @NotNull String besideAnnotatedType(@NotNull String enclosingName) {
        return "@ClassBuilder is on " + enclosingName + " as well as on this member - "
            + "one type carries one builder, so keep whichever set of slots is wanted and "
            + "drop the other annotation";
    }

    /**
     * The error for {@code @ClassBuilder} on a member of a type an earlier
     * annotated member already carries the builder of.
     *
     * @param enclosingName the simple name of the type
     * @return the sentence both halves report
     */
    public static @NotNull String secondMember(@NotNull String enclosingName) {
        return "@ClassBuilder is already on another member of " + enclosingName
            + " - one type carries one builder";
    }

    /**
     * The error for {@code @ClassBuilder} on a member of a type that declares a
     * {@code @Lazy} field.
     *
     * @param enclosingName the simple name of the type
     * @param fieldName the name of its {@code @Lazy} field
     * @return the sentence both halves report
     */
    public static @NotNull String lazyField(@NotNull String enclosingName, @NotNull String fieldName) {
        return "@ClassBuilder on a member of " + enclosingName + ", whose field '"
            + fieldName + "' is @Lazy - that rewrites the field's storage "
            + "and every constructor parameter feeding it, so the slots this builder passes "
            + "would no longer match. Move @ClassBuilder onto the type";
    }

}
