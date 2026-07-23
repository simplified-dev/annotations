package dev.simplified.accessor.apt;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree;
import dev.simplified.accessor.mutate.AccessorMutator;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NamingStyle;
import dev.simplified.classbuilder.apt.AccessorScheme;
import dev.simplified.shared.apt.MemberSpec;
import dev.simplified.shared.javac.AstMarkers;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Resolves how one member is read when an annotation asks to go through an
 * accessor rather than to touch the field.
 *
 * <p>Deliberately its own resolver rather than a reuse of the builder's
 * {@code from(T)} seeding ladder. That ladder matches on <b>name only</b>,
 * with no return-type and no owner check, and unions the inherited members -
 * which is right for seeding a builder, where a wrong guess produces a compile
 * error at the assignment. Here a wrong guess produces a member of the wrong
 * type silently dispatched down the wrong emission row, and the first symptom
 * is a hash table losing entries.
 *
 * <p>So the accepted accessor must be declared by the target itself, take no
 * parameters, and return something assignable to the member's own type.
 * Anything else falls back to the direct read, and the resolved type is what
 * the emission row is chosen from.
 *
 * <p><b>An accessor this pipeline minted earlier in the same round is
 * accepted too, and it has to be reached through the tree.</b> The accessor
 * pass appends a {@code JCMethodDecl} to the target's definitions and enters no
 * symbol, and javac completed every root class before the round began, so a
 * synthesised accessor can never appear in the element model - which would make
 * {@code useAccessors} beside {@code @Getter} emit the field reads it exists to
 * avoid.
 *
 * <p>That tree-side match cannot run the model's return-type test, because the
 * declared type there is an unattributed expression {@code Types.isAssignable}
 * cannot be applied to. What stands in for it is a claim about <b>one pass</b>
 * and no other: {@link AccessorMutator} mints a read accessor's return type
 * from {@code element.asType()} on the very field the body returns, so a method
 * that pass produced has the member's own type exactly, by construction rather
 * than by inference. The match is therefore gated on
 * {@link AstMarkers#isPassMarked(JCTree, String)} for
 * {@link AccessorMutator#PASS} - not on generated authorship, which several
 * other passes also stamp on zero-arg instance methods. The read-candidate list
 * ends with the bare field name, so a field named {@code mutate} or
 * {@code hashCode} would otherwise be read through the builder's
 * {@code mutate()} or the equality pass's {@code hashCode()} and typed as the
 * field, silently choosing the wrong emission row.
 */
public final class AccessorReads {

    private AccessorReads() {}

    /**
     * How a member is read, and the type that read produces.
     *
     * @param name the field or accessor name to select off a receiver
     * @param method whether the read is a zero-arg call
     * @param type the type of the read expression
     */
    public record Read(String name, boolean method, TypeMirror type) {
    }

    /**
     * Resolves the read for one member.
     *
     * @param target the annotated type
     * @param targetTree the target's source tree, or {@code null} to consult the
     *                   model alone
     * @param member the selected member
     * @param useAccessors whether the annotation asked for the accessor route
     * @param typeUtils the model's type operations
     * @param label the asking annotation's spelling, for the fallback note
     * @param messager sink for the note recording a fallback
     * @return the resolved read
     */
    public static Read resolve(TypeElement target, JCClassDecl targetTree, MemberSpec member,
                               boolean useAccessors, Types typeUtils, String label,
                               Messager messager) {
        Read direct = new Read(member.name(), member.method(), member.type());
        if (!useAccessors || member.method()) return direct;

        Element element = member.element();
        Getter onField = element == null ? null : element.getAnnotation(Getter.class);
        if (onField != null && onField.value() == AccessLevel.NONE) return direct;

        NamingStyle style = onField != null ? onField.style() : styleOf(target);
        String written = onField != null ? onField.name() : nameOf(target);
        AccessorScheme scheme = AccessorScheme.resolve(style, written);
        boolean isBoolean = member.type().getKind() == TypeKind.BOOLEAN;

        // Per candidate rather than model-then-tree, so the spelling the scheme
        // itself mints keeps beating a looser one further down the list.
        for (String candidate : scheme.readCandidates(member.name(), isBoolean)) {
            ExecutableElement accessor = declaredReader(target, candidate, member.type(), typeUtils);
            if (accessor != null) return new Read(candidate, true, accessor.getReturnType());
            if (synthesisedReader(targetTree, candidate)) {
                return new Read(candidate, true, member.type());
            }
        }

        messager.printMessage(Diagnostic.Kind.NOTE,
            label + "(useAccessors) found no declared accessor for '" + member.name()
                + "' returning its own type - reading the field directly",
            element != null ? element : target);
        return direct;
    }

    private static NamingStyle styleOf(TypeElement target) {
        Getter onType = target.getAnnotation(Getter.class);
        return onType != null ? onType.style() : NamingStyle.SIMPLIFIED;
    }

    private static String nameOf(TypeElement target) {
        Getter onType = target.getAnnotation(Getter.class);
        return onType != null ? onType.name() : null;
    }

    private static ExecutableElement declaredReader(TypeElement target, String name,
                                                    TypeMirror memberType, Types typeUtils) {
        for (Element enclosed : target.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            if (!enclosed.getSimpleName().contentEquals(name)) continue;
            if (enclosed.getModifiers().contains(Modifier.STATIC)) continue;
            ExecutableElement method = (ExecutableElement) enclosed;
            if (!method.getParameters().isEmpty()) continue;
            TypeMirror returned = method.getReturnType();
            if (returned.getKind() == TypeKind.VOID) continue;
            if (typeUtils.isAssignable(returned, memberType)) return method;
        }
        return null;
    }

    /**
     * Whether the accessor pass already minted a read accessor of that name onto
     * the target's tree.
     *
     * <p>No return-type test, and none is possible here - the declared type is
     * an unattributed expression. The <b>accessor pass's</b> mark is what stands
     * in for one, because that pass alone builds a read accessor's return type
     * out of the field the body returns. Matching generated authorship instead
     * accepts any zero-arg instance method the pipeline injects, which is how a
     * field named after one of them ends up read through it at the wrong type.
     */
    private static boolean synthesisedReader(JCClassDecl targetTree, String name) {
        if (targetTree == null) return false;
        for (JCTree def : targetTree.defs) {
            if (!(def instanceof JCMethodDecl method)) continue;
            if (!method.name.contentEquals(name)) continue;
            if (!method.params.isEmpty()) continue;
            if ((method.mods.flags & Flags.STATIC) != 0) continue;
            if (AstMarkers.isPassMarked(method, AccessorMutator.PASS)) return true;
        }
        return false;
    }

}
