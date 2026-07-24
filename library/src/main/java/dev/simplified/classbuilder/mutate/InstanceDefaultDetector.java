package dev.simplified.classbuilder.mutate;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeScanner;
import dev.simplified.classbuilder.apt.FieldSpec;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which retained initializers reach for the enclosing instance.
 *
 * <p>A default touching no instance state is hoisted into a {@code static}
 * {@code $default$} provider and evaluated when the builder is created. One
 * that does reach for the instance cannot be: no target exists at that point.
 * Those are computed in the generated constructor instead, where {@code this}
 * is available - the same place an ordinary field initializer runs.
 *
 * <p>Detection is syntactic. At annotation-processing time the initializer has
 * been parsed and entered but not attributed, so its identifiers carry no
 * resolved symbols. It is therefore deliberately over-eager: a lambda
 * parameter sharing a name with an instance field counts as a reference. That
 * costs nothing but a slightly later evaluation, because the constructor path
 * handles static-safe expressions correctly too - it is the more general of
 * two working choices, never the wrong one.
 */
final class InstanceDefaultDetector {

    private InstanceDefaultDetector() {
    }

    /**
     * Names of fields whose captured initializer reads instance state.
     *
     * @param target the annotated type
     * @param fields the builder-visible fields
     * @param elements element utilities, for walking inherited members
     * @return field names needing the constructor-computed path, never null
     */
    static Set<String> detect(TypeElement target, List<FieldSpec> fields, Elements elements) {
        Set<String> instanceMembers = instanceMemberNames(target, elements);
        Set<String> out = new LinkedHashSet<>();
        for (FieldSpec f : fields) {
            Tree captured = f.sourceInitializerTree;
            if (!(captured instanceof JCTree tree)) continue;
            if (readsInstanceState(tree, instanceMembers)) out.add(f.name);
        }
        return out;
    }

    /**
     * Every non-static field and method visible on the target, inherited ones
     * included. {@code getClass()} and friends arrive via
     * {@link Elements#getAllMembers}, which is why a bare {@code getClass()} in
     * an initializer is spotted rather than mistaken for a static call.
     */
    private static Set<String> instanceMemberNames(TypeElement target, Elements elements) {
        Set<String> names = new HashSet<>();
        for (Element member : elements.getAllMembers(target)) {
            ElementKind kind = member.getKind();
            if (kind != ElementKind.FIELD && kind != ElementKind.METHOD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;
            names.add(member.getSimpleName().toString());
        }
        return names;
    }

    private static boolean readsInstanceState(JCTree tree, Set<String> instanceMembers) {
        Detector detector = new Detector(instanceMembers);
        tree.accept(detector);
        return detector.found;
    }

    private static final class Detector extends TreeScanner {

        private final Set<String> instanceMembers;
        private boolean found;

        Detector(Set<String> instanceMembers) {
            this.instanceMembers = instanceMembers;
        }

        @Override
        public void visitIdent(JCIdent tree) {
            String name = tree.name.toString();
            if (name.equals("this") || name.equals("super") || instanceMembers.contains(name)) {
                found = true;
            }
            super.visitIdent(tree);
        }

        @Override
        public void visitSelect(JCFieldAccess tree) {
            // Qualified forms such as Outer.this.field. The selected expression
            // is scanned by super, which reaches any this/super ident, so only
            // the selected name itself needs checking here.
            if (tree.name.toString().equals("this")) found = true;
            super.visitSelect(tree);
        }
    }

}
