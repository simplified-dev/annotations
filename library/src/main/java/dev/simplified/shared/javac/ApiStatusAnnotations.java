package dev.simplified.shared.javac;

import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import java.util.HashSet;
import java.util.Set;

/**
 * Copies a field's {@code @ApiStatus} markings onto a member this pipeline
 * generates from it.
 *
 * <p>The markings have to travel or they say nothing. A field is private, so it
 * is not the thing a consumer can reach - the generated accessor is, and an
 * accessor carrying no marking is ordinary published surface as far as every
 * reader and every tool that reads one is concerned.
 *
 * <p>The whole family travels, not one member of it, because they answer the
 * same question about the same member: what a consumer may rely on. A field
 * marked experimental whose accessor is unmarked publishes the thing the
 * marking was meant to hold back.
 *
 * <p>Written trees are copied rather than rebuilt, so an argument survives.
 * {@code @ApiStatus.AvailableSince} has no default for its value, so a copy
 * that dropped arguments would not merely lose information - it would emit a
 * member javac rejects. The copy is fresh on every call because a javac tree
 * cannot be shared between two parents, and it keeps the author's own spelling,
 * which resolves because the generated member lands in the compilation unit
 * that spelling was written in.
 *
 * <p>Unlike nullness this is safe on a member whose type is not the field's
 * own. It makes no claim about the type; it says what a consumer may rely on,
 * and that stays true however the slot is retyped.
 *
 * <p><b>A marking written without its enclosing name does not travel.</b> The
 * family is nested, so it can be imported directly and written bare, and the
 * qualifier is absent exactly when the written name alone cannot say whether it
 * belongs to the family. Deciding that would mean resolving every annotation on
 * every field, which the editor half refuses to do - and the two halves must
 * agree on what a generated member carries, so this half declines it too.
 */
public final class ApiStatusAnnotations {

    /** The type every marking in the family is nested in. */
    private static final String OWNER = "ApiStatus";

    /** What a member of the family is called once resolved. */
    public static final String PREFIX = "org.jetbrains.annotations.ApiStatus.";

    private ApiStatusAnnotations() {}

    /**
     * The family markings written on a field, rebuilt as fresh trees.
     *
     * @param element the field's model element, for the resolved names
     * @param field the field's source tree, for the written form and arguments
     * @param make the tree factory
     * @return the annotations to attach, empty when the field carries none
     */
    public static List<JCAnnotation> copy(Element element, JCVariableDecl field, TreeMaker make) {
        List<JCAnnotation> out = List.nil();
        if (element == null || field == null || field.mods == null) return out;

        Set<String> family = new HashSet<>();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String fqn = mirror.getAnnotationType().toString();
            if (fqn.startsWith(PREFIX)) family.add(fqn.substring(fqn.lastIndexOf('.') + 1));
        }
        if (family.isEmpty()) return out;

        TreeCopier<Void> copier = new TreeCopier<>(make);
        for (JCAnnotation written : field.mods.annotations) {
            if (!qualifiedByOwner(written.annotationType)) continue;
            String text = written.annotationType.toString();
            if (!family.contains(text.substring(text.lastIndexOf('.') + 1))) continue;
            out = out.append(copier.copy(written));
        }
        return out;
    }

    /**
     * Whether the written name carries the family's enclosing type as its
     * qualifier, which covers both the imported spelling and the fully
     * qualified one.
     */
    private static boolean qualifiedByOwner(JCTree annotationType) {
        String text = annotationType.toString();
        int lastDot = text.lastIndexOf('.');
        if (lastDot < 0) return false;
        String qualifier = text.substring(0, lastDot);
        return qualifier.equals(OWNER) || qualifier.endsWith("." + OWNER);
    }

}
